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
package com.aardarch.aardink.ui.view

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.SelectionChangeReason
import com.aardarch.aardink.core.edit.EditingCommands
import com.aardarch.aardink.core.edit.SelectionSet
import com.aardarch.aardink.core.edit.TextNavigator
import com.aardarch.aardink.platform.PlatformInfo
import com.aardarch.aardink.platform.TypedTextDecoder
import com.aardarch.aardink.platform.editorClipboardFromKeys
import com.aardarch.aardink.platform.editorImeWindowed
import com.aardarch.aardink.platform.readPlainText
import com.aardarch.aardink.platform.writePlainText
import com.aardarch.aardink.ui.EditorMenuItem
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the host layout does for commands that are not about the text: panels, dialogs, popups. */
internal class EditorHostActions(
    val onFind: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onGoToLine: () -> Unit = {},
    /** Escape with one caret: returns whether it closed something (a popup, the find panel). */
    val onEscape: () -> Boolean = { false },
    /** Ctrl+Space. */
    val onTriggerSuggest: () -> Unit = {},
    /** After every input edit, with the character typed when it was one (completion follows it). */
    val onInput: (CodeEditorState.TypedInput?) -> Unit = {},
    /** F12 / Shift+F12 / Shift+Alt+F; each returns whether it did anything. */
    val onGoToDefinition: () -> Boolean = { false },
    val onFindReferences: () -> Boolean = { false },
    val onFormat: () -> Boolean = { false },
    /** Sees every key first, for a popup that takes keys while it is up; returns whether it took it. */
    val onPopupKey: (KeyEvent) -> Boolean = { false },
    /** Show the documentation of the symbol at an offset (the touch menu's "Info"). Null: no such item. */
    val onShowInfo: ((Int) -> Unit)? = null,
    /** F8 (forward) / Shift+F8: to the next or previous problem; returns whether there was one. */
    val onGoToProblem: (forward: Boolean) -> Boolean = { false },
    /** Ctrl+.: the quick fixes at the caret. */
    val onQuickFix: () -> Boolean = { false },
    /** F2: rename the symbol at the caret. */
    val onRename: () -> Boolean = { false },
    /** F3 (forward) / Shift+F3: the next or previous find match. */
    val onFindNext: (forward: Boolean) -> Boolean = { false },
    /** Alt+Enter while the find panel shows matches: a selection at every one. */
    val onSelectAllMatches: () -> Boolean = { false },
    /** A single tap (touch) placed the caret at an offset in the text. */
    val onTapText: (Int) -> Unit = {},
    /** Ctrl/Cmd+click at an offset: opens the link there; returns whether there was one. */
    val onOpenLink: (Int) -> Boolean = { false },
)

/**
 * Everything the editor's own renderer does with input: keys, typed text, input-method edits
 * (through [ime]), the pointer (through [pointer]), the clipboard, and the caret's place on
 * screen for the input method ([EditorImeGeometry]).
 */
@Stable
internal class EditorController(val state: CodeEditorState, val view: EditorView) : EditorImeGeometry {

    val ime = EditorImeAdapter(state, editorImeWindowed)

    /** Column selections, from the keys and the mouse alike, so one carries on from the other. */
    val columns = ColumnSelector(view)

    val pointer = EditorPointerHandler(view, columns)

    private val decoder = TypedTextDecoder()

    var readOnly by mutableStateOf(false)

    var focused by mutableStateOf(false)
        internal set

    /** The last press was a touch: selection handles and the text toolbar show. */
    var touchMode by mutableStateOf(false)

    /** Where the right-click menu is open, in viewport coordinates; null when closed. */
    var contextMenuAt by mutableStateOf<Offset?>(null)

    /** The selection handle being dragged, if any. */
    var draggingHandle by mutableStateOf<SelectionHandle?>(null)

    /** Where the magnifier looks, in viewport coordinates, while a handle is dragged. */
    var magnifierCenter by mutableStateOf<Offset?>(null)

    /** The caret's handle was tapped: the touch menu shows over the caret, for pasting. */
    var showToolbarAtCaret by mutableStateOf(false)

    /** Where a mouse rests over the text, in viewport coordinates; null when it is elsewhere or pressed. */
    var hoverAt by mutableStateOf<Offset?>(null)

    /** Ctrl (Cmd on macOS) is held: the symbol under the mouse shows as a link, and a click follows it. */
    var linkModifier by mutableStateOf(false)

    /** The symbol under the mouse that has a definition to go to, while [linkModifier] is held; set by the host. */
    var link by mutableStateOf<TextRange?>(null)

    /** The text area's layout coordinates, from the input node. */
    var coordinates: LayoutCoordinates? = null

    var actions = EditorHostActions()

    /** The host's items in the touch menu and the right-click menu, after the editor's own. */
    var touchMenuItems: List<EditorMenuItem> = emptyList()
    var contextMenuItems: List<EditorMenuItem> = emptyList()

    /** Runs a host's menu item: its command through the state, else its click. */
    fun runMenuItem(item: EditorMenuItem) {
        val command = item.command
        if (command != null) state.execute(command) else item.onClick()
    }

    /** Whether a host's menu item can be chosen now. */
    fun canRun(item: EditorMenuItem): Boolean = item.enabled && (item.command?.let(state::canExecute) ?: true)

    var languageService: () -> LanguageService? = { null }

    var clipboard: Clipboard? = null

    /** For clipboard work and anything else that suspends. */
    var scope: CoroutineScope? = null

    /** Moves focus to the text area. */
    var requestFocus: () -> Unit = {}

    // What the last copy or cut put on the clipboard: pasting the same text back as whole lines
    // puts them above the caret's line, as in VS Code.
    private var lastCopy: EditingCommands.Copied? = null

    // The x the caret keeps while moving up and down through shorter lines.
    private var preferredX: Float? = null

    init {
        ime.service = { languageService() }
        ime.onInput = { typed ->
            actions.onInput(typed)
            revealCaret()
        }
        pointer.onPress = { touch ->
            touchMode = touch
            hoverAt = null
            contextMenuAt = null
            showToolbarAtCaret = false
            preferredX = null
            requestFocus()
            if (touch && !readOnly) ime.keyboardRequests++
        }
        pointer.onContextMenu = { position -> contextMenuAt = position }
        pointer.onLinkClick = { actions.onOpenLink(state.selection.end) || actions.onGoToDefinition() }
        pointer.onTap = { offset -> actions.onTapText(offset) }
    }

    /** A selection handle dragged to [position] (viewport coordinates): that end of the selection follows it. */
    fun dragHandle(handle: SelectionHandle, position: Offset) {
        val hit = view.offsetAt(position).offset
        val selection = state.selection
        val next = when (handle) {
            SelectionHandle.Start -> TextRange(selection.max, hit)
            SelectionHandle.End -> TextRange(selection.min, hit)
            SelectionHandle.Insertion -> TextRange(hit)
        }
        state.replaceSelections(SelectionSet.single(next))
        showToolbarAtCaret = false
        if (position.y < 0f || position.y > view.scroll.viewportHeight) view.requestReveal(hit)
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    fun onKeyEvent(event: KeyEvent): Boolean = state.withSelectionReason(SelectionChangeReason.Keyboard) { handleKey(event) }

    /** Ctrl+K (Cmd+K) was pressed: the next key completes a chord. */
    internal var chordPending = false
        private set

    private fun handleKey(event: KeyEvent): Boolean {
        linkModifier = if (PlatformInfo.isMacOs) event.isMetaPressed else event.isCtrlPressed
        showToolbarAtCaret = false
        if (chordPending) {
            // Modifier keys on their own neither complete nor cancel the chord.
            if (event.key in MODIFIER_KEYS) return false
            if (event.type != KeyEventType.KeyDown) return true
            chordPending = false
            // A pair that means nothing is swallowed, as VS Code does, and so is Escape.
            val second = EditorKeyBindings.resolveChord(event, PlatformInfo.isMacOs) ?: return true
            execute(second)
            return true
        }
        if (actions.onPopupKey(event)) return true
        if (EditorKeyBindings.startsChord(event, PlatformInfo.isMacOs)) {
            chordPending = true
            return true
        }
        val binding = EditorKeyBindings.resolve(event, PlatformInfo.isMacOs)
        if (binding != null && execute(binding)) {
            touchMode = false
            return true
        }
        if (readOnly) return false
        val typed = decoder.typedText(event) ?: return false
        if (typed.isNotEmpty()) type(typed)
        return true
    }

    /** Types [text] at every selection, as a keyboard does. */
    fun type(text: String) {
        if (readOnly) return
        val primary = state.selection
        val typed = state.applyInput(primary.min, primary.max, text, service = languageService())
        preferredX = null
        actions.onInput(typed)
        revealCaret()
    }

    /** Runs [binding]; false when it does not apply (so the key goes on to others). */
    fun execute(binding: KeyBinding): Boolean {
        val document = state.document
        val select = binding.select
        val vertical = binding.command == KeyCommand.LineUp || binding.command == KeyCommand.LineDown ||
            binding.command == KeyCommand.PageUp || binding.command == KeyCommand.PageDown
        if (!vertical) preferredX = null
        val handled = when (binding.command) {
            KeyCommand.CharLeft -> move(select) { if (!select && !it.collapsed) it.min else TextNavigator.charLeft(document, it.end) }

            KeyCommand.CharRight -> move(select) { if (!select && !it.collapsed) it.max else TextNavigator.charRight(document, it.end) }

            KeyCommand.WordLeft -> move(select) { TextNavigator.wordLeft(document, it.end) }

            KeyCommand.WordRight -> move(select) { TextNavigator.wordRight(document, it.end) }

            KeyCommand.LineStart -> move(select) { TextNavigator.smartHome(document, it.end) }

            KeyCommand.LineEnd -> move(select) { TextNavigator.lineEnd(document, it.end) }

            KeyCommand.DocumentStart -> single(select, 0)

            KeyCommand.DocumentEnd -> single(select, document.length)

            KeyCommand.LineUp -> vertical(select, -1)

            KeyCommand.LineDown -> vertical(select, 1)

            KeyCommand.PageUp -> page(select, -1)

            KeyCommand.PageDown -> page(select, 1)

            KeyCommand.DeleteLeft -> edit { state.deleteLeft() }

            KeyCommand.DeleteRight -> edit { state.deleteRight() }

            KeyCommand.DeleteWordLeft -> edit { state.deleteLeft(word = true) }

            KeyCommand.DeleteWordRight -> edit { state.deleteRight(word = true) }

            KeyCommand.DeleteToLineStart -> edit {
                state.moveSelections(extend = true) { document.lineStart(document.offsetToLineCol(it.end).first) }
                state.deleteLeft()
            }

            KeyCommand.Enter -> !readOnly && true.also { type("\n") }

            // In a snippet, Tab and Shift+Tab step through its tab stops instead, as in Monaco.
            KeyCommand.Indent -> edit { if (!state.moveInSnippet(forward = true)) state.indentSelection() }

            KeyCommand.Outdent -> edit { if (!state.moveInSnippet(forward = false)) state.outdentSelection() }

            KeyCommand.Undo -> edit { state.undo() }

            KeyCommand.Redo -> edit { state.redo() }

            KeyCommand.SelectAll -> true.also { state.selectAll() }

            KeyCommand.Copy -> editorClipboardFromKeys && true.also { copyToClipboard() }

            KeyCommand.Cut -> editorClipboardFromKeys && true.also { cutToClipboard() }

            KeyCommand.Paste -> editorClipboardFromKeys && !readOnly && true.also { pasteFromClipboard() }

            KeyCommand.Find -> true.also { actions.onFind() }

            KeyCommand.Replace -> true.also { actions.onReplace() }

            KeyCommand.GoToLine -> true.also { actions.onGoToLine() }

            KeyCommand.ToggleComment -> edit { state.toggleComment() }

            KeyCommand.MoveLinesUp -> edit { state.moveLines(up = true) }

            KeyCommand.MoveLinesDown -> edit { state.moveLines(up = false) }

            KeyCommand.CopyLinesUp -> edit { state.copyLines(down = false) }

            KeyCommand.CopyLinesDown -> edit { state.copyLines(down = true) }

            KeyCommand.DeleteLines -> edit { state.deleteLines() }

            KeyCommand.AddNextOccurrence -> state.addNextOccurrence().also { revealCaret() }

            KeyCommand.SelectAllOccurrences -> state.selectAllOccurrences()

            KeyCommand.AddCursorAbove -> addCursor(-1)

            KeyCommand.AddCursorBelow -> addCursor(1)

            KeyCommand.ColumnSelectLeft -> columnSelect(rows = 0, columns = -1)

            KeyCommand.ColumnSelectRight -> columnSelect(rows = 0, columns = 1)

            KeyCommand.ColumnSelectUp -> columnSelect(rows = -1, columns = 0)

            KeyCommand.ColumnSelectDown -> columnSelect(rows = 1, columns = 0)

            KeyCommand.ColumnSelectPageUp -> columnSelect(rows = -(view.visibleRows - 1).coerceAtLeast(1), columns = 0)

            KeyCommand.ColumnSelectPageDown -> columnSelect(rows = (view.visibleRows - 1).coerceAtLeast(1), columns = 0)

            KeyCommand.Escape -> escape()

            KeyCommand.TriggerSuggest -> !readOnly && true.also { actions.onTriggerSuggest() }

            KeyCommand.GoToDefinition -> actions.onGoToDefinition()

            KeyCommand.FindReferences -> actions.onFindReferences()

            KeyCommand.FormatDocument -> !readOnly && actions.onFormat()

            KeyCommand.NextProblem -> actions.onGoToProblem(true)

            KeyCommand.PreviousProblem -> actions.onGoToProblem(false)

            KeyCommand.Rename -> !readOnly && actions.onRename()

            KeyCommand.QuickFix -> !readOnly && actions.onQuickFix()

            KeyCommand.FindNext -> actions.onFindNext(true)

            KeyCommand.FindPrevious -> actions.onFindNext(false)

            KeyCommand.SelectAllMatches -> actions.onSelectAllMatches()

            KeyCommand.Command -> binding.run?.let(state::execute) ?: false
        }
        return handled
    }

    /** An editing command: nothing when read-only, but the key is still the editor's. */
    private inline fun edit(block: () -> Unit): Boolean {
        if (readOnly) return true
        block()
        revealCaret()
        return true
    }

    private inline fun move(select: Boolean, crossinline target: (TextRange) -> Int): Boolean {
        state.moveSelections(select) { target(it) }
        revealCaret()
        return true
    }

    /** Document start or end: one caret, as in VS Code. */
    private fun single(select: Boolean, offset: Int): Boolean {
        val primary = state.selection
        state.replaceSelections(SelectionSet.single(if (select) TextRange(primary.start, offset) else TextRange(offset)))
        revealCaret()
        return true
    }

    private fun vertical(select: Boolean, rows: Int): Boolean {
        val primary = state.selection
        val x = preferredX ?: view.caretX(primary.end)
        state.moveSelections(select) { range -> view.verticalMove(range.end, rows, if (range == primary) x else null) }
        preferredX = x
        revealCaret()
        return true
    }

    private fun page(select: Boolean, direction: Int): Boolean {
        val rows = (view.visibleRows - 1).coerceAtLeast(1) * direction
        val lineHeight = view.style?.metrics?.lineHeight ?: 0f
        view.scroll.scrollToY(view.scroll.scrollY + rows * lineHeight)
        return vertical(select, rows)
    }

    /** A caret on the row above (or below) each caret, keeping their x. */
    private fun addCursor(direction: Int): Boolean {
        val current = state.currentSelections()
        val added = current.ranges.map { TextRange(view.verticalMove(it.end, direction, view.caretX(it.end))) }
        state.replaceSelections(SelectionSet.of(current.ranges + added))
        view.requestReveal(added.first().end)
        return true
    }

    /**
     * Grows or shrinks the column selection by [rows] rows and [columns] characters at its active
     * corner; the first press starts one from the primary selection.
     */
    private fun columnSelect(rows: Int, columns: Int): Boolean {
        val charWidth = view.style?.metrics?.charWidth ?: return true
        if (view.totalRows == 0) return true
        val box = this.columns.current()
        this.columns.select(
            box.copy(
                activeRow = (box.activeRow + rows).coerceIn(0, view.totalRows - 1),
                activeX = (box.activeX + columns * charWidth).coerceAtLeast(0f),
            ),
        )
        revealCaret()
        return true
    }

    private fun escape(): Boolean {
        contextMenuAt = null
        if (state.snippet != null) {
            state.endSnippet()
            return true
        }
        if (!state.currentSelections().isSingle) {
            state.replaceSelections(state.currentSelections().collapsedToPrimary())
            return true
        }
        return actions.onEscape()
    }

    fun revealCaret() {
        view.requestReveal(state.selection.end)
    }

    // ── Clipboard ────────────────────────────────────────────────────────────

    /** Copies the selections (or the carets' lines); returns the text. */
    fun copyText(): String {
        val copied = state.copySelections()
        lastCopy = copied
        return copied.text
    }

    /** Cuts the selections (or the carets' lines); returns the text, or just copies when read-only. */
    fun cutText(): String? {
        if (readOnly) return copyText()
        val copied = state.cutSelections() ?: return null
        lastCopy = copied
        revealCaret()
        return copied.text
    }

    /** Pastes [text]: whole lines above the caret's line when it is what the last copy of carets took. */
    fun pasteText(text: String) {
        if (readOnly) return
        val last = lastCopy
        state.paste(text, wholeLines = last != null && last.wholeLines && last.text == text)
        revealCaret()
    }

    fun copyToClipboard() {
        val text = copyText()
        launchWithClipboard { writePlainText(text) }
    }

    fun cutToClipboard() {
        val text = cutText() ?: return
        launchWithClipboard { writePlainText(text) }
    }

    fun pasteFromClipboard() {
        launchWithClipboard { readPlainText()?.let { pasteText(it) } }
    }

    private fun launchWithClipboard(block: suspend Clipboard.() -> Unit) {
        val clipboard = clipboard ?: return
        scope?.launch { clipboard.block() }
    }

    // ── Geometry for the input method ────────────────────────────────────────

    private fun attached(): LayoutCoordinates? = coordinates?.takeIf { it.isAttached }

    override fun caretRectInRoot(): Rect? {
        val coordinates = attached() ?: return null
        // Snapshot reads: the web re-places its hidden text area when these change.
        view.scroll.scrollY
        view.scroll.scrollX
        val caret = view.caretRect(state.selection.end)
        return caret.translate(coordinates.localToRoot(Offset.Zero))
    }

    override fun boundsInRoot(): Rect? = attached()?.boundsInRoot()

    override fun textOriginInRoot(): Offset? {
        val coordinates = attached() ?: return null
        val metrics = view.style?.metrics ?: return null
        return coordinates.localToRoot(Offset(metrics.paddingStart - view.scroll.scrollX, metrics.paddingTop - view.scroll.scrollY))
    }

    override fun characterBoundsInRoot(offset: Int): Rect? {
        val coordinates = attached() ?: return null
        val frame = view.frame
        val line = frame.lineContaining(offset) ?: return null
        val column = offset - line.start
        if (column >= line.shownLength) return null
        val layout = line.layout
        val box = layout.getBoundingBox(column)
        val shift = layout.rowShift(layout.rowFor(column), frame.metrics.lineHeight)
        return box.translate(Offset(frame.textLeft, line.top - frame.scrollY + shift) + coordinates.localToRoot(Offset.Zero))
    }
}

/** Keys that are only modifiers, which do not complete a chord. */
private val MODIFIER_KEYS = setOf(
    Key.CtrlLeft,
    Key.CtrlRight,
    Key.ShiftLeft,
    Key.ShiftRight,
    Key.AltLeft,
    Key.AltRight,
    Key.MetaLeft,
    Key.MetaRight,
)
