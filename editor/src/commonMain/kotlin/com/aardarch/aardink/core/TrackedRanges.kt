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

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aardarch.aardink.core.text.DocumentChange
import com.aardarch.aardink.core.text.DocumentChangeListener

/**
 * A list of items that each address a range of the document, kept on their text from one list
 * to the next. Every edit moves their ranges along, as Monaco moves its markers and decorations,
 * so an item stays on its word while the user types above it. Replacing the whole text drops
 * them: they describe text that is gone.
 *
 * Diagnostics, decorations, document symbols and colour swatches share it. The work per edit is
 * proportional to the number of items, never to the size of the document.
 *
 * @param rangeOf the range (inclusive of its last character) an item addresses.
 * @param withRange the item with its range moved.
 * @param keepsOneCharacter whether a range keeps at least one character when its text is deleted,
 *   as a squiggle needs one. Otherwise it collapses to an empty range at the deletion.
 */
@Stable
internal open class TrackedRanges<T>(
    private val rangeOf: (T) -> IntRange,
    private val withRange: (T, IntRange) -> T,
    private val keepsOneCharacter: Boolean = true,
) : DocumentChangeListener {

    /** The list as it was given, before any edit moved it; null until the first. */
    var given: List<T>? = null
        private set

    /** [given], moved along with the edits made since. */
    var items by mutableStateOf<List<T>>(emptyList())
        private set

    /** Tracks [list], whose ranges address the document as it is now. */
    fun replace(list: List<T>) {
        given = list
        items = list
    }

    override fun onDocumentChange(change: DocumentChange) {
        val current = items
        if (current.isEmpty()) return
        if (change.isReset) {
            items = emptyList()
            return
        }
        var moved = false
        val next = current.map { item ->
            val range = rangeOf(item)
            val mapped = mapRange(range, change, keepsOneCharacter)
            if (mapped == range) {
                item
            } else {
                moved = true
                withRange(item, mapped)
            }
        }
        if (moved) items = next
    }

    companion object {
        /**
         * [range] (inclusive of its last character) after [change]. Text inserted before it or at
         * its start moves it along; text inserted inside it grows it, and at its end does not;
         * deleted text shrinks it. With [keepsOneCharacter] it keeps at least one character, so a
         * range whose text was deleted entirely marks the character after the deletion; without
         * it, such a range becomes empty (`at until at`).
         */
        fun mapRange(range: IntRange, change: DocumentChange, keepsOneCharacter: Boolean = true): IntRange {
            val start = mapOffset(range.first, change, movesWithInsertion = true)
            val end = mapOffset(maxOf(range.last + 1, range.first), change, movesWithInsertion = false)
            return if (keepsOneCharacter) start..maxOf(end - 1, start) else start until maxOf(end, start)
        }

        /** [offset] after [change]; one exactly at an insertion moves along when [movesWithInsertion]. */
        fun mapOffset(offset: Int, change: DocumentChange, movesWithInsertion: Boolean): Int {
            val at = change.offset
            if (change.deletedLength > 0) {
                val deletedEnd = at + change.deletedLength
                return when {
                    offset <= at -> offset
                    offset >= deletedEnd -> offset - change.deletedLength
                    else -> at
                }
            }
            return when {
                offset > at || (offset == at && movesWithInsertion) -> offset + change.insertedLength
                else -> offset
            }
        }
    }
}
