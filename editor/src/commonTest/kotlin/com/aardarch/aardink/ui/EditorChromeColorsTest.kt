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
import androidx.compose.ui.graphics.luminance
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorChromeColorsTest {

    private fun assertDarkChrome(theme: EditorTheme) {
        val chrome = EditorChromeColors(theme)
        assertTrue(chrome.isDark)
        for (level in 0..4) assertTrue(chrome.surface(level).luminance() < 0.2f, "surface($level) is dark")
        assertTrue(chrome.foreground.luminance() > 0.2f, "text on it is light")
        val scheme = chrome.colorScheme()
        assertTrue(scheme.surfaceContainerHigh.luminance() < 0.2f)
        assertTrue(scheme.onSurface.luminance() > 0.2f)
    }

    private fun assertLightChrome(theme: EditorTheme) {
        val chrome = EditorChromeColors(theme)
        assertFalse(chrome.isDark)
        for (level in 0..4) assertTrue(chrome.surface(level).luminance() > 0.5f, "surface($level) is light")
        assertTrue(chrome.foreground.luminance() < 0.2f, "text on it is dark")
    }

    @Test
    fun `built-in dark themes get dark chrome`() {
        listOf(EditorThemes.VsCodeDark, EditorThemes.MaterialDark, EditorThemes.MidnightOcean, EditorThemes.SolarizedDark)
            .forEach(::assertDarkChrome)
    }

    @Test
    fun `built-in light themes get light chrome`() {
        listOf(EditorThemes.VsCodeLight, EditorThemes.MaterialLight).forEach(::assertLightChrome)
    }

    @Test
    fun `a registered dark theme gets dark chrome`() {
        // Monaco's defineTheme({ base: 'vs-dark', ... }) as the npm package hands it over.
        val json = """
            {
              "type": "dark",
              "base": "vscode-dark",
              "colors": { "editor.background": "#16161A", "editor.foreground": "#E6E6E6" },
              "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#FF8800" } } ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)!!
        assertDarkChrome(theme)
        assertEquals(Color(0xFFFF8800), EditorChromeColors(theme).accent)
    }

    @Test
    fun `a registered light theme gets light chrome`() {
        val json = """{ "colors": { "editor.background": "#FAFAFA", "editor.foreground": "#202020" } }"""
        assertLightChrome(EditorThemeParser.fromJson(json)!!)
    }

    @Test
    fun `surfaces step from the background towards the text`() {
        val chrome = EditorChromeColors(EditorThemes.VsCodeDark)
        assertEquals(EditorThemes.VsCodeDark.background, chrome.surface(0))
        assertTrue(chrome.surface(3).luminance() > chrome.surface(1).luminance())
    }

    @Test
    fun `the accent is the keyword colour where it reads`() {
        assertEquals(EditorThemes.VsCodeLight.tokenColors[TokenType.Keyword], EditorChromeColors(EditorThemes.VsCodeLight).accent)
        assertEquals(EditorThemes.MidnightOcean.tokenColors[TokenType.Keyword], EditorChromeColors(EditorThemes.MidnightOcean).accent)
    }

    @Test
    fun `a keyword colour too dim for the chrome is lightened on a dark theme`() {
        // VS Code Dark's #569CD6 is under 4.5:1 on the lighter surfaces.
        val keyword = EditorThemes.VsCodeDark.tokenColors.getValue(TokenType.Keyword)
        val accent = EditorChromeColors(EditorThemes.VsCodeDark).accent
        assertTrue(accent.luminance() > keyword.luminance())
    }

    @Test
    fun `the contrast ratio is WCAG's`() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrastRatio(Color(0xFF777777), Color(0xFF777777)), 0.001f)
        // #767676 on white is the classic 4.54:1.
        assertEquals(4.54f, contrastRatio(Color(0xFF767676), Color.White), 0.01f)
    }

    private val allThemes: List<Pair<String, EditorTheme>> = listOf(
        "VsCodeDark" to EditorThemes.VsCodeDark,
        "VsCodeLight" to EditorThemes.VsCodeLight,
        "MaterialDark" to EditorThemes.MaterialDark,
        "MaterialLight" to EditorThemes.MaterialLight,
        "MidnightOcean" to EditorThemes.MidnightOcean,
        "SolarizedDark" to EditorThemes.SolarizedDark,
        // A registered theme whose text barely shows on its background, and whose keyword
        // and error colours are the background's own: the chrome must still read.
        "low-contrast" to EditorThemeParser.fromJson(
            """
            {
              "colors": { "editor.background": "#505050", "editor.foreground": "#707070", "editor.selectionBackground": "#606060" },
              "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#555555" } } ]
            }
            """.trimIndent(),
        )!!,
    )

    private fun assertReads(name: String, role: String, text: Color, backgrounds: List<Color>, min: Float = 4.5f) {
        for (background in backgrounds) {
            val ratio = contrastRatio(text, background)
            assertTrue(ratio >= min, "$name: $role $text on $background is $ratio:1, under $min:1")
        }
    }

    @Test
    fun `chrome text reaches 4_5 to 1 on every surface it is drawn on`() {
        for ((name, theme) in allThemes) {
            val chrome = EditorChromeColors(theme)
            val scheme = chrome.colorScheme()
            val surfaces = listOf(
                scheme.surface,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            )
            // Body and secondary text, also on a selected row (completion list, references).
            assertReads(name, "onSurface", scheme.onSurface, surfaces + scheme.secondaryContainer)
            assertReads(name, "onSurfaceVariant", scheme.onSurfaceVariant, surfaces + scheme.secondaryContainer)
            assertReads(name, "onSecondaryContainer", scheme.onSecondaryContainer, listOf(scheme.secondaryContainer))
            // Accent text: buttons, the active parameter, a focused field's label, kind badges.
            assertReads(name, "primary", scheme.primary, surfaces)
            assertReads(name, "secondary", scheme.secondary, surfaces)
            assertReads(name, "tertiary", scheme.tertiary, surfaces)
            assertReads(name, "error", scheme.error, surfaces)
            assertReads(name, "onPrimary", scheme.onPrimary, listOf(scheme.primary))
            assertReads(name, "onErrorContainer", scheme.onErrorContainer, listOf(scheme.errorContainer))
            // A text field's border at rest is a control's edge: 3:1.
            assertReads(name, "outline", scheme.outline, surfaces, min = 3f)
            // The toolbar draws its icons and characters on surface(3).
            assertReads(name, "toolbar", chrome.foreground, listOf(chrome.surface(3)))
            assertReads(name, "toolbar accent", chrome.accent, listOf(chrome.surface(3)))
        }
    }

    @Test
    fun `diagnostic banners are tinted with the severity colour and their text reads`() {
        for ((name, theme) in allThemes) {
            val chrome = EditorChromeColors(theme)
            for (severity in DiagnosticSeverity.entries) {
                val container = chrome.severityContainer(severity)
                assertReads(name, "$severity banner text", chrome.onColor(container), listOf(container))
            }
        }
        // VS Code Dark's error banner is red-tinted and still dark.
        val error = EditorChromeColors(EditorThemes.VsCodeDark).severityContainer(DiagnosticSeverity.Error)
        assertTrue(error.red > error.green && error.red > error.blue, "red-tinted: $error")
        assertTrue(error.luminance() < 0.2f, "dark: $error")
    }

    @Test
    fun `text that already reads keeps the theme's colour`() {
        assertEquals(EditorThemes.VsCodeDark.tokenColors[TokenType.Default], EditorChromeColors(EditorThemes.VsCodeDark).foreground)
        assertEquals(EditorThemes.VsCodeLight.tokenColors[TokenType.Default], EditorChromeColors(EditorThemes.VsCodeLight).foreground)
    }

    @Test
    fun `Solarized's dim text is lightened for the chrome`() {
        val text = EditorThemes.SolarizedDark.tokenColors.getValue(TokenType.Default)
        assertTrue(EditorChromeColors(EditorThemes.SolarizedDark).foreground.luminance() > text.luminance())
    }
}
