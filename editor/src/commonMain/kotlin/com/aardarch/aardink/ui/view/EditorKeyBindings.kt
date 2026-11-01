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

/** Everything a key can make the editor do. */
internal enum class KeyCommand {
    CharLeft,
    CharRight,
    WordLeft,
    WordRight,
    LineUp,
    LineDown,
    LineStart,
    LineEnd,
    DocumentStart,
    DocumentEnd,
    PageUp,
    PageDown,
    DeleteLeft,
    DeleteRight,
    DeleteWordLeft,
    DeleteWordRight,
    DeleteToLineStart,
    Enter,
    Indent,
    Outdent,
    Undo,
    Redo,
    SelectAll,
    Copy,
    Cut,
    Paste,
    Find,
    Replace,
    GoToLine,
    ToggleComment,
    MoveLinesUp,
    MoveLinesDown,
    CopyLinesUp,
    CopyLinesDown,
    DeleteLines,
    AddNextOccurrence,
    SelectAllOccurrences,
    AddCursorAbove,
    AddCursorBelow,
    ColumnSelectLeft,
    ColumnSelectRight,
    ColumnSelectUp,
    ColumnSelectDown,
    ColumnSelectPageUp,
    ColumnSelectPageDown,
    Escape,
    TriggerSuggest,
    GoToDefinition,
    FindReferences,
    FormatDocument,
    NextProblem,
    PreviousProblem,
    Rename,
    QuickFix,
    FindNext,
    FindPrevious,
    SelectAllMatches,

    /** Runs [KeyBinding.run], a public command, through [com.aardarch.aardink.core.CodeEditorState.execute]. */
    Command,
}

/**
 * A [command], and for the movement commands whether Shift extends the selection ([select]). For
 * [KeyCommand.Command], [run] is the public command the key runs.
 */
internal data class KeyBinding(val command: KeyCommand, val select: Boolean = false, val run: EditorCommand? = null) {
    constructor(run: EditorCommand) : this(KeyCommand.Command, run = run)
}

/**
 * The editor's keyboard, as a table: VS Code's default bindings, with Cmd for Ctrl on macOS and
 * macOS's own movement keys (Option+arrows by word, Cmd+arrows to line and document ends).
 *
 * Keys the browser keeps for itself (Ctrl+W, Ctrl+T, Ctrl+N) never reach a web page, so they are
 * not bound.
 */
internal object EditorKeyBindings {

    fun resolve(event: KeyEvent, isMac: Boolean): KeyBinding? {
        if (event.type != KeyEventType.KeyDown) return null
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val ctrl = event.isCtrlPressed
        val meta = event.isMetaPressed
        // The primary modifier (Cmd on macOS), and the one that moves by word (Option on macOS).
        val primary = if (isMac) meta else ctrl
        val word = if (isMac) alt && !meta && !ctrl else ctrl && !alt && !meta
        val plain = !alt && !ctrl && !meta

        fun move(command: KeyCommand) = KeyBinding(command, select = shift)

        // Ctrl+Shift+Alt (Cmd+Shift+Option on macOS) with an arrow or page key: a column selection.
        if (primary && shift && alt) {
            return when (event.key) {
                Key.DirectionLeft -> KeyBinding(KeyCommand.ColumnSelectLeft)
                Key.DirectionRight -> KeyBinding(KeyCommand.ColumnSelectRight)
                Key.DirectionUp -> KeyBinding(KeyCommand.ColumnSelectUp)
                Key.DirectionDown -> KeyBinding(KeyCommand.ColumnSelectDown)
                Key.PageUp -> KeyBinding(KeyCommand.ColumnSelectPageUp)
                Key.PageDown -> KeyBinding(KeyCommand.ColumnSelectPageDown)
                else -> null
            }
        }

        return when (event.key) {
            Key.DirectionLeft -> when {
                isMac && meta && !alt -> move(KeyCommand.LineStart)
                word -> move(KeyCommand.WordLeft)
                plain -> move(KeyCommand.CharLeft)
                else -> null
            }

            Key.DirectionRight -> when {
                isMac && meta && !alt -> move(KeyCommand.LineEnd)
                word -> move(KeyCommand.WordRight)
                plain -> move(KeyCommand.CharRight)
                else -> null
            }

            Key.DirectionUp, Key.DirectionDown -> {
                val up = event.key == Key.DirectionUp
                when {
                    // Ctrl+Alt (Cmd+Option on macOS): a caret on the line above or below as well.
                    primary && alt && !shift -> KeyBinding(if (up) KeyCommand.AddCursorAbove else KeyCommand.AddCursorBelow)

                    isMac && meta && !alt -> move(if (up) KeyCommand.DocumentStart else KeyCommand.DocumentEnd)

                    alt && !primary && shift -> KeyBinding(if (up) KeyCommand.CopyLinesUp else KeyCommand.CopyLinesDown)

                    alt && !primary -> KeyBinding(if (up) KeyCommand.MoveLinesUp else KeyCommand.MoveLinesDown)

                    plain -> move(if (up) KeyCommand.LineUp else KeyCommand.LineDown)

                    else -> null
                }
            }

            Key.MoveHome -> when {
                primary && !alt -> move(KeyCommand.DocumentStart)
                plain -> move(KeyCommand.LineStart)
                else -> null
            }

            Key.MoveEnd -> when {
                primary && !alt -> move(KeyCommand.DocumentEnd)
                plain -> move(KeyCommand.LineEnd)
                else -> null
            }

            Key.PageUp -> if (plain) move(KeyCommand.PageUp) else null

            Key.PageDown -> if (plain) move(KeyCommand.PageDown) else null

            Key.Backspace -> when {
                isMac && meta -> KeyBinding(KeyCommand.DeleteToLineStart)
                word -> KeyBinding(KeyCommand.DeleteWordLeft)
                !alt && !primary -> KeyBinding(KeyCommand.DeleteLeft)
                else -> null
            }

            Key.Delete -> when {
                !isMac && shift && plain -> KeyBinding(KeyCommand.Cut)
                word -> KeyBinding(KeyCommand.DeleteWordRight)
                !alt && !primary -> KeyBinding(KeyCommand.DeleteRight)
                else -> null
            }

            Key.Insert -> when {
                !isMac && ctrl && !shift && !alt -> KeyBinding(KeyCommand.Copy)
                !isMac && shift && plain -> KeyBinding(KeyCommand.Paste)
                else -> null
            }

            Key.Enter, Key.NumPadEnter -> when {
                alt && !primary && !shift -> KeyBinding(KeyCommand.SelectAllMatches)
                !alt && !primary -> KeyBinding(KeyCommand.Enter)
                else -> null
            }

            Key.Tab -> when {
                ctrl || meta || alt -> null
                shift -> KeyBinding(KeyCommand.Outdent)
                else -> KeyBinding(KeyCommand.Indent)
            }

            Key.Escape -> if (plain && !shift) KeyBinding(KeyCommand.Escape) else null

            Key.F2 -> if (plain && !shift) KeyBinding(KeyCommand.Rename) else null

            Key.F3 -> when {
                shift && !alt && !primary -> KeyBinding(KeyCommand.FindPrevious)
                plain && !shift -> KeyBinding(KeyCommand.FindNext)
                else -> null
            }

            Key.F8 -> when {
                shift && !alt && !primary -> KeyBinding(KeyCommand.PreviousProblem)
                plain && !shift -> KeyBinding(KeyCommand.NextProblem)
                else -> null
            }

            Key.F12 -> when {
                shift && !alt && !primary -> KeyBinding(KeyCommand.FindReferences)
                plain && !shift -> KeyBinding(KeyCommand.GoToDefinition)
                else -> null
            }

            Key.Spacebar -> if (ctrl && !alt && !meta && !shift) KeyBinding(KeyCommand.TriggerSuggest) else null

            Key.F -> when {
                shift && alt && !primary -> KeyBinding(KeyCommand.FormatDocument)
                primary && !alt && !shift -> KeyBinding(KeyCommand.Find)
                else -> null
            }

            else -> if (primary && !alt) chord(event.key, shift) else null
        }
    }

    /** Cmd/Ctrl chords on letters and punctuation. */
    private fun chord(key: Key, shift: Boolean): KeyBinding? = when (key) {
        Key.Z -> KeyBinding(if (shift) KeyCommand.Redo else KeyCommand.Undo)
        Key.Y -> if (shift) null else KeyBinding(KeyCommand.Redo)
        Key.A -> if (shift) null else KeyBinding(KeyCommand.SelectAll)
        Key.C -> if (shift) null else KeyBinding(KeyCommand.Copy)
        Key.X -> if (shift) null else KeyBinding(KeyCommand.Cut)
        Key.V -> if (shift) null else KeyBinding(KeyCommand.Paste)
        Key.H -> if (shift) null else KeyBinding(KeyCommand.Replace)
        Key.G -> if (shift) null else KeyBinding(KeyCommand.GoToLine)
        Key.Slash -> if (shift) null else KeyBinding(KeyCommand.ToggleComment)
        Key.K -> if (shift) KeyBinding(KeyCommand.DeleteLines) else null
        Key.D -> if (shift) null else KeyBinding(KeyCommand.AddNextOccurrence)
        Key.L -> if (shift) KeyBinding(KeyCommand.SelectAllOccurrences) else null
        Key.Period -> if (shift) null else KeyBinding(KeyCommand.QuickFix)
        Key.O -> if (shift) KeyBinding(EditorCommand.GoToSymbol) else null
        else -> null
    }
}
