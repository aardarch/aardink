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
package com.aardarch.aardink.core.edit

/**
 * What Shift+Alt+Right grows a selection to when the language service has no selection ranges:
 * the word at the caret, then the inside of the string it is in, the string with its quotes, the
 * inside of the brackets around it, the brackets too, the line's text, the whole line, and the
 * document, each strictly larger than the one before. Ranges are `[start, end)` offsets.
 *
 * The scans for strings and brackets look no further than [MAX_SCAN] characters either way, so a
 * huge document costs no more than a small one.
 */
internal object SelectionExpander {

    const val MAX_SCAN = 64 * 1024

    /** The ranges around `[start, end)` in [text], innermost first, each strictly larger than the last. */
    fun ranges(text: CharSequence, start: Int, end: Int): List<IntRange> {
        val length = text.length
        val a = start.coerceIn(0, length)
        val b = end.coerceIn(a, length)
        val candidates = ArrayList<IntRange>()
        val word = TextNavigator.wordAt(text, a)
        if (!word.isEmpty()) candidates += word
        stringAround(text, a, b)?.let { (inside, whole) ->
            candidates += inside
            candidates += whole
        }
        var from = a
        var to = b
        // Each pair of brackets around the selection, inside then with the brackets, outwards.
        repeat(MAX_BRACKET_LEVELS) {
            val pair = bracketsAround(text, from, to) ?: return@repeat
            candidates += (pair.first + 1) until pair.second
            candidates += pair.first..pair.second
            from = pair.first
            to = pair.second + 1
        }
        val lineStart = lineStart(text, a)
        val lineEnd = lineEnd(text, b)
        val trimmedStart = (lineStart until lineEnd).firstOrNull { !text[it].isWhitespace() } ?: lineStart
        val trimmedEnd = ((lineEnd - 1) downTo lineStart).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: lineEnd
        if (trimmedEnd > trimmedStart) candidates += trimmedStart until trimmedEnd
        candidates += lineStart until (if (lineEnd < length) lineEnd + 1 else lineEnd)
        candidates += 0 until length
        // Keep those holding the selection, smallest first, each strictly larger than the last.
        val holding = candidates
            .filter { it.first <= a && it.last + 1 >= b }
            .sortedWith(compareBy({ it.last + 1 - it.first }, { it.first }))
        val out = ArrayList<IntRange>()
        for (range in holding) {
            val last = out.lastOrNull()
            if (last == null) {
                if (range.first != a || range.last + 1 != b) out += range
            } else if (range.first <= last.first && range.last >= last.last && (range.first < last.first || range.last > last.last)) {
                out += range
            }
        }
        return out
    }

    /** The string literal around `[a, b)` on its line: its inside and the whole literal, or null. */
    private fun stringAround(text: CharSequence, a: Int, b: Int): Pair<IntRange, IntRange>? {
        val lineStart = lineStart(text, a)
        val lineEnd = lineEnd(text, b)
        var i = lineStart
        while (i < lineEnd) {
            val quote = text[i]
            if (quote != '"' && quote != '\'' && quote != '`') {
                i++
                continue
            }
            var j = i + 1
            while (j < lineEnd && text[j] != quote) j += if (text[j] == '\\') 2 else 1
            if (j >= lineEnd) return null
            if (i < a && j >= b) return (i + 1 until j) to (i..j)
            i = j + 1
        }
        return null
    }

    /**
     * The innermost pair of brackets around `[a, b)`, as the offsets of the two brackets, or null.
     * A bracket in a string literal is text, not structure: in `f("(", arg)` the call's are found.
     */
    private fun bracketsAround(text: CharSequence, a: Int, b: Int): Pair<Int, Int>? {
        var depth = 0
        var open = -1
        val stop = (a - MAX_SCAN).coerceAtLeast(0)
        // Back a line at a time: which brackets are in strings is read from a line's start.
        var end = a
        while (open < 0 && end > stop) {
            val start = lineStart(text, end - 1).coerceAtLeast(stop)
            val brackets = codeBrackets(text, start, start, end)
            for (k in brackets.indices.reversed()) {
                val i = brackets[k]
                if (text[i] in CLOSERS) {
                    depth++
                } else if (depth == 0) {
                    open = i
                    break
                } else {
                    depth--
                }
            }
            end = start
        }
        if (open < 0) return null
        val wanted = CLOSERS[OPENERS.indexOf(text[open])]
        depth = 0
        val limit = (b + MAX_SCAN).coerceAtMost(text.length)
        for (j in codeBrackets(text, lineStart(text, b).coerceAtLeast(b - MAX_SCAN), b, limit)) {
            if (text[j] in OPENERS) {
                depth++
            } else {
                if (depth == 0) return if (text[j] == wanted) open to j else null
                depth--
            }
        }
        return null
    }

    /**
     * The offsets of the brackets in `[from, to)` outside string literals, the strings read from
     * [scanFrom] on, so one already open at [from] is known. A string is read as [stringAround]
     * reads one: closed by its own quote on its line, past backslash escapes; a quote with no
     * partner on its line opens none.
     */
    private fun codeBrackets(text: CharSequence, scanFrom: Int, from: Int, to: Int): List<Int> {
        val brackets = ArrayList<Int>()
        var i = scanFrom
        while (i < to) {
            val c = text[i]
            if (c == '"' || c == '\'' || c == '`') {
                val close = closingQuote(text, i)
                if (close >= 0) {
                    i = close + 1
                    continue
                }
            } else if (i >= from && (c in OPENERS || c in CLOSERS)) {
                brackets += i
            }
            i++
        }
        return brackets
    }

    /** The offset of the quote that closes the one at [open] on its line, or -1. */
    private fun closingQuote(text: CharSequence, open: Int): Int {
        val quote = text[open]
        var j = open + 1
        while (j < text.length && text[j] != '\n') {
            if (text[j] == quote) return j
            j += if (text[j] == '\\') 2 else 1
        }
        return -1
    }

    private fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"
    private const val MAX_BRACKET_LEVELS = 8
}
