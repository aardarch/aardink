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
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.CooperativePacer
import com.aardarch.aardink.languages.internal.PackedTokenBuilder
import com.aardarch.aardink.languages.internal.PackedTokens
import com.aardarch.aardink.languages.internal.RESCAN_LOOKBACK
import com.aardarch.aardink.languages.internal.ScanCache
import com.aardarch.aardink.languages.internal.diffTexts
import com.aardarch.aardink.languages.internal.lineEndAt
import com.aardarch.aardink.languages.internal.lineSpan
import com.aardarch.aardink.languages.internal.lineStartAt
import com.aardarch.aardink.languages.internal.partialResult

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
 *
 * [tokenizeLines] is incremental. Between constructs (a tag, a comment, an entity...) the scanner
 * has no state but its position, so after an edit it restarts outside any construct a little
 * before the change, and stops once it is back between constructs at a position past the change
 * where the old scan was between constructs too.
 */
object XmlTokenizer : IncrementalTokenizer {

    /**
     * The last complete scan: its tokens, the span of every construct (the scanner was between
     * constructs everywhere else), and where an unclosed tag ended the scan (the text's length
     * when none did; nothing after it was tokenized).
     */
    private class XmlScan(val text: String, val tokens: PackedTokens, val constructs: PackedTokens, val stoppedAt: Int) {
        /** Whether the scanner stood at [offset] between constructs. */
        fun isBetweenConstructs(offset: Int): Boolean {
            if (offset > stoppedAt) return false
            val index = constructs.firstEndingAfter(offset)
            return index >= constructs.size || constructs.starts[index] >= offset
        }
    }

    /** The scans behind the token lists this tokenizer returned last, for incremental passes. */
    private val scans = ScanCache<XmlScan>()

    override fun tokenizeFull(text: String): List<Token> {
        val tokens = PackedTokenBuilder(text.length / 8)
        val constructs = PackedTokenBuilder(text.length / 16)
        val stoppedAt = scan(text, 0, tokens, constructs, onStep = { }, converged = { false })
        val raw = tokens.build()
        return remembered(XmlScan(text, raw, constructs.build(), stoppedAt), raw.toTokens())
    }

    private fun remembered(scan: XmlScan, result: List<Token>): List<Token> {
        scans.remember(result, scan)
        return result
    }

    override suspend fun tokenizeFullCooperative(text: String): List<Token> {
        val pacer = CooperativePacer()
        val tokens = PackedTokenBuilder(text.length / 8)
        val constructs = PackedTokenBuilder(text.length / 16)
        val stoppedAt = scan(text, 0, tokens, constructs, onStep = { tokenCount -> pacer.onProgress(tokenCount) }, converged = { false })
        val raw = tokens.build()
        return remembered(XmlScan(text, raw, constructs.build(), stoppedAt), raw.toTokens())
    }

    /**
     * Re-tokenizes the lines around the change since the last scan and returns their tokens: at
     * least [dirtyRange], and further when the change altered how later text scans. With no
     * [previousTokens] it scans just [dirtyRange] provisionally, as `RegexTokenizer` does.
     */
    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
        if (previousTokens.isEmpty()) return scanLinesProvisionally(text, dirtyRange)
        val previous = scans.find(previousTokens) ?: return tokenizeFull(text)
        val diff = diffTexts(previous.text, text)

        // Restart between constructs, a little before the change: at the start of the construct
        // that holds that point, if one does.
        var restart = (diff.start - RESCAN_LOOKBACK).coerceIn(0, previous.stoppedAt)
        val holding = previous.constructs.firstEndingAfter(restart)
        if (holding < previous.constructs.size && previous.constructs.starts[holding] < restart) {
            restart = previous.constructs.starts[holding]
        }
        val keptTokens = previous.tokens.firstEndingAfter(restart)
        val keptConstructs = previous.constructs.firstEndingAfter(restart)
        val tokens = PackedTokenBuilder(previous.tokens.size + 16)
        val constructs = PackedTokenBuilder(previous.constructs.size + 8)
        tokens.addAll(previous.tokens, 0, keptTokens)
        constructs.addAll(previous.constructs, 0, keptConstructs)

        var resumeAt = -1
        val stopped = scan(text, restart, tokens, constructs, onStep = { }) { position ->
            // In step again once past the change, at a place the old scan also stood between
            // constructs: the text from here on is the same, so the scan would be too.
            val inStep = position - 1 >= diff.newEnd && previous.isBetweenConstructs(position - diff.delta)
            if (inStep) resumeAt = position
            inStep
        }
        val rescannedUntil = tokens.size
        val stoppedAt = if (resumeAt >= 0) {
            val oldPosition = resumeAt - diff.delta
            tokens.addAll(previous.tokens, previous.tokens.firstStartingAtOrAfter(oldPosition), previous.tokens.size, diff.delta)
            constructs.addAll(
                previous.constructs,
                previous.constructs.firstStartingAtOrAfter(oldPosition),
                previous.constructs.size,
                diff.delta,
            )
            if (previous.stoppedAt < previous.text.length) previous.stoppedAt + diff.delta else text.length
        } else {
            stopped
        }
        val raw = tokens.build()

        // Whole lines from where the rescan started to where it got back in step; to the end of the
        // text when it never did, since everything after it may have changed.
        val toOffset = if (resumeAt < 0) text.length else lineEndAt(text, maxOf(resumeAt - 1, diff.newEnd))
        val fromOffset = lineStartAt(text, minOf(restart, diff.start))
        val result = partialResult(text, raw, fromOffset, toOffset, dirtyRange) { from, to -> raw.toTokens(from, to) }
        return remembered(XmlScan(text, raw, constructs.build(), stoppedAt), result)
    }

    /** Scans only the lines of [lines], assuming the first starts between constructs. */
    private fun scanLinesProvisionally(text: String, lines: IntRange): List<Token> {
        val span = lineSpan(text, lines) ?: return emptyList()
        val slice = text.substring(span.first, span.last)
        val tokens = PackedTokenBuilder()
        scan(slice, 0, tokens, PackedTokenBuilder(), onStep = { }, converged = { false })
        val raw = tokens.build()
        return List(raw.size) { Token(raw.starts[it] + span.first, raw.ends[it] + span.first, raw.types[it]) }
    }

    /**
     * The scanner behind every entry point: appends the tokens of [text] from [from] to [tokens]
     * and the span of each construct to [constructs]. [onStep] runs before each character is
     * examined; [converged] is asked at each position between constructs past [from] and may end
     * the scan there. Returns where the scan ended: the text's length, the position of an unclosed
     * tag, or where it converged.
     */
    private inline fun scan(
        text: String,
        from: Int,
        tokens: PackedTokenBuilder,
        constructs: PackedTokenBuilder,
        onStep: (tokenCount: Int) -> Unit,
        converged: (position: Int) -> Boolean,
    ): Int {
        var i = from
        val n = text.length
        while (i < n) {
            if (i > from && converged(i)) return i
            onStep(tokens.size)
            val c = text[i]
            // <!-- comment -->
            if (c == '<' && i + 3 < n && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                val end = text.indexOf("-->", i + 4)
                val close = if (end < 0) n else end + 3
                tokens.add(i, close, TokenType.Comment)
                constructs.add(i, close, TokenType.Comment)
                i = close
                continue
            }
            // <![CDATA[ … ]]>
            if (c == '<' && i + 8 < n && text.regionMatches(i + 1, "![CDATA[", 0, 8)) {
                val end = text.indexOf("]]>", i + 9)
                val close = if (end < 0) n else end + 3
                tokens.add(i, close, TokenType.StringLiteral)
                constructs.add(i, close, TokenType.StringLiteral)
                i = close
                continue
            }
            // <?xml … ?>
            if (c == '<' && i + 1 < n && text[i + 1] == '?') {
                val end = text.indexOf("?>", i + 2)
                val close = if (end < 0) n else end + 2
                tokens.add(i, close, TokenType.Annotation)
                constructs.add(i, close, TokenType.Annotation)
                i = close
                continue
            }
            // <!DOCTYPE …>
            if (c == '<' && i + 1 < n && text[i + 1] == '!') {
                val end = text.indexOf('>', i + 2)
                val close = if (end < 0) n else end + 1
                tokens.add(i, close, TokenType.Annotation)
                constructs.add(i, close, TokenType.Annotation)
                i = close
                continue
            }
            // Tag start
            if (c == '<') {
                val tagEnd = findTagEnd(text, i + 1) ?: return i
                tokenizeTag(text, i, tagEnd + 1, tokens)
                constructs.add(i, tagEnd + 1, TokenType.Punctuation)
                i = tagEnd + 1
                continue
            }
            // Entity reference
            if (c == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi > 0 && semi - i <= 10) {
                    tokens.add(i, semi + 1, TokenType.Number)
                    constructs.add(i, semi + 1, TokenType.Number)
                    i = semi + 1
                    continue
                }
            }
            i++
        }
        return n
    }

    /** No longer consulted by the editor for this tokenizer: [tokenizeLines] follows every construct itself. */
    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

    override val commentSyntax: CommentSyntax = CommentSyntax(blockStart = "<!--", blockEnd = "-->")

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

    private fun tokenizeTag(text: String, start: Int, end: Int, out: PackedTokenBuilder) {
        // start points at '<'; end points one past '>'
        out.add(start, start + 1, TokenType.Punctuation)
        var i = start + 1
        if (i < end - 1 && text[i] == '/') {
            out.add(i, i + 1, TokenType.Punctuation)
            i++
        }
        // Element name
        val nameStart = i
        while (i < end - 1 && (text[i].isLetterOrDigit() || text[i] == ':' || text[i] == '-' || text[i] == '_')) i++
        if (i > nameStart) {
            out.add(nameStart, i, TokenType.TypeName)
        }
        // Attributes
        while (i < end - 1) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c == '/' -> {
                    out.add(i, i + 1, TokenType.Punctuation)
                    i++
                }

                c == '=' -> {
                    out.add(i, i + 1, TokenType.Operator)
                    i++
                }

                c == '"' || c == '\'' -> {
                    val close = text.indexOf(c, i + 1)
                    val stop = if (close < 0 || close >= end - 1) end - 1 else close + 1
                    out.add(i, stop, TokenType.StringLiteral)
                    i = stop
                }

                c.isLetter() || c == '_' || c == ':' -> {
                    val attrStart = i
                    while (i < end - 1 && (text[i].isLetterOrDigit() || text[i] == ':' || text[i] == '-' || text[i] == '_')) i++
                    out.add(attrStart, i, TokenType.Identifier)
                }

                else -> i++
            }
        }
        // Closing '>'
        if (end - 1 > start && text[end - 1] == '>') {
            out.add(end - 1, end, TokenType.Punctuation)
        }
    }
}
