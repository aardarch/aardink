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
package com.aardarch.aardink.ui

import androidx.compose.runtime.Immutable
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
import com.aardarch.aardink.platform.PlatformInfo

/**
 * A key with its modifiers, as Monaco names them: [ctrlCmd] is Cmd on macOS and Ctrl elsewhere,
 * [winCtrl] is Ctrl on every platform. One stroke of an [EditorKeyBinding].
 */
@Immutable
data class KeyChord(
    val key: Key,
    val ctrlCmd: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val winCtrl: Boolean = false,
) {
    /** Whether [event], a key going down, is this chord, with no other modifier held. */
    fun matches(event: KeyEvent): Boolean = matches(event, PlatformInfo.isMacOs)

    internal fun matches(event: KeyEvent, isMac: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != key) return false
        val wantCtrl = if (isMac) winCtrl else ctrlCmd || winCtrl
        val wantMeta = isMac && ctrlCmd
        return event.isCtrlPressed == wantCtrl && event.isMetaPressed == wantMeta &&
            event.isShiftPressed == shift && event.isAltPressed == alt
    }

    override fun toString(): String = buildString {
        if (ctrlCmd) append("CtrlCmd+")
        if (winCtrl) append("WinCtrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(key)
    }

    companion object {
        /**
         * A chord from text as Monaco's keybinding labels and Electron's accelerators write it:
         * `"CtrlCmd+S"`, `"Shift+F8"`, `"Ctrl+Alt+Enter"`, `"Mod+K"`. `CtrlCmd`, `CmdOrCtrl`, `Mod`
         * and `Cmd` are [ctrlCmd]; `Ctrl` and `WinCtrl` are [winCtrl]. Null for text it cannot read.
         */
        fun parse(text: String): KeyChord? {
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

        private fun keyNamed(name: String): Key? {
            if (name.length == 1) {
                val c = name[0].uppercaseChar()
                if (c in 'A'..'Z') return LETTER_KEYS[c - 'A']
                if (c in '0'..'9') return DIGIT_KEYS[c - '0']
                PUNCTUATION_KEYS[c]?.let { return it }
            }
            val lower = name.lowercase()
            if (lower.startsWith("f")) lower.drop(1).toIntOrNull()?.let { n -> if (n in 1..12) return FUNCTION_KEYS[n - 1] }
            return NAMED_KEYS[lower]
        }
    }
}

/**
 * A key (or two keys in a row, a chord such as Ctrl+K Ctrl+C) and the command it runs. In
 * [EditorOptions.keyBindings] a binding with a null [command] removes the editor's own binding of
 * those keys, which then do what they do without one (a letter types).
 */
@Immutable
data class EditorKeyBinding(val chord: List<KeyChord>, val command: EditorCommand?) {
    constructor(key: KeyChord, command: EditorCommand?) : this(listOf(key), command)

    init {
        require(chord.size in 1..2) { "a binding is one key or a chord of two, not ${chord.size}" }
    }
}

/**
 * The editor's own keys for its commands, as data a host can show (a menu's shortcuts) and copy:
 * [EditorOptions.keyBindings] adds to them and overrides them. Moving the caret, typing, the
 * clipboard, Tab, Enter and Escape are the editor's fixed keys and not listed.
 */
object EditorKeyBindings {

    /** The bindings for the platform the editor runs on: Cmd for Ctrl on macOS, as VS Code's. */
    val Default: List<EditorKeyBinding>
        get() = defaults(PlatformInfo.isMacOs)

    internal fun defaults(isMac: Boolean): List<EditorKeyBinding> = if (isMac) MAC else OTHERS

    private fun bind(key: KeyChord, command: EditorCommand) = EditorKeyBinding(key, command)

    private fun chordK(second: Key, command: EditorCommand) =
        EditorKeyBinding(listOf(KeyChord(Key.K, ctrlCmd = true), KeyChord(second, ctrlCmd = true)), command)

    private fun common(isMac: Boolean): List<EditorKeyBinding> = buildList {
        add(bind(KeyChord(Key.Z, ctrlCmd = true), EditorCommand.Undo))
        add(bind(KeyChord(Key.Z, ctrlCmd = true, shift = true), EditorCommand.Redo))
        if (!isMac) add(bind(KeyChord(Key.Y, ctrlCmd = true), EditorCommand.Redo))
        add(bind(KeyChord(Key.Slash, ctrlCmd = true), EditorCommand.ToggleComment))
        add(bind(KeyChord(Key.L, ctrlCmd = true, shift = true), EditorCommand.SelectAllOccurrences))
        add(bind(KeyChord(Key.F, ctrlCmd = true), EditorCommand.ShowFind))
        add(bind(KeyChord(Key.H, ctrlCmd = true), EditorCommand.ShowReplace))
        add(bind(KeyChord(Key.G, ctrlCmd = true), EditorCommand.GoToLine))
        add(bind(KeyChord(Key.Spacebar, winCtrl = true), EditorCommand.TriggerSuggest))
        add(bind(KeyChord(Key.F12), EditorCommand.GoToDefinition))
        add(bind(KeyChord(Key.F12, shift = true), EditorCommand.FindReferences))
        add(bind(KeyChord(Key.F8), EditorCommand.NextProblem))
        add(bind(KeyChord(Key.F8, shift = true), EditorCommand.PreviousProblem))
        add(bind(KeyChord(Key.F2), EditorCommand.Rename))
        add(bind(KeyChord(Key.Period, ctrlCmd = true), EditorCommand.ShowCodeActions))
        add(bind(KeyChord(Key.O, ctrlCmd = true, shift = true), EditorCommand.GoToSymbol))
        if (isMac) {
            add(bind(KeyChord(Key.DirectionRight, ctrlCmd = true, winCtrl = true, shift = true), EditorCommand.ExpandSelection))
            add(bind(KeyChord(Key.DirectionLeft, ctrlCmd = true, winCtrl = true, shift = true), EditorCommand.ShrinkSelection))
            add(bind(KeyChord(Key.LeftBracket, ctrlCmd = true, alt = true), EditorCommand.Fold))
            add(bind(KeyChord(Key.RightBracket, ctrlCmd = true, alt = true), EditorCommand.Unfold))
        } else {
            add(bind(KeyChord(Key.DirectionRight, shift = true, alt = true), EditorCommand.ExpandSelection))
            add(bind(KeyChord(Key.DirectionLeft, shift = true, alt = true), EditorCommand.ShrinkSelection))
            add(bind(KeyChord(Key.LeftBracket, ctrlCmd = true, shift = true), EditorCommand.Fold))
            add(bind(KeyChord(Key.RightBracket, ctrlCmd = true, shift = true), EditorCommand.Unfold))
            add(bind(KeyChord(Key.DirectionUp, winCtrl = true), EditorCommand.ScrollLines(-1)))
            add(bind(KeyChord(Key.DirectionDown, winCtrl = true), EditorCommand.ScrollLines(1)))
        }
        add(chordK(Key.Zero, EditorCommand.FoldAll))
        add(chordK(Key.J, EditorCommand.UnfoldAll))
        add(chordK(Key.One, EditorCommand.FoldLevel(1)))
        add(chordK(Key.Two, EditorCommand.FoldLevel(2)))
        add(chordK(Key.Three, EditorCommand.FoldLevel(3)))
        add(chordK(Key.Four, EditorCommand.FoldLevel(4)))
        add(chordK(Key.Five, EditorCommand.FoldLevel(5)))
        add(chordK(Key.C, EditorCommand.AddLineComment))
        add(chordK(Key.U, EditorCommand.RemoveLineComment))
        add(chordK(Key.D, EditorCommand.SkipOccurrence))
        add(chordK(Key.X, EditorCommand.TrimTrailingWhitespace))
        add(bind(KeyChord(Key.J, winCtrl = true), EditorCommand.JoinLines))
        add(bind(KeyChord(Key.Backslash, ctrlCmd = true, shift = true), EditorCommand.JumpToBracket))
        add(bind(KeyChord(Key.U, ctrlCmd = true), EditorCommand.CursorUndo))
        add(bind(KeyChord(Key.F2, ctrlCmd = true), EditorCommand.ChangeAllOccurrences))
        add(bind(KeyChord(Key.I, shift = true, alt = true), EditorCommand.CursorsAtLineEnds))
        add(bind(KeyChord(Key.A, shift = true, alt = true), EditorCommand.BlockComment))
        add(bind(KeyChord(Key.Enter, ctrlCmd = true), EditorCommand.InsertLineBelow))
        add(bind(KeyChord(Key.Enter, ctrlCmd = true, shift = true), EditorCommand.InsertLineAbove))
        add(bind(KeyChord(Key.L, ctrlCmd = true), EditorCommand.SelectLine))
    }

    private val MAC = common(isMac = true)
    private val OTHERS = common(isMac = false)
}

/**
 * What a key does by the bindings: run a command, start a chord, or nothing because a host
 * removed the binding. Found by [resolveKeyBinding].
 */
internal sealed class KeyBindingMatch {
    class Run(val command: EditorCommand) : KeyBindingMatch()

    /** The first key of a chord: wait for the second. */
    class ChordStarted(val first: KeyChord) : KeyBindingMatch()

    /** A binding with no command: the key does what it does without one. */
    data object Unbound : KeyBindingMatch()
}

/**
 * What [event] does by [bindings] (the host's first, then [EditorKeyBindings.Default]): when
 * [pending] is the first key of a chord, as its second key; otherwise as a key of its own or the
 * start of a chord. The second key of a chord may be pressed with or without Ctrl (Cmd), as
 * VS Code allows. Null when no binding has the key.
 */
internal fun resolveKeyBinding(bindings: List<EditorKeyBinding>, event: KeyEvent, isMac: Boolean, pending: KeyChord?): KeyBindingMatch? {
    if (pending != null) {
        val binding = bindings.firstOrNull { binding ->
            binding.chord.size == 2 && binding.chord[0] == pending &&
                (binding.chord[1].matches(event, isMac) || binding.chord[1].copy(ctrlCmd = !binding.chord[1].ctrlCmd).matches(event, isMac))
        } ?: return null
        return binding.command?.let { KeyBindingMatch.Run(it) } ?: KeyBindingMatch.Unbound
    }
    for (binding in bindings) {
        if (!binding.chord[0].matches(event, isMac)) continue
        return when {
            binding.chord.size == 2 -> KeyBindingMatch.ChordStarted(binding.chord[0])
            binding.command == null -> KeyBindingMatch.Unbound
            else -> KeyBindingMatch.Run(binding.command)
        }
    }
    return null
}

private val LETTER_KEYS = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)
private val DIGIT_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val FUNCTION_KEYS = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
private val PUNCTUATION_KEYS = mapOf(
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
