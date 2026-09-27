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

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.aardarch.aardink.platform.PlatformInfo

/**
 * What [editorKeyboardShortcuts] invokes: undo/redo, find/replace, go-to-line, indent/outdent,
 * the line commands (toggle comment, move, copy and delete lines) and escape. No multi-cursor
 * yet: Ctrl+D needs the editor's own renderer to show more than one caret.
 *
 * @param onEscape returns whether it dismissed anything, which decides whether the key is
 *   consumed. Escape must keep bubbling when there is nothing to dismiss, or it would trap a
 *   host's own dialog handling.
 */
internal class EditorShortcutActions(
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onFind: () -> Unit,
    val onReplace: () -> Unit,
    val onGoToLine: () -> Unit,
    val onIndent: () -> Unit,
    val onOutdent: () -> Unit,
    val onToggleComment: () -> Unit,
    val onMoveLines: (up: Boolean) -> Unit,
    val onCopyLines: (down: Boolean) -> Unit,
    val onDeleteLines: () -> Unit,
    val onEscape: () -> Boolean,
)

/**
 * Hardware-keyboard shortcuts for the editor field.
 *
 * Handled at *preview* level, before the text field sees the event. That matters most for
 * undo: `BasicTextField` has its own built-in Ctrl+Z which operates on the field's private undo
 * stack and bypasses the editor's own undo history entirely, desynchronising the field from
 * [com.aardarch.aardink.core.CodeDocument]. Intercepting here routes every undo through the same
 * history the toolbar buttons use. On Android this is a net-new fix for hosts
 * with a hardware keyboard attached, not a behaviour change for touch input — none of these
 * chords are reachable without one.
 *
 * Cmd on macOS, Ctrl everywhere else, per [PlatformInfo.isMacOs].
 */
internal fun Modifier.editorKeyboardShortcuts(actions: EditorShortcutActions): Modifier = onPreviewKeyEvent { event ->
    // KeyUp would fire a second time for the same chord, and auto-repeat should keep
    // repeating, so only KeyDown is considered.
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

    val command = if (PlatformInfo.isMacOs) event.isMetaPressed else event.isCtrlPressed
    when {
        // Shift+Cmd/Ctrl+Z before the plain chord: the plain branch would match otherwise.
        command && event.isShiftPressed && event.key == Key.Z -> {
            actions.onRedo()
            true
        }

        command && event.key == Key.Z -> {
            actions.onUndo()
            true
        }

        command && event.key == Key.Y -> {
            actions.onRedo()
            true
        }

        command && event.key == Key.F -> {
            actions.onFind()
            true
        }

        command && event.key == Key.H -> {
            actions.onReplace()
            true
        }

        command && event.key == Key.G -> {
            actions.onGoToLine()
            true
        }

        command && event.key == Key.Slash -> {
            actions.onToggleComment()
            true
        }

        command && event.isShiftPressed && event.key == Key.K -> {
            actions.onDeleteLines()
            true
        }

        // Alt (Option on macOS) + arrows, as in VS Code: with Shift they copy, without they move.
        event.isAltPressed && (event.key == Key.DirectionUp || event.key == Key.DirectionDown) -> {
            val up = event.key == Key.DirectionUp
            if (event.isShiftPressed) actions.onCopyLines(!up) else actions.onMoveLines(up)
            true
        }

        // Tab is indentation in a code editor, not focus traversal. A host that needs to
        // tab out of the editor can still do so from any other focusable.
        event.key == Key.Tab && event.isShiftPressed -> {
            actions.onOutdent()
            true
        }

        event.key == Key.Tab -> {
            actions.onIndent()
            true
        }

        event.key == Key.Escape -> actions.onEscape()

        else -> false
    }
}
