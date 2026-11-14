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
        val noMatchFrom = unknownHorizons()
        scan(text, 0, builder, horizons = null, noMatchFrom, onStep = { }, converged = { false })
        val raw = builder.build()
        return remembered(text, raw, refined(text, raw, 0, raw.size), noMatchFrom)
    }

    override suspend fun tokenizeFullCooperative(text: String): List<Token> {
        val pacer = CooperativePacer()
        val builder = PackedTokenBuilder(text.length / 8)
        val noMatchFrom = unknownHorizons()
        scan(text, 0, builder, horizons = null, noMatchFrom, onStep = { tokenCount -> pacer.onProgress(tokenCount) }, converged = { false })
        val raw = builder.build()
        return remembered(text, raw, refined(text, raw, 0, raw.size), noMatchFrom)
    }

    /**
     * [text]'s tokens, as [tokenizeFull] gives them, but not remembered for an incremental pass:
     * for a language embedded in another, whose own tokenizer keeps the scans. Remembered, they
     * would push this tokenizer's own documents' scans out of its few.
     */
    internal fun tokenizeEmbedded(text: String): List<Token> {
        val builder = PackedTokenBuilder(text.length / 8)
        val noMatchFrom = unknownHorizons()
        scan(text, 0, builder, horizons = null, noMatchFrom, onStep = { }, converged = { false })
        val raw = builder.build()
        return refined(text, raw, 0, raw.size)
    }

    private fun remembered(text: String, raw: PackedTokens, result: List<Token>, noMatchFrom: IntArray): List<Token> {
        scans.remember(result, ScanSnapshot(text, raw, noMatchFrom))
        return result
    }

    /** For each rule, where in a text it is known to match no more: nowhere yet. */
    private fun unknownHorizons(): IntArray = IntArray(rules.size) { Int.MAX_VALUE }

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

        // Where each rule can match no more in the new text. Past the edit, the new text is the old
        // one shifted by delta, so a rule that matched nowhere from some point of the old text
        // matches nowhere from that point shifted, once far enough past the edit that what the
        // rule looks behind at is unchanged too. Without this, a rule that never matches (a raw
        // string in a file that has none) searched the rest of the document again on every edit.
        val horizons = IntArray(rules.size) { i ->
            val oldHorizon = previous.noMatchFrom.getOrElse(i) { Int.MAX_VALUE }
            if (oldHorizon == Int.MAX_VALUE) Int.MAX_VALUE else maxOf(oldHorizon + diff.delta, diff.newEnd + RESCAN_LOOKBACK)
        }
        val noMatchFrom = horizons.copyOf()
        var resumeOld = -1
        scan(text, restart, builder, horizons, noMatchFrom, onStep = { }) { end ->
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
        return remembered(text, raw, result, noMatchFrom)
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
        scan(slice, 0, builder, horizons = null, unknownHorizons(), onStep = { }, converged = { false })
        val raw = builder.build()
        val tokens = raw.toTokens()
        refine(slice, tokens)
        return withEscapes(slice, tokens).map { Token(it.start + start, it.end + start, it.type) }
    }

    /**
     * The scanner behind every entry point: appends the tokens of [text] from [from] to
     * [builder]. [onStep] runs before each token is sought; after each token [converged] may
     * end the scan.
     *
     * [horizons], for a rescan, holds where each rule is known to match no more in [text]
     * (`Int.MAX_VALUE` where that is not known); null for a scan of the whole text. [noMatchFrom]
     * comes back holding where each rule matches no more, as far as this scan found out, for the
     * next rescan's horizons.
     */
    private inline fun scan(
        text: String,
        from: Int,
        builder: PackedTokenBuilder,
        horizons: IntArray?,
        noMatchFrom: IntArray,
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

        // A rescan, which usually gets back in step within a few tokens, looks at a rule's few
        // positions before its horizon, or before the best match so far, one at a time instead:
        // find() would search on to the rule's next match, which can be the end of the document.
        // probedTo holds how far each rule has been looked at that way and matched nowhere.
        val probedTo = IntArray(if (horizons == null) 0 else ruleList.size)
        var probing = horizons != null

        var cursor = from
        val length = text.length
        while (cursor < length) {
            onStep(builder.size)
            // A rescan this long has not got back in step soon: the rest of it searches as a full scan does.
            if (probing && cursor - from > MAX_PROBED_RESCAN) probing = false
            var bestStart = -1
            var bestEnd = -1
            var bestType: TokenType? = null
            for (i in ruleList.indices) {
                if (exhausted[i]) continue
                val (regex, type) = ruleList[i]
                var match = nextMatch[i]
                if (match == null || match.range.first < cursor) {
                    val horizon = horizons?.get(i) ?: Int.MAX_VALUE
                    val limit = minOf(if (bestStart >= 0) minOf(bestStart, horizon) else horizon, length)
                    val lookFrom = if (probing) maxOf(cursor, probedTo[i]) else cursor
                    if (probing && limit - lookFrom <= PROBE_WINDOW) {
                        match = firstMatchBetween(regex, text, lookFrom, limit)
                        if (match == null) {
                            // Nothing before the limit: none at all when that is the horizon (or
                            // the end), and none that could beat the best match otherwise.
                            probedTo[i] = limit
                            nextMatch[i] = null
                            if (limit >= minOf(horizon, length)) {
                                exhausted[i] = true
                                noMatchFrom[i] = minOf(noMatchFrom[i], cursor)
                            }
                            continue
                        }
                    } else {
                        match = regex.find(text, cursor)
                        if (match == null) {
                            exhausted[i] = true
                            noMatchFrom[i] = minOf(noMatchFrom[i], cursor)
                            continue
                        }
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

    /** [regex]'s first match starting in [from, to), tried at each position: what find() would give, when it starts there. */
    private fun firstMatchBetween(regex: Regex, text: String, from: Int, to: Int): MatchResult? {
        for (position in from until to) regex.matchAt(text, position)?.let { return it }
        return null
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

/** How many positions a rescan tries a rule at one by one before it searches with find() instead. */
private const val PROBE_WINDOW = 4 * RESCAN_LOOKBACK

/** How far a rescan goes looking at rules position by position before it searches as a full scan does. */
private const val MAX_PROBED_RESCAN = 8 * RESCAN_LOOKBACK

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
