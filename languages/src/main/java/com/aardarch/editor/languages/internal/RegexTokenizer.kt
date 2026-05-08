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
package com.aardarch.editor.languages.internal

import com.aardarch.editor.core.IncrementalTokenizer
import com.aardarch.editor.core.Token
import com.aardarch.editor.core.TokenType

/**
 * Base class for the regex-driven tokenizers shipped with `:languages`.
 *
 * Subclasses provide [rules] — an ordered list of `(Regex, TokenType)` pairs. At each cursor
 * position the tokenizer picks the rule whose match has the **earliest start**; ties break in
 * declaration order, so put higher-priority rules (comments, strings) before lower-priority ones
 * (identifiers, operators).
 *
 * For sample-sized documents this full-doc retokenize is fast enough; both [tokenizeFull] and
 * [tokenizeLines] go through the same path. A real incremental tokenizer can be plugged in later
 * by overriding [tokenizeLines].
 */
abstract class RegexTokenizer : IncrementalTokenizer {

    /** Ordered list of patterns. Earlier rules win on equal-start ties. */
    protected abstract val rules: List<Pair<Regex, TokenType>>

    /** True if a change on any line can affect tokenization of subsequent lines. */
    protected open val multiLineConstructs: Boolean = false

    override fun tokenizeFull(text: String): List<Token> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val tokens = ArrayList<Token>(text.length / 8)
        var cursor = 0
        val length = text.length
        while (cursor < length) {
            var bestStart = -1
            var bestEnd = -1
            var bestType: TokenType? = null
            for ((regex, type) in rules) {
                val match = regex.find(text, cursor) ?: continue
                val start = match.range.first
                val end = match.range.last + 1
                if (end <= start) continue
                if (bestStart == -1 || start < bestStart) {
                    bestStart = start
                    bestEnd = end
                    bestType = type
                    if (start == cursor) break // can't beat earliest possible
                }
            }
            if (bestStart == -1 || bestType == null) break
            tokens.add(Token(bestStart, bestEnd, bestType))
            cursor = bestEnd
        }
        return tokens
    }

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = tokenizeFull(text)

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = multiLineConstructs
}
