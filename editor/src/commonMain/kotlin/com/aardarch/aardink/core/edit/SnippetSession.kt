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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.text.DocumentChange
import com.aardarch.aardink.core.text.DocumentChangeListener

/**
 * A snippet being filled in, as Monaco's snippet session: Tab and Shift+Tab step through its tab
 * stops, selecting every place the current one occurs, until the final stop ends it.
 *
 * The stops are document ranges kept in step with every edit (the session listens to the
 * document). The current stop's ranges grow when text is typed at either edge, so typing into an
 * empty `$1` or at the end of a placeholder stays inside it; the other stops never grow at their
 * edges, so text typed next to them is not theirs.
 *
 * @param base Where [snippet]'s text starts in the document.
 */
internal class SnippetSession(base: Int, snippet: ExpandedSnippet) : DocumentChangeListener {

    private class Stop(val index: Int, val starts: IntArray, val ends: IntArray, val choices: List<String>?)

    private val stops = snippet.stops.map { stop ->
        Stop(
            stop.index,
            IntArray(stop.ranges.size) { base + stop.ranges[it].first },
            IntArray(stop.ranges.size) { base + stop.ranges[it].last + 1 },
            stop.choices,
        )
    }

    /** Which of the stops is current: an index into Tab's order. */
    private var current = 0

    /** Bumped when the current stop changes, so what draws the stops observes it. */
    var version by mutableIntStateOf(0)
        private set

    /** The document was replaced: the stops mean nothing any more. */
    var isBroken = false
        private set

    /** Whether the current stop is the last one, `$0`. */
    val isAtFinal: Boolean get() = stops[current].index == 0

    /** The current stop's ranges, its first occurrence first. */
    fun currentRanges(): List<TextRange> {
        val stop = stops[current]
        return List(stop.starts.size) { TextRange(stop.starts[it], stop.ends[it]) }
    }

    /** Every stop's ranges but the final one's, to highlight while the snippet is being filled in. */
    fun placeholderRanges(): List<TextRange> = stops.filter { it.index != 0 }.flatMap { stop ->
        List(stop.starts.size) { TextRange(stop.starts[it], stop.ends[it]) }
    }

    /** The current stop's choices, when it is a `${1|a,b|}` stop. */
    val currentChoices: List<String>? get() = stops[current].choices

    /** The current stop selected: every range of it, the first primary. */
    fun selections(): SelectionSet = SelectionSet.of(currentRanges())

    /** Whether [offset] is in (or at an edge of) one of the current stop's ranges. */
    fun contains(offset: Int): Boolean {
        val stop = stops[current]
        for (i in stop.starts.indices) if (offset in stop.starts[i]..stop.ends[i]) return true
        return false
    }

    /**
     * Moves to the next stop ([forward]) or the previous one. Returns false, staying put, when
     * there is no previous one.
     */
    fun move(forward: Boolean): Boolean {
        val target = if (forward) current + 1 else current - 1
        if (target !in stops.indices) return false
        current = target
        version++
        return true
    }

    override fun onDocumentChange(change: DocumentChange) {
        if (change.isReset) {
            isBroken = true
            return
        }
        for ((i, stop) in stops.withIndex()) {
            val active = i == current
            for (r in stop.starts.indices) {
                val start = map(stop.starts[r], change, movesWithInsertion = !active)
                val end = map(stop.ends[r], change, movesWithInsertion = active || stop.starts[r] == stop.ends[r])
                stop.starts[r] = start
                stop.ends[r] = maxOf(end, start)
            }
        }
    }

    /** [offset] after [change]; one exactly where text was inserted moves along when [movesWithInsertion]. */
    private fun map(offset: Int, change: DocumentChange, movesWithInsertion: Boolean): Int {
        val at = change.offset
        if (change.deletedLength > 0) {
            val deletedEnd = at + change.deletedLength
            return when {
                offset <= at -> offset
                offset >= deletedEnd -> offset - change.deletedLength
                else -> at
            }
        }
        return if (offset > at || (offset == at && movesWithInsertion)) offset + change.insertedLength else offset
    }
}
