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

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.TokenType

/**
 * The colours of the chrome the editor draws itself on touch devices (the keyboard toolbar, the
 * completion strip, the menu over a selection), taken from an [EditorTheme] rather than the host's
 * [MaterialTheme]: a dark editor theme gets dark chrome even where the host has no Material theme
 * at all, as on the web, whose default Material colours are light.
 *
 * Surfaces are the editor's background moved towards its text colour, as VS Code's widgets sit a
 * shade off the editor; the accent is the theme's keyword colour.
 */
internal class EditorChromeColors(theme: EditorTheme) {
    /** The editor's background, opaque. */
    val background: Color = theme.background.compositeOver(Color.Black)

    /** Whether the theme is a dark one, by its background. */
    val isDark: Boolean = background.luminance() < 0.5f

    /** The text colour: the theme's default token colour. */
    val foreground: Color = (theme.tokenColors[TokenType.Default] ?: if (isDark) Color(0xFFD4D4D4) else Color(0xFF1F1F1F))
        .compositeOver(background)

    /** Secondary text: the foreground faded towards the background. */
    val foregroundVariant: Color = lerp(foreground, background, 0.3f)

    /** The accent for controls: the keyword colour, else the text colour. */
    val accent: Color = theme.tokenColors[TokenType.Keyword]?.compositeOver(background) ?: foreground

    /** A surface [level] steps (0 to 4) off the background, towards the text colour. */
    fun surface(level: Int): Color = lerp(background, foreground, SURFACE_STEP * level)

    /** A Material colour scheme from these colours, for the Material components in the chrome. */
    fun colorScheme(theme: EditorTheme): ColorScheme {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = background,
            primaryContainer = surface(3),
            onPrimaryContainer = foreground,
            secondary = theme.tokenColors[TokenType.TypeName]?.compositeOver(background) ?: base.secondary,
            secondaryContainer = theme.selectionColor.compositeOver(background),
            onSecondaryContainer = foreground,
            tertiary = theme.tokenColors[TokenType.StringLiteral]?.compositeOver(background) ?: base.tertiary,
            error = theme.errorColor,
            background = background,
            onBackground = foreground,
            surface = background,
            onSurface = foreground,
            surfaceVariant = surface(2),
            onSurfaceVariant = foregroundVariant,
            surfaceTint = accent,
            inverseSurface = foreground,
            inverseOnSurface = background,
            surfaceBright = surface(2),
            surfaceDim = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = surface(1),
            surfaceContainer = surface(1),
            surfaceContainerHigh = surface(2),
            surfaceContainerHighest = surface(3),
            outline = lerp(background, foreground, 0.4f),
            outlineVariant = surface(4),
        )
    }

    private companion object {
        const val SURFACE_STEP = 0.04f
    }
}

/**
 * Runs [content] under a [MaterialTheme] whose colours follow the active [LocalEditorTheme] (see
 * [EditorChromeColors]); the host's typography and shapes are kept.
 */
@Composable
internal fun EditorChromeTheme(content: @Composable () -> Unit) {
    val theme = LocalEditorTheme.current
    val colorScheme = remember(theme) { EditorChromeColors(theme).colorScheme(theme) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
