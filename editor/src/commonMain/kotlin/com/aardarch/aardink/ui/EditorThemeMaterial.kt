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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.aardarch.aardink.core.DefaultBracketPairColors
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.LightBracketPairColors
import com.aardarch.aardink.core.TokenType

/**
 * A theme from a Material 3 [colorScheme], so the editor follows an app's colours, dynamic colour
 * included: the surface for the background, the primary colour for the caret, keywords and tags,
 * the secondary and tertiary for strings, numbers and types, the error colour for errors. [dark]
 * says which way the scheme goes, for the colours Material 3 has no role for (warnings, brackets).
 */
fun EditorTheme.Companion.fromColorScheme(colorScheme: ColorScheme, dark: Boolean): EditorTheme {
    val surface = colorScheme.surface
    fun tint(color: Color, alpha: Float) = color.copy(alpha = alpha).compositeOver(surface)
    return EditorTheme(
        background = surface,
        gutterBackground = colorScheme.surfaceContainerLow,
        gutterForeground = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        lineHighlight = tint(colorScheme.onSurface, 0.05f),
        selectionColor = colorScheme.primary.copy(alpha = 0.3f),
        findMatchColor = colorScheme.tertiary.copy(alpha = 0.35f),
        cursorColor = colorScheme.primary,
        tokenColors = mapOf(
            TokenType.Default to colorScheme.onSurface,
            TokenType.Keyword to colorScheme.primary,
            TokenType.Operator to colorScheme.onSurfaceVariant,
            TokenType.Punctuation to colorScheme.onSurfaceVariant,
            TokenType.StringLiteral to colorScheme.tertiary,
            TokenType.Comment to colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            TokenType.Number to colorScheme.secondary,
            TokenType.Identifier to colorScheme.onSurface,
            TokenType.TypeName to colorScheme.secondary,
            TokenType.FunctionCall to colorScheme.primary,
            TokenType.Annotation to colorScheme.tertiary,
            TokenType.Invalid to colorScheme.error,
        ),
        errorColor = colorScheme.error,
        warningColor = if (dark) Color(0xFFFFB300) else Color(0xFFB26A00),
        infoColor = colorScheme.secondary,
        bracketPairColors = if (dark) DefaultBracketPairColors else LightBracketPairColors,
    )
}
