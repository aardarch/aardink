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
package com.aardarch.aardink.ui.view

import com.aardarch.aardink.core.CodeDocument

/**
 * What the indent and bracket-pair guides need for the lines [firstLine] to [lastLine]: a window
 * around the view, so that scrolling inside it asks for nothing new. Worked out once per change of
 * the text, its tokens or the window, never per frame, and never for the whole document.
 *
 * @property levels Each line's indentation in levels of [tabSize] columns, rounded up; a blank line
 *   takes its neighbours', as VS Code's guides do.
 * @property pairs The bracket pairs that open and close on different lines and reach into the window.
 */
internal class Guides(
    val firstLine: Int,
    val lastLine: Int,
    val tabSize: Int,
    private val levels: IntArray,
    private val columns: IntArray,
    val pairs: List<BracketGuide>,
) {
    /** [line]'s level, 0 outside the window. */
    fun level(line: Int): Int = if (line in firstLine..lastLine) levels[line - firstLine] else 0

    /** [line]'s indentation in columns, 0 outside the window. */
    fun indentColumns(line: Int): Int = if (line in firstLine..lastLine) columns[line - firstLine] else 0

    /** Whether [from]..[to] still lies well inside the window, so that it need not be worked out again. */
    fun covers(from: Int, to: Int, lineCount: Int): Boolean =
        (firstLine == 0 || from - firstLine >= MARGIN / 2) && (lastLine == lineCount - 1 || lastLine - to >= MARGIN / 2)

    /**
     * The level of the guide to highlight for a caret on [caretLine], and the lines it runs over: the
     * innermost block holding the caret. Null when the caret is at the outermost level.
     */
    fun activeGuide(caretLine: Int): ActiveGuide? {
        if (caretLine !in firstLine..lastLine) return null
        var level = level(caretLine)
        // A line that opens a block (the next is deeper) highlights the block it opens.
        val next = (caretLine + 1).takeIf { it <= lastLine }
        if (next != null && level(next) > level) level = level(next)
        if (level == 0) return null
        var from = caretLine
        while (from > firstLine && level(from - 1) >= level) from--
        var to = caretLine
        while (to < lastLine && level(to + 1) >= level) to++
        if (level(caretLine) < level) from = caretLine + 1
        return ActiveGuide(level - 1, from, to)
    }

    companion object {
        /** Lines worked out on either side of those shown. */
        const val MARGIN = 500

        /**
         * The guides for [shownFrom]..[shownTo] of [document], from [MARGIN] lines either side.
         * [forEachBracket] gives the code brackets of a line (not in strings or comments) in order.
         */
        fun compute(
            document: CodeDocument,
            shownFrom: Int,
            shownTo: Int,
            tabSize: Int,
            withPairs: Boolean,
            forEachBracket: (line: Int, action: (column: Int, open: Boolean) -> Unit) -> Unit,
        ): Guides {
            val lineCount = document.lineCount
            val first = (shownFrom - MARGIN).coerceAtLeast(0)
            val last = (shownTo + MARGIN).coerceIn(first, lineCount - 1)
            val size = last - first + 1
            val columns = IntArray(size)
            val blank = BooleanArray(size)
            for (i in 0 until size) {
                val text = document.lineText(first + i)
                var column = 0
                var index = 0
                while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
                    column = if (text[index] == '\t') (column / tabSize + 1) * tabSize else column + 1
                    index++
                }
                columns[i] = column
                blank[i] = index == text.length
            }
            val levels = IntArray(size) { (columns[it] + tabSize - 1) / tabSize }
            // A blank line: one level inside the line above when the line below is deeper, else the line below's.
            var i = 0
            while (i < size) {
                if (!blank[i]) {
                    i++
                    continue
                }
                var end = i
                while (end < size && blank[end]) end++
                val above = if (i > 0) levels[i - 1] else 0
                val below = if (end < size) levels[end] else 0
                val level = if (above < below) above + 1 else below
                for (j in i until end) {
                    levels[j] = level
                    columns[j] = level * tabSize
                }
                i = end
            }
            val pairs = if (withPairs) bracketPairs(first, last, shownFrom, shownTo, columns, forEachBracket) else emptyList()
            return Guides(first, last, tabSize, levels, columns, pairs)
        }

        private fun bracketPairs(
            first: Int,
            last: Int,
            shownFrom: Int,
            shownTo: Int,
            columns: IntArray,
            forEachBracket: (line: Int, action: (column: Int, open: Boolean) -> Unit) -> Unit,
        ): List<BracketGuide> {
            val pairs = ArrayList<BracketGuide>()
            val openLines = IntArrayList()
            for (line in first..last) {
                forEachBracket(line) { _, open ->
                    if (open) {
                        openLines.add(line)
                    } else if (openLines.size > 0) {
                        val opened = openLines.removeLast()
                        val depth = openLines.size
                        if (opened < line && line > shownFrom && opened < shownTo) {
                            val column = minOf(columns[opened - first], columns[line - first])
                            pairs += BracketGuide(opened + 1, line - 1, column, depth)
                        }
                    }
                }
            }
            return pairs.filter { it.fromLine <= it.toLine }
        }
    }
}

/** A guide down the lines [fromLine]..[toLine] between a bracket pair, at [column], [depth] brackets deep. */
internal data class BracketGuide(val fromLine: Int, val toLine: Int, val column: Int, val depth: Int)

/** The indent guide at [level] (0 at the left) highlighted on [fromLine]..[toLine]. */
internal data class ActiveGuide(val level: Int, val fromLine: Int, val toLine: Int)

/** A growable list of ints, so a stack of lines costs no boxing. */
private class IntArrayList {
    private var items = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun removeLast(): Int = items[--size]
}
