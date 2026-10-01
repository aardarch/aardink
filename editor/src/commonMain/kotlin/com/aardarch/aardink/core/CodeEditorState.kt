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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.edit.AppliedChange
import com.aardarch.aardink.core.edit.CommandEdit
import com.aardarch.aardink.core.edit.EditKind
import com.aardarch.aardink.core.edit.EditingCommands
import com.aardarch.aardink.core.edit.LineCommands
import com.aardarch.aardink.core.edit.OccurrenceFinder
import com.aardarch.aardink.core.edit.SelectionSet
import com.aardarch.aardink.core.edit.SnippetParser
import com.aardarch.aardink.core.edit.SnippetSession
import com.aardarch.aardink.core.edit.TextChange
import com.aardarch.aardink.core.edit.TextNavigator
import com.aardarch.aardink.core.edit.TypingRules
import com.aardarch.aardink.core.edit.UndoHistory
import com.aardarch.aardink.core.edit.applyChanges
import com.aardarch.aardink.core.edit.mapOffset
import com.aardarch.aardink.core.edit.reapply
import com.aardarch.aardink.core.edit.revert
import com.aardarch.aardink.platform.EditorDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

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
            scheduleTokenization(debounce = false)
        }

    val document = CodeDocument(initialText)
    internal val tokenStore = TokenStore(document)

    /** Bracket depth per line, for bracket-pair colours; kept in step with edits and passes. */
    internal val brackets = BracketIndex(document, tokenStore).also { index ->
        tokenStore.onRetokenized = { lines -> if (lines == null) index.invalidateAll() else index.invalidate(lines.first, lines.last) }
    }

    /**
     * What Tab inserts and one indentation step is: [EditorOptions.tabSize][com.aardarch.aardink.ui.EditorOptions]
     * spaces, or a tab without `insertSpaces`. Set by the layout from its options.
     */
    internal var indentUnit: String = LineCommands.INDENT
    private val history = UndoHistory()

    // ── Snapshot-backed observable state ──────────────────────────────────────

    /** Incremented every time the document text changes. Triggers recomposition of text-dependent UI. */
    var textVersion by mutableIntStateOf(0)
        private set

    /** Incremented every time the token cache is updated. Triggers recomposition of the syntax overlay. */
    var tokenVersion by mutableIntStateOf(0)
        private set

    /**
     * Bumped by every mutation that did not come from typing — every call to [applyEdit],
     * [applyTextEdits], [loadText], [undo], [redo], and the editor's key commands.
     * [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout] uses this to dismiss transient
     * UI (the completion dropdown, a diagnostic tooltip, the code-action menu) whose state
     * describes text that just changed out from under it.
     */
    var externalEditVersion by mutableIntStateOf(0)
        private set

    /**
     * The primary cursor or selection, in document-absolute character offsets. Setting it replaces
     * every selection with this one.
     */
    var selection: TextRange
        get() = currentSelections().primary
        set(value) = setSelections(listOf(value))

    /**
     * Every selection, primary first (as in Monaco's `getSelections`). A range's `start` is its
     * anchor and its `end` the caret, so a range selected backwards has `start > end`.
     */
    val selections: List<TextRange>
        get() = currentSelections().ranges

    /**
     * Replaces the selections; the first becomes the primary one. Ranges that overlap are merged;
     * each is clamped to the document.
     *
     * @throws IllegalArgumentException if [selections] is empty.
     */
    fun setSelections(selections: List<TextRange>) {
        applySelections(SelectionSet.of(selections).clampedTo(document.length))
    }

    /** The selections, snapshot state: reading them in composition or drawing observes them. */
    private var selectionSet by mutableStateOf(SelectionSet.caret(0))

    internal fun currentSelections(): SelectionSet = selectionSet

    private fun applySelections(set: SelectionSet) {
        selectionSet = set
        leaveSnippetIfOutside()
    }

    /** Whether [undo] would change anything. Snapshot state: a toolbar button can observe it. */
    var canUndo by mutableStateOf(false)
        private set

    /** Whether [redo] would change anything. */
    var canRedo by mutableStateOf(false)
        private set

    /**
     * Monaco's alternative version id: a new value for every edit, and after [undo] or [redo] the
     * value the document had at that point. Store it when saving; the document is unchanged since
     * the save exactly while it still equals the stored value, however it got there.
     */
    var alternativeVersionId by mutableLongStateOf(0L)
        private set

    /** What the last change to the document was: an edit, an undo, a redo, or a whole new text. */
    var lastChangeKind by mutableStateOf(EditChangeKind.Flush)
        private set

    /**
     * Ends the current undo step, so the next edit starts a new one (Monaco's `pushUndoStop`).
     * Typing otherwise joins one step until the caret moves or a space follows a word; call this
     * before a programmatic edit that should undo on its own, or after saving.
     */
    fun pushUndoStop() {
        history.pushStop()
    }

    private fun refreshHistoryState() {
        canUndo = history.canUndo
        canRedo = history.canRedo
        alternativeVersionId = history.alternativeVersionId
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

    /** Bumped by [revalidate]; [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout] asks again on each. */
    internal var revalidations by mutableIntStateOf(0)
        private set

    /**
     * Asks the language service for diagnostics again now, without waiting for an edit: for when
     * what they depend on outside the text has changed (another file, a setting). An edit made
     * before they arrive is asked about once typing pauses, as usual. Does nothing while the
     * layout shows a list the host passed rather than collecting its own.
     */
    fun revalidate() {
        revalidations++
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
        endSnippet()
        document.replaceAll(newText)
        // A new document, not an edit: highlight it with a full pass.
        lastTokenizerResult = null
        history.clear()
        placeSelections(SelectionSet.caret(0))
        lastChangeKind = EditChangeKind.Flush
        refreshHistoryState()
        textVersion++
        scheduleTokenization(debounce = false)
    }

    /**
     * Applies a programmatic text edit — from the keyboard toolbar, find/replace, a completion
     * accept, or a quick fix — inserting [insertText] (may be empty) after deleting
     * [deleteLength] characters starting at [deleteOffset], then placing the selection at
     * [newSelection]. One undo step.
     */
    fun applyEdit(deleteOffset: Int, deleteLength: Int, insertText: String, newSelection: TextRange) {
        val start = deleteOffset.coerceIn(0, document.length)
        val end = (deleteOffset + deleteLength).coerceIn(start, document.length)
        applyChanges(listOf(TextChange(start, end, insertText)), EditKind.Other) { SelectionSet.single(newSelection) }
    }

    /**
     * Applies a batch of [TextEdit]s atomically to the document as one undo step, and carries
     * every selection through them: a rename or import inserted above a caret must not leave it at
     * a number that now points into unrelated text. A caret inside a replaced range ends up after
     * the replacement. Several inserts at one offset land in the order given, as LSP specifies.
     */
    fun applyTextEdits(edits: List<TextEdit>) {
        if (edits.isEmpty()) return
        val changes = edits.map { edit ->
            val start = edit.range.first.coerceIn(0, document.length)
            TextChange(start, (edit.range.last + 1).coerceIn(start, document.length), edit.newText)
        }
        val before = currentSelections()
        applyChanges(changes, EditKind.Other) { before.map { mapOffset(it, changes) } }
    }

    /**
     * Undoes the most recent undo step, restoring the selections from before it, and returns the
     * new document text (for a host that wants to react to it), or null if there is nothing to undo.
     */
    fun undo(): String? {
        val entry = history.undo() ?: return null
        document.revert(entry.changes)
        afterHistoryStep(entry.selectionsBefore, EditChangeKind.Undo)
        return document.text
    }

    /** Redoes the last undone step and returns the new document text, or null. */
    fun redo(): String? {
        val entry = history.redo() ?: return null
        document.reapply(entry.changes)
        afterHistoryStep(entry.selectionsAfter, EditChangeKind.Redo)
        return document.text
    }

    private fun afterHistoryStep(selections: SelectionSet, kind: EditChangeKind) {
        endSnippet()
        placeSelections(selections.clampedTo(document.length))
        lastChangeKind = kind
        refreshHistoryState()
        textVersion++
        scheduleTokenization()
    }

    // -- Line commands --------------------------------------------------------

    /**
     * Tab. With only carets, inserts an indent at each; with a selection, indents every line it
     * touches and then selects those lines whole, so Tab can be pressed repeatedly.
     */
    fun indentSelection() {
        val before = currentSelections()
        val edit = LineCommands.indent(document, before, indentUnit)
        if (before.ranges.all { it.collapsed }) applyCommand(edit) else applyKeepingLinesSelected(edit.changes, before)
    }

    /**
     * Shift+Tab. Removes up to one indent (four spaces, or one tab) from every line the selections
     * touch; lines with no leading whitespace are left alone rather than eating real text.
     */
    fun outdentSelection() {
        val before = currentSelections()
        val edit = LineCommands.outdent(document, before, indentUnit) ?: return
        if (before.ranges.all { it.collapsed }) applyCommand(edit) else applyKeepingLinesSelected(edit.changes, before)
    }

    /** Whether [offset] is code rather than inside a string or a comment, as the tokens have it. */
    internal fun isCode(offset: Int): Boolean {
        val (line, column) = document.offsetToLineCol(offset)
        val runs = tokenStore.lineTokens(line)
        for (i in 0 until runs.size) {
            if (runs.start(i) > column) break
            if (column < runs.end(i)) {
                val type = runs.types[i]
                return !type.isCommentOrString
            }
        }
        return true
    }

    /** The bracket pair the primary caret touches, or null. */
    internal fun matchingBracket(): BracketMatcher.Match? {
        val primary = selection
        if (!primary.collapsed) return null
        return BracketMatcher.find(document, primary.end, ::isCode)
    }

    /** Ctrl+/: toggles line comments (or a block comment) per [IncrementalTokenizer.commentSyntax]. */
    internal fun toggleComment(): Boolean = applyCommand(LineCommands.toggleComment(document, currentSelections(), tokenizer.commentSyntax))

    /** Alt+Up / Alt+Down: moves the selected lines. */
    internal fun moveLines(up: Boolean): Boolean = applyCommand(LineCommands.moveLines(document, currentSelections(), up))

    /** Shift+Alt+Down / Up: copies the selected lines below themselves. */
    internal fun copyLines(down: Boolean): Boolean = applyCommand(LineCommands.copyLines(document, currentSelections(), down))

    /** Ctrl+Shift+K: deletes the selected lines. */
    internal fun deleteLines(): Boolean = applyCommand(LineCommands.deleteLines(document, currentSelections()))

    /** Ctrl/Cmd+D: selects the word at each caret, then adds the next occurrence of the selection. */
    internal fun addNextOccurrence(): Boolean {
        val current = currentSelections()
        val primary = current.primary
        // Whole words when the selection is exactly a word, as after the first Ctrl+D.
        val word = TextNavigator.wordAt(document, primary.min)
        val wholeWord = !primary.collapsed && word.first == primary.min && word.last + 1 == primary.max
        val next = OccurrenceFinder.addNext(document, current, wholeWord) ?: return false
        applySelections(next)
        return true
    }

    /** Ctrl+Shift+L: selects every occurrence of the primary selection (or of the word at the caret). */
    internal fun selectAllOccurrences(): Boolean {
        val all = OccurrenceFinder.selectAll(document, currentSelections()) ?: return false
        applySelections(all)
        return true
    }

    private fun applyCommand(edit: CommandEdit?): Boolean {
        edit ?: return false
        return applyChanges(edit.changes, EditKind.Other) { edit.selectionsAfter }
    }

    /** Applies [changes] and selects every line the [before] selections covered, whole. */
    private fun applyKeepingLinesSelected(changes: List<TextChange>, before: SelectionSet) {
        // Indenting changes no line numbers, so the blocks stay valid across the edit.
        val lines = before.ranges.map { range -> LineCommands.lineBlocks(document, SelectionSet.single(range)).first() }
        applyChanges(changes, EditKind.Other) {
            SelectionSet.of(lines.map { TextRange(document.lineStart(it.first), document.lineEnd(it.last)) })
        }
    }

    /**
     * The one path every programmatic edit takes: applies [changes] to the document, records them
     * as one undo step (joining the open one when [kind] continues it), places the selections
     * [selectionsAfter] gives (evaluated once the document has changed), and schedules tokenization.
     * Returns whether the text changed.
     */
    private fun applyChanges(
        changes: List<TextChange>,
        kind: EditKind,
        external: Boolean = true,
        selectionsAfter: () -> SelectionSet,
    ): Boolean {
        val before = currentSelections()
        val applied = document.applyChanges(changes)
        val after = selectionsAfter().clampedTo(document.length)
        if (applied.isEmpty()) {
            applySelections(after)
            return false
        }
        history.record(kind, applied, before, after)
        afterEdit(after, external)
        return true
    }

    /** What every edit ends with: the selections placed, history state and versions updated, tokenization scheduled. */
    private fun afterEdit(selections: SelectionSet, external: Boolean) {
        placeSelections(selections, external)
        lastChangeKind = EditChangeKind.Edit
        refreshHistoryState()
        textVersion++
        scheduleTokenization()
    }

    // ── Snippets ──────────────────────────────────────────────────────────────

    /** The snippet whose tab stops Tab steps through, while one is being filled in. */
    internal var snippet: SnippetSession? by mutableStateOf(null)
        private set

    /**
     * Inserts [template], in VS Code's snippet syntax, in place of [target] (inclusive, as a
     * completion's range), with [additionalEdits] in the same undo step, and selects its first tab
     * stop. With [adjustWhitespace], the snippet's lines follow the indentation of the line it lands
     * on and its leading tabs become [indentUnit] (Monaco does so unless `KeepWhitespace` is set).
     */
    internal fun insertSnippet(
        target: IntRange,
        template: String,
        additionalEdits: List<TextEdit> = emptyList(),
        adjustWhitespace: Boolean = true,
    ) {
        endSnippet()
        val start = target.first.coerceIn(0, document.length)
        val end = (target.last + 1).coerceIn(start, document.length)
        val line = document.offsetToLineCol(start).first
        val lineStart = document.lineStart(line)
        val lineText = document.subSequence(lineStart, document.lineEnd(line)).toString()
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val expanded = SnippetParser.expand(
            template,
            variable = { name -> snippetVariable(name, line, lineText, start - lineStart) },
            indent = if (adjustWhitespace) indent else null,
            indentUnit = if (adjustWhitespace) indentUnit else null,
        )
        val additional = additionalEdits.map { edit ->
            val from = edit.range.first.coerceIn(0, document.length)
            TextChange(from, (edit.range.last + 1).coerceIn(from, document.length), edit.newText)
        }
        val changes = additional + TextChange(start, end, expanded.text)
        // Where the snippet's text starts once every change is in: edits above it move it, and one
        // inserted at the same place (an import at the start of the document) lands before it.
        val base = mapOffset(start, additional, stickToEnd = true)
        val session = SnippetSession(base, expanded)
        applyChanges(changes, EditKind.Other) { session.selections() }
        // Only a final stop: the caret goes there and there is nothing to step through.
        if (session.isAtFinal) return
        document.addChangeListener(session)
        snippet = session
        history.pushStop()
    }

    /**
     * Tab ([forward]) or Shift+Tab while a snippet is being filled in: selects the next or the
     * previous tab stop. Reaching the final one ends the snippet. Returns false, doing nothing,
     * when there is no snippet (or no previous stop), so the key does its usual work.
     */
    internal fun moveInSnippet(forward: Boolean): Boolean {
        val session = snippet ?: return false
        if (session.isBroken) {
            endSnippet()
            return false
        }
        if (!session.move(forward)) return false
        history.pushStop()
        val selections = session.selections().clampedTo(document.length)
        if (session.isAtFinal) endSnippet()
        applySelections(selections)
        return true
    }

    /** Stops filling in the snippet: Escape, or the caret went elsewhere. */
    internal fun endSnippet() {
        val session = snippet ?: return
        document.removeChangeListener(session)
        snippet = null
    }

    /** Ends the snippet when the caret left its current tab stop, as Monaco does. */
    private fun leaveSnippetIfOutside() {
        val session = snippet ?: return
        if (session.isBroken || !session.contains(selectionSet.primary.start) || !session.contains(selectionSet.primary.end)) endSnippet()
    }

    /** The value of the snippet variable [name] for a snippet inserted at [column] of [line]. */
    private fun snippetVariable(name: String, line: Int, lineText: String, column: Int): String? = when (name) {
        "TM_SELECTED_TEXT" -> selectionSet.primary.let { if (it.collapsed) "" else document.subSequence(it.min, it.max).toString() }

        "TM_CURRENT_LINE" -> lineText

        "TM_CURRENT_WORD" -> {
            var from = column.coerceIn(0, lineText.length)
            var to = from
            while (from > 0 && lineText[from - 1].isWordChar()) from--
            while (to < lineText.length && lineText[to].isWordChar()) to++
            lineText.substring(from, to)
        }

        "TM_LINE_INDEX" -> line.toString()

        "TM_LINE_NUMBER" -> (line + 1).toString()

        else -> null
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    // ── Input (the editor's own renderer) ────────────────────────────────────

    /** A character [applyInput] typed at [offset], and how many characters were auto-closed after it. */
    internal class TypedInput(val offset: Int, val char: Char, val autoCloseLength: Int)

    /**
     * Text from the keyboard or an input method: replaces [start, end) with [text] and places the
     * primary caret at [selectionAfter] (after the text when null). An edit around the primary
     * selection (typing over it, or an input method's backspace replacing a character before it)
     * is repeated at every other selection, as with several cursors in VS Code; while [composing],
     * only when the composition ends ([endComposition]). A single character typed at a caret gets
     * the typing rules ([TypingRules]: smart indent, auto-close) from [service], except while
     * [composing].
     *
     * Not an external edit: popups that follow typing stay up. Returns the character when one was
     * typed at a caret, for the completion trigger; null for anything else.
     */
    internal fun applyInput(
        start: Int,
        end: Int,
        text: String,
        selectionAfter: TextRange? = null,
        composing: Boolean = false,
        service: LanguageService? = null,
    ): TypedInput? {
        val normalized = TypingRules.normalizeLineEndings(text)
        val from = start.coerceIn(0, document.length)
        val to = end.coerceIn(from, document.length)
        val before = currentSelections()
        val primary = before.primary
        // How far the edit reaches past the primary selection on either side: an input method's
        // backspace takes one character before it, typing none.
        val reachBefore = primary.min - from
        val reachAfter = to - primary.max
        val aroundPrimary = !before.isSingle && reachBefore >= 0 && reachAfter >= 0
        val mirrored = !composing && aroundPrimary
        val changes = if (mirrored) {
            mirroredChanges(before.inDocumentOrder, reachBefore, reachAfter, normalized)
        } else {
            listOf(TextChange(from, to, normalized))
        }
        if (composing) trackComposition(from, to, normalized.length, before, aroundPrimary)
        val applied = document.applyChanges(changes).toMutableList()
        // A caret the input method placed means nothing once normalisation changed the text's length.
        val primaryAfter = selectionAfter?.takeIf { normalized.length == text.length } ?: TextRange(from + normalized.length)
        var after = if (mirrored) {
            SelectionSet.of(before.ranges.map { TextRange(mapOffset(it.max, changes)) })
        } else {
            SelectionSet.of(
                listOf(primaryAfter) + before.ranges.drop(1).map {
                    TextRange(mapOffset(it.start, changes), mapOffset(it.end, changes))
                },
            )
        }.clampedTo(document.length)
        if (applied.isEmpty()) {
            applySelections(after)
            return null
        }
        val typed = normalized.singleOrNull()?.takeIf { changes.all { it.start == it.end } }
        var autoCloseLength = 0
        if (typed != null && service != null && !composing) {
            // The follow-up at each caret the character was typed at, in document order; every
            // insertion moves the carets after it.
            val carets = after.ranges.withIndex().filter { mirrored || it.index == 0 }.sortedBy { it.value.end }
            val placed = after.ranges.toMutableList()
            var shift = 0
            for ((index, caret) in carets) {
                val position = caret.end + shift
                placed[index] = TextRange(position)
                if (position == 0 || document[position - 1] != typed) continue
                val followUp = TypingRules.afterTyping(document, service, position - 1, typed) ?: continue
                applied += document.applyChanges(listOf(TextChange(followUp.insertAt, followUp.insertAt, followUp.text)))
                placed[index] = TextRange(followUp.caret)
                shift += followUp.text.length
                if (index == 0 && typed != '\n') autoCloseLength = followUp.text.length
            }
            after = SelectionSet.of(placed).clampedTo(document.length)
        }
        val primaryChange = changes.firstOrNull { it.start <= primary.min && it.end >= primary.max } ?: changes.first()
        val kind = if (composing) EditKind.Composing else kindOfInput(primaryChange, before)
        history.record(kind, applied, before, after)
        afterEdit(after, external = false)
        compositionMirror?.let {
            it.selections = currentSelections()
            it.version = textVersion
        }
        return typed?.let { TypedInput(from, it, autoCloseLength) }
    }

    /**
     * [text] in place of each of [ranges] (in document order), reaching [before] characters
     * before each and [after] after it. Replacements that would overlap merge into one.
     */
    private fun mirroredChanges(ranges: List<TextRange>, before: Int, after: Int, text: String): List<TextChange> {
        val changes = ArrayList<TextChange>(ranges.size)
        for (range in ranges) {
            val start = (range.min - before).coerceAtLeast(0)
            val end = (range.max + after).coerceAtMost(document.length)
            val last = changes.lastOrNull()
            if (last != null && start < last.end) {
                changes[changes.size - 1] = TextChange(last.start, maxOf(last.end, end), text)
            } else {
                changes.add(TextChange(start, end, text))
            }
        }
        return changes
    }

    /**
     * An input method composing while there are several selections: the composition happens at
     * the primary one only, and when it ends its net edit is repeated at the others, as in VS
     * Code. The composition has replaced the primary selection, with [reachBefore] characters
     * before it and [reachAfter] after it, by the text now at [start, end).
     */
    private class CompositionMirror(var start: Int, var end: Int, var reachBefore: Int, var reachAfter: Int) {
        /** What the composition's last step left; anything else changing them cancels the mirror. */
        var selections: SelectionSet? = null
        var version: Int = -1
    }

    private var compositionMirror: CompositionMirror? = null

    /** Follows a composing edit of [from, to) to [inserted] characters, for [endComposition]. */
    private fun trackComposition(from: Int, to: Int, inserted: Int, before: SelectionSet, aroundPrimary: Boolean) {
        val mirror = compositionMirror
        if (mirror == null || mirror.version != textVersion || mirror.selections != before) {
            // The composition's first step (or the first since something else happened).
            compositionMirror = if (aroundPrimary) {
                CompositionMirror(from, from + inserted, before.primary.min - from, to - before.primary.max)
            } else {
                null
            }
            return
        }
        // A later step may reach into text on either side that the composition had not touched.
        if (from < mirror.start) mirror.reachBefore += mirror.start - from
        if (to > mirror.end) mirror.reachAfter += to - mirror.end
        val start = minOf(mirror.start, from)
        mirror.end = maxOf(mirror.end, to) - (to - from) + inserted
        mirror.start = start
    }

    /**
     * An input method's composition ended, committed or left as it stands: what it did at the
     * primary selection is repeated at the others, in the same undo step. Nothing with one
     * selection, or when anything else has changed the text or the selections since.
     */
    internal fun endComposition() {
        val mirror = compositionMirror ?: return
        compositionMirror = null
        val current = currentSelections()
        if (current.isSingle || mirror.version != textVersion || mirror.selections != current) return
        val text = document.subSequence(mirror.start, mirror.end).toString()
        val others = current.ranges.drop(1).sortedBy { it.min }
        val changes = mirroredChanges(others, mirror.reachBefore, mirror.reachAfter, text)
            .filter { it.end <= mirror.start || it.start >= mirror.end }
        if (changes.isEmpty()) return
        applyChanges(changes, EditKind.Composing, external = false) {
            SelectionSet.of(
                listOf(TextRange(mapOffset(current.primary.start, changes), mapOffset(current.primary.end, changes))) +
                    current.ranges.drop(1).map { TextRange(mapOffset(it.max, changes)) },
            )
        }
    }

    /** How the undo history groups an input edit: typing, backspace, forward delete, or anything else. */
    private fun kindOfInput(change: TextChange, before: SelectionSet): EditKind {
        val primary = before.primary
        return when {
            change.text.isNotEmpty() -> TypingRules.kindOfTyping(change.text, change.end - change.start)
            primary.collapsed && primary.start == change.end -> EditKind.DeletingLeft
            primary.collapsed && primary.start == change.start -> EditKind.DeletingRight
            else -> EditKind.Other
        }
    }

    /** Backspace, or Ctrl+Backspace with [word], at every selection. */
    internal fun deleteLeft(word: Boolean = false): Boolean {
        val before = currentSelections()
        val edit = EditingCommands.deleteLeft(document, before, word) ?: return false
        val kind = if (!word && before.ranges.all { it.collapsed }) EditKind.DeletingLeft else EditKind.Other
        return applyChanges(edit.changes, kind) { edit.selectionsAfter }
    }

    /** Delete, or Ctrl+Delete with [word], at every selection. */
    internal fun deleteRight(word: Boolean = false): Boolean {
        val before = currentSelections()
        val edit = EditingCommands.deleteRight(document, before, word) ?: return false
        val kind = if (!word && before.ranges.all { it.collapsed }) EditKind.DeletingRight else EditKind.Other
        return applyChanges(edit.changes, kind) { edit.selectionsAfter }
    }

    /** What copying the selections puts on the clipboard (their lines, when all are carets). */
    internal fun copySelections(): EditingCommands.Copied = EditingCommands.copy(document, currentSelections())

    /** Cuts the selections (their lines, when all are carets) and returns what to put on the clipboard. */
    internal fun cutSelections(): EditingCommands.Copied? {
        val (copied, edit) = EditingCommands.cut(document, currentSelections()) ?: return null
        applyChanges(edit.changes, EditKind.Other) { edit.selectionsAfter }
        return copied
    }

    /** Pastes [text] at every selection; see [EditingCommands.paste] for [wholeLines] and spreading lines. */
    internal fun paste(text: String, wholeLines: Boolean = false): Boolean {
        val edit = EditingCommands.paste(document, currentSelections(), text, wholeLines) ?: return false
        return applyChanges(edit.changes, EditKind.Other) { edit.selectionsAfter }
    }

    /** Ctrl+A. */
    internal fun selectAll() {
        applySelections(SelectionSet.single(TextRange(0, document.length)))
    }

    /** Moves every caret to [target] of its selection; with [extend], the anchors stay (Shift+arrows). */
    internal fun moveSelections(extend: Boolean, target: (TextRange) -> Int) {
        applySelections(EditingCommands.move(currentSelections(), extend, target).clampedTo(document.length))
    }

    /** Replaces the selections, clamped to the document. */
    internal fun replaceSelections(set: SelectionSet) {
        applySelections(set.clampedTo(document.length))
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
        if (document.length > 0) scheduleTokenization(debounce = false)
    }

    /**
     * Schedules an incremental tokenization pass after [tokenizeDebounceMs].
     * Cancels any in-flight pass so only the final state of a burst of edits is processed.
     */
    fun scheduleTokenization() {
        scheduleTokenization(debounce = true)
    }

    /**
     * Without [debounce] the pass starts at once: a new document or a new tokenizer is not a
     * burst of typing, and its colours should not wait for one to end.
     */
    private fun scheduleTokenization(debounce: Boolean) {
        tokenizationJob?.cancel()
        tokenizationJob = tokenizationScope.launch {
            if (debounce && tokenizeDebounceMs > 0) delay(tokenizeDebounceMs)
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

    /**
     * Places the selections after a change to the text, and bumps [externalEditVersion] when the
     * change did not come from typing ([external]).
     */
    private fun placeSelections(selections: SelectionSet, external: Boolean = true) {
        selectionSet = selections
        leaveSnippetIfOutside()
        if (external) externalEditVersion++
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
