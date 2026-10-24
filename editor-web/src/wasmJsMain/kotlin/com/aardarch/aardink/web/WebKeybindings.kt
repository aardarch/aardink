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
import kotlinx.browser.window

/**
 * A key with modifiers, as a host binds a command to it: `"CtrlCmd+S"`, `"Shift+F8"`,
 * `"Ctrl+Alt+Enter"`, or Monaco's number `KeyMod.CtrlCmd | KeyCode.KeyS`. `CtrlCmd` is Cmd on
 * macOS and Ctrl elsewhere, as in Monaco; `WinCtrl` is Ctrl on every platform; `Meta` is Cmd or
 * the Windows key.
 */
internal data class KeyChord(
    val key: Key,
    val ctrlCmd: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val winCtrl: Boolean = false,
) {

    /** Whether [event], a key going down, is this chord, with no other modifier held. */
    fun matches(event: KeyEvent, isMac: Boolean = IS_MAC): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != key) return false
        val wantCtrl = if (isMac) winCtrl else ctrlCmd || winCtrl
        val wantMeta = isMac && ctrlCmd
        return event.isCtrlPressed == wantCtrl && event.isMetaPressed == wantMeta &&
            event.isShiftPressed == shift && event.isAltPressed == alt
    }

    companion object {
        /** A chord from its text or Monaco number; null for one it cannot read. */
        fun parse(text: String): KeyChord? {
            text.trim().toIntOrNull()?.let { return fromMonaco(it) }
            var ctrlCmd = false
            var shift = false
            var alt = false
            var winCtrl = false
            var key: Key? = null
            for (part in text.split('+').map { it.trim() }.filter { it.isNotEmpty() }) {
                when (part.lowercase()) {
                    "ctrlcmd", "cmdorctrl", "mod", "cmd", "command" -> ctrlCmd = true
                    "ctrl", "control", "winctrl" -> winCtrl = true
                    "shift" -> shift = true
                    "alt", "option", "opt" -> alt = true
                    else -> key = keyNamed(part) ?: return null
                }
            }
            return KeyChord(key ?: return null, ctrlCmd, shift, alt, winCtrl)
        }

        /** Monaco's `KeyMod | KeyCode` number. */
        private fun fromMonaco(value: Int): KeyChord? {
            val key = MONACO_KEY_CODES[value and 0xFF] ?: return null
            return KeyChord(
                key,
                ctrlCmd = value and 2048 != 0,
                shift = value and 1024 != 0,
                alt = value and 512 != 0,
                winCtrl = value and 256 != 0,
            )
        }

        private fun keyNamed(name: String): Key? {
            if (name.length == 1) {
                val c = name[0].uppercaseChar()
                if (c in 'A'..'Z') return LETTERS[c - 'A']
                if (c in '0'..'9') return DIGITS[c - '0']
                PUNCTUATION[c]?.let { return it }
            }
            val lower = name.lowercase()
            if (lower.startsWith("f")) lower.drop(1).toIntOrNull()?.let { n -> if (n in 1..12) return FUNCTION_KEYS[n - 1] }
            return NAMED_KEYS[lower]
        }

        private val IS_MAC: Boolean by lazy { "Mac" in window.navigator.platform }

        private val LETTERS = listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
            Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
        )
        private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        private val FUNCTION_KEYS = listOf(
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
        )
        private val PUNCTUATION = mapOf(
            ';' to Key.Semicolon, '=' to Key.Equals, ',' to Key.Comma, '-' to Key.Minus, '.' to Key.Period, '/' to Key.Slash,
            '`' to Key.Grave, '[' to Key.LeftBracket, '\\' to Key.Backslash, ']' to Key.RightBracket, '\'' to Key.Apostrophe,
        )
        private val NAMED_KEYS = mapOf(
            "enter" to Key.Enter, "return" to Key.Enter, "escape" to Key.Escape, "esc" to Key.Escape, "tab" to Key.Tab,
            "space" to Key.Spacebar, "backspace" to Key.Backspace, "delete" to Key.Delete, "insert" to Key.Insert,
            "home" to Key.MoveHome, "end" to Key.MoveEnd, "pageup" to Key.PageUp, "pagedown" to Key.PageDown,
            "left" to Key.DirectionLeft, "leftarrow" to Key.DirectionLeft,
            "right" to Key.DirectionRight, "rightarrow" to Key.DirectionRight,
            "up" to Key.DirectionUp, "uparrow" to Key.DirectionUp, "down" to Key.DirectionDown, "downarrow" to Key.DirectionDown,
        )

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
    }
}

/** A command or action a host added, with the keys that run it. */
internal class WebAction(
    val id: String,
    val label: String,
    val keybindings: List<KeyChord>,
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
    }

    fun command(id: String): EditorCommand? = byId[id]?.second

    fun label(id: String): String? = byId[id]?.first
}
