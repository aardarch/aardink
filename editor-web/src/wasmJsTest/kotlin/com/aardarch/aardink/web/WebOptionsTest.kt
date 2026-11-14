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
package com.aardarch.aardink.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import com.aardarch.aardink.core.ChromeColors
import com.aardarch.aardink.core.FindEngine
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.EditorThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.10's web options, themes, workspace search and Alt keys. */
class WebOptionsTest {

    @Test
    fun `wordWrap is off by default, as Monaco's, and the new options keep what the editor did`() {
        val options = AardinkWeb.parseOptions("{}")
        assertFalse(options.wordWrap)
        assertEquals("on", options.lineNumbers)
        assertEquals(emptyList(), options.rulers)
        assertFalse(options.indentGuides)
        assertFalse(options.dragAndDrop)
        assertNull(options.wordWrapColumn)
    }

    @Test
    fun `the 0_10 options read from JSON in Monaco's names`() {
        val options = AardinkWeb.parseOptions(
            """{ "wordWrap": true, "wordWrapColumn": 72, "lineNumbers": "relative", "rulers": [80, 100],
                 "indentGuides": true, "bracketPairGuides": true, "cursorStyle": "block", "cursorBlinking": "phase",
                 "smoothScrolling": true, "scrollBeyondLastLine": true, "wrappingIndent": "deepIndent",
                 "cursorSurroundingLines": 3, "dragAndDrop": true }""",
        )
        assertEquals(72, options.wordWrapColumn)
        assertEquals("relative", options.lineNumbers)
        assertEquals(listOf(80, 100), options.rulers)
        assertEquals("block", options.cursorStyle)
        assertEquals("deepIndent", options.wrappingIndent)
        assertEquals(3, options.cursorSurroundingLines)
    }

    @Test
    fun `Monaco's high-contrast themes are built in`() {
        assertEquals(EditorThemes.HighContrastDark, AardinkWeb.builtInThemes["hc-black"])
        assertEquals(EditorThemes.HighContrastLight, AardinkWeb.builtInThemes["hc-light"])
    }

    @Test
    fun `Monaco theme data is read as Monaco's, with a rule's background and its high-contrast base`() {
        AardinkWeb.registerTheme(
            "toy-hc",
            """{ "base": "hc-black", "inherit": true,
                 "rules": [{ "token": "invalid", "foreground": "ffffff", "background": "5a1d1d" }],
                 "colors": { "editorRuler.foreground": "#ff0000" } }""",
        )
        val theme = AardinkWeb.registeredThemes.getValue("toy-hc")
        assertEquals(EditorThemes.HighContrastDark.background, theme.background)
        assertEquals(Color(0xFF5A1D1D), theme.tokenBackgrounds[NamedTokenType("invalid")])
        assertEquals(Color(0xFFFF0000), theme.chromeColors.ruler)
        assertEquals(EditorThemes.HighContrastDark.chromeColors.widgetBorder, theme.chromeColors.widgetBorder)
        assertEquals(EditorThemes.HighContrastDark.tokenColors[TokenType.Keyword], theme.tokenColors[TokenType.Keyword])
    }

    @Test
    fun `a VS Code theme takes the chrome colours it leaves out from its base`() {
        AardinkWeb.registerTheme("toy-on-hc", """{ "base": "hc-black", "colors": { "editorWidget.border": "#00ff00" } }""")
        val theme = AardinkWeb.registeredThemes.getValue("toy-on-hc")
        assertEquals(Color(0xFF00FF00), theme.chromeColors.widgetBorder)
        assertEquals(EditorThemes.HighContrastDark.chromeColors.indentGuide, theme.chromeColors.indentGuide)
        assertEquals(ChromeColors(), AardinkWeb.registeredThemes.getValue("vscode-dark").chromeColors)
    }

    @Test
    fun `a workspace search goes to the page as VS Code's Find in Files arguments`() {
        assertEquals(
            """{"query":"a\"b","isRegex":true,"isCaseSensitive":false,"matchWholeWord":true}""",
            searchWorkspaceJson("a\"b", FindEngine.Options(caseSensitive = false, wholeWord = true, useRegex = true)),
        )
    }

    @Test
    fun `an Alt key is read by its place on the keyboard`() {
        assertEquals(Key.A, characterKeyAt("KeyA"))
        assertEquals(Key.Z, characterKeyAt("KeyZ"))
        assertEquals(Key.Seven, characterKeyAt("Digit7"))
        assertEquals(Key.RightBracket, characterKeyAt("BracketRight"))
        assertEquals(Key.Slash, characterKeyAt("Slash"))
        assertNull(characterKeyAt("ArrowUp"))
        assertNull(characterKeyAt("KeyAB"))
        assertNull(characterKeyAt("F5"))
    }

    @Test
    fun `fontFamily names a registered font by the first of its names that was registered`() {
        assertNull(registeredFontIn("'Toy Mono', monospace"))
        val truetype = byteArrayOf(0, 1, 0, 0) + ByteArray(12)
        assertFailsWith<IllegalArgumentException> { AardinkWeb.registerFont("Toy Mono", "<!doctype html><html>".encodeToByteArray()) }
        assertNull(registeredFontIn("'Toy Mono', monospace"), "what is not a font is not registered")
        assertTrue(isFontFile("OTTO........".encodeToByteArray()))
        AardinkWeb.registerFont("Toy Mono", truetype)
        val font = AardinkWeb.registeredFonts.getValue("Toy Mono")
        assertEquals(font, registeredFontIn("'Toy Mono', monospace"))
        assertEquals(font, registeredFontIn("Other, \"Toy Mono\""))
        assertNull(registeredFontIn("monospace"))
        assertNull(registeredFontIn(null))
        assertNull(AardinkWeb.parseOptions("{}").fontFamily)
    }
}
