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

import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token

/**
 * XML tokenizer: [MarkupTokenizer]'s state machine, with no embedded languages. Tags, attributes,
 * comments, CDATA, declarations and entity references each have their token type; see
 * [MarkupTokenizer] for the mapping, and for how [tokenizeLines] rescans only around a change.
 */
object XmlTokenizer : IncrementalTokenizer {

    private val markup = MarkupTokenizer()

    override fun tokenizeFull(text: String): List<Token> = markup.tokenizeFull(text)

    override suspend fun tokenizeFullCooperative(text: String): List<Token> = markup.tokenizeFullCooperative(text)

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> =
        markup.tokenizeLines(text, dirtyRange, previousTokens)

    /** No longer consulted by the editor for this tokenizer: [tokenizeLines] follows every construct itself. */
    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

    override val commentSyntax: CommentSyntax = CommentSyntax(blockStart = "<!--", blockEnd = "-->")

    override fun keyboardToolbarChars(): List<Char> = listOf('<', '>', '/', '=', '"', '?', '!', '&', ';')
}
