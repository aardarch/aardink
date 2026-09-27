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
 * The diagnostics on screen, kept on their text from one list to the next. Every edit moves their
 * ranges (and line numbers) along, as Monaco moves its markers, so a squiggle stays under its word
 * while the user types above it rather than drifting until the next list arrives. Replacing the
 * whole text drops them: they describe text that is gone.
 */
@Stable
internal class DiagnosticsTracker(private val document: CodeDocument) : DocumentChangeListener {

    /** The list as it was given, before any edit moved it; null until the first. */
    var given: List<Diagnostic>? = null
        private set

    /** [given], moved along with the edits made since. */
    var diagnostics by mutableStateOf<List<Diagnostic>>(emptyList())
        private set

    /** Shows [list], whose ranges address the document as it is now. */
    fun replace(list: List<Diagnostic>) {
        given = list
        diagnostics = list
    }

    override fun onDocumentChange(change: DocumentChange) {
        val current = diagnostics
        if (current.isEmpty()) return
        if (change.isReset) {
            diagnostics = emptyList()
            return
        }
        diagnostics = current.map { diagnostic ->
            val range = mapRange(diagnostic.range, change)
            if (range == diagnostic.range) {
                diagnostic
            } else {
                diagnostic.copy(range = range, lineNumber = document.offsetToLineCol(range.first).first)
            }
        }
    }

    companion object {
        /**
         * [range] (inclusive of its last character) after [change]. Text inserted before it or at
         * its start moves it along; text inserted inside it grows it, and at its end does not;
         * deleted text shrinks it. It keeps at least one character, as a squiggle needs one, so a
         * range whose text was deleted entirely marks the character after the deletion.
         */
        fun mapRange(range: IntRange, change: DocumentChange): IntRange {
            val start = mapOffset(range.first, change, movesWithInsertion = true)
            val end = mapOffset(maxOf(range.last + 1, range.first), change, movesWithInsertion = false)
            return start..maxOf(end - 1, start)
        }

        /** [offset] after [change]; one exactly at an insertion moves along when [movesWithInsertion]. */
        private fun mapOffset(offset: Int, change: DocumentChange, movesWithInsertion: Boolean): Int {
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
