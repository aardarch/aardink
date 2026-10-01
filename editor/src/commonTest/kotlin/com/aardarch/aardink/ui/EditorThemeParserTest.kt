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
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenFontStyle
import com.aardarch.aardink.core.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorThemeParserTest {

    @Test
    fun `malformed json returns null`() {
        assertNull(EditorThemeParser.fromJson("not json"))
        assertNull(EditorThemeParser.fromJson("{"))
    }

    @Test
    fun `missing colors and tokenColors falls back to the built-in dark theme`() {
        val theme = EditorThemeParser.fromJson("{}")
        assertEquals(EditorThemes.VsCodeDark.background, theme?.background)
    }

    @Test
    fun `editor colors are parsed from hex strings`() {
        val json = """
            {
              "colors": {
                "editor.background": "#101010",
                "editor.foreground": "#ffffff",
                "editorCursor.foreground": "#ff0000"
              }
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(Color(0x10, 0x10, 0x10), theme?.background)
        assertEquals(Color(0xff, 0xff, 0xff), theme?.tokenColors?.get(TokenType.Default))
        assertEquals(Color(0xff, 0x00, 0x00), theme?.cursorColor)
    }

    @Test
    fun `8-digit hex colors carry alpha last, as VS Code writes them`() {
        val json = """{ "colors": { "editor.background": "#10203080" } }"""
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(Color(0x10, 0x20, 0x30, 0x80), theme?.background)
    }

    @Test
    fun `short hex colors expand each digit`() {
        assertEquals(Color(0xFF, 0x00, 0xAA), EditorThemeParser.parseHex("#f0a"))
        assertEquals(Color(0xFF, 0x00, 0xAA, 0x88), EditorThemeParser.parseHex("#f0a8"))
    }

    @Test
    fun `bracket pair colours come from editorBracketHighlight`() {
        val json = """{ "colors": { "editorBracketHighlight.foreground1": "#111111", "editorBracketHighlight.foreground2": "#222222" } }"""
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(listOf(Color(0xFF111111), Color(0xFF222222)), theme?.bracketPairColors)
    }

    @Test
    fun `a light theme without bracket colours gets the light defaults`() {
        val json = """{ "colors": { "editor.background": "#ffffff" } }"""
        assertEquals(EditorThemes.VsCodeLight.bracketPairColors, EditorThemeParser.fromJson(json)?.bracketPairColors)
    }

    @Test
    fun `whitespace colour comes from editorWhitespace and is unspecified without it`() {
        val json = """{ "colors": { "editorWhitespace.foreground": "#e3e4e229" } }"""
        assertEquals(Color(0xE3, 0xE4, 0xE2, 0x29), EditorThemeParser.fromJson(json)?.whitespaceColor)
        assertEquals(Color.Unspecified, EditorThemeParser.fromJson("{}")?.whitespaceColor)
    }

    @Test
    fun `tokenColors with a string scope maps to a token type`() {
        val json = """
            {
              "tokenColors": [
                { "scope": "keyword.control", "settings": { "foreground": "#ff00ff" } }
              ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(Color(0xff, 0x00, 0xff), theme?.tokenColors?.get(TokenType.Keyword))
    }

    @Test
    fun `the general rule colours a built-in type, whatever order the specific ones come in`() {
        val json = """
            {
              "tokenColors": [
                { "scope": "keyword", "settings": { "foreground": "#0000ff" } },
                { "scope": "keyword.control", "settings": { "foreground": "#ff00ff" } },
                { "scope": "comment", "settings": { "foreground": "#00ff00", "fontStyle": "italic" } },
                { "scope": "comment.block.documentation", "settings": { "foreground": "#008800" } },
                { "scope": "constant.character.escape", "settings": { "foreground": "#ffff00" } }
              ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)!!
        assertEquals(Color(0x00, 0x00, 0xff), theme.tokenColors[TokenType.Keyword])
        // VS Code's scopes stand for Monaco's names: keyword.flow, comment.doc, string.escape.
        assertEquals(Color(0xff, 0x00, 0xff), theme.tokenColors[NamedTokenType("keyword.flow")])
        assertEquals(Color(0x00, 0x88, 0x00), theme.tokenColors[NamedTokenType("comment.doc")])
        assertEquals(Color(0xff, 0xff, 0x00), theme.tokenColors[NamedTokenType("string.escape")])
        assertEquals(TokenFontStyle(italic = true), theme.tokenFontStyles[TokenType.Comment])
        // The rule's own scope is kept too, for a grammar token by that name.
        assertEquals(Color(0xff, 0x00, 0xff), theme.tokenColors[NamedTokenType("keyword.control")])
    }

    @Test
    fun `Monaco rule names colour the built-in types, and fontStyle alone is a rule`() {
        val json = """
            {
              "tokenColors": [
                { "scope": "number", "settings": { "foreground": "#123456" } },
                { "scope": "delimiter", "settings": { "foreground": "#654321" } },
                { "scope": "tag.aardflex", "settings": { "fontStyle": "bold" } }
              ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)!!
        assertEquals(Color(0x12, 0x34, 0x56), theme.tokenColors[TokenType.Number])
        assertEquals(Color(0x65, 0x43, 0x21), theme.tokenColors[TokenType.Punctuation])
        assertEquals(TokenFontStyle(bold = true), theme.tokenFontStyles[NamedTokenType("tag.aardflex")])
    }

    @Test
    fun `a theme's own keyword colour drops the fallback's keyword sub-names`() {
        val json = """{ "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#ff0000" } } ] }"""
        val theme = EditorThemeParser.fromJson(json)!!
        assertEquals(null, theme.tokenColors[NamedTokenType("keyword.flow")])
        // Sub-names under types the theme leaves alone stay.
        assertEquals(
            EditorThemes.VsCodeDark.tokenColors[NamedTokenType("string.escape")],
            theme.tokenColors[NamedTokenType("string.escape")],
        )
    }

    @Test
    fun `tokenColors with a comma-separated scope string applies to every listed scope`() {
        val json = """
            {
              "tokenColors": [
                { "scope": "string, comment", "settings": { "foreground": "#00ff00" } }
              ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(Color(0x00, 0xff, 0x00), theme?.tokenColors?.get(TokenType.StringLiteral))
        assertEquals(Color(0x00, 0xff, 0x00), theme?.tokenColors?.get(TokenType.Comment))
    }

    @Test
    fun `tokenColors with an array scope applies to every entry`() {
        val json = """
            {
              "tokenColors": [
                { "scope": ["entity.name.function", "support.function"], "settings": { "foreground": "#0000ff" } }
              ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(Color(0x00, 0x00, 0xff), theme?.tokenColors?.get(TokenType.FunctionCall))
    }

    @Test
    fun `a tokenColors entry without settings or foreground is ignored`() {
        val json = """
            {
              "tokenColors": [
                { "scope": "keyword" },
                { "scope": "string", "settings": {} }
              ]
            }
        """.trimIndent()
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(EditorThemes.VsCodeDark.tokenColors[TokenType.Keyword], theme?.tokenColors?.get(TokenType.Keyword))
        assertEquals(EditorThemes.VsCodeDark.tokenColors[TokenType.StringLiteral], theme?.tokenColors?.get(TokenType.StringLiteral))
    }

    @Test
    fun `gutter background falls back to a darkened editor background`() {
        val json = """{ "colors": { "editor.background": "#202020" } }"""
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(
            Color(0x20, 0x20, 0x20).let { c ->
                Color(
                    (c.red - 0.04f).coerceAtLeast(0f),
                    (c.green - 0.04f).coerceAtLeast(0f),
                    (
                        c.blue -
                            0.04f
                        ).coerceAtLeast(0f),
                    c.alpha,
                )
            },
            theme?.gutterBackground,
        )
    }
}
