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
package com.aardarch.aardink.core

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * Holds the set of currently-folded ranges for a [CodeEditorState].
 *
 * - [foldableRanges] is the full set of foldable regions in the document — recomputed when text
 *   changes. The renderer reads this to draw fold triangles in the gutter.
 * - The internal map of folded `startLine` → [FoldRange] is what the renderer uses to decide which
 *   lines to hide or replace with a placeholder.
 *
 * Folded entries that no longer exist after a text edit (e.g. the user deleted the opening tag) are
 * pruned automatically by [updateFoldableRanges].
 */
@Stable
class FoldState {

    private val foldedMap = SnapshotStateMap<Int, FoldRange>()

    var foldableRanges by mutableStateOf<List<FoldRange>>(emptyList())
        private set

    val foldedCount: Int get() = foldedMap.size

    fun isFoldable(startLine: Int): Boolean = foldableRanges.any { it.startLine == startLine }

    fun isFolded(startLine: Int): Boolean = startLine in foldedMap

    fun rangeAt(startLine: Int): FoldRange? = foldableRanges.firstOrNull { it.startLine == startLine }

    /** Returns the currently-folded ranges, in start-line order. */
    fun foldedRanges(): List<FoldRange> = foldedMap.values.sortedBy { it.startLine }

    fun toggle(startLine: Int) {
        if (startLine in foldedMap) {
            foldedMap.remove(startLine)
        } else {
            rangeAt(startLine)?.let { foldedMap[startLine] = it }
        }
    }

    fun foldAll() {
        foldedMap.clear()
        foldableRanges.forEach { foldedMap[it.startLine] = it }
    }

    fun unfoldAll() {
        foldedMap.clear()
    }

    /**
     * Folds the ranges nested [level] deep, 1 being the outermost, as VS Code's "Fold Level n"
     * (Ctrl+K Ctrl+1 to 5); returns whether there were any.
     */
    fun foldLevel(level: Int): Boolean {
        val ranges = foldableRanges
        val enclosing = enclosingCounts(ranges)
        val atLevel = ranges.filterIndexed { i, _ -> enclosing[i] == level - 1 }
        for (range in atLevel) if (range.startLine !in foldedMap) foldedMap[range.startLine] = range
        return atLevel.isNotEmpty()
    }

    /**
     * How many other ranges of [ranges] enclose each one: start on or before it and end on or after
     * it, without being the same lines. Counted in one ordered pass over a Fenwick tree of end lines,
     * not by comparing every pair, so Fold Level stays fast with thousands of ranges.
     */
    private fun enclosingCounts(ranges: List<FoldRange>): IntArray {
        val counts = IntArray(ranges.size)
        if (ranges.isEmpty()) return counts
        val maxEnd = ranges.maxOf { it.endLine }.coerceAtLeast(0)
        // Indexed by maxEnd - endLine, so a prefix sum counts the ranges ending at or after a line.
        val tree = IntArray(maxEnd + 2)
        fun slot(endLine: Int) = maxEnd - endLine.coerceIn(0, maxEnd) + 1
        fun add(endLine: Int) {
            var i = slot(endLine)
            while (i < tree.size) {
                tree[i]++
                i += i and -i
            }
        }
        fun endingAtOrAfter(endLine: Int): Int {
            var i = slot(endLine)
            var sum = 0
            while (i > 0) {
                sum += tree[i]
                i -= i and -i
            }
            return sum
        }
        // Earlier starts first, and on one start the longer first: what encloses a range comes before it.
        val order = ranges.indices.sortedWith(compareBy<Int> { ranges[it].startLine }.thenByDescending { ranges[it].endLine })
        var g = 0
        while (g < order.size) {
            val first = ranges[order[g]]
            var end = g
            while (end < order.size && ranges[order[end]].startLine == first.startLine && ranges[order[end]].endLine == first.endLine) end++
            // Ranges over the same lines do not enclose each other: count the group before adding it.
            val count = endingAtOrAfter(first.endLine)
            for (k in g until end) counts[order[k]] = count
            for (k in g until end) add(first.endLine)
            g = end
        }
        return counts
    }

    /**
     * Folds the innermost unfolded range that holds [line] (Ctrl+Shift+[), as VS Code's "Fold":
     * one starting on [line] first. Returns whether one was folded.
     */
    fun foldAt(line: Int): Boolean {
        val range = foldableRanges
            .filter { it.startLine !in foldedMap && line in it.startLine..it.endLine }
            .minWithOrNull(compareBy<FoldRange> { if (it.startLine == line) 0 else 1 }.thenBy { it.lineCount })
            ?: return false
        foldedMap[range.startLine] = range
        return true
    }

    /** Unfolds the innermost folded range that holds [line] (Ctrl+Shift+]); returns whether one was. */
    fun unfoldAt(line: Int): Boolean {
        val range = foldedMap.values.filter { line in it.startLine..it.endLine }.minByOrNull { it.lineCount } ?: return false
        foldedMap.remove(range.startLine)
        return true
    }

    /**
     * Moves the ranges along with an edit that merged [removedLines] lines into [line] and added
     * [addedLines] lines after it, so what is folded stays folded, over the same text, until the
     * next [updateFoldableRanges]. A range whose first line was merged into another is dropped; one
     * the edit happened inside grows or shrinks with it.
     */
    internal fun onLinesChanged(line: Int, removedLines: Int, addedLines: Int) {
        val delta = addedLines - removedLines
        if (delta == 0) return
        fun shift(range: FoldRange): FoldRange? = when {
            range.endLine < line -> range

            range.startLine > line + removedLines -> range.copy(startLine = range.startLine + delta, endLine = range.endLine + delta)

            range.startLine > line -> null

            else -> {
                val end = if (range.endLine <= line + removedLines) line else range.endLine + delta
                if (end <= range.startLine) null else range.copy(endLine = end)
            }
        }
        foldableRanges = foldableRanges.mapNotNull(::shift)
        val folded = foldedMap.values.mapNotNull(::shift)
        foldedMap.clear()
        folded.forEach { foldedMap[it.startLine] = it }
    }

    /**
     * Replaces the foldable-ranges list and prunes stale folded entries.
     * Call this from a [LaunchedEffect] watching the document's textVersion.
     */
    fun updateFoldableRanges(newRanges: List<FoldRange>) {
        foldableRanges = newRanges
        val validStartLines = newRanges.map { it.startLine }.toSet()
        foldedMap.keys.toList().forEach { startLine ->
            if (startLine !in validStartLines) foldedMap.remove(startLine)
        }
    }
}
