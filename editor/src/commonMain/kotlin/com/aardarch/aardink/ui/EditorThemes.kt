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

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.TokenType

/**
 * Six built-in [EditorTheme] instances.
 *
 * - [VsCodeDark] — VS Code Dark+ (reference theme, formalises legacy hardcoded colors)
 * - [VsCodeLight] — VS Code Light+
 * - [MaterialDark] — Material 3 dark surface with tonal token palette
 * - [MaterialLight] — Material 3 light surface
 * - [MidnightOcean] — Deep navy + teal/cyan (marketing screenshot theme)
 * - [SolarizedDark] — Ethan Schoonover's Solarized Dark
 */
object EditorThemes {

    val VsCodeDark: EditorTheme = EditorTheme(
        background = Color(0xFF1E1E1E),
        gutterBackground = Color(0xFF252526),
        gutterForeground = Color(0xFF858585),
        lineHighlight = Color(0xFF2D2D2D),
        selectionColor = Color(0xFF264F78),
        findMatchColor = Color(0xFF515C6A),
        cursorColor = Color(0xFFAEAFAD),
        tokenColors = mapOf(
            TokenType.Default to Color(0xFFD4D4D4),
            TokenType.Keyword to Color(0xFF569CD6),
            TokenType.Operator to Color(0xFFD4D4D4),
            TokenType.Punctuation to Color(0xFFD4D4D4),
            TokenType.StringLiteral to Color(0xFFCE9178),
            TokenType.Comment to Color(0xFF6A9955),
            TokenType.Number to Color(0xFFB5CEA8),
            TokenType.Identifier to Color(0xFF9CDCFE),
            TokenType.TypeName to Color(0xFF4EC9B0),
            TokenType.FunctionCall to Color(0xFFDCDCAA),
            TokenType.Annotation to Color(0xFFD7BA7D),
            TokenType.Invalid to Color(0xFFF44747),
        ),
        errorColor = Color(0xFFF44747),
        warningColor = Color(0xFFFFCC00),
        infoColor = Color(0xFF75BEFF),
    )

    val VsCodeLight: EditorTheme = EditorTheme(
        background = Color(0xFFFFFFFF),
        gutterBackground = Color(0xFFF3F3F3),
        gutterForeground = Color(0xFF237893),
        lineHighlight = Color(0xFFEBF1FB),
        selectionColor = Color(0xFFADD6FF),
        findMatchColor = Color(0xFFA8AC94),
        cursorColor = Color(0xFF000000),
        tokenColors = mapOf(
            TokenType.Default to Color(0xFF000000),
            TokenType.Keyword to Color(0xFF0000FF),
            TokenType.Operator to Color(0xFF000000),
            TokenType.Punctuation to Color(0xFF000000),
            TokenType.StringLiteral to Color(0xFFA31515),
            TokenType.Comment to Color(0xFF008000),
            TokenType.Number to Color(0xFF098658),
            TokenType.Identifier to Color(0xFF001080),
            TokenType.TypeName to Color(0xFF267F99),
            TokenType.FunctionCall to Color(0xFF795E26),
            TokenType.Annotation to Color(0xFF808000),
            TokenType.Invalid to Color(0xFFCD3131),
        ),
        errorColor = Color(0xFFCD3131),
        warningColor = Color(0xFF8B6914),
        infoColor = Color(0xFF316BCD),
    )

    val MaterialDark: EditorTheme = EditorTheme(
        background = Color(0xFF1C1B1F),
        gutterBackground = Color(0xFF211F26),
        gutterForeground = Color(0xFF938F99),
        lineHighlight = Color(0xFF2B2930),
        selectionColor = Color(0xFF4A4458),
        findMatchColor = Color(0xFF3B3849),
        cursorColor = Color(0xFFD0BCFF),
        tokenColors = mapOf(
            TokenType.Default to Color(0xFFE6E1E5),
            TokenType.Keyword to Color(0xFFCBA6F7),
            TokenType.Operator to Color(0xFF89B4FA),
            TokenType.Punctuation to Color(0xFFCDD6F4),
            TokenType.StringLiteral to Color(0xFFA6E3A1),
            TokenType.Comment to Color(0xFF6C7086),
            TokenType.Number to Color(0xFFFAB387),
            TokenType.Identifier to Color(0xFF89DCEB),
            TokenType.TypeName to Color(0xFF89B4FA),
            TokenType.FunctionCall to Color(0xFF89B4FA),
            TokenType.Annotation to Color(0xFFF38BA8),
            TokenType.Invalid to Color(0xFFF38BA8),
        ),
        errorColor = Color(0xFFF38BA8),
        warningColor = Color(0xFFFAB387),
        infoColor = Color(0xFF89B4FA),
    )

    val MaterialLight: EditorTheme = EditorTheme(
        background = Color(0xFFFEF7FF),
        gutterBackground = Color(0xFFF7F2FA),
        gutterForeground = Color(0xFF79747E),
        lineHighlight = Color(0xFFEDE7F6),
        selectionColor = Color(0xFFD0BCFF),
        findMatchColor = Color(0xFFE8DEF8),
        cursorColor = Color(0xFF6750A4),
        tokenColors = mapOf(
            TokenType.Default to Color(0xFF1C1B1F),
            TokenType.Keyword to Color(0xFF6750A4),
            TokenType.Operator to Color(0xFF1C1B1F),
            TokenType.Punctuation to Color(0xFF49454F),
            TokenType.StringLiteral to Color(0xFF397741),
            TokenType.Comment to Color(0xFF938F99),
            TokenType.Number to Color(0xFFB53E06),
            TokenType.Identifier to Color(0xFF006874),
            TokenType.TypeName to Color(0xFF006874),
            TokenType.FunctionCall to Color(0xFF7E5700),
            TokenType.Annotation to Color(0xFFB3261E),
            TokenType.Invalid to Color(0xFFB3261E),
        ),
        errorColor = Color(0xFFB3261E),
        warningColor = Color(0xFF7E5700),
        infoColor = Color(0xFF006874),
    )

    /** Deep navy + teal/cyan — the marketing screenshot theme. */
    val MidnightOcean: EditorTheme = EditorTheme(
        background = Color(0xFF0D1117),
        gutterBackground = Color(0xFF0D1117),
        gutterForeground = Color(0xFF484F58),
        lineHighlight = Color(0xFF161B22),
        selectionColor = Color(0xFF264F78),
        findMatchColor = Color(0xFF3D444D),
        cursorColor = Color(0xFF79C0FF),
        tokenColors = mapOf(
            TokenType.Default to Color(0xFFE6EDF3),
            TokenType.Keyword to Color(0xFFFF7B72),
            TokenType.Operator to Color(0xFFE6EDF3),
            TokenType.Punctuation to Color(0xFFC9D1D9),
            TokenType.StringLiteral to Color(0xFFA5D6FF),
            TokenType.Comment to Color(0xFF8B949E),
            TokenType.Number to Color(0xFF79C0FF),
            TokenType.Identifier to Color(0xFFE6EDF3),
            TokenType.TypeName to Color(0xFFFFA657),
            TokenType.FunctionCall to Color(0xFFD2A8FF),
            TokenType.Annotation to Color(0xFF7EE787),
            TokenType.Invalid to Color(0xFFFF7B72),
        ),
        errorColor = Color(0xFFFF7B72),
        warningColor = Color(0xFFE3B341),
        infoColor = Color(0xFF79C0FF),
    )

    /** Ethan Schoonover's classic Solarized Dark palette. */
    val SolarizedDark: EditorTheme = EditorTheme(
        background = Color(0xFF002B36),
        gutterBackground = Color(0xFF073642),
        gutterForeground = Color(0xFF586E75),
        lineHighlight = Color(0xFF073642),
        selectionColor = Color(0xFF124652),
        findMatchColor = Color(0xFF0D3640),
        cursorColor = Color(0xFF839496),
        tokenColors = mapOf(
            TokenType.Default to Color(0xFF839496),
            TokenType.Keyword to Color(0xFF859900),
            TokenType.Operator to Color(0xFF839496),
            TokenType.Punctuation to Color(0xFF839496),
            TokenType.StringLiteral to Color(0xFF2AA198),
            TokenType.Comment to Color(0xFF586E75),
            TokenType.Number to Color(0xFFD33682),
            TokenType.Identifier to Color(0xFF268BD2),
            TokenType.TypeName to Color(0xFFCB4B16),
            TokenType.FunctionCall to Color(0xFF268BD2),
            TokenType.Annotation to Color(0xFF6C71C4),
            TokenType.Invalid to Color(0xFFDC322F),
        ),
        errorColor = Color(0xFFDC322F),
        warningColor = Color(0xFFCB4B16),
        infoColor = Color(0xFF268BD2),
    )
}
