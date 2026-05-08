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
package com.aardarch.aardink.sample.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aardarch.editor.core.EditorTheme
import com.aardarch.editor.core.TokenType
import com.aardarch.editor.ui.LocalEditorTheme

/**
 * Wraps content in a [MaterialTheme] whose [ColorScheme] is derived from the supplied
 * [EditorTheme], so the whole sample app reskin tracks the editor theme dropdown.
 *
 * The editor surface itself continues to honour [LocalEditorTheme] for its syntax colours.
 */
@Composable
fun AardInkSampleTheme(
    editorTheme: EditorTheme,
    content: @Composable () -> Unit,
) {
    val scheme = editorTheme.toColorScheme()
    CompositionLocalProvider(LocalEditorTheme provides editorTheme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private fun EditorTheme.toColorScheme(): ColorScheme {
    val isDark = background.luminance() < 0.5f
    val base = if (isDark) darkColorScheme() else lightColorScheme()

    val keyword = tokenColors[TokenType.Keyword] ?: cursorColor
    val string = tokenColors[TokenType.StringLiteral] ?: keyword
    val function = tokenColors[TokenType.FunctionCall]
        ?: tokenColors[TokenType.Number]
        ?: keyword

    val onBg = if (isDark) Color.White else Color.Black
    // Container layers: keep enough contrast against `background` that surfaces drawn over the
    // editor canvas (e.g. the keyboard toolbar's `surfaceContainerHighest`) remain distinct.
    val containerLow = lerp(background, onBg, 0.06f)
    val container = lerp(background, onBg, 0.12f)
    val containerHigh = lerp(background, onBg, 0.18f)
    val containerHighest = lerp(background, onBg, 0.24f)
    val outline = lerp(background, onBg, 0.36f)

    return base.copy(
        primary = keyword,
        onPrimary = readableOn(keyword),
        primaryContainer = lerp(keyword, background, 0.70f),
        onPrimaryContainer = keyword,
        secondary = string,
        onSecondary = readableOn(string),
        secondaryContainer = lerp(string, background, 0.72f),
        onSecondaryContainer = string,
        tertiary = function,
        onTertiary = readableOn(function),
        tertiaryContainer = lerp(function, background, 0.72f),
        onTertiaryContainer = function,
        error = errorColor,
        onError = readableOn(errorColor),
        errorContainer = lerp(errorColor, background, 0.75f),
        onErrorContainer = errorColor,
        background = background,
        onBackground = onBg,
        surface = background,
        onSurface = onBg,
        surfaceVariant = container,
        onSurfaceVariant = lerp(onBg, background, 0.20f),
        surfaceContainerLowest = background,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
        outline = outline,
        outlineVariant = lerp(background, onBg, 0.22f),
        inverseSurface = onBg,
        inverseOnSurface = background,
    )
}

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun readableOn(c: Color): Color = if (c.luminance() < 0.5f) Color.White else Color.Black
