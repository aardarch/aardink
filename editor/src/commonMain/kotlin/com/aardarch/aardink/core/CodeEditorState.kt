/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aardarch.aardink.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.platform.EditorDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The central state holder for a [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout].
 *
 * All mutations flow through this class — the document, token cache, undo manager, and selection
 * are co-located here so that invariants between them can be maintained atomically.
 *
 * This class is [@Stable][Stable]: Compose will only re-read snapshot-state-backed properties
 * ([text], [selection], [tokenVersion]) during recomposition. Heavy derived state (e.g.
 * [AnnotatedString] construction) should happen in [LaunchedEffect] or a ViewModel, not directly
 * in composition.
 *
 * @param initialText The document content to load on creation.
 * @param tokenizer The tokenizer used for syntax highlighting. Defaults to [PlainTextTokenizer].
 * @param tokenizeDebounceMs Delay (ms) after the last keystroke before incremental tokenization
 *   runs. A 0 value tokenizes synchronously (use only for tests or small documents).
 */
@Stable
class CodeEditorState(
    initialText: String = "",
    val tokenizer: IncrementalTokenizer = PlainTextTokenizer,
    val tokenizeDebounceMs: Long = 150L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    val document = CodeDocument(initialText)
    val tokenCache = TokenCache()
    val undoManager = EditorUndoManager()

    /**
     * The `BasicTextField` interop point — [document] remains the canonical text/undo model;
     * this field mirrors it for the field to render and receive IME input into. Advanced hosts
     * may read it, but every mutation should still go through this class's own methods
     * ([applyEdit], [applyTextEdits], [loadText], [undo], [redo]) so [document] and the undo
     * history stay in sync with it.
     */
    @ExperimentalFoundationApi
    val textFieldState: TextFieldState = TextFieldState(initialText)

    // ── Snapshot-backed observable state ──────────────────────────────────────

    /** Incremented every time the document text changes. Triggers recomposition of text-dependent UI. */
    var textVersion by mutableIntStateOf(0)
        private set

    /** Incremented every time the token cache is updated. Triggers recomposition of the syntax overlay. */
    var tokenVersion by mutableIntStateOf(0)
        private set

    /**
     * Bumped by every mutation that did not originate from a keystroke inside [textFieldState]
     * itself — i.e. every call to [applyEdit], [applyTextEdits], [loadText], [undo], and [redo].
     * [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout] uses this to dismiss transient
     * UI (the completion dropdown, a diagnostic tooltip, the code-action menu) whose state
     * describes text that just changed out from under it.
     */
    var externalEditVersion by mutableIntStateOf(0)
        private set

    /** Current cursor / selection in document-absolute character offsets. */
    var selection: TextRange
        get() = textFieldState.selection
        internal set(value) {
            textFieldState.edit { selection = value }
        }

    /** Cursor line (0-based). Derived from [selection] and the document's line index. */
    val cursorLine: Int
        get() = document.offsetToLineCol(selection.start).first

    /** Cursor column (0-based, UTF-16). Derived from [selection] and the document's line index. */
    val cursorColumn: Int
        get() = document.offsetToLineCol(selection.start).second

    /** Whether the current selection is non-empty (a range rather than a cursor). */
    val hasSelection: Boolean
        get() = !selection.collapsed

    /**
     * One-shot navigation request. Set by Find/Replace and Go-To-Line; consumed by
     * [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout] which scrolls the viewport to the
     * target offset and (optionally) updates the selection.
     */
    var pendingNavigation by mutableStateOf<Navigation?>(null)
        private set

    data class Navigation(val targetOffset: Int, val select: TextRange? = null)

    /** Requests the editor to scroll to [offset] and optionally place a [select] there. */
    fun navigateTo(offset: Int, select: TextRange? = null) {
        pendingNavigation = Navigation(offset.coerceIn(0, document.length), select)
    }

    /** Called by [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout] after consuming [pendingNavigation]. */
    fun clearNavigation() {
        pendingNavigation = null
    }

    /**
     * One-shot rename request. Set by the host app (a menu item, a toolbar button); consumed by
     * [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout], which resolves the symbol at the
     * offset through the language service and opens the rename dialog.
     */
    var pendingRename by mutableStateOf<Rename?>(null)
        private set

    data class Rename(val offset: Int)

    /** Requests the editor to rename the symbol at [offset], defaulting to the one at the cursor. */
    fun requestRename(offset: Int = selection.start) {
        pendingRename = Rename(offset.coerceIn(0, document.length))
    }

    /** Called by [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout] after consuming [pendingRename]. */
    fun clearRename() {
        pendingRename = null
    }

    // ── Convenience reads ─────────────────────────────────────────────────────

    /**
     * Current document text. Calls [CodeDocument.text] which copies the internal buffer — prefer
     * reading [document] fields directly in tight loops.
     */
    val text: String get() {
        textVersion
        return document.text
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Replaces the entire document content with [newText] and resets undo/redo history.
     * Use this when loading a file from disk — not for user edits.
     */
    fun loadText(newText: String) {
        document.replaceAll(newText)
        undoManager.clear()
        textFieldState.undoState.clearHistory()
        syncFieldToDocument(TextRange(0))
        textVersion++
        scheduleTokenization()
    }

    /**
     * Applies a programmatic text edit — from the keyboard toolbar, find/replace, a completion
     * accept, or a quick fix — inserting [insertText] (may be empty) after deleting
     * [deleteLength] characters starting at [deleteOffset].
     *
     * Records the edit in [undoManager] and advances [textVersion]. Not used for the user's own
     * keystrokes inside [textFieldState] — those are applied directly to [document] and
     * [undoManager] by the `InputTransformation` installed on the field, without a round trip
     * through this method.
     */
    fun applyEdit(deleteOffset: Int, deleteLength: Int, insertText: String, newSelection: TextRange) {
        undoManager.flushPendingInsert()
        val deletedText = if (deleteLength > 0) {
            val start = deleteOffset.coerceIn(0, document.length)
            val end = (deleteOffset + deleteLength).coerceIn(start, document.length)
            document.text.substring(start, end)
        } else {
            ""
        }

        if (deleteLength > 0) {
            document.delete(deleteOffset, deleteLength)
            undoManager.recordDelete(deleteOffset, deleteLength, deletedText)
        }
        if (insertText.isNotEmpty()) {
            document.insert(deleteOffset, insertText)
            undoManager.recordInsert(deleteOffset, insertText)
        }

        syncFieldToDocument(newSelection)
        textVersion++
        scheduleTokenization()
    }

    /**
     * Applies a batch of [TextEdit]s atomically to the document, recording the operation in
     * undo history as a single batch and scheduling tokenization.
     *
     * [selection] is clamped to the resulting document — a batch that shortens the text past the
     * cursor would otherwise leave a selection out of bounds, which `TextFieldState` rejects.
     */
    fun applyTextEdits(edits: List<TextEdit>) {
        if (edits.isEmpty()) return
        undoManager.flushPendingInsert()

        // Apply edits in reverse range order so modifying earlier offsets doesn't skew subsequent
        // ranges. Edits sharing an offset are applied last-to-first, which lands them in the order
        // they were given: LSP says several inserts at one position appear in array order, so
        // inserting "a" then "b" must read "ab" and not "ba".
        val sorted = edits.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<TextEdit>> { it.value.range.first }
                    .thenByDescending { it.value.range.last }
                    .thenByDescending { it.index },
            )
            .map { it.value }
        val ops = mutableListOf<EditorUndoManager.EditOperation>()
        // One snapshot for the whole batch: edits are applied high-to-low, so text below the lowest
        // offset touched so far is unchanged and can be sliced from the snapshot — O(len) per edit
        // instead of an O(n) document copy per edit.
        val snapshot = document.text
        var untouchedBelow = snapshot.length

        for (edit in sorted) {
            val start = edit.range.first.coerceIn(0, document.length)
            val end = (edit.range.last + 1).coerceIn(start, document.length)
            val deleteLen = end - start
            val deletedText = when {
                deleteLen == 0 -> ""
                end <= untouchedBelow -> snapshot.substring(start, end)
                else -> document.text.substring(start, end) // overlapping edits: fall back to live text
            }
            untouchedBelow = minOf(untouchedBelow, start)

            if (deleteLen > 0) {
                document.delete(start, deleteLen)
                ops.add(EditorUndoManager.EditOperation.Delete(start, deleteLen, deletedText))
            }
            if (edit.newText.isNotEmpty()) {
                document.insert(start, edit.newText)
                ops.add(EditorUndoManager.EditOperation.Insert(start, edit.newText))
            }
        }

        if (ops.isNotEmpty()) {
            undoManager.recordBatch(ops)
        }

        // Carry the caret through the batch: a rename or import inserted above it must not leave
        // it at a number that now points into unrelated text.
        val before = selection
        val newSelection = clampToDocument(
            TextRange(mapThroughEdits(before.start, sorted), mapThroughEdits(before.end, sorted)),
        )
        syncFieldToDocument(newSelection)
        textVersion++
        scheduleTokenization()
    }

    /**
     * Where [offset] (into the text before a batch) sits after [edits] are applied. An edit ending
     * at or before the offset shifts it by the size difference; one the offset falls inside snaps
     * it to the end of the replacement, the same place a single [applyEdit] leaves the caret.
     */
    private fun mapThroughEdits(offset: Int, edits: List<TextEdit>): Int {
        var shift = 0
        var snapped: Int? = null
        for (edit in edits) {
            val start = edit.range.first.coerceAtLeast(0)
            val end = (edit.range.last + 1).coerceAtLeast(start)
            when {
                end <= offset -> shift += edit.newText.length - (end - start)
                start < offset -> snapped = start + edit.newText.length
            }
        }
        return (snapped ?: offset) + shift
    }

    private fun clampToDocument(range: TextRange): TextRange {
        val start = range.start.coerceIn(0, document.length)
        val end = range.end.coerceIn(0, document.length)
        return if (start == range.start && end == range.end) range else TextRange(start, end)
    }

    /**
     * Undoes the most recent edit and returns the new document text (for a host that wants to
     * react to it), or null if there is nothing to undo.
     */
    fun undo(): String? {
        val op = undoManager.undo() ?: return null
        val newSelection = applyOperationToDocument(undoManager.inverseOf(op))
        syncFieldToDocument(newSelection)
        textVersion++
        scheduleTokenization()
        return document.text
    }

    /**
     * Redoes the previously undone edit and returns the new document text, or null.
     */
    fun redo(): String? {
        val op = undoManager.redo() ?: return null
        val newSelection = applyOperationToDocument(op)
        syncFieldToDocument(newSelection)
        textVersion++
        scheduleTokenization()
        return document.text
    }

    // ── Tokenization scheduling ───────────────────────────────────────────────

    /**
     * Dispatcher for tokenization and the other pure-computation passes the editor runs off the
     * main thread.
     *
     * Defaults to [EditorDispatchers.compute], which is a background pool on Android and JVM and
     * the single event loop on wasmJs. Tests override it with a test dispatcher to keep work on
     * the test scheduler; hosts rarely need to.
     */
    var computeDispatcher: CoroutineDispatcher = EditorDispatchers.compute

    private var tokenizationJob: Job? = null
    private val tokenizationScope = scope

    init {
        // Tokenize the initial document so consumers see syntax highlighting on first frame
        // without having to type a character first.
        if (document.length > 0) scheduleTokenization()
    }

    /**
     * Schedules an incremental tokenization pass after [tokenizeDebounceMs].
     * Cancels any in-flight pass so only the final state of a burst of edits is processed.
     */
    fun scheduleTokenization() {
        tokenizationJob?.cancel()
        tokenizationJob = tokenizationScope.launch {
            if (tokenizeDebounceMs > 0) delay(tokenizeDebounceMs)
            runTokenization()
        }
    }

    private suspend fun runTokenization() {
        val snapshot = document.text
        val dirty = document.dirtyLines

        // Read the cache here, on the scope's dispatcher, not inside the withContext below.
        // TokenCache.tokens is a *mutating* getter: it rebuilds and caches a flattened list
        // behind non-volatile fields, while reset/merge/pruneLines run on this dispatcher.
        // Touching it from the compute thread is a data race on Android and JVM (benign on
        // wasmJs, which is single-threaded). The tokenizer only needs the previous tokens as
        // an immutable input, so one snapshot before the hop is enough.
        val previousTokens = tokenCache.tokens

        val updatedTokens = withContext(computeDispatcher) {
            if (dirty == null || previousTokens.isEmpty()) {
                tokenizer.tokenizeFull(snapshot)
            } else {
                val expandedDirty = if (tokenizer.canSpanLines(dirty.first, previousTokens)) {
                    0..dirty.last
                } else {
                    dirty
                }
                tokenizer.tokenizeLines(snapshot, expandedDirty, previousTokens)
            }
        }

        // Resumed on the scope's dispatcher (Dispatchers.Main in prod, testDispatcher in tests)
        val dirty2 = document.dirtyLines
        if (dirty2 == null) {
            tokenCache.reset(document, updatedTokens)
        } else {
            tokenCache.merge(document, dirty2, updatedTokens)
        }
        tokenCache.pruneLines(document.lineCount - 1)
        tokenVersion++
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Applies [op] to [document] only (not [textFieldState]) and returns the caret it implies. */
    private fun applyOperationToDocument(op: EditorUndoManager.EditOperation): TextRange = when (op) {
        is EditorUndoManager.EditOperation.Insert -> {
            document.insert(op.offset, op.text)
            TextRange(op.offset + op.text.length)
        }

        is EditorUndoManager.EditOperation.Delete -> {
            document.delete(op.offset, op.length)
            TextRange(op.offset)
        }

        is EditorUndoManager.EditOperation.Batch -> {
            var caret = TextRange(0)
            op.operations.forEach { caret = applyOperationToDocument(it) }
            caret
        }
    }

    /**
     * Replaces [textFieldState]'s entire content with [document]'s current text and places its
     * selection at [newSelection], in one atomic edit so the selection is never validated against
     * stale text. Bumps [externalEditVersion]. Called by every mutation method above — never by
     * the field's own `InputTransformation`, which is already editing [textFieldState] directly.
     */
    private fun syncFieldToDocument(newSelection: TextRange) {
        val newText = document.text
        textFieldState.edit {
            replace(0, length, newText)
            selection = newSelection
        }
        externalEditVersion++
    }

    /**
     * Advances [textVersion] and schedules a tokenization pass, without touching [textFieldState]
     * or [externalEditVersion]. Called by the field's `InputTransformation` after it has already
     * mutated [document] and [undoManager] directly to mirror a user keystroke it applied to
     * [textFieldState] itself.
     */
    internal fun bumpTextVersionAndScheduleTokenization() {
        textVersion++
        scheduleTokenization()
    }
}

/** Creates and [remember]s a [CodeEditorState]. */
@Composable
fun rememberCodeEditorState(
    initialText: String = "",
    tokenizer: IncrementalTokenizer = PlainTextTokenizer,
    tokenizeDebounceMs: Long = 150L,
): CodeEditorState = remember(tokenizer) {
    CodeEditorState(initialText, tokenizer, tokenizeDebounceMs)
}
