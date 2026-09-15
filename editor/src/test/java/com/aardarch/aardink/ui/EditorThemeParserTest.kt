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
import com.aardarch.aardink.core.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
    fun `8-digit hex colors include alpha`() {
        val json = """{ "colors": { "editor.background": "#80101010" } }"""
        val theme = EditorThemeParser.fromJson(json)
        assertEquals(Color(0x10, 0x10, 0x10, 0x80), theme?.background)
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
