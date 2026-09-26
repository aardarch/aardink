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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.RegexTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

class RegexTokenizerTest {

    // Rules chosen to stress the match cache: a rare rule whose next match is far ahead, a rule
    // that can match empty, a lookbehind (sees text before the search start), and overlapping
    // rules where an earlier-declared one must win a tie.
    private val testRules = listOf(
        Regex("//[^\n]*") to TokenType.Comment,
        Regex("\"[^\"\n]*\"") to TokenType.StringLiteral,
        Regex("RARE") to TokenType.Annotation,
        Regex("x*") to TokenType.Operator, // matches empty almost everywhere
        Regex("(?<=\\.)[a-z]+") to TokenType.FunctionCall,
        Regex("\\b(fun|val)\\b") to TokenType.Keyword,
        Regex("[A-Za-z_][A-Za-z0-9_]*") to TokenType.Identifier,
        Regex("\\d+") to TokenType.Number,
        Regex("[{}()\\[\\].,;=]") to TokenType.Punctuation,
    )

    private class TestTokenizer(override val rules: List<Pair<Regex, TokenType>>) : RegexTokenizer()

    /** The scanner as it was before the match cache: every rule searched afresh at every step. */
    private fun referenceTokenize(text: String, rules: List<Pair<Regex, TokenType>>): List<Token> {
        val tokens = ArrayList<Token>()
        var cursor = 0
        while (cursor < text.length) {
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
                    if (start == cursor) break
                }
            }
            if (bestStart == -1 || bestType == null) break
            tokens.add(Token(bestStart, bestEnd, bestType))
            cursor = bestEnd
        }
        return tokens
    }

    private val source = """
        fun main() { val s = "str" // note
            foo.bar(xxx, 42); obj.call()
        }
        RARE xx "unterminated
        val y = x.z + 1000
    """.trimIndent()

    @Test
    fun `cached scanner matches the uncached reference`() {
        val tokenizer = TestTokenizer(testRules)
        for (text in listOf("", "x", "xxx", source, source.repeat(20))) {
            assertEquals(referenceTokenize(text, testRules), tokenizer.tokenizeFull(text), "mismatch for: $text")
        }
    }
}
