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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.aardarch.aardink.ui.EditorThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenStyleResolverTest {

    private val red = Color(0xFFFF0000)
    private val green = Color(0xFF00FF00)
    private val blue = Color(0xFF0000FF)

    private fun theme(colors: Map<TokenType, Color>, fonts: Map<TokenType, TokenFontStyle> = emptyMap()) =
        EditorThemes.VsCodeDark.copy(tokenColors = colors, tokenFontStyles = fonts)

    @Test
    fun `a type's own entry wins, else the longest prefix of its name`() {
        val resolver = TokenStyleResolver(
            theme(mapOf(TokenType.Comment to green, NamedTokenType("comment.doc") to blue, NamedTokenType("tag") to red)),
        )
        assertEquals(green, resolver.color(TokenType.Comment))
        assertEquals(blue, resolver.color(NamedTokenType("comment.doc.kotlin")))
        assertEquals(green, resolver.color(NamedTokenType("comment.line")))
        assertEquals(red, resolver.color(NamedTokenType("tag.aardflex")))
        assertNull(resolver.color(NamedTokenType("text.toy")))
    }

    @Test
    fun `a named entry for a built-in type's scope colours that type's sub-names`() {
        val resolver = TokenStyleResolver(theme(mapOf(TokenType.Keyword to blue, NamedTokenType("keyword") to red)))
        assertEquals(blue, resolver.color(TokenType.Keyword), "its own entry")
        assertEquals(red, resolver.color(NamedTokenType("keyword.flow")), "the named entry wins for the scope")
    }

    @Test
    fun `Monaco's standard names fall back to the built-in type that colours them`() {
        val resolver = TokenStyleResolver(theme(mapOf(TokenType.TypeName to green, TokenType.StringLiteral to red)))
        assertEquals(green, resolver.color(NamedTokenType("tag.aardflex")))
        assertEquals(red, resolver.color(NamedTokenType("attribute.value.xml")))
        assertEquals(red, resolver.color(NamedTokenType("regexp")))
    }

    @Test
    fun `font styles are found separately, and None stops a general one`() {
        val resolver = TokenStyleResolver(
            theme(
                colors = mapOf(TokenType.Comment to green, NamedTokenType("comment.doc") to blue),
                fonts = mapOf(TokenType.Comment to TokenFontStyle(italic = true), NamedTokenType("comment.line") to TokenFontStyle.None),
            ),
        )
        val doc = resolver.spanStyle(NamedTokenType("comment.doc"))!!
        assertEquals(blue, doc.color, "its own colour")
        assertEquals(FontStyle.Italic, doc.fontStyle, "comment's italics")
        assertNull(resolver.spanStyle(NamedTokenType("comment.line"))!!.fontStyle)
        assertNull(resolver.spanStyle(NamedTokenType("text")))
    }

    @Test
    fun `bold and decorations reach the span style`() {
        val resolver = TokenStyleResolver(theme(emptyMap(), mapOf(NamedTokenType("tag") to TokenFontStyle(bold = true, underline = true))))
        val style = resolver.spanStyle(NamedTokenType("tag.aardflex"))!!
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertTrue(style.textDecoration!!.contains(androidx.compose.ui.text.style.TextDecoration.Underline))
    }

    @Test
    fun `a name with a comment or string part is a comment or a string`() {
        assertTrue(NamedTokenType("comment.doc").isCommentOrString)
        assertTrue(NamedTokenType("string.escape.json").isCommentOrString)
        assertTrue(NamedTokenType("regexp").isCommentOrString)
        assertTrue(TokenType.StringLiteral.isCommentOrString)
        assertFalse(NamedTokenType("keyword.flow").isCommentOrString)
        assertFalse(NamedTokenType("commentary").isCommentOrString)
        assertFalse(TokenType.Keyword.isCommentOrString)
    }

    @Test
    fun `fontStyle words parse in any order`() {
        assertEquals(TokenFontStyle(bold = true, italic = true), TokenFontStyle.parse("italic bold"))
        assertEquals(TokenFontStyle.None, TokenFontStyle.parse(""))
        assertEquals(TokenFontStyle(underline = true, strikethrough = true), TokenFontStyle.parse("underline strikethrough"))
    }
}
