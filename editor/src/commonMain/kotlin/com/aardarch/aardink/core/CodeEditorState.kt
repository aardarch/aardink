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
import kotlin.time.TimeSource

/** One indentation step. Spaces, not a tab, to match what smartIndent already inserts. */
private const val INDENT: String = "    "

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
 * @param tokenizer The tokenizer used for syntax highlighting; see the [tokenizer] property.
 * @param tokenizeDebounceMs Delay (ms) after the last keystroke before incremental tokenization
 *   runs. A 0 value tokenizes synchronously (use only for tests or small documents).
 * @param scope The scope tokenization is scheduled on. Defaults to `Dispatchers.Main`, which
 *   Android and the browser provide out of the box. **On desktop JVM there is no Main
 *   dispatcher unless `kotlinx-coroutines-swing` (or `-javafx`) is on the runtime classpath**;
 *   without it, constructing a state with the default scope throws at runtime. Add that
 *   dependency, or pass a scope of your own.
 */
@Stable
class CodeEditorState(
    initialText: String = "",
    tokenizer: IncrementalTokenizer = PlainTextTokenizer,
    val tokenizeDebounceMs: Long = 150L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    /**
     * The tokenizer used for syntax highlighting. Defaults to [PlainTextTokenizer].
     *
     * Assigning a different one re-highlights the document with it and keeps everything else:
     * text, selection, and undo history. That is how a host switches the language of an open
     * document.
     */
    var tokenizer: IncrementalTokenizer = tokenizer
        set(value) {
            if (value === field) return
            field = value
            lastTokenizerResult = null
            tokenizerIsIncremental = null
            scheduleTokenization()
        }

    val document = CodeDocument(initialText)
    internal val tokenStore = TokenStore(document)
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
     * The syntax tokens on [line] (0-based), with document-absolute offsets, as of the last
     * tokenization pass. Read [tokenVersion] to recompose when they change. Between an edit and the
     * next pass the tokens of the edited lines are shifted to follow the text, so they may briefly
     * miss a character or two. A token that spans lines is returned as one token per line.
     */
    fun tokensForLine(line: Int): List<Token> = tokenStore.tokensForLine(line)

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
        // A new document, not an edit: highlight it with a full pass.
        lastTokenizerResult = null
        undoManager.clear()
        syncFieldToDocument(TextRange(0))
        // After the sync, not before: syncFieldToDocument edits the field, and that edit pushes
        // its own entry onto the field's undo stack. Clearing first left the freshly loaded
        // document undoable back to the previous one, so a platform-level undo gesture could
        // resurrect text the document no longer has.
        textFieldState.undoState.clearHistory()
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
            document.subSequence(start, end).toString()
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
                else -> document.subSequence(start, end).toString() // overlapping edits: fall back to live text
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

    // -- Indentation ----------------------------------------------------------

    /**
     * Indents every line touched by the selection by [INDENT] spaces.
     *
     * With a collapsed cursor and nothing selected this inserts an indent at the cursor, which
     * is what Tab does in every editor. With a selection it shifts whole lines and keeps the
     * selection covering them, so Tab can be pressed repeatedly.
     */
    fun indentSelection() {
        val sel = selection
        if (sel.collapsed) {
            applyEdit(sel.start, 0, INDENT, TextRange(sel.start + INDENT.length))
            return
        }
        val (firstLine, lastLine) = selectedLineRange(sel)
        val edits = (firstLine..lastLine).map { line ->
            val start = document.lineStart(line)
            TextEdit(range = start until start, newText = INDENT)
        }
        if (edits.isEmpty()) return
        applyTextEdits(edits)
        selection = TextRange(
            document.lineStart(firstLine),
            document.lineEnd(lastLine),
        )
    }

    /**
     * Removes up to [INDENT] leading spaces (or one leading tab) from every line the selection
     * touches. Lines with no leading whitespace are left alone rather than eating real text.
     */
    fun outdentSelection() {
        val sel = selection
        val (firstLine, lastLine) = selectedLineRange(sel)
        val edits = (firstLine..lastLine).mapNotNull { line ->
            val start = document.lineStart(line)
            val text = document.lineText(line)
            val removable = leadingIndentWidth(text)
            if (removable == 0) null else TextEdit(range = start until (start + removable), newText = "")
        }
        if (edits.isEmpty()) return
        applyTextEdits(edits)
        if (!sel.collapsed) {
            selection = TextRange(document.lineStart(firstLine), document.lineEnd(lastLine))
        }
    }

    /** The inclusive range of lines the selection touches. */
    private fun selectedLineRange(sel: TextRange): Pair<Int, Int> {
        val first = document.offsetToLineCol(sel.min).first
        // A selection ending exactly at a line start has not really reached that line; treating
        // it as included would indent a line the user never highlighted.
        val endOffset = if (sel.max > sel.min && sel.max == document.lineStart(
                document.offsetToLineCol(sel.max).first,
            )
        ) {
            sel.max - 1
        } else {
            sel.max
        }
        val last = document.offsetToLineCol(endOffset.coerceAtLeast(sel.min)).first
        return first to maxOf(first, last)
    }

    /** How many characters of leading indentation one outdent step should remove. */
    private fun leadingIndentWidth(lineText: String): Int {
        if (lineText.startsWith("\t")) return 1
        var spaces = 0
        while (spaces < INDENT.length && spaces < lineText.length && lineText[spaces] == ' ') {
            spaces++
        }
        return spaces
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

    /**
     * Whether [computeDispatcher] shares a thread with the UI, which is what switches on the
     * [EditorLimits] guards. Mirrors [EditorDispatchers.computeIsMainThread]; tests flip it to
     * exercise the wasmJs paths on the JVM.
     */
    internal var computeOnMainThread: Boolean = EditorDispatchers.computeIsMainThread

    /**
     * True when the document is too large to tokenize or fold on this platform at all (see
     * [EditorLimits.plainTextFallbackChars]); it then renders in the plain text colour.
     */
    internal val exceedsAnalysisLimit: Boolean
        get() = computeOnMainThread && document.length > EditorLimits.plainTextFallbackChars

    private var tokenizationJob: Job? = null
    private val tokenizationScope = scope

    /**
     * The list the tokenizer returned from its last pass over this document, passed back to it as
     * `previousTokens`: the built-in tokenizers recognise their own result and rescan only what
     * changed since. Null until a pass completes, and after [loadText] or a new [tokenizer].
     */
    private var lastTokenizerResult: List<Token>? = null

    /**
     * On a single-threaded host, whether [IncrementalTokenizer.tokenizeLines] is cheap for this
     * tokenizer (null until a call shows it). A tokenizer whose partial pass is really a full scan
     * blocks the UI for as long as that scan takes, so once one call takes longer than
     * [SLOW_PARTIAL_PASS_MS] the editor goes back to chunked full passes for it.
     */
    private var tokenizerIsIncremental: Boolean? = null

    /**
     * The document lines on screen, reported by the layout. Highlighted first when a large
     * document is tokenized from scratch on a single-threaded host.
     */
    internal var visibleLines: IntRange? = null

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
        if (exceedsAnalysisLimit) {
            // Also clears dirtyLines, so shrinking back under the limit retokenizes from scratch.
            tokenStore.replaceAll(emptyList())
            lastTokenizerResult = null
            tokenVersion++
            return
        }

        val snapshot = document.text
        val version = document.version
        val dirty = document.dirtyLines
        val previous = lastTokenizerResult
        val large = computeOnMainThread && snapshot.length > EditorLimits.cooperativeTokenizeThresholdChars

        // A partial pass: only what changed since the last one, when there is a last one.
        if (dirty != null && previous != null && !(large && tokenizerIsIncremental == false)) {
            val lines = if (tokenizer.canSpanLines(dirty.first, previous)) 0..dirty.last else dirty
            val started = TimeSource.Monotonic.markNow()
            val updated = withContext(computeDispatcher) { tokenizer.tokenizeLines(snapshot, lines, previous) }
            if (large) tokenizerIsIncremental = started.elapsedNow().inWholeMilliseconds < SLOW_PARTIAL_PASS_MS
            // Every edit schedules a new pass and cancels this one; the version check covers an
            // edit made straight through the public CodeDocument, which schedules nothing.
            if (document.version != version) return
            tokenStore.merge(lines, updated)
            lastTokenizerResult = updated
            tokenVersion++
            return
        }

        // A full pass. On a single-threaded host a large document is tokenized in chunks that
        // yield to the UI, after a quick provisional pass over the lines on screen.
        val allTokens = if (large) {
            if (tokenizerIsIncremental != false) highlightVisibleLinesFirst(snapshot, version)
            withContext(computeDispatcher) { tokenizer.tokenizeFullCooperative(snapshot) }
        } else {
            withContext(computeDispatcher) { tokenizer.tokenizeFull(snapshot) }
        }
        if (document.version != version) return
        tokenStore.replaceAll(allTokens)
        lastTokenizerResult = allTokens
        tokenVersion++
    }

    /**
     * Tokenizes just the visible lines, with no previous tokens (a provisional pass the built-in
     * tokenizers answer by scanning only those lines), so they are coloured before the full pass
     * over a large document finishes.
     */
    private fun highlightVisibleLinesFirst(snapshot: String, version: Long) {
        val last = document.lineCount - 1
        val visible = visibleLines ?: 0..INITIAL_VISIBLE_LINES
        val lines = visible.first.coerceIn(0, last)..visible.last.coerceIn(0, last)
        val started = TimeSource.Monotonic.markNow()
        val provisional = tokenizer.tokenizeLines(snapshot, lines, emptyList())
        if (started.elapsedNow().inWholeMilliseconds >= SLOW_PARTIAL_PASS_MS) tokenizerIsIncremental = false
        if (document.version != version) return
        tokenStore.merge(lines, provisional)
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

/**
 * Creates and [remember]s a [CodeEditorState]; a different [tokenizer] creates a new one from
 * [initialText]. To switch the language of an open document and keep its text and undo history,
 * assign [CodeEditorState.tokenizer] instead.
 */
@Composable
fun rememberCodeEditorState(
    initialText: String = "",
    tokenizer: IncrementalTokenizer = PlainTextTokenizer,
    tokenizeDebounceMs: Long = 150L,
): CodeEditorState = remember(tokenizer) {
    CodeEditorState(initialText, tokenizer, tokenizeDebounceMs)
}

/** Lines highlighted first when the layout has not reported what is on screen yet. */
private const val INITIAL_VISIBLE_LINES = 100

/** A partial tokenization pass slower than this is treated as a full scan on single-threaded hosts. */
private const val SLOW_PARTIAL_PASS_MS = 50L
