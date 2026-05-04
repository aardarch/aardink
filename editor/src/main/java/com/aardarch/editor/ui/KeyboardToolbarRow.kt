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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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

    // Stable lambdas via rememberUpdatedState
    val insertChar by rememberUpdatedState(onInsertChar)

    LazyRow(
        modifier = Modifier
            .background(background)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // Cursor nudge — always first
        item {
            ToolbarButton(label = "←", enabled = true, color = primary, onClick = onMoveCursorLeft)
        }
        item {
            ToolbarButton(label = "→", enabled = true, color = primary, onClick = onMoveCursorRight)
        }

        // Quick-insert characters
        items(quickChars, key = { it }) { char ->
            ToolbarButton(
                label = char.toString(),
                enabled = true,
                color = onSurface,
                onClick = { insertChar(char) },
            )
        }

        // Undo / Redo — always last
        item {
            ToolbarButton(label = "↩", enabled = canUndo, color = onSurface, onClick = onUndo)
        }
        item {
            ToolbarButton(label = "↪", enabled = canRedo, color = onSurface, onClick = onRedo)
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    enabled: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) color else color.copy(alpha = 0.38f),
        )
    }
}
