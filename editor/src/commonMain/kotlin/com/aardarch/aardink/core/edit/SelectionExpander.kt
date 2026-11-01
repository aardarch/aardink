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

    /** The innermost pair of brackets around `[a, b)`, as the offsets of the two brackets, or null. */
    private fun bracketsAround(text: CharSequence, a: Int, b: Int): Pair<Int, Int>? {
        var depth = 0
        var open = -1
        var i = a - 1
        val stop = (a - MAX_SCAN).coerceAtLeast(0)
        while (i >= stop) {
            val c = text[i]
            if (c in CLOSERS) {
                depth++
            } else if (c in OPENERS) {
                if (depth == 0) {
                    open = i
                    break
                }
                depth--
            }
            i--
        }
        if (open < 0) return null
        val wanted = CLOSERS[OPENERS.indexOf(text[open])]
        depth = 0
        var j = b
        val limit = (b + MAX_SCAN).coerceAtMost(text.length)
        while (j < limit) {
            val c = text[j]
            if (c in OPENERS) {
                depth++
            } else if (c in CLOSERS) {
                if (depth == 0) return if (c == wanted) open to j else null
                depth--
            }
            j++
        }
        return null
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
