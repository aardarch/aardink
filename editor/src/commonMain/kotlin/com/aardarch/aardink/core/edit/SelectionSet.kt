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

import androidx.compose.ui.text.TextRange

/**
 * The editor's selections: one or more ranges, each with an anchor ([TextRange.start], where it
 * began) and an active end ([TextRange.end], where the caret is), so a range selected backwards has
 * `start > end`. The first is the primary one, as in Monaco: it is the one the text field shows,
 * the one IME composition happens at, and the one kept when the others are dropped.
 *
 * Always normalised: no two ranges overlap (touching carets and overlapping ranges merge), and
 * [ranges] after the first are in document order.
 */
internal class SelectionSet private constructor(val ranges: List<TextRange>) {

    val primary: TextRange get() = ranges[0]

    val isSingle: Boolean get() = ranges.size == 1

    /** Every range in document order, primary included. */
    val inDocumentOrder: List<TextRange> get() = ranges.sortedBy { it.min }

    /** Just the primary range. */
    fun collapsedToPrimary(): SelectionSet = if (isSingle) this else SelectionSet(listOf(primary))

    /** This set with every endpoint moved by [transform]; normalised again. */
    fun map(transform: (Int) -> Int): SelectionSet = of(ranges.map { TextRange(transform(it.start), transform(it.end)) })

    /** Every range clamped to [0, length]. */
    fun clampedTo(length: Int): SelectionSet = map { it.coerceIn(0, length) }

    override fun equals(other: Any?): Boolean = other is SelectionSet && other.ranges == ranges

    override fun hashCode(): Int = ranges.hashCode()

    override fun toString(): String = "SelectionSet$ranges"

    companion object {
        fun single(range: TextRange): SelectionSet = SelectionSet(listOf(range))

        fun caret(offset: Int): SelectionSet = single(TextRange(offset))

        /**
         * A normalised set from [ranges] (first = primary). Ranges that overlap, or carets at the
         * same place, merge into one; the merged range is primary if any part of it was.
         */
        fun of(ranges: List<TextRange>): SelectionSet {
            require(ranges.isNotEmpty()) { "a selection set needs at least one range" }
            if (ranges.size == 1) return SelectionSet(ranges)
            val sorted = ranges.withIndex().sortedWith(compareBy({ it.value.min }, { it.value.max }))
            val merged = ArrayList<Pair<TextRange, Boolean>>(ranges.size) // range, holds the primary
            for ((index, range) in sorted) {
                val last = merged.lastOrNull()
                // Overlapping, or a caret touching another range (or caret) at the same point.
                val overlaps = last != null &&
                    (range.min < last.first.max || (range.min == last.first.max && (range.collapsed || last.first.collapsed)))
                if (last != null && overlaps) {
                    val min = minOf(last.first.min, range.min)
                    val max = maxOf(last.first.max, range.max)
                    // Keep the direction of the range holding the primary, else of the earlier one.
                    val backwards = if (index == 0) range.reversed else last.first.reversed
                    merged[merged.size - 1] = (if (backwards) TextRange(max, min) else TextRange(min, max)) to (last.second || index == 0)
                } else {
                    merged.add(range to (index == 0))
                }
            }
            val primaryIndex = merged.indexOfFirst { it.second }
            val ordered = listOf(merged[primaryIndex].first) + merged.filterIndexed { i, _ -> i != primaryIndex }.map { it.first }
            return SelectionSet(ordered)
        }
    }
}
