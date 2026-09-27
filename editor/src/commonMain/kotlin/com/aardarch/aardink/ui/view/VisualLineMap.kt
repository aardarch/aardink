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

/**
 * How many rows each document line takes on screen: one, more when soft wrap breaks it, none when
 * it is hidden inside a fold. A Fenwick tree over those counts maps a row to its line and a line
 * to its first row in O(log n), so scrolling, hit-testing and the gutter never walk the document.
 *
 * Wrapped row counts start as estimates and are corrected as lines are laid out
 * ([setRows]); structural edits splice the counts ([splice]) and rebuild the tree in O(n).
 */
internal class VisualLineMap {
    private var rows = IntArray(0)

    /** 1-based Fenwick tree over [rows]. */
    private var tree = IntArray(1)

    var lineCount: Int = 0
        private set

    var totalRows: Int = 0
        private set

    /** Rebuilds the map for [lineCount] lines, asking [rowsOf] for each one. */
    fun reset(lineCount: Int, rowsOf: (Int) -> Int) {
        this.lineCount = lineCount
        rows = IntArray(lineCount) { rowsOf(it) }
        rebuildTree()
    }

    /**
     * After an edit that merged [removed] lines into [line] and added [added] new lines after it:
     * the counts of the other lines move along, and [rowsOf] is asked for [line] and the new ones.
     */
    fun splice(line: Int, removed: Int, added: Int, rowsOf: (Int) -> Int) {
        if (removed == 0 && added == 0) {
            setRows(line, rowsOf(line))
            return
        }
        val newCount = lineCount - removed + added
        val next = IntArray(newCount)
        rows.copyInto(next, 0, 0, line)
        for (i in line..line + added) next[i] = rowsOf(i)
        rows.copyInto(next, line + added + 1, line + removed + 1, lineCount)
        rows = next
        lineCount = newCount
        rebuildTree()
    }

    fun rowsOf(line: Int): Int = rows[line]

    fun isHidden(line: Int): Boolean = rows[line] == 0

    /** Sets the number of rows [line] takes, and returns how many rows that added (or removed). */
    fun setRows(line: Int, count: Int): Int {
        val delta = count - rows[line]
        if (delta == 0) return 0
        rows[line] = count
        var i = line + 1
        while (i <= lineCount) {
            tree[i] += delta
            i += i and -i
        }
        totalRows += delta
        return delta
    }

    /** The row [line] starts at: the number of rows above it. */
    fun firstRowOf(line: Int): Int {
        var sum = 0
        var i = line.coerceIn(0, lineCount)
        while (i > 0) {
            sum += tree[i]
            i -= i and -i
        }
        return sum
    }

    /**
     * The line [row] belongs to. Hidden lines own no rows, so they are never returned; a row
     * past the end gives the last line that has one.
     */
    fun lineAtRow(row: Int): Int {
        if (lineCount == 0) return 0
        if (row >= totalRows) return if (totalRows == 0) 0 else lineAtRow(totalRows - 1)
        var position = 0
        var remaining = row.coerceAtLeast(0)
        var step = 1 shl (31 - lineCount.countLeadingZeroBits())
        while (step > 0) {
            val next = position + step
            if (next <= lineCount && tree[next] <= remaining) {
                position = next
                remaining -= tree[next]
            }
            step = step shr 1
        }
        return position.coerceAtMost(lineCount - 1)
    }

    /** [line] if it is shown, else the shown line above it: the fold start that hides it. */
    fun visibleLineAtOrBefore(line: Int): Int {
        val clamped = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        if (lineCount == 0 || !isHidden(clamped)) return clamped
        val row = firstRowOf(clamped)
        return if (row == 0) lineAtRow(0) else lineAtRow(row - 1)
    }

    private fun rebuildTree() {
        val built = IntArray(lineCount + 1)
        var total = 0
        for (i in 1..lineCount) {
            built[i] += rows[i - 1]
            total += rows[i - 1]
            val parent = i + (i and -i)
            if (parent <= lineCount) built[parent] += built[i]
        }
        tree = built
        totalRows = total
    }
}
