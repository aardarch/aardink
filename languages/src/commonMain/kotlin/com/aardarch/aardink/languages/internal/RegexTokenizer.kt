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
package com.aardarch.aardink.languages.internal

import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType

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

    override fun tokenizeFull(text: String): List<Token> = scan(text) { }

    override suspend fun tokenizeFullCooperative(text: String): List<Token> {
        val pacer = CooperativePacer()
        return scan(text) { tokenCount -> pacer.onProgress(tokenCount) }
    }

    /** The one scanner behind both entry points; [onStep] runs before each token is sought. */
    private inline fun scan(text: String, onStep: (tokenCount: Int) -> Unit): List<Token> {
        val ruleList = rules
        if (text.isEmpty() || ruleList.isEmpty()) return emptyList()
        val tokens = ArrayList<Token>(text.length / 8)

        // Each rule's first match at or after the cursor, kept across steps. The cursor only moves
        // forward, so a cached match that still starts at or after it is exactly what a fresh
        // find() would return; only rules the cursor has passed are searched again. Without this,
        // every step re-ran every rule, and a rule whose next match was far away rescanned to it
        // each time -- quadratic in document size.
        val nextMatch = arrayOfNulls<MatchResult>(ruleList.size)
        val exhausted = BooleanArray(ruleList.size)

        var cursor = 0
        val length = text.length
        while (cursor < length) {
            onStep(tokens.size)
            var bestStart = -1
            var bestEnd = -1
            var bestType: TokenType? = null
            for (i in ruleList.indices) {
                if (exhausted[i]) continue
                val (regex, type) = ruleList[i]
                var match = nextMatch[i]
                if (match == null || match.range.first < cursor) {
                    match = regex.find(text, cursor)
                    if (match == null) {
                        exhausted[i] = true
                        continue
                    }
                    nextMatch[i] = match
                }
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
        refine(text, tokens)
        return tokens
    }

    /**
     * Adjusts the scanned [tokens] in place before they are returned. Override it for context a
     * rule would otherwise need a lookbehind for: Kotlin/wasm's regex engine evaluates a
     * lookbehind at every candidate position, which makes such a rule orders of magnitude slower
     * there than on the JVM.
     */
    protected open fun refine(text: String, tokens: MutableList<Token>) {}

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = tokenizeFull(text)

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = multiLineConstructs
}
