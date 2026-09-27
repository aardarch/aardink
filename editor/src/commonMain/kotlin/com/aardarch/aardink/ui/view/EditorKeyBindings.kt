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

/** Everything a key can make the editor do. */
internal enum class EditorCommand {
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
}

/** A [command], and for the movement commands whether Shift extends the selection ([select]). */
internal data class KeyBinding(val command: EditorCommand, val select: Boolean = false)

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

        fun move(command: EditorCommand) = KeyBinding(command, select = shift)

        // Ctrl+Shift+Alt (Cmd+Shift+Option on macOS) with an arrow or page key: a column selection.
        if (primary && shift && alt) {
            return when (event.key) {
                Key.DirectionLeft -> KeyBinding(EditorCommand.ColumnSelectLeft)
                Key.DirectionRight -> KeyBinding(EditorCommand.ColumnSelectRight)
                Key.DirectionUp -> KeyBinding(EditorCommand.ColumnSelectUp)
                Key.DirectionDown -> KeyBinding(EditorCommand.ColumnSelectDown)
                Key.PageUp -> KeyBinding(EditorCommand.ColumnSelectPageUp)
                Key.PageDown -> KeyBinding(EditorCommand.ColumnSelectPageDown)
                else -> null
            }
        }

        return when (event.key) {
            Key.DirectionLeft -> when {
                isMac && meta && !alt -> move(EditorCommand.LineStart)
                word -> move(EditorCommand.WordLeft)
                plain -> move(EditorCommand.CharLeft)
                else -> null
            }

            Key.DirectionRight -> when {
                isMac && meta && !alt -> move(EditorCommand.LineEnd)
                word -> move(EditorCommand.WordRight)
                plain -> move(EditorCommand.CharRight)
                else -> null
            }

            Key.DirectionUp, Key.DirectionDown -> {
                val up = event.key == Key.DirectionUp
                when {
                    // Ctrl+Alt (Cmd+Option on macOS): a caret on the line above or below as well.
                    primary && alt && !shift -> KeyBinding(if (up) EditorCommand.AddCursorAbove else EditorCommand.AddCursorBelow)

                    isMac && meta && !alt -> move(if (up) EditorCommand.DocumentStart else EditorCommand.DocumentEnd)

                    alt && !primary && shift -> KeyBinding(if (up) EditorCommand.CopyLinesUp else EditorCommand.CopyLinesDown)

                    alt && !primary -> KeyBinding(if (up) EditorCommand.MoveLinesUp else EditorCommand.MoveLinesDown)

                    plain -> move(if (up) EditorCommand.LineUp else EditorCommand.LineDown)

                    else -> null
                }
            }

            Key.MoveHome -> when {
                primary && !alt -> move(EditorCommand.DocumentStart)
                plain -> move(EditorCommand.LineStart)
                else -> null
            }

            Key.MoveEnd -> when {
                primary && !alt -> move(EditorCommand.DocumentEnd)
                plain -> move(EditorCommand.LineEnd)
                else -> null
            }

            Key.PageUp -> if (plain) move(EditorCommand.PageUp) else null

            Key.PageDown -> if (plain) move(EditorCommand.PageDown) else null

            Key.Backspace -> when {
                isMac && meta -> KeyBinding(EditorCommand.DeleteToLineStart)
                word -> KeyBinding(EditorCommand.DeleteWordLeft)
                !alt && !primary -> KeyBinding(EditorCommand.DeleteLeft)
                else -> null
            }

            Key.Delete -> when {
                !isMac && shift && plain -> KeyBinding(EditorCommand.Cut)
                word -> KeyBinding(EditorCommand.DeleteWordRight)
                !alt && !primary -> KeyBinding(EditorCommand.DeleteRight)
                else -> null
            }

            Key.Insert -> when {
                !isMac && ctrl && !shift && !alt -> KeyBinding(EditorCommand.Copy)
                !isMac && shift && plain -> KeyBinding(EditorCommand.Paste)
                else -> null
            }

            Key.Enter, Key.NumPadEnter -> if (!alt && !primary) KeyBinding(EditorCommand.Enter) else null

            Key.Tab -> when {
                ctrl || meta || alt -> null
                shift -> KeyBinding(EditorCommand.Outdent)
                else -> KeyBinding(EditorCommand.Indent)
            }

            Key.Escape -> if (plain && !shift) KeyBinding(EditorCommand.Escape) else null

            Key.F12 -> when {
                shift && !alt && !primary -> KeyBinding(EditorCommand.FindReferences)
                plain && !shift -> KeyBinding(EditorCommand.GoToDefinition)
                else -> null
            }

            Key.Spacebar -> if (ctrl && !alt && !meta && !shift) KeyBinding(EditorCommand.TriggerSuggest) else null

            Key.F -> when {
                shift && alt && !primary -> KeyBinding(EditorCommand.FormatDocument)
                primary && !alt && !shift -> KeyBinding(EditorCommand.Find)
                else -> null
            }

            else -> if (primary && !alt) chord(event.key, shift) else null
        }
    }

    /** Cmd/Ctrl chords on letters and punctuation. */
    private fun chord(key: Key, shift: Boolean): KeyBinding? = when (key) {
        Key.Z -> KeyBinding(if (shift) EditorCommand.Redo else EditorCommand.Undo)
        Key.Y -> if (shift) null else KeyBinding(EditorCommand.Redo)
        Key.A -> if (shift) null else KeyBinding(EditorCommand.SelectAll)
        Key.C -> if (shift) null else KeyBinding(EditorCommand.Copy)
        Key.X -> if (shift) null else KeyBinding(EditorCommand.Cut)
        Key.V -> if (shift) null else KeyBinding(EditorCommand.Paste)
        Key.H -> if (shift) null else KeyBinding(EditorCommand.Replace)
        Key.G -> if (shift) null else KeyBinding(EditorCommand.GoToLine)
        Key.Slash -> if (shift) null else KeyBinding(EditorCommand.ToggleComment)
        Key.K -> if (shift) KeyBinding(EditorCommand.DeleteLines) else null
        Key.D -> if (shift) null else KeyBinding(EditorCommand.AddNextOccurrence)
        Key.L -> if (shift) KeyBinding(EditorCommand.SelectAllOccurrences) else null
        else -> null
    }
}
