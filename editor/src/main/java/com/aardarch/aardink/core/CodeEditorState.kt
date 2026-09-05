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
package com.aardarch.aardink.core

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

    // ── Snapshot-backed observable state ──────────────────────────────────────

    /** Incremented every time the document text changes. Triggers recomposition of text-dependent UI. */
    var textVersion by mutableIntStateOf(0)
        private set

    /** Incremented every time the token cache is updated. Triggers recomposition of the syntax overlay. */
    var tokenVersion by mutableIntStateOf(0)
        private set

    /** Current cursor / selection in document-absolute character offsets. */
    var selection by mutableStateOf(TextRange(0))
        internal set

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
        selection = TextRange(0)
        textVersion++
        scheduleTokenization()
    }

    /**
     * Applies a user-initiated text edit: inserts [insertText] (may be empty) after deleting
     * [deleteLength] characters starting at [deleteOffset].
     *
     * Records the edit in [undoManager] and advances [textVersion].
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

        selection = newSelection
        textVersion++
        scheduleTokenization()
    }

    /**
     * Applies a batch of [TextEdit]s atomically to the document, recording the operation in
     * undo history as a single batch and scheduling tokenization.
     */
    fun applyTextEdits(edits: List<TextEdit>) {
        if (edits.isEmpty()) return
        undoManager.flushPendingInsert()

        // Apply edits in reverse range order so modifying earlier offsets doesn't skew subsequent ranges
        val sorted = edits.sortedByDescending { it.range.first }
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

        textVersion++
        scheduleTokenization()
    }

    /**
     * Undoes the most recent edit and returns the new document text (for the IME bridge to sync),
     * or null if there is nothing to undo.
     */
    fun undo(): String? {
        val op = undoManager.undo() ?: return null
        applyOperationToDocument(undoManager.inverseOf(op))
        textVersion++
        scheduleTokenization()
        return document.text
    }

    /**
     * Redoes the previously undone edit and returns the new document text, or null.
     */
    fun redo(): String? {
        val op = undoManager.redo() ?: return null
        applyOperationToDocument(op)
        textVersion++
        scheduleTokenization()
        return document.text
    }

    // ── Tokenization scheduling ───────────────────────────────────────────────

    // Visible for testing — override to use test dispatcher and avoid real background threads
    var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

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

        val updatedTokens = withContext(computeDispatcher) {
            if (dirty == null || tokenCache.tokens.isEmpty()) {
                tokenizer.tokenizeFull(snapshot)
            } else {
                val expandedDirty = if (tokenizer.canSpanLines(dirty.first, tokenCache.tokens)) {
                    0..dirty.last
                } else {
                    dirty
                }
                tokenizer.tokenizeLines(snapshot, expandedDirty, tokenCache.tokens)
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

    private fun applyOperationToDocument(op: EditorUndoManager.EditOperation) {
        when (op) {
            is EditorUndoManager.EditOperation.Insert -> {
                document.insert(op.offset, op.text)
                selection = TextRange(op.offset + op.text.length)
            }

            is EditorUndoManager.EditOperation.Delete -> {
                document.delete(op.offset, op.length)
                selection = TextRange(op.offset)
            }

            is EditorUndoManager.EditOperation.Batch -> {
                op.operations.forEach { applyOperationToDocument(it) }
            }
        }
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
