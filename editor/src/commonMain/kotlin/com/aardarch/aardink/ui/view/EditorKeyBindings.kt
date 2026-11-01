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
    SelectAll,
    Copy,
    Cut,
    Paste,
    MoveLinesUp,
    MoveLinesDown,
    CopyLinesUp,
    CopyLinesDown,
    DeleteLines,
    AddNextOccurrence,
    AddCursorAbove,
    AddCursorBelow,
    ColumnSelectLeft,
    ColumnSelectRight,
    ColumnSelectUp,
    ColumnSelectDown,
    ColumnSelectPageUp,
    ColumnSelectPageDown,
    Escape,
    FormatDocument,
    FindNext,
    FindPrevious,
    SelectAllMatches,
}

/** A [command], and for the movement commands whether Shift extends the selection ([select]). */
internal data class KeyBinding(val command: KeyCommand, val select: Boolean = false)

/**
 * The editor's fixed keys, as a table: moving the caret and selecting, deleting, typing's own keys
 * (Enter, Tab, Escape), the clipboard, line moves and copies, and the find panel's keys, as VS
 * Code binds them, with Cmd for Ctrl on macOS and macOS's own movement keys (Option+arrows by word,
 * Cmd+arrows to line and document ends). The keys that run public commands are data instead:
 * [com.aardarch.aardink.ui.EditorKeyBindings], which a host overrides.
 *
 * Keys the browser keeps for itself (Ctrl+W, Ctrl+T, Ctrl+N) never reach a web page, so they are
 * not bound.
 */
internal object KeyTable {

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

            Key.F3 -> when {
                shift && !alt && !primary -> KeyBinding(KeyCommand.FindPrevious)
                plain && !shift -> KeyBinding(KeyCommand.FindNext)
                else -> null
            }

            Key.F -> when {
                shift && alt && !primary -> KeyBinding(KeyCommand.FormatDocument)
                else -> null
            }

            else -> if (primary && !alt) chord(event.key, shift) else null
        }
    }

    /** Cmd/Ctrl chords on letters and punctuation. */
    private fun chord(key: Key, shift: Boolean): KeyBinding? = when (key) {
        Key.A -> if (shift) null else KeyBinding(KeyCommand.SelectAll)
        Key.C -> if (shift) null else KeyBinding(KeyCommand.Copy)
        Key.X -> if (shift) null else KeyBinding(KeyCommand.Cut)
        Key.V -> if (shift) null else KeyBinding(KeyCommand.Paste)
        Key.K -> if (shift) KeyBinding(KeyCommand.DeleteLines) else null
        Key.D -> if (shift) null else KeyBinding(KeyCommand.AddNextOccurrence)
        else -> null
    }
}
