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
package com.aardarch.aardink.core.text

/**
 * The offset at which each line starts, kept up to date edit by edit.
 *
 * Stored as a gap buffer of line starts. Entries before the gap hold absolute offsets; entries
 * after it hold their distance from the end of the document, which an edit earlier in the text
 * does not change. So an edit touches only the entries for the lines it adds or removes, plus the
 * entries the gap moves past when the edit point changes line, rather than rescanning the whole
 * text the way rebuilding the index would.
 *
 * Line 0 always starts at 0. Line starts are the offsets just after each '\n'.
 */
internal class LineIndex(text: CharSequence = "") {

    private var starts = IntArray(MIN_CAPACITY)
    private var gapStart = 0
    private var gapEnd = starts.size

    /** Length of the document the index describes; after-gap entries are relative to it. */
    private var documentLength = 0

    init {
        reset(text)
    }

    val lineCount: Int get() = starts.size - (gapEnd - gapStart)

    fun lineStart(line: Int): Int {
        if (line < 0 || line >= lineCount) throw IndexOutOfBoundsException("line $line, lineCount $lineCount")
        return if (line < gapStart) starts[line] else documentLength - starts[line + (gapEnd - gapStart)]
    }

    /** The line containing [offset]: the last line whose start is at or before it. */
    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStart(mid) <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Rebuilds the index for [text] from scratch. */
    fun reset(text: CharSequence) {
        gapStart = 0
        gapEnd = starts.size
        documentLength = text.length
        append(0)
        for (i in text.indices) {
            if (text[i] == '\n') append(i + 1)
        }
    }

    /**
     * Records that [text] was inserted at [offset]. Returns the line the insertion started on;
     * `text.count { it == '\n' }` new lines follow it.
     */
    fun onInsert(offset: Int, text: CharSequence): Int {
        val line = lineOf(offset)
        moveGap(line + 1)
        documentLength += text.length
        for (i in text.indices) {
            if (text[i] == '\n') append(offset + i + 1)
        }
        return line
    }

    /**
     * Records that the characters in [start, end) were deleted, given the index as it was before.
     * Returns the pair (first line touched, number of lines removed).
     */
    fun onDelete(start: Int, end: Int): Pair<Int, Int> {
        val firstLine = lineOf(start)
        val lastLine = lineOf(end)
        moveGap(firstLine + 1)
        val removed = lastLine - firstLine
        gapEnd += removed // the starts of lines whose preceding '\n' was deleted
        documentLength -= end - start
        return firstLine to removed
    }

    /** All line starts as a fresh array, for a read-only snapshot. */
    fun toArray(): IntArray = IntArray(lineCount) { lineStart(it) }

    private fun append(absoluteStart: Int) {
        if (gapStart == gapEnd) grow()
        starts[gapStart++] = absoluteStart
    }

    private fun grow() {
        val newSize = starts.size * 2
        val grown = IntArray(newSize)
        starts.copyInto(grown, 0, 0, gapStart)
        val tail = starts.size - gapEnd
        starts.copyInto(grown, newSize - tail, gapEnd, starts.size)
        gapEnd = newSize - tail
        starts = grown
    }

    /** Moves the gap so that it sits just before line [line]; converts the entries it passes. */
    private fun moveGap(line: Int) {
        if (line == gapStart) return
        val gap = gapEnd - gapStart
        if (line < gapStart) {
            // Entries [line, gapStart) move after the gap and become end-relative.
            for (i in gapStart - 1 downTo line) {
                starts[i + gap] = documentLength - starts[i]
            }
        } else {
            // Entries [gapEnd, line + gap) move before the gap and become absolute.
            for (i in gapEnd until line + gap) {
                starts[i - gap] = documentLength - starts[i]
            }
        }
        gapStart = line
        gapEnd = line + gap
    }

    private companion object {
        const val MIN_CAPACITY = 16
    }
}
