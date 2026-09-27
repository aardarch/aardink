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

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import kotlin.concurrent.Volatile

// Shared pieces of the built-in tokenizers' incremental passes. Each tokenizer keeps the text and
// raw tokens of its last complete scan; the next pass diffs the new text against that text,
// rescans from a safe point before the change, and stops as soon as the rescan is back in step
// with the old scan, reusing the old tokens after that point shifted by the change in length.

/** Tokens in three parallel arrays, sorted by start and not overlapping, so ends are sorted too. */
internal class PackedTokens(val starts: IntArray, val ends: IntArray, val types: Array<TokenType>) {
    val size: Int get() = types.size

    /** Index of the first token whose end is greater than [offset], or [size]. */
    fun firstEndingAfter(offset: Int): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the first token whose start is at or after [offset], or [size]. */
    fun firstStartingAtOrAfter(offset: Int): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the token that ends exactly at [end], or -1. */
    fun indexEndingAt(end: Int): Int {
        val index = firstEndingAfter(end - 1)
        return if (index < size && ends[index] == end) index else -1
    }

    fun token(index: Int): Token = Token(starts[index], ends[index], types[index])

    fun toTokens(from: Int = 0, to: Int = size): MutableList<Token> {
        val out = ArrayList<Token>(to - from)
        for (i in from until to) out.add(token(i))
        return out
    }

    companion object {
        val EMPTY = PackedTokens(IntArray(0), IntArray(0), emptyArray())
    }
}

internal class PackedTokenBuilder(capacity: Int = 16) {
    private var starts = IntArray(capacity.coerceAtLeast(4))
    private var ends = IntArray(capacity.coerceAtLeast(4))
    private val types = ArrayList<TokenType>(capacity)

    val size: Int get() = types.size

    val lastEnd: Int get() = if (types.isEmpty()) 0 else ends[types.size - 1]

    fun add(start: Int, end: Int, type: TokenType) {
        val index = types.size
        if (index == starts.size) {
            starts = starts.copyOf(index * 2)
            ends = ends.copyOf(index * 2)
        }
        starts[index] = start
        ends[index] = end
        types.add(type)
    }

    /** Appends tokens [from, to) of [source], moved by [delta]. */
    fun addAll(source: PackedTokens, from: Int, to: Int, delta: Int = 0) {
        for (i in from until to) add(source.starts[i] + delta, source.ends[i] + delta, source.types[i])
    }

    fun build(): PackedTokens = PackedTokens(starts.copyOf(size), ends.copyOf(size), types.toTypedArray())
}

/** Where two texts differ: [start, oldEnd) of the old text became [start, newEnd) of the new one. */
internal class TextDiff(val start: Int, val oldEnd: Int, val newEnd: Int) {
    val delta: Int get() = newEnd - oldEnd
    val isEmpty: Boolean get() = start == oldEnd && start == newEnd
}

/** The smallest single span that turns [old] into [new]: their common prefix and suffix trimmed. */
internal fun diffTexts(old: String, new: String): TextDiff {
    val limit = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < limit && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return TextDiff(prefix, old.length - suffix, new.length - suffix)
}

/** Start of the line containing [offset]. */
internal fun lineStartAt(text: String, offset: Int): Int =
    if (offset <= 0) 0 else text.lastIndexOf('\n', (offset - 1).coerceAtMost(text.length - 1)) + 1

/** End of the line containing [offset]: the index of its '\n', or the text's length. */
internal fun lineEndAt(text: String, offset: Int): Int {
    val newline = text.indexOf('\n', offset.coerceIn(0, text.length))
    return if (newline < 0) text.length else newline
}

/** The offsets from the start of line [lines].first to the end of line [lines].last, or null past the end. */
internal fun lineSpan(text: String, lines: IntRange): IntRange? {
    var start = 0
    repeat(lines.first) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) return null
        start = newline + 1
    }
    var end = start
    repeat(lines.last - lines.first) {
        val newline = text.indexOf('\n', end)
        if (newline < 0) return start..text.length
        end = newline + 1
    }
    return start..lineEndAt(text, end)
}

/**
 * Widens tokens [from, to) of [tokens] to whole lines: a caller replaces every line those tokens
 * touch, so a token that spans lines at either edge must bring the rest of its first and last
 * line's tokens along. Returns the widened (from, to).
 */
internal fun wholeLines(text: String, tokens: PackedTokens, from: Int, to: Int): Pair<Int, Int> {
    if (from >= to) return from to to
    var lo = from
    var hi = to
    while (true) {
        val newLo = tokens.firstEndingAfter(lineStartAt(text, tokens.starts[lo]))
        val newHi = tokens.firstStartingAtOrAfter(lineEndAt(text, tokens.ends[hi - 1] - 1))
        if (newLo >= lo && newHi <= hi) return lo to hi
        lo = minOf(lo, newLo)
        hi = maxOf(hi, newHi)
    }
}

/**
 * What a partial pass returns: whole lines covering [fromOffset, toOffset) and every line of
 * [dirtyRange], with [tokensIn] supplying tokens [from, to) of [raw]. A zero-length
 * [TokenType.Default] token marks each end of the covered span: the editor replaces every line from
 * the first token's line to the last token's line, so the markers make it clear lines whose tokens
 * all went away, which a tokens-only result could not reach.
 */
internal inline fun partialResult(
    text: String,
    raw: PackedTokens,
    fromOffset: Int,
    toOffset: Int,
    dirtyRange: IntRange,
    tokensIn: (from: Int, to: Int) -> List<Token>,
): List<Token> {
    var start = fromOffset
    var end = toOffset
    lineSpan(text, dirtyRange)?.let {
        start = minOf(start, it.first)
        end = maxOf(end, it.last)
    }
    val (from, to) = wholeLines(text, raw, raw.firstEndingAfter(start), raw.firstStartingAtOrAfter(end))
    val tokens = tokensIn(from, to)
    if (from < to) {
        start = minOf(start, raw.starts[from])
        end = maxOf(end, raw.ends[to - 1])
    }
    val result = ArrayList<Token>(tokens.size + 2)
    result.add(Token(start, start, TokenType.Default))
    result.addAll(tokens)
    result.add(Token(end, end, TokenType.Default))
    return result
}

/** The text of the last complete scan and its raw tokens: what the next incremental pass diffs against. */
internal class ScanSnapshot(val text: String, val tokens: PackedTokens)

/**
 * The scans behind the last few token lists a tokenizer returned, looked up by the identity of
 * the list. The editor passes the list a tokenizer last gave it back as `previousTokens`, so the
 * list identifies the document and its state: a tokenizer object shared by several editors never
 * diffs one document against another's scan, and a caller that passes anything else gets a full
 * scan. Thread-safe by replacing an immutable list; a lost update only costs a full scan.
 */
internal class ScanCache<S : Any>(private val capacity: Int = 4) {
    @Volatile
    private var entries: List<Pair<List<Token>, S>> = emptyList()

    fun find(result: List<Token>): S? = entries.firstOrNull { it.first === result }?.second

    fun remember(result: List<Token>, scan: S) {
        entries = (listOf(result to scan) + entries).take(capacity)
    }
}

/** How far before an edit a rescan starts, for rules whose match looks ahead into the edited text. */
internal const val RESCAN_LOOKBACK = 256
