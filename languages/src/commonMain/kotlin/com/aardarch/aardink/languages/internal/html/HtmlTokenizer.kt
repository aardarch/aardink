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
package com.aardarch.aardink.languages.internal.html

import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.languages.internal.CooperativePacer
import com.aardarch.aardink.languages.internal.RegexTokenizer
import com.aardarch.aardink.languages.internal.css.CssTokenizer
import com.aardarch.aardink.languages.internal.json.JsonTokenizer
import com.aardarch.aardink.languages.internal.typescript.TypeScriptTokenizer
import com.aardarch.aardink.languages.internal.xml.MarkupTokenizer

/**
 * HTML tokenizer: XML's state machine, with a `<style>` element's body tokenized as CSS and a
 * `<script>` element's as JavaScript (or as JSON for a JSON `type`, an import map's included, and
 * as plain text for any other type it does not know, such as a template's).
 */
object HtmlTokenizer : IncrementalTokenizer {

    private val markup = MarkupTokenizer(::tokenizerOf)

    override fun tokenizeFull(text: String): List<Token> = markup.tokenizeFull(text)

    override suspend fun tokenizeFullCooperative(text: String): List<Token> = markup.tokenizeFullCooperative(text)

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> =
        markup.tokenizeLines(text, dirtyRange, previousTokens)

    /** No longer consulted by the editor for this tokenizer: [tokenizeLines] follows every construct itself. */
    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

    override val commentSyntax: CommentSyntax = CommentSyntax(blockStart = "<!--", blockEnd = "-->")

    override fun keyboardToolbarChars(): List<Char> = listOf('<', '>', '/', '=', '"', '&', ';', '#', '.')

    /** A body in [tokenizer]'s language: scans that it does not remember for an incremental pass of its own. */
    private class Embedded(private val tokenizer: RegexTokenizer) : MarkupTokenizer.EmbeddedLanguage {
        override fun tokenize(body: String): List<Token> = tokenizer.tokenizeEmbedded(body)

        override suspend fun tokenizeCooperatively(body: String, pacer: CooperativePacer, tokenBase: Int): List<Token> =
            tokenizer.tokenizeEmbeddedCooperative(body, pacer, tokenBase)
    }

    private val css = Embedded(CssTokenizer)
    private val javaScript = Embedded(TypeScriptTokenizer)
    private val json = Embedded(JsonTokenizer)

    private val plainText = object : MarkupTokenizer.EmbeddedLanguage {
        override fun tokenize(body: String): List<Token> = emptyList()

        override suspend fun tokenizeCooperatively(body: String, pacer: CooperativePacer, tokenBase: Int): List<Token> = emptyList()
    }

    private fun tokenizerOf(text: String, tagStart: Int, tagEnd: Int): MarkupTokenizer.EmbeddedLanguage? = when {
        isElement(text, tagStart, "style") -> css
        isElement(text, tagStart, "script") -> scriptTokenizer(typeOf(text, tagStart, tagEnd))
        else -> null
    }

    /**
     * The `type` attribute's value in the opening tag from its `<` at [tagStart] to its `>` at
     * [tagEnd], quoted or not and the name in any case; null without one. Attributes are read one
     * by one, as the tag is tokenized, so `type=` inside another attribute's value is that value's.
     */
    private fun typeOf(text: String, tagStart: Int, tagEnd: Int): String? {
        var i = tagStart + 1
        while (i < tagEnd && text[i].isNameCharacter()) i++
        while (i < tagEnd) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                // A value with no name before it: skipped whole.
                val close = text.indexOf(c, i + 1)
                i = if (close < 0 || close >= tagEnd) tagEnd else close + 1
                continue
            }
            if (!(c.isLetter() || c == '_' || c == ':')) {
                i++
                continue
            }
            val nameStart = i
            while (i < tagEnd && text[i].isNameCharacter()) i++
            val isType = i - nameStart == 4 && text.regionMatches(nameStart, "type", 0, 4, ignoreCase = true)
            var j = i
            while (j < tagEnd && text[j].isWhitespace()) j++
            if (j >= tagEnd || text[j] != '=') {
                if (isType) return ""
                continue
            }
            j++
            while (j < tagEnd && text[j].isWhitespace()) j++
            val valueStart: Int
            val valueEnd: Int
            if (j < tagEnd && (text[j] == '"' || text[j] == '\'')) {
                val close = text.indexOf(text[j], j + 1)
                valueStart = j + 1
                valueEnd = if (close < 0 || close >= tagEnd) tagEnd else close
                i = minOf(valueEnd + 1, tagEnd)
            } else {
                valueStart = j
                var k = j
                while (k < tagEnd && !text[k].isWhitespace()) k++
                valueEnd = k
                i = k
            }
            if (isType) return text.substring(valueStart, valueEnd)
        }
        return null
    }

    private fun Char.isNameCharacter() = isLetterOrDigit() || this == ':' || this == '-' || this == '_'

    /** Whether the opening tag at [tagStart] is [name]'s, in any case. */
    private fun isElement(text: String, tagStart: Int, name: String): Boolean {
        val after = tagStart + 1 + name.length
        return text.regionMatches(tagStart + 1, name, 0, name.length, ignoreCase = true) &&
            after < text.length && (text[after].isWhitespace() || text[after] == '>' || text[after] == '/')
    }

    private fun scriptTokenizer(typeAttribute: String?): MarkupTokenizer.EmbeddedLanguage {
        val type = typeAttribute?.trim()?.lowercase() ?: return javaScript
        return when {
            type.isEmpty() || type == "module" || type in SCRIPT_TYPES -> javaScript
            type == "importmap" || type == "speculationrules" || type.endsWith("json") -> json
            else -> plainText
        }
    }

    /** The types a browser runs as JavaScript, and those of the tools that compile to it in the page. */
    private val SCRIPT_TYPES = setOf(
        "text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript",
        "application/x-javascript", "text/x-javascript", "text/jsx", "text/babel", "text/typescript",
    )
}
