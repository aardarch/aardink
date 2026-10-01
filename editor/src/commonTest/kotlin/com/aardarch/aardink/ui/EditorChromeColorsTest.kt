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
        val scheme = chrome.colorScheme(theme)
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
    fun `the toolbar's accent is the keyword colour`() {
        assertEquals(EditorThemes.VsCodeDark.tokenColors[TokenType.Keyword], EditorChromeColors(EditorThemes.VsCodeDark).accent)
    }
}
