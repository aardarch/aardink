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

    // ── Overview ruler ────────────────────────────────────────────────────────
    /**
     * The colours of the overview ruler (`EditorOptions.overviewRuler`). Each one left
     * [Color.Unspecified], the default, follows from the theme's other colours.
     */
    val overviewRulerColors: OverviewRulerColors = OverviewRulerColors(),

    // ── Chrome colours ────────────────────────────────────────────────────────
    /**
     * The colours of the minimap, the scrollbars, the guides and rulers, the editor's cards and
     * the text it adds (inlay hints, lenses, ghost text). Each one left [Color.Unspecified], the
     * default, follows from the theme's other colours, as it always has.
     */
    val chromeColors: ChromeColors = ChromeColors(),

    // ── Token backgrounds ─────────────────────────────────────────────────────
    /**
     * Backgrounds by token type, found as [tokenColors] are, drawn behind the token's text, as a
     * Monaco rule's `background`. Empty by default: no token has a background of its own.
     */
    val tokenBackgrounds: Map<TokenType, Color> = emptyMap(),
) {
    /** For themes built from elsewhere: `EditorTheme.fromColorScheme(...)` in `com.aardarch.aardink.ui`. */
    companion object
}

/**
 * The overview ruler's colours, VS Code's `editorOverviewRuler.*`. Diagnostics take the theme's
 * error, warning and info colours. [Color.Unspecified], the default for each, follows from the
 * theme: [background] its background, [findMatch] its find-match colour made opaque, [selection]
 * its caret colour, [added] and [modified] the diff lane's green and blue.
 */
@Immutable
data class OverviewRulerColors(
    val background: Color = Color.Unspecified,
    val border: Color = Color.Unspecified,
    val findMatch: Color = Color.Unspecified,
    val selection: Color = Color.Unspecified,
    val added: Color = Color.Unspecified,
    val modified: Color = Color.Unspecified,
)

/**
 * The colours of the editor's chrome, by VS Code's color keys. Each one [Color.Unspecified], the
 * default, follows from the theme as it did before these existed:
 *
 * - [minimapBackground] (`minimap.background`): the editor's background; [minimapSlider]
 *   (`minimapSlider.background`): the text colour, faint.
 * - [scrollbarSlider] and [scrollbarSliderHover] (`scrollbarSlider.background` and
 *   `.hoverBackground`): the platform's scrollbar colours. Android draws no scrollbar.
 * - [indentGuide] and [activeIndentGuide] (`editorIndentGuide.background1` and
 *   `.activeBackground1`): the text colour at 16 % and 45 %.
 * - [bracketPairGuide] (`editorBracketPairGuide.background1`): one colour for every depth, in
 *   place of the theme's bracket-pair colours at half strength.
 * - [ruler] (`editorRuler.foreground`): the text colour at 20 %.
 * - [widgetBackground], [widgetForeground] and [widgetBorder] (`editorWidget.*`): the cards,
 *   menus and find panel. Their background is otherwise a shade off the editor's, their text the
 *   theme's text colour; they have a border only with [widgetBorder]. Text is moved towards white
 *   or black where it would not read.
 * - [inlayHint] and [inlayHintBackground] (`editorInlayHint.foreground` and `.background`), and
 *   [codeLens] (`editorCodeLens.foreground`): the text colour at 60 %, a hint on a ground of 8 %.
 * - [ghostText] (`editorGhostText.foreground`): the text colour at 45 %.
 */
@Immutable
data class ChromeColors(
    val minimapBackground: Color = Color.Unspecified,
    val minimapSlider: Color = Color.Unspecified,
    val scrollbarSlider: Color = Color.Unspecified,
    val scrollbarSliderHover: Color = Color.Unspecified,
    val indentGuide: Color = Color.Unspecified,
    val activeIndentGuide: Color = Color.Unspecified,
    val bracketPairGuide: Color = Color.Unspecified,
    val ruler: Color = Color.Unspecified,
    val widgetBackground: Color = Color.Unspecified,
    val widgetForeground: Color = Color.Unspecified,
    val widgetBorder: Color = Color.Unspecified,
    val inlayHint: Color = Color.Unspecified,
    val inlayHintBackground: Color = Color.Unspecified,
    val codeLens: Color = Color.Unspecified,
    val ghostText: Color = Color.Unspecified,
)

/** VS Code's bracket-pair colours for dark themes: gold, orchid, blue. */
internal val DefaultBracketPairColors = listOf(Color(0xFFFFD700), Color(0xFFDA70D6), Color(0xFF179FFF))

/** VS Code's bracket-pair colours for light themes. */
internal val LightBracketPairColors = listOf(Color(0xFF0431FA), Color(0xFF319331), Color(0xFF7B3814))
