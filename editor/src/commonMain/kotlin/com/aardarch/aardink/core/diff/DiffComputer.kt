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
package com.aardarch.aardink.core.diff

import kotlin.time.TimeSource

/**
 * The differences between two texts, line by line and then character by character within the lines
 * that changed, for the diff editor. Lines are compared with Myers' diff in linear space, after the
 * lines the texts start and end with in common are set aside.
 */
internal object DiffComputer {

    private val origin = TimeSource.Monotonic.markNow()
    private val monotonicClock: () -> Long = { origin.elapsedNow().inWholeMilliseconds }

    /** Characters apart that two character changes must be, at least, not to be one change. */
    private const val MIN_EQUAL_RUN = 3

    /**
     * Diffs [original] against [modified]. [clock] returns milliseconds and is read to enforce
     * [DiffOptions.maxComputationTimeMs]; past it the unresolved lines are reported as they are.
     */
    fun compute(
        original: CharSequence,
        modified: CharSequence,
        options: DiffOptions = DiffOptions(),
        clock: () -> Long = monotonicClock,
    ): DiffResult {
        val startedAt = clock()
        var tripped = false
        val expired: () -> Boolean = {
            if (!tripped && options.maxComputationTimeMs > 0 && clock() - startedAt >= options.maxComputationTimeMs) tripped = true
            tripped
        }
        val a = Lines(original)
        val b = Lines(modified)
        val ids = HashMap<String, Int>()
        val aIds = a.ids(ids, options.ignoreTrimWhitespace)
        val bIds = b.ids(ids, options.ignoreTrimWhitespace)
        val lineDiff = LineDiff.diff(aIds, bIds, expired = expired)!!
        var hitTimeout = lineDiff.unresolved
        val changes = ArrayList<LineChange>(lineDiff.hunks.size)
        for (hunk in lineDiff.hunks) {
            var charChanges: List<CharChange>? = null
            if (options.computeCharChanges && hunk.aStart < hunk.aEnd && hunk.bStart < hunk.bEnd && !expired() &&
                a.length(hunk.aStart, hunk.aEnd) <= options.maxCharChangeChars &&
                b.length(hunk.bStart, hunk.bEnd) <= options.maxCharChangeChars
            ) {
                val left = Block(a, hunk.aStart, hunk.aEnd)
                val right = Block(b, hunk.bStart, hunk.bEnd)
                val chars = LineDiff.diff(left.chars, right.chars, expired = expired)!!
                if (chars.unresolved) hitTimeout = true
                charChanges = charChanges(left, right, chars.hunks, options.ignoreTrimWhitespace)
            }
            changes += LineChange(hunk.aStart, hunk.aEnd, hunk.bStart, hunk.bEnd, charChanges)
        }
        val identical = changes.isEmpty() && (!options.ignoreTrimWhitespace || sameLines(a, b))
        return DiffResult(changes, identical, hitTimeout)
    }

    private fun sameLines(a: Lines, b: Lines): Boolean {
        if (a.count != b.count) return false
        for (i in 0 until a.count) {
            if (a.content(i, false) != b.content(i, false)) return false
        }
        return true
    }

    /** The character changes of two blocks, with those a few characters apart merged and, if asked, the whitespace at line edges dropped. */
    private fun charChanges(left: Block, right: Block, hunks: List<LineHunk>, dropEdgeWhitespace: Boolean): List<CharChange> {
        val merged = ArrayList<LineHunk>()
        for (hunk in hunks) {
            val last = merged.lastOrNull()
            if (last != null && hunk.aStart - last.aEnd < MIN_EQUAL_RUN) {
                merged[merged.size - 1] = LineHunk(last.aStart, hunk.aEnd, last.bStart, hunk.bEnd)
            } else {
                merged += hunk
            }
        }
        val result = ArrayList<CharChange>(merged.size)
        for (hunk in merged) {
            if (dropEdgeWhitespace && left.isEdgeWhitespace(hunk.aStart, hunk.aEnd) &&
                right.isEdgeWhitespace(hunk.bStart, hunk.bEnd)
            ) {
                continue
            }
            result += CharChange(
                left.start(hunk.aStart),
                left.end(hunk.aStart, hunk.aEnd),
                right.start(hunk.bStart),
                right.end(hunk.bStart, hunk.bEnd),
            )
        }
        return result
    }

    /** A text cut at its line feeds: line i starts at `start(i)` and its line break is at `start(i + 1) - 1`. */
    private class Lines(val text: CharSequence) {
        val count: Int
        private val starts: IntArray

        init {
            var breaks = 0
            for (i in 0 until text.length) if (text[i] == '\n') breaks++
            count = breaks + 1
            starts = IntArray(count + 1)
            var line = 1
            for (i in 0 until text.length) if (text[i] == '\n') starts[line++] = i + 1
            starts[count] = text.length + 1
        }

        fun start(line: Int): Int = starts[line]

        /** Where the content of [line] ends: before its line break, and before a carriage return that precedes it. */
        fun end(line: Int): Int {
            var end = starts[line + 1] - 1
            if (end > starts[line] && text[end - 1] == '\r') end--
            return end
        }

        fun content(line: Int, trim: Boolean): String {
            var from = starts[line]
            var to = end(line)
            if (trim) {
                while (from < to && text[from].isWhitespace()) from++
                while (to > from && text[to - 1].isWhitespace()) to--
            }
            return text.subSequence(from, to).toString()
        }

        fun ids(table: HashMap<String, Int>, trim: Boolean): IntArray =
            IntArray(count) { line -> table.getOrPut(content(line, trim)) { table.size } }

        /** An upper bound of the characters in lines [from, to) once they are joined with line feeds. */
        fun length(from: Int, to: Int): Int = end(to - 1) - starts[from]
    }

    /**
     * Lines [from, to) as the characters of their content joined with line feeds, each with its
     * offset in the whole text. A carriage return before a line break is left out.
     */
    private class Block(lines: Lines, from: Int, to: Int) {
        val chars: IntArray
        private val offsets: IntArray
        private val textEnd: Int = lines.end(to - 1)

        init {
            var size = to - from - 1
            for (line in from until to) size += lines.end(line) - lines.start(line)
            chars = IntArray(size)
            offsets = IntArray(size)
            var at = 0
            for (line in from until to) {
                if (line > from) {
                    chars[at] = '\n'.code
                    offsets[at++] = lines.start(line) - 1
                }
                for (i in lines.start(line) until lines.end(line)) {
                    chars[at] = lines.text[i].code
                    offsets[at++] = i
                }
            }
        }

        fun start(index: Int): Int = if (index < chars.size) offsets[index] else textEnd

        fun end(from: Int, to: Int): Int = if (to > from) offsets[to - 1] + 1 else start(from)

        /** True when [from, to) holds only whitespace of one line, all of what leads it or all of what trails it. */
        fun isEdgeWhitespace(from: Int, to: Int): Boolean {
            if (from == to) return true
            for (i in from until to) if (chars[i] == '\n'.code || !chars[i].toChar().isWhitespace()) return false
            var lineStart = from
            while (lineStart > 0 && chars[lineStart - 1] != '\n'.code) lineStart--
            var leading = true
            for (i in lineStart until from) if (!chars[i].toChar().isWhitespace()) leading = false
            if (leading) return true
            var trailing = true
            var i = to
            while (i < chars.size && chars[i] != '\n'.code) {
                if (!chars[i].toChar().isWhitespace()) trailing = false
                i++
            }
            return trailing
        }
    }
}
