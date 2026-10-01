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
import com.aardarch.aardink.core.NamedTokenType
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
 * [tokenizeLines] is incremental. The tokenizer keeps the text and tokens of its last scan, and
 * the scanner's only state is its position, so after an edit it restarts at a token boundary a
 * little before the change and stops as soon as it ends a token where the old scan also ended
 * one, past the change: from there on the text is the same, so the old tokens are, shifted. It
 * returns only the tokens of the lines it rescanned. A block comment opened or closed by the edit
 * simply keeps the rescan going until the two scans agree again.
 */
abstract class RegexTokenizer : IncrementalTokenizer {

    /** Ordered list of patterns. Earlier rules win on equal-start ties. */
    protected abstract val rules: List<Pair<Regex, TokenType>>

    /**
     * Whether a change on a line can affect tokenization of later lines. No longer consulted:
     * the incremental rescan follows a multi-line construct for as long as it matters.
     */
    protected open val multiLineConstructs: Boolean = false

    /** The scans behind the token lists this tokenizer returned last, for incremental passes. */
    private val scans = ScanCache<ScanSnapshot>()

    override fun tokenizeFull(text: String): List<Token> {
        val builder = PackedTokenBuilder(text.length / 8)
        scan(text, 0, builder, onStep = { }, converged = { false })
        val raw = builder.build()
        return remembered(text, raw, refined(text, raw, 0, raw.size))
    }

    override suspend fun tokenizeFullCooperative(text: String): List<Token> {
        val pacer = CooperativePacer()
        val builder = PackedTokenBuilder(text.length / 8)
        scan(text, 0, builder, onStep = { tokenCount -> pacer.onProgress(tokenCount) }, converged = { false })
        val raw = builder.build()
        return remembered(text, raw, refined(text, raw, 0, raw.size))
    }

    private fun remembered(text: String, raw: PackedTokens, result: List<Token>): List<Token> {
        scans.remember(result, ScanSnapshot(text, raw))
        return result
    }

    /**
     * Re-tokenizes the lines around the change since the last scan and returns their tokens: at
     * least [dirtyRange], and further when the change altered how later text scans.
     *
     * With no [previousTokens] (the editor has not highlighted this text yet) it scans just
     * [dirtyRange], assuming the first of those lines starts outside any multi-line construct, and
     * leaves its state alone: a quick provisional result for the visible lines, which the full
     * pass that follows replaces.
     */
    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
        if (previousTokens.isEmpty()) return scanLinesProvisionally(text, dirtyRange)
        val previous = scans.find(previousTokens) ?: return tokenizeFull(text)
        val diff = diffTexts(previous.text, text)
        val old = previous.tokens

        // Restart at the end of the last token that ends well before the change, so a rule that
        // peeks ahead into the edited text is rescanned too.
        val kept = old.firstEndingAfter(diff.start - RESCAN_LOOKBACK)
        val restart = if (kept == 0) 0 else old.ends[kept - 1]
        val builder = PackedTokenBuilder(old.size + 16)
        builder.addAll(old, 0, kept)

        var resumeOld = -1
        scan(text, restart, builder, onStep = { }) { end ->
            // In step again once a token ends past the change (so even a rule that looks at the
            // character before its match sees unchanged text) where the old scan ended one.
            if (end - 1 < diff.newEnd) return@scan false
            val index = old.indexEndingAt(end - diff.delta)
            if (index >= 0) resumeOld = index + 1
            index >= 0
        }
        val rescannedUntil = builder.size
        if (resumeOld >= 0) builder.addAll(old, resumeOld, old.size, diff.delta)
        val raw = builder.build()

        // Return whole lines: from the line where the rescan started to the line after the last
        // rescanned token, which a refine() rule may have retyped because of it; to the end of the
        // text when the rescan never got back in step, since everything after it may have changed.
        val toOffset = if (resumeOld < 0) {
            text.length
        } else {
            val lastRescanned = if (rescannedUntil > 0) raw.ends[rescannedUntil - 1] - 1 else 0
            val next = if (rescannedUntil < raw.size) raw.starts[rescannedUntil] else lastRescanned
            lineEndAt(text, maxOf(lastRescanned, next, diff.newEnd))
        }
        val fromOffset = lineStartAt(text, minOf(restart, diff.start))
        val result = partialResult(text, raw, fromOffset, toOffset, dirtyRange) { from, to -> refined(text, raw, from, to) }
        return remembered(text, raw, result)
    }

    /** Scans only the lines of [lines], with no state, for a first provisional colouring. */
    private fun scanLinesProvisionally(text: String, lines: IntRange): List<Token> {
        var start = 0
        repeat(lines.first) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) return emptyList()
            start = newline + 1
        }
        var end = start
        repeat(lines.last - lines.first + 1) {
            val newline = text.indexOf('\n', end)
            end = if (newline < 0) text.length else newline + 1
        }
        // The slice keeps every regex search inside the visible lines.
        val slice = text.substring(start, end)
        val builder = PackedTokenBuilder()
        scan(slice, 0, builder, onStep = { }, converged = { false })
        val raw = builder.build()
        val tokens = raw.toTokens()
        refine(slice, tokens)
        return withEscapes(slice, tokens).map { Token(it.start + start, it.end + start, it.type) }
    }

    /**
     * The scanner behind every entry point: appends the tokens of [text] from [from] to
     * [builder]. [onStep] runs before each token is sought; after each token [converged] may
     * end the scan.
     */
    private inline fun scan(
        text: String,
        from: Int,
        builder: PackedTokenBuilder,
        onStep: (tokenCount: Int) -> Unit,
        converged: (end: Int) -> Boolean,
    ) {
        val ruleList = rules
        if (text.isEmpty() || ruleList.isEmpty()) return

        // Each rule's first match at or after the cursor, kept across steps. The cursor only moves
        // forward, so a cached match that still starts at or after it is exactly what a fresh
        // find() would return; only rules the cursor has passed are searched again. Without this,
        // every step re-ran every rule, and a rule whose next match was far away rescanned to it
        // each time -- quadratic in document size.
        val nextMatch = arrayOfNulls<MatchResult>(ruleList.size)
        val exhausted = BooleanArray(ruleList.size)

        var cursor = from
        val length = text.length
        while (cursor < length) {
            onStep(builder.size)
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
            builder.add(bestStart, bestEnd, bestType)
            cursor = bestEnd
            if (converged(bestEnd)) break
        }
    }

    /** Tokens [from, to) of [raw] after [refine], which sees one raw token either side as context. */
    private fun refined(text: String, raw: PackedTokens, from: Int, to: Int): List<Token> {
        if (from >= to) return emptyList()
        val contextFrom = (from - 1).coerceAtLeast(0)
        val contextTo = (to + 1).coerceAtMost(raw.size)
        val tokens = raw.toTokens(contextFrom, contextTo)
        refine(text, tokens)
        return withEscapes(text, tokens.subList(from - contextFrom, to - contextFrom))
    }

    /**
     * [tokens] with every escape sequence ([stringEscapes]) inside a string token split out as a
     * token of its own, `string.escape`, as Monaco's languages give them. Searches each string
     * token's own characters only.
     */
    private fun withEscapes(text: String, tokens: List<Token>): List<Token> {
        val escapes = stringEscapes ?: return ArrayList(tokens)
        val result = ArrayList<Token>(tokens.size)
        for (token in tokens) {
            if (token.type != TokenType.StringLiteral || !hasEscapes(text, token)) {
                result.add(token)
                continue
            }
            var pos = token.start
            var i = token.start
            while (i < token.end) {
                if (text[i] != '\\') {
                    i++
                    continue
                }
                val match = escapes.matchAt(text, i)
                if (match == null) {
                    i++
                    continue
                }
                val end = minOf(match.range.last + 1, token.end)
                if (i > pos) result.add(Token(pos, i, TokenType.StringLiteral))
                result.add(Token(i, end, STRING_ESCAPE))
                pos = end
                i = end
            }
            if (pos < token.end) result.add(if (pos == token.start) token else Token(pos, token.end, TokenType.StringLiteral))
        }
        return result
    }

    /**
     * Adjusts the scanned [tokens] in place before they are returned. Override it for context a
     * rule would otherwise need a lookbehind for: Kotlin/wasm's regex engine evaluates a
     * lookbehind at every candidate position, which makes such a rule orders of magnitude slower
     * there than on the JVM.
     *
     * Always called on freshly scanned tokens, and possibly on part of a document with one token of
     * context either side, so a rule may only look at a token's immediate neighbours.
     */
    protected open fun refine(text: String, tokens: MutableList<Token>) {}

    /**
     * An escape sequence in a string literal (it starts with a backslash), which then gets a token
     * of its own typed `string.escape`; null leaves strings whole.
     */
    protected open val stringEscapes: Regex? = null

    /** Whether the string [token] can hold escapes; false for a raw string. */
    protected open fun hasEscapes(text: String, token: Token): Boolean = true

    /** Always false: the incremental rescan in [tokenizeLines] tracks multi-line constructs itself. */
    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
}

/** Monaco's name for an escape sequence in a string. */
internal val STRING_ESCAPE: TokenType = NamedTokenType("string.escape")

/** A backslash escape: `\uXXXX`, or a backslash and any one character. */
internal val BACKSLASH_ESCAPE = Regex("\\\\(?:u[0-9A-Fa-f]{4}|[\\s\\S])")

/** Monaco's name for a documentation comment (KDoc, JSDoc). */
internal val COMMENT_DOC: TokenType = NamedTokenType("comment.doc")

/** Monaco's name for a keyword that changes where execution goes (`if`, `return`, `throw`). */
internal val KEYWORD_FLOW: TokenType = NamedTokenType("keyword.flow")

/** Monaco's names for numbers written in hex, binary, or with a fraction or exponent. */
internal val NUMBER_HEX: TokenType = NamedTokenType("number.hex")
internal val NUMBER_BINARY: TokenType = NamedTokenType("number.binary")
internal val NUMBER_FLOAT: TokenType = NamedTokenType("number.float")
