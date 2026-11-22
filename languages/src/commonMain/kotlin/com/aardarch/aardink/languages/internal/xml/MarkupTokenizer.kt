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

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.CooperativePacer
import com.aardarch.aardink.languages.internal.PackedTokenBuilder
import com.aardarch.aardink.languages.internal.PackedTokens
import com.aardarch.aardink.languages.internal.RESCAN_LOOKBACK
import com.aardarch.aardink.languages.internal.ScanCache
import com.aardarch.aardink.languages.internal.TextDiff
import com.aardarch.aardink.languages.internal.diffTexts
import com.aardarch.aardink.languages.internal.lineEndAt
import com.aardarch.aardink.languages.internal.lineSpan
import com.aardarch.aardink.languages.internal.lineStartAt
import com.aardarch.aardink.languages.internal.partialResult

/**
 * The scanner behind [XmlTokenizer] and [com.aardarch.aardink.languages.internal.html.HtmlTokenizer]:
 * an explicit state machine, as regex on its own struggles to keep tag names, attribute names,
 * attribute values and text content separate.
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
 * With [embedded], an element it names a language for is raw text, as HTML's `<style>` and
 * `<script>` are: its body, up to the element's closing tag, is that language's tokens. The opening
 * tag and the body are one construct, so a rescan that starts in the tag starts at the tag, and the
 * body is never taken as in step on its own (the tag before it decides its language). An edit
 * inside the body alone, with both tags as they were, rescans only the body's changed lines: each
 * body keeps its own scan, which the language rescans as its own tokenizer would.
 *
 * [tokenizeLines] is incremental. Between constructs (a tag, a comment, an entity...) the scanner
 * has no state but its position, so after an edit it restarts outside any construct a little
 * before the change, and stops once it is back between constructs at a position past the change
 * where the old scan was between constructs too.
 */
internal class MarkupTokenizer(private val embedded: EmbeddedLanguages? = null) {

    /** What languages a markup language's raw-text elements are in. */
    fun interface EmbeddedLanguages {
        /**
         * What tokenizes the body of the element whose opening tag in [text] runs from its `<` at
         * [tagStart] to its `>` at [tagEnd], or null when its body is markup.
         */
        fun tokenizerOf(text: String, tagStart: Int, tagEnd: Int): EmbeddedLanguage?
    }

    /** The language of a raw-text element's body. The markup's scan keeps each body's own scan. */
    interface EmbeddedLanguage {
        /** The scan of [body]. */
        fun scan(body: String): BodyScan

        /** The scan of [body], letting the page draw as [pacer] has it; the markup's scan has [tokenBase] tokens so far. */
        suspend fun scanCooperatively(body: String, pacer: CooperativePacer, tokenBase: Int): BodyScan
    }

    /** One body's scan, with what a rescan of it after an edit needs. */
    interface BodyScan {
        /** All the body's tokens, from 0. */
        fun tokens(): List<Token>

        /** The scan of [body], the text of this scan's body after an edit, from this scan. */
        fun rescan(body: String): BodyRescan
    }

    /**
     * A body's rescan: the new [scan] and [tokens], the tokens of the span [fromOffset, toOffset)
     * of the new body. Every old token before [fromOffset] is unchanged, and so is every one from
     * the old span's end on (`toOffset` less the change in length), moved by that change.
     */
    internal class BodyRescan(val scan: BodyScan, val fromOffset: Int, val toOffset: Int, val tokens: List<Token>)

    /**
     * A raw-text element whose body has a scan of its own: the opening tag from [tagStart] and the
     * body from [bodyStart] to [bodyEnd], where its closing tag starts; [nameLength] is the
     * element name's length.
     */
    private class Block(val tagStart: Int, val bodyStart: Int, val bodyEnd: Int, val nameLength: Int, val scan: BodyScan) {
        fun movedBy(delta: Int) = Block(tagStart + delta, bodyStart + delta, bodyEnd + delta, nameLength, scan)
    }

    /**
     * The last complete scan: its tokens, the span of every construct (the scanner was between
     * constructs everywhere else), the raw-text [blocks] by position, and where an unclosed tag
     * ended the scan (the text's length when none did; nothing after it was tokenized).
     */
    private class MarkupScan(
        val text: String,
        val tokens: PackedTokens,
        val constructs: PackedTokens,
        val blocks: List<Block>,
        val stoppedAt: Int,
    ) {
        /** The block whose opening tag starts at [tagStart], if any. */
        fun blockAt(tagStart: Int): Block? = blocks.getOrNull(firstBlockAtOrAfter(tagStart))?.takeIf { it.tagStart == tagStart }

        /** Index of the first block whose opening tag starts at or after [offset]. */
        fun firstBlockAtOrAfter(offset: Int): Int {
            var lo = 0
            var hi = blocks.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (blocks[mid].tagStart < offset) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Whether the scanner stood at [offset] between constructs. */
        fun isBetweenConstructs(offset: Int): Boolean {
            if (offset > stoppedAt) return false
            val index = constructs.firstEndingAfter(offset)
            return index >= constructs.size || constructs.starts[index] >= offset
        }
    }

    /** The scans behind the token lists this tokenizer returned last, for incremental passes. */
    private val scans = ScanCache<MarkupScan>()

    fun tokenizeFull(text: String): List<Token> {
        val tokens = PackedTokenBuilder(text.length / 8)
        val constructs = PackedTokenBuilder(text.length / 16)
        val blocks = ArrayList<Block>()
        val stoppedAt = scan(text, 0, tokens, constructs, blocks, onStep = { }, converged = { false })
        val raw = tokens.build()
        return remembered(MarkupScan(text, raw, constructs.build(), blocks, stoppedAt), raw.toTokens())
    }

    private fun remembered(scan: MarkupScan, result: List<Token>): List<Token> {
        scans.remember(result, scan)
        return result
    }

    suspend fun tokenizeFullCooperative(text: String): List<Token> {
        val pacer = CooperativePacer()
        val tokens = PackedTokenBuilder(text.length / 8)
        val constructs = PackedTokenBuilder(text.length / 16)
        val blocks = ArrayList<Block>()
        val stoppedAt = scan(
            text,
            0,
            tokens,
            constructs,
            blocks,
            onStep = { tokenCount -> pacer.onProgress(tokenCount) },
            converged = { false },
            // A large `<script>` body yields too, not only the markup around it.
            embed = { language, body, tokenCount -> language.scanCooperatively(body, pacer, tokenCount) },
        )
        val raw = tokens.build()
        return remembered(MarkupScan(text, raw, constructs.build(), blocks, stoppedAt), raw.toTokens())
    }

    /**
     * Re-tokenizes the lines around the change since the last scan and returns their tokens: at
     * least [dirtyRange], and further when the change altered how later text scans. With no
     * [previousTokens] it scans just [dirtyRange] provisionally, as `RegexTokenizer` does.
     */
    fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
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
        previous.blockAt(restart)?.let { block ->
            if (diff.start > block.bodyEnd + block.nameLength + 2) {
                // The edit is past the closing tag and the character that ends its name: the
                // block is as it was, and the scan restarts after its body.
                restart = block.bodyEnd
            } else {
                rescanBody(text, previous, block, diff, dirtyRange)?.let { return it }
            }
        }
        val keptTokens = previous.tokens.firstEndingAfter(restart)
        val keptConstructs = previous.constructs.firstEndingAfter(restart)
        val tokens = PackedTokenBuilder(previous.tokens.size + 16)
        val constructs = PackedTokenBuilder(previous.constructs.size + 8)
        val blocks = ArrayList<Block>()
        tokens.addAll(previous.tokens, 0, keptTokens)
        constructs.addAll(previous.constructs, 0, keptConstructs)
        for (k in 0 until previous.firstBlockAtOrAfter(restart)) blocks.add(previous.blocks[k])

        var resumeAt = -1
        val stopped = scan(text, restart, tokens, constructs, blocks, onStep = { }, converged = { position ->
            // In step again once past the change, at a place the old scan also stood between
            // constructs: the text from here on is the same, so the scan would be too.
            val inStep = position - 1 >= diff.newEnd && previous.isBetweenConstructs(position - diff.delta)
            if (inStep) resumeAt = position
            inStep
        })
        val stoppedAt = if (resumeAt >= 0) {
            val oldPosition = resumeAt - diff.delta
            tokens.addAll(previous.tokens, previous.tokens.firstStartingAtOrAfter(oldPosition), previous.tokens.size, diff.delta)
            constructs.addAll(
                previous.constructs,
                previous.constructs.firstStartingAtOrAfter(oldPosition),
                previous.constructs.size,
                diff.delta,
            )
            for (k in previous.firstBlockAtOrAfter(oldPosition) until previous.blocks.size) {
                blocks.add(previous.blocks[k].movedBy(diff.delta))
            }
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
        return remembered(MarkupScan(text, raw, constructs.build(), blocks, stoppedAt), result)
    }

    /**
     * The tokens after an edit inside [block]'s body that leaves its opening and closing tags as
     * they were: the body is rescanned from its own scan, only where the edit is, and the rest of
     * the old scan is kept, moved. Null when the edit reaches either tag or makes an earlier
     * closing tag: the opening tag and the body are one construct, whose scan starts at the tag.
     */
    private fun rescanBody(text: String, previous: MarkupScan, block: Block, diff: TextDiff, dirtyRange: IntRange): List<Token>? {
        if (diff.start < block.bodyStart || diff.oldEnd > block.bodyEnd) return null
        // The old closing tag still ends the body when no closing tag starts in the changed text,
        // or just before it, where its name could begin: from the edit on, the old text is the new.
        val name = nameAt(text, block.tagStart + 1)
        val searchFrom = maxOf(block.bodyStart, diff.start - name.length - 2)
        for (at in searchFrom until minOf(diff.newEnd, text.length - 1)) {
            if (isClosingTagAt(text, at, name)) return null
        }
        val bodyEnd = block.bodyEnd + diff.delta
        val rescan = block.scan.rescan(text.substring(block.bodyStart, bodyEnd))
        val oldFrom = previous.tokens.firstStartingAtOrAfter(block.bodyStart + rescan.fromOffset)
        val oldTo = previous.tokens.firstStartingAtOrAfter(block.bodyStart + rescan.toOffset - diff.delta)
        val oldEnd = previous.tokens.firstStartingAtOrAfter(block.bodyEnd)
        if (oldFrom > oldTo || oldTo > oldEnd) return null

        val tokens = PackedTokenBuilder(previous.tokens.size + 16)
        tokens.addAll(previous.tokens, 0, oldFrom)
        for (token in rescan.tokens) {
            if (token.end > token.start) tokens.add(token.start + block.bodyStart, token.end + block.bodyStart, token.type)
        }
        tokens.addAll(previous.tokens, oldTo, previous.tokens.size, diff.delta)

        // The block's construct ends where the body does, at the old end moved.
        val constructs = PackedTokenBuilder(previous.constructs.size + 8)
        constructs.addAll(previous.constructs, 0, previous.constructs.firstEndingAfter(block.tagStart))
        constructs.add(block.tagStart, bodyEnd, TokenType.Punctuation)
        constructs.addAll(
            previous.constructs,
            previous.constructs.firstStartingAtOrAfter(block.bodyEnd),
            previous.constructs.size,
            diff.delta,
        )

        val blocks = ArrayList<Block>(previous.blocks.size)
        for (k in 0 until previous.firstBlockAtOrAfter(block.tagStart)) blocks.add(previous.blocks[k])
        blocks.add(Block(block.tagStart, block.bodyStart, bodyEnd, block.nameLength, rescan.scan))
        for (k in previous.firstBlockAtOrAfter(block.bodyEnd) until previous.blocks.size) {
            blocks.add(previous.blocks[k].movedBy(diff.delta))
        }
        val stoppedAt = if (previous.stoppedAt < previous.text.length) previous.stoppedAt + diff.delta else text.length
        val raw = tokens.build()

        // Whole lines from the first rescanned token to the end of the last, or of the body when
        // the rescan never got back in step.
        val fromOffset = lineStartAt(text, minOf(block.bodyStart + rescan.fromOffset, diff.start))
        val toOffset = lineEndAt(text, maxOf(block.bodyStart + rescan.toOffset - 1, diff.newEnd))
        val result = partialResult(text, raw, fromOffset, toOffset, dirtyRange) { from, to -> raw.toTokens(from, to) }
        return remembered(MarkupScan(text, raw, constructs.build(), blocks, stoppedAt), result)
    }

    /** Scans only the lines of [lines], assuming the first starts between constructs. */
    private fun scanLinesProvisionally(text: String, lines: IntRange): List<Token> {
        val span = lineSpan(text, lines) ?: return emptyList()
        val slice = text.substring(span.first, span.last)
        val tokens = PackedTokenBuilder()
        scan(slice, 0, tokens, PackedTokenBuilder(), ArrayList(), onStep = { }, converged = { false })
        val raw = tokens.build()
        return List(raw.size) { Token(raw.starts[it] + span.first, raw.ends[it] + span.first, raw.types[it]) }
    }

    /**
     * The scanner behind every entry point: appends the tokens of [text] from [from] to [tokens]
     * and the span of each construct to [constructs]. [onStep] runs before each character is
     * examined; [converged] is asked at each position between constructs past [from] and may end
     * the scan there; [embed] tokenizes a raw-text element's body, given the tokens so far.
     * Returns where the scan ended: the text's length, the position of an unclosed tag, or where
     * it converged.
     */
    private inline fun scan(
        text: String,
        from: Int,
        tokens: PackedTokenBuilder,
        constructs: PackedTokenBuilder,
        blocks: MutableList<Block>,
        onStep: (tokenCount: Int) -> Unit,
        converged: (position: Int) -> Boolean,
        embed: (
            language: EmbeddedLanguage,
            body: String,
            tokenCount: Int,
        ) -> BodyScan = { language, body, _ -> language.scan(body) },
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
                val language = if (embedded == null || text[tagEnd - 1] == '/') null else embedded.tokenizerOf(text, i, tagEnd)
                if (language == null) {
                    constructs.add(i, tagEnd + 1, TokenType.Punctuation)
                    i = tagEnd + 1
                    continue
                }
                val bodyStart = tagEnd + 1
                val name = nameAt(text, i + 1)
                val bodyEnd = closingTagAt(text, bodyStart, name)
                if (bodyEnd > bodyStart) {
                    val bodyScan = embed(language, text.substring(bodyStart, bodyEnd), tokens.size)
                    for (token in bodyScan.tokens()) {
                        if (token.end > token.start) tokens.add(token.start + bodyStart, token.end + bodyStart, token.type)
                    }
                    blocks.add(Block(i, bodyStart, bodyEnd, name.length, bodyScan))
                }
                constructs.add(i, bodyEnd, TokenType.Punctuation)
                i = bodyEnd
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

    /** The element name that starts at [from], just after a tag's `<`. */
    private fun nameAt(text: String, from: Int): String {
        var i = from
        while (i < text.length && text[i].isNameCharacter()) i++
        return text.substring(from, i)
    }

    /**
     * Where the raw-text element [name]'s body from [from] ends: at its closing tag, `</` and the
     * name in any case, then whitespace, `/` or `>`, as HTML ends one; at the end without one.
     */
    private fun closingTagAt(text: String, from: Int, name: String): Int {
        var at = text.indexOf("</", from)
        while (at >= 0) {
            if (isClosingTagAt(text, at, name)) return at
            at = text.indexOf("</", at + 2)
        }
        return text.length
    }

    /** Whether the closing tag of the raw-text element [name] starts at [at]: `</`, the name, then whitespace, `/`, `>` or the end. */
    private fun isClosingTagAt(text: String, at: Int, name: String): Boolean {
        if (!text.startsWith("</", at)) return false
        val after = at + 2 + name.length
        return text.regionMatches(at + 2, name, 0, name.length, ignoreCase = true) &&
            (after >= text.length || text[after].isWhitespace() || text[after] == '/' || text[after] == '>')
    }

    private fun Char.isNameCharacter() = isLetterOrDigit() || this == ':' || this == '-' || this == '_'

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
