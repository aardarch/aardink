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
package com.aardarch.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A horizontally scrollable row of quick-input buttons that slides in above the IME.
 *
 * Provides:
 * - Language-specific quick-insert characters from [quickChars]
 * - Cursor nudge arrows (← →) for precise caret placement without touch-targeting individual chars
 * - Undo / Redo shortcuts
 *
 * The row is only visible when the IME is open ([WindowInsets.ime] bottom > 0).
 *
 * @param quickChars Characters to display as single-tap insert buttons. Supplied by
 *   [com.aardarch.editor.core.IncrementalTokenizer.keyboardToolbarChars].
 * @param canUndo Whether the undo button should be enabled.
 * @param canRedo Whether the redo button should be enabled.
 * @param onInsertChar Called when the user taps a quick-insert character button.
 * @param onMoveCursorLeft Called when the user taps the ← cursor nudge.
 * @param onMoveCursorRight Called when the user taps the → cursor nudge.
 * @param onUndo Called when the user taps Undo.
 * @param onRedo Called when the user taps Redo.
 */
@Composable
fun KeyboardToolbarRow(
    quickChars: List<Char>,
    canUndo: Boolean,
    canRedo: Boolean,
    onInsertChar: (Char) -> Unit,
    onMoveCursorLeft: () -> Unit,
    onMoveCursorRight: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottom > 0

    AnimatedVisibility(
        visible = imeVisible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        ToolbarContent(
            quickChars = quickChars,
            canUndo = canUndo,
            canRedo = canRedo,
            onInsertChar = onInsertChar,
            onMoveCursorLeft = onMoveCursorLeft,
            onMoveCursorRight = onMoveCursorRight,
            onUndo = onUndo,
            onRedo = onRedo,
        )
    }
}

@Composable
private fun ToolbarContent(
    quickChars: List<Char>,
    canUndo: Boolean,
    canRedo: Boolean,
    onInsertChar: (Char) -> Unit,
    onMoveCursorLeft: () -> Unit,
    onMoveCursorRight: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary

    val insertChar by rememberUpdatedState(onInsertChar)

    LazyRow(
        modifier = Modifier
            .background(background)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        item {
            ToolbarIconButton(
                icon = EditorIcons.KeyboardArrowLeft,
                contentDescription = "Move cursor left",
                tint = primary,
                onClick = onMoveCursorLeft,
            )
        }
        item {
            ToolbarIconButton(
                icon = EditorIcons.KeyboardArrowRight,
                contentDescription = "Move cursor right",
                tint = primary,
                onClick = onMoveCursorRight,
            )
        }

        items(quickChars, key = { it }) { char ->
            ToolbarCharButton(
                label = char.toString(),
                color = onSurface,
                onClick = { insertChar(char) },
            )
        }

        item {
            ToolbarIconButton(
                icon = EditorIcons.Undo,
                contentDescription = "Undo",
                tint = onSurface,
                enabled = canUndo,
                onClick = onUndo,
            )
        }
        item {
            ToolbarIconButton(
                icon = EditorIcons.Redo,
                contentDescription = "Redo",
                tint = onSurface,
                enabled = canRedo,
                onClick = onRedo,
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun ToolbarCharButton(label: String, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
