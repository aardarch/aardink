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
package com.aardarch.aardink.languages.internal.xml

import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType

/**
 * XML tokenizer with an explicit state machine — regex on its own struggles to keep tag names,
 * attribute names, attribute values, and text content separate.
 *
 * Token mapping:
 *   - `<`, `>`, `/` punctuation → [TokenType.Punctuation]
 *   - element / attribute names → [TokenType.TypeName] / [TokenType.Identifier]
 *   - attribute values (quoted) → [TokenType.StringLiteral]
 *   - `<!-- … -->` → [TokenType.Comment]
 *   - `<?xml … ?>` declarations → [TokenType.Annotation]
 *   - `<![CDATA[ … ]]>` → [TokenType.StringLiteral]
 *   - entity refs (`&amp;`) → [TokenType.Number] (visually distinct from text content)
 */
object XmlTokenizer : IncrementalTokenizer {

    override fun tokenizeFull(text: String): List<Token> {
        val tokens = ArrayList<Token>(text.length / 8)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // <!-- comment -->
            if (c == '<' && i + 3 < n && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                val end = text.indexOf("-->", i + 4)
                val close = if (end < 0) n else end + 3
                tokens.add(Token(i, close, TokenType.Comment))
                i = close
                continue
            }
            // <![CDATA[ … ]]>
            if (c == '<' && i + 8 < n && text.regionMatches(i + 1, "![CDATA[", 0, 8)) {
                val end = text.indexOf("]]>", i + 9)
                val close = if (end < 0) n else end + 3
                tokens.add(Token(i, close, TokenType.StringLiteral))
                i = close
                continue
            }
            // <?xml … ?>
            if (c == '<' && i + 1 < n && text[i + 1] == '?') {
                val end = text.indexOf("?>", i + 2)
                val close = if (end < 0) n else end + 2
                tokens.add(Token(i, close, TokenType.Annotation))
                i = close
                continue
            }
            // <!DOCTYPE …>
            if (c == '<' && i + 1 < n && text[i + 1] == '!') {
                val end = text.indexOf('>', i + 2)
                val close = if (end < 0) n else end + 1
                tokens.add(Token(i, close, TokenType.Annotation))
                i = close
                continue
            }
            // Tag start
            if (c == '<') {
                val tagEnd = findTagEnd(text, i + 1) ?: break
                tokenizeTag(text, i, tagEnd + 1, tokens)
                i = tagEnd + 1
                continue
            }
            // Entity reference
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi > 0 && semi - i <= 10) {
                    tokens.add(Token(i, semi + 1, TokenType.Number))
                    i = semi + 1
                    continue
                }
            }
            i++
        }
        return tokens
    }

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = tokenizeFull(text)

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = true

    override fun keyboardToolbarChars(): List<Char> = listOf('<', '>', '/', '=', '"', '?', '!', '&', ';')

    private fun findTagEnd(text: String, from: Int): Int? {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return null
    }

    private fun tokenizeTag(text: String, start: Int, end: Int, out: MutableList<Token>) {
        // start points at '<'; end points one past '>'
        out.add(Token(start, start + 1, TokenType.Punctuation))
        var i = start + 1
        if (i < end - 1 && text[i] == '/') {
            out.add(Token(i, i + 1, TokenType.Punctuation))
            i++
        }
        // Element name
        val nameStart = i
        while (i < end - 1 && (text[i].isLetterOrDigit() || text[i] == ':' || text[i] == '-' || text[i] == '_')) i++
        if (i > nameStart) {
            out.add(Token(nameStart, i, TokenType.TypeName))
        }
        // Attributes
        while (i < end - 1) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c == '/' -> {
                    out.add(Token(i, i + 1, TokenType.Punctuation))
                    i++
                }

                c == '=' -> {
                    out.add(Token(i, i + 1, TokenType.Operator))
                    i++
                }

                c == '"' || c == '\'' -> {
                    val close = text.indexOf(c, i + 1)
                    val stop = if (close < 0 || close >= end - 1) end - 1 else close + 1
                    out.add(Token(i, stop, TokenType.StringLiteral))
                    i = stop
                }

                c.isLetter() || c == '_' || c == ':' -> {
                    val attrStart = i
                    while (i < end - 1 && (text[i].isLetterOrDigit() || text[i] == ':' || text[i] == '-' || text[i] == '_')) i++
                    out.add(Token(attrStart, i, TokenType.Identifier))
                }

                else -> i++
            }
        }
        // Closing '>'
        if (end - 1 > start && text[end - 1] == '>') {
            out.add(Token(end - 1, end, TokenType.Punctuation))
        }
    }
}
