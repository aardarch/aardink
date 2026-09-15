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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role

/**
 * A horizontally scrollable row of quick-input buttons that slides in above the IME.
 *
 * All buttons (undo/redo, cursor arrows, quick-insert characters) share the same shape
 * and size so the toolbar reads as a single, consistent surface. Customize the look via
 * [style] (see [KeyboardToolbarDefaults.style]).
 *
 * Provides:
 * - Undo / Redo shortcuts
 * - Cursor nudge arrows (← →) for precise caret placement
 * - Language-specific quick-insert characters from [quickChars]
 *
 * The row is only visible when the IME is open ([ime] inset bottom > 0).
 *
 * @param quickChars Characters to display as single-tap insert buttons. Supplied by
 *   [com.aardarch.aardink.core.IncrementalTokenizer.keyboardToolbarChars].
 * @param canUndo Whether the undo button should be enabled.
 * @param canRedo Whether the redo button should be enabled.
 * @param onInsertChar Called when the user taps a quick-insert character button.
 * @param onMoveCursorLeft Called when the user taps the ← cursor nudge.
 * @param onMoveCursorRight Called when the user taps the → cursor nudge.
 * @param onUndo Called when the user taps Undo.
 * @param onRedo Called when the user taps Redo.
 * @param style Visual style for the toolbar. Defaults to [KeyboardToolbarDefaults.style].
 * @param alwaysVisible When true, the toolbar is always shown regardless of IME state. When
 *   false (default), it slides in/out tied to IME visibility — appropriate when anchored just
 *   above the keyboard.
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
    style: KeyboardToolbarStyle = KeyboardToolbarDefaults.style(),
    alwaysVisible: Boolean = false,
) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val visible = alwaysVisible || imeBottom > 0

    AnimatedVisibility(
        visible = visible,
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
            style = style,
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
    style: KeyboardToolbarStyle,
) {
    val insertChar by rememberUpdatedState(onInsertChar)

    LazyRow(
        modifier = Modifier.background(style.background),
        contentPadding = style.contentPadding,
        horizontalArrangement = Arrangement.spacedBy(style.itemSpacing),
    ) {
        item {
            IconToolbarButton(
                icon = EditorIcons.Undo,
                tint = style.iconColor,
                contentDescription = "Undo",
                enabled = canUndo,
                style = style,
                onClick = onUndo,
            )
        }
        item {
            IconToolbarButton(
                icon = EditorIcons.Redo,
                tint = style.iconColor,
                contentDescription = "Redo",
                enabled = canRedo,
                style = style,
                onClick = onRedo,
            )
        }

        item { SectionGap(style) }

        item {
            IconToolbarButton(
                icon = EditorIcons.KeyboardArrowLeft,
                tint = style.accentColor,
                contentDescription = "Move cursor left",
                style = style,
                onClick = onMoveCursorLeft,
            )
        }
        item {
            IconToolbarButton(
                icon = EditorIcons.KeyboardArrowRight,
                tint = style.accentColor,
                contentDescription = "Move cursor right",
                style = style,
                onClick = onMoveCursorRight,
            )
        }

        if (quickChars.isNotEmpty()) {
            item { SectionGap(style) }
        }

        items(quickChars, key = { it }) { char ->
            CharToolbarButton(
                char = char,
                style = style,
                onClick = { insertChar(char) },
            )
        }
    }
}

@Composable
private fun IconToolbarButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    style: KeyboardToolbarStyle,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else style.disabledAlpha
    ToolbarSlot(style = style, contentDescription = contentDescription, enabled = enabled, onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(style.iconSize),
        )
    }
}

@Composable
private fun CharToolbarButton(char: Char, style: KeyboardToolbarStyle, onClick: () -> Unit) {
    ToolbarSlot(style = style, contentDescription = "Insert $char", onClick = onClick) {
        Text(
            text = char.toString(),
            fontSize = style.charFontSize,
            fontFamily = style.charFontFamily,
            color = style.charColor,
        )
    }
}

@Composable
private fun ToolbarSlot(
    style: KeyboardToolbarStyle,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = style.buttonShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(style.buttonSize)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier.size(style.buttonSize),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun SectionGap(style: KeyboardToolbarStyle) {
    Spacer(Modifier.width(style.sectionSpacing - style.itemSpacing))
}
