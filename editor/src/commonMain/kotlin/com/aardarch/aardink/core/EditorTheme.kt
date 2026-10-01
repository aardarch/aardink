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
package com.aardarch.aardink.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Complete visual specification for the code editor.
 *
 * Obtain pre-built instances from [com.aardarch.aardink.ui.EditorThemes], or build your own.
 * Override for a specific editor instance via [com.aardarch.aardink.ui.LocalEditorTheme].
 */
@Immutable
data class EditorTheme(
    // ── Chrome ────────────────────────────────────────────────────────────────
    val background: Color,
    val gutterBackground: Color,
    val gutterForeground: Color,
    val lineHighlight: Color,
    val selectionColor: Color,
    val findMatchColor: Color,
    val cursorColor: Color,

    // ── Tokens ────────────────────────────────────────────────────────────────
    /**
     * Colours by token type. A type without its own entry takes the entry of the longest dotted
     * prefix of its [TokenType.scope] that has one (a [NamedTokenType] key, or a built-in type by
     * its scope), as in Monaco: `comment.doc` takes `comment`'s colour unless it has its own.
     */
    val tokenColors: Map<TokenType, Color>,

    // ── Diagnostics ───────────────────────────────────────────────────────────
    val errorColor: Color,
    val warningColor: Color,
    val infoColor: Color,

    // ── Brackets ──────────────────────────────────────────────────────────────
    /**
     * Colours of bracket pairs by nesting depth, repeating: the outermost pair gets the first.
     * Shown with `EditorOptions.bracketPairColorization`. VS Code's dark-theme colours by default.
     */
    val bracketPairColors: List<Color> = DefaultBracketPairColors,

    // ── Whitespace ────────────────────────────────────────────────────────────
    /**
     * The dots and arrows `EditorOptions.renderWhitespace` draws for spaces and tabs.
     * [Color.Unspecified], the default, takes the text colour, faint.
     */
    val whitespaceColor: Color = Color.Unspecified,

    // ── Token styles ──────────────────────────────────────────────────────────
    /**
     * Bold, italic, underline and strikethrough by token type, found as [tokenColors] are: by the
     * type, else by the longest prefix of its scope with an entry. [TokenFontStyle.None] stops a
     * prefix's style. Empty by default: every token upright and regular.
     */
    val tokenFontStyles: Map<TokenType, TokenFontStyle> = emptyMap(),
)

/** VS Code's bracket-pair colours for dark themes: gold, orchid, blue. */
internal val DefaultBracketPairColors = listOf(Color(0xFFFFD700), Color(0xFFDA70D6), Color(0xFF179FFF))

/** VS Code's bracket-pair colours for light themes. */
internal val LightBracketPairColors = listOf(Color(0xFF0431FA), Color(0xFF319331), Color(0xFF7B3814))
