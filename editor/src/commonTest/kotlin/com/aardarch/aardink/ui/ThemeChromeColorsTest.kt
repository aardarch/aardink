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
import androidx.compose.ui.graphics.compositeOver
import com.aardarch.aardink.core.ChromeColors
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.core.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `EditorTheme.chromeColors` and `tokenBackgrounds`, and the high-contrast themes. */
class ThemeChromeColorsTest {

    @Test
    fun `the built-in themes leave every chrome colour to follow the theme, as before`() {
        listOf(
            EditorThemes.VsCodeDark,
            EditorThemes.VsCodeLight,
            EditorThemes.MaterialDark,
            EditorThemes.MaterialLight,
            EditorThemes.MidnightOcean,
            EditorThemes.SolarizedDark,
        ).forEach {
            assertEquals(ChromeColors(), it.chromeColors)
            assertTrue(it.tokenBackgrounds.isEmpty())
        }
    }

    @Test
    fun `every token colour of the high-contrast themes reaches 7 to 1 on their background`() {
        for (theme in listOf(EditorThemes.HighContrastDark, EditorThemes.HighContrastLight)) {
            for ((type, color) in theme.tokenColors) {
                val ratio = contrastRatio(color.compositeOver(theme.background), theme.background)
                assertTrue(ratio >= 7f, "$type at $ratio")
            }
            assertTrue(contrastRatio(theme.gutterForeground, theme.gutterBackground) >= 7f)
            assertTrue(theme.chromeColors.widgetBorder != Color.Unspecified, "the cards have a border")
        }
    }

    @Test
    fun `the parser reads the chrome keys`() {
        val theme = EditorThemeParser.fromMonacoJson(
            """{
              "base": "vs-dark",
              "rules": [],
              "colors": {
                "minimap.background": "#101010",
                "minimapSlider.background": "#20202080",
                "scrollbarSlider.background": "#30303080",
                "scrollbarSlider.hoverBackground": "#404040",
                "editorIndentGuide.background1": "#505050",
                "editorIndentGuide.activeBackground": "#606060",
                "editorBracketPairGuide.background1": "#707070",
                "editorRuler.foreground": "#808080",
                "editorWidget.background": "#252526",
                "editorWidget.foreground": "#CCCCCC",
                "editorWidget.border": "#454545",
                "editorInlayHint.foreground": "#969696",
                "editorInlayHint.background": "#2A2A2A",
                "editorCodeLens.foreground": "#999999",
                "editorGhostText.foreground": "#777777"
              }
            }""",
        )!!
        assertEquals(
            ChromeColors(
                minimapBackground = Color(0xFF101010),
                minimapSlider = Color(0x80202020),
                scrollbarSlider = Color(0x80303030),
                scrollbarSliderHover = Color(0xFF404040),
                indentGuide = Color(0xFF505050),
                activeIndentGuide = Color(0xFF606060),
                bracketPairGuide = Color(0xFF707070),
                ruler = Color(0xFF808080),
                widgetBackground = Color(0xFF252526),
                widgetForeground = Color(0xFFCCCCCC),
                widgetBorder = Color(0xFF454545),
                inlayHint = Color(0xFF969696),
                inlayHintBackground = Color(0xFF2A2A2A),
                codeLens = Color(0xFF999999),
                ghostText = Color(0xFF777777),
            ),
            theme.chromeColors,
        )
    }

    @Test
    fun `a VS Code theme's contrastBorder borders the cards`() {
        val theme = EditorThemeParser.fromJson("""{ "colors": { "contrastBorder": "#6FC3DF" } }""")!!
        assertEquals(Color(0xFF6FC3DF), theme.chromeColors.widgetBorder)
    }

    @Test
    fun `a Monaco rule's background is a token background, and the empty token's the editor's`() {
        val theme = EditorThemeParser.fromMonacoJson(
            """{
              "base": "vs-dark",
              "rules": [
                { "token": "invalid", "background": "5A1D1D" },
                { "token": "string.escape", "foreground": "D7BA7D", "background": "#303030" },
                { "token": "", "background": "101820" }
              ]
            }""",
        )!!
        assertEquals(Color(0xFF5A1D1D), theme.tokenBackgrounds[NamedTokenType("invalid")])
        assertEquals(Color(0xFF303030), theme.tokenBackgrounds[NamedTokenType("string.escape")])
        assertEquals(Color(0xFF101820), theme.background)
        assertEquals(Color(0xFFD7BA7D), theme.tokenColors[NamedTokenType("string.escape")], "the foreground is still read")
    }

    @Test
    fun `Monaco's high-contrast bases fill in from the high-contrast themes`() {
        val dark = EditorThemeParser.fromMonacoJson("""{ "base": "hc-black", "rules": [] }""")!!
        assertEquals(EditorThemes.HighContrastDark.background, dark.background)
        assertEquals(EditorThemes.HighContrastDark.chromeColors, dark.chromeColors)
        val light = EditorThemeParser.fromMonacoJson("""{ "base": "hc-light", "rules": [] }""")!!
        assertEquals(EditorThemes.HighContrastLight.background, light.background)
        assertEquals(EditorThemes.HighContrastLight.chromeColors.widgetBorder, light.chromeColors.widgetBorder)
    }

    @Test
    fun `a token background is found by the longest prefix of the token's scope, and drawn behind its text`() {
        val theme = EditorThemes.VsCodeDark.copy(tokenBackgrounds = mapOf(NamedTokenType("string") to Color(0xFF303030)))
        val resolver = TokenStyleResolver(theme)
        assertEquals(Color(0xFF303030), resolver.background(TokenType.StringLiteral))
        assertEquals(Color(0xFF303030), resolver.background(NamedTokenType("string.escape")))
        assertNull(resolver.background(TokenType.Keyword))
        assertEquals(Color(0xFF303030), resolver.spanStyle(NamedTokenType("string.escape"))?.background)
        assertEquals(Color.Unspecified, resolver.spanStyle(TokenType.Keyword)?.background)
        // A token with only a background still gets a style.
        val plain = TokenStyleResolver(
            EditorTheme(
                background = Color.Black, gutterBackground = Color.Black, gutterForeground = Color.Gray, lineHighlight = Color.Black,
                selectionColor = Color.Blue, findMatchColor = Color.Blue, cursorColor = Color.White, tokenColors = emptyMap(),
                errorColor = Color.Red, warningColor = Color.Yellow, infoColor = Color.Blue,
                tokenBackgrounds = mapOf(TokenType.Comment to Color(0xFF202020)),
            ),
        )
        assertEquals(Color(0xFF202020), plain.spanStyle(TokenType.Comment)?.background)
    }

    @Test
    fun `a theme's widget colours are the cards' background and text`() {
        val theme = EditorThemes.VsCodeDark.copy(
            chromeColors = ChromeColors(widgetBackground = Color(0xFF252526), widgetForeground = Color(0xFFCCCCCC)),
        )
        val chrome = EditorChromeColors(theme)
        assertEquals(Color(0xFF252526), chrome.surface(2))
        assertEquals(Color(0xFFCCCCCC), chrome.foreground)
        assertEquals(theme.background, chrome.surface(0))
        val scheme = chrome.colorScheme()
        assertEquals(Color(0xFF252526), scheme.surfaceContainerHigh)
    }
}
