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

    /** A script's `type`, in any case, quoted or not. */
    private val TYPE = Regex("""\stype\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    // Scans that the tokenizers do not remember for an incremental pass of their own.
    private val css: (String) -> List<Token> = CssTokenizer::tokenizeEmbedded
    private val javaScript: (String) -> List<Token> = TypeScriptTokenizer::tokenizeEmbedded
    private val json: (String) -> List<Token> = JsonTokenizer::tokenizeEmbedded
    private val plainText: (String) -> List<Token> = { emptyList() }

    private fun tokenizerOf(text: String, tagStart: Int, tagEnd: Int): ((String) -> List<Token>)? = when {
        isElement(text, tagStart, "style") -> css
        isElement(text, tagStart, "script") -> scriptTokenizer(text.substring(tagStart, tagEnd))
        else -> null
    }

    /** Whether the opening tag at [tagStart] is [name]'s, in any case. */
    private fun isElement(text: String, tagStart: Int, name: String): Boolean {
        val after = tagStart + 1 + name.length
        return text.regionMatches(tagStart + 1, name, 0, name.length, ignoreCase = true) &&
            after < text.length && (text[after].isWhitespace() || text[after] == '>' || text[after] == '/')
    }

    private fun scriptTokenizer(tag: String): (String) -> List<Token> {
        val match = TYPE.find(tag) ?: return javaScript
        val type = (match.groups[1] ?: match.groups[2] ?: match.groups[3])?.value.orEmpty().trim().lowercase()
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
