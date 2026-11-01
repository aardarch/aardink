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
package com.aardarch.aardink.web

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.ui.KeyChord
import kotlinx.browser.window

/**
 * A host's keybinding: text, `"CtrlCmd+S"`, `"Shift+F8"`, or a chord of two keys separated by a
 * space, `"CtrlCmd+K CtrlCmd+S"` (see [KeyChord.parse]); or Monaco's number, `KeyMod.CtrlCmd |
 * KeyCode.KeyS`, a chord being `KeyMod.chord(first, second)`. Null for one it cannot read.
 */
internal fun parseKeybinding(text: String): List<KeyChord>? {
    text.trim().toIntOrNull()?.let { value ->
        val first = monacoChord(value and 0xFFFF) ?: return null
        val second = (value ushr 16) and 0xFFFF
        if (second == 0) return listOf(first)
        return listOf(first, monacoChord(second) ?: return null)
    }
    val strokes = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (strokes.size !in 1..2) return null
    return strokes.map { KeyChord.parse(it) ?: return null }
}

/** Monaco's `KeyMod | KeyCode` number for one key. */
private fun monacoChord(value: Int): KeyChord? {
    val key = MONACO_KEY_CODES[value and 0xFF] ?: return null
    return KeyChord(
        key,
        ctrlCmd = value and 2048 != 0,
        shift = value and 1024 != 0,
        alt = value and 512 != 0,
        winCtrl = value and 256 != 0,
    )
}

private val LETTERS = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)
private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val FUNCTION_KEYS = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)

/** Monaco's `KeyCode` numbers, for the keys a command is bound to. */
private val MONACO_KEY_CODES: Map<Int, Key> = buildMap {
    put(1, Key.Backspace)
    put(2, Key.Tab)
    put(3, Key.Enter)
    put(9, Key.Escape)
    put(10, Key.Spacebar)
    put(11, Key.PageUp)
    put(12, Key.PageDown)
    put(13, Key.MoveEnd)
    put(14, Key.MoveHome)
    put(15, Key.DirectionLeft)
    put(16, Key.DirectionUp)
    put(17, Key.DirectionRight)
    put(18, Key.DirectionDown)
    put(19, Key.Insert)
    put(20, Key.Delete)
    DIGITS.forEachIndexed { i, key -> put(21 + i, key) }
    LETTERS.forEachIndexed { i, key -> put(31 + i, key) }
    FUNCTION_KEYS.forEachIndexed { i, key -> put(59 + i, key) }
    put(85, Key.Semicolon)
    put(86, Key.Equals)
    put(87, Key.Comma)
    put(88, Key.Minus)
    put(89, Key.Period)
    put(90, Key.Slash)
    put(91, Key.Grave)
    put(92, Key.LeftBracket)
    put(93, Key.Backslash)
    put(94, Key.RightBracket)
    put(95, Key.Apostrophe)
}

/** A command or action a host added, with the keys that run it. */
internal class WebAction(
    val id: String,
    val label: String,
    /** Each binding one key, or a chord of two. */
    val keybindings: List<List<KeyChord>>,
    val inContextMenu: Boolean,
    val run: () -> Unit,
)

/** Monaco's ids for the editor's own actions, with their labels, for `trigger` and `getAction`. */
internal object MonacoActions {
    private val byId: Map<String, Pair<String, EditorCommand>> = buildMap {
        put("editor.action.formatDocument", "Format Document" to EditorCommand.Format)
        put("editor.action.formatSelection", "Format Selection" to EditorCommand.FormatSelection)
        put("editor.action.triggerSuggest", "Trigger Suggest" to EditorCommand.TriggerSuggest)
        put("editor.action.showHover", "Show Hover" to EditorCommand.ShowHover)
        put("editor.action.quickFix", "Quick Fix..." to EditorCommand.ShowCodeActions)
        put("editor.action.rename", "Rename Symbol" to EditorCommand.Rename)
        put("editor.action.revealDefinition", "Go to Definition" to EditorCommand.GoToDefinition)
        put("editor.action.goToReferences", "Go to References" to EditorCommand.FindReferences)
        put("editor.action.referenceSearch.trigger", "Peek References" to EditorCommand.FindReferences)
        put("editor.action.marker.next", "Go to Next Problem" to EditorCommand.NextProblem)
        put("editor.action.marker.prev", "Go to Previous Problem" to EditorCommand.PreviousProblem)
        put("actions.find", "Find" to EditorCommand.ShowFind)
        put("editor.action.startFindReplaceAction", "Replace" to EditorCommand.ShowReplace)
        put("editor.action.gotoLine", "Go to Line/Column..." to EditorCommand.GoToLine)
        put("editor.foldAll", "Fold All" to EditorCommand.FoldAll)
        put("editor.unfoldAll", "Unfold All" to EditorCommand.UnfoldAll)
        for (level in 1..7) put("editor.foldLevel$level", "Fold Level $level" to EditorCommand.FoldLevel(level))
        put("undo", "Undo" to EditorCommand.Undo)
        put("redo", "Redo" to EditorCommand.Redo)
        put("editor.action.commentLine", "Toggle Line Comment" to EditorCommand.ToggleComment)
        put("editor.action.selectHighlights", "Select All Occurrences of Find Match" to EditorCommand.SelectAllOccurrences)
        put("editor.action.quickOutline", "Go to Symbol..." to EditorCommand.GoToSymbol)
        put("editor.action.smartSelect.expand", "Expand Selection" to EditorCommand.ExpandSelection)
        put("editor.action.smartSelect.shrink", "Shrink Selection" to EditorCommand.ShrinkSelection)
        put("editor.fold", "Fold" to EditorCommand.Fold)
        put("editor.unfold", "Unfold" to EditorCommand.Unfold)
        put("editor.action.joinLines", "Join Lines" to EditorCommand.JoinLines)
        put("editor.action.sortLinesAscending", "Sort Lines Ascending" to EditorCommand.SortLines())
        put("editor.action.sortLinesDescending", "Sort Lines Descending" to EditorCommand.SortLines(descending = true))
        put("editor.action.transformToUppercase", "Transform to Uppercase" to EditorCommand.UpperCase)
        put("editor.action.transformToLowercase", "Transform to Lowercase" to EditorCommand.LowerCase)
        put("editor.action.trimTrailingWhitespace", "Trim Trailing Whitespace" to EditorCommand.TrimTrailingWhitespace)
        put("editor.action.jumpToBracket", "Go to Bracket" to EditorCommand.JumpToBracket)
        put("cursorUndo", "Cursor Undo" to EditorCommand.CursorUndo)
        put("editor.action.moveSelectionToNextFindMatch", "Move Last Selection to Next Find Match" to EditorCommand.SkipOccurrence)
        put("editor.action.changeAll", "Change All Occurrences" to EditorCommand.ChangeAllOccurrences)
        put("editor.action.insertCursorAtEndOfEachLineSelected", "Add Cursors to Line Ends" to EditorCommand.CursorsAtLineEnds)
        put("editor.action.addCommentLine", "Add Line Comment" to EditorCommand.AddLineComment)
        put("editor.action.removeCommentLine", "Remove Line Comment" to EditorCommand.RemoveLineComment)
        put("editor.action.blockComment", "Toggle Block Comment" to EditorCommand.BlockComment)
        put("editor.action.insertLineAfter", "Insert Line Below" to EditorCommand.InsertLineBelow)
        put("editor.action.insertLineBefore", "Insert Line Above" to EditorCommand.InsertLineAbove)
        put("expandLineSelection", "Expand Line Selection" to EditorCommand.SelectLine)
        put("editor.action.showColorPicker", "Show Color Picker" to EditorCommand.ShowColorPicker)
    }

    fun command(id: String): EditorCommand? = byId[id]?.second

    fun label(id: String): String? = byId[id]?.first
}
