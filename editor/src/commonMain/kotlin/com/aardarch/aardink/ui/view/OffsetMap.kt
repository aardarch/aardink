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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle

/**
 * Text shown on a line that is not in the document, before the character at [column]. It is
 * spliced into the line's layout as real characters, and a line break in [text] is a hard break
 * in the same paragraph, so that it adds a row to the line.
 */
@Immutable
internal data class Injection(
    val column: Int,
    val text: String,
    val style: SpanStyle? = null,
    /**
     * Which side of this text a caret at [column] is on: after it for text that belongs with what
     * comes before it (a row above the line), before it for what follows the caret (ghost text).
     */
    val caretAfter: Boolean = false,
    /** What a click on this text is reported with; null when a click goes to the text under it. */
    val id: Any? = null,
) {
    /** Whether this is whole rows above the line's text, as a code lens is. */
    val isRowAbove: Boolean get() = column == 0 && caretAfter && text.endsWith('\n')

    companion object {
        /** A row of [text] above a line. */
        fun rowAbove(text: String, style: SpanStyle? = null, id: Any? = null): Injection =
            Injection(0, text + "\n", style, caretAfter = true, id = id)
    }
}

/**
 * Everything injected into one line, in layout order: by column, and at one column the text that
 * comes before the caret first. Compared by value: it is part of what a cached layout shows.
 */
@Immutable
internal class LineInjections private constructor(val items: List<Injection>) {

    /** Rows the line breaks in the text add to the line. */
    val addedRows: Int = items.sumOf { injection -> injection.text.count { it == '\n' } }

    /** How many of [addedRows] are above the line's text. */
    val rowsAbove: Int = items.sumOf { injection -> if (injection.isRowAbove) injection.text.count { it == '\n' } else 0 }

    /** Characters that share rows with the line's text, for estimating how it wraps. */
    val inlineChars: Int = items.sumOf { if (it.isRowAbove) 0 else it.text.length }

    private val hash = items.hashCode()

    /**
     * These injections as a line of [text] can show them, whose first [indent] characters are
     * laid out as hidden glyphs: one past the end of the text is dropped, and one inside the
     * indentation moves to its end, except rows above the line, which come before it. A column
     * between the halves of a surrogate pair moves before the pair. Injected text past
     * [maxChars] is dropped, so that a long hint cannot cost what a long line is cut to avoid.
     * Null when nothing is left.
     */
    fun fitted(text: CharSequence, indent: Int, maxChars: Int): LineInjections? {
        var changed = false
        var budget = maxChars
        val kept = ArrayList<Injection>(items.size)
        for (item in items) {
            val column = fittedColumn(item, text, indent)
            if (column == null || item.text.length > budget) {
                changed = true
                continue
            }
            budget -= item.text.length
            if (column != item.column) changed = true
            kept += if (column == item.column) item else item.copy(column = column)
        }
        return when {
            !changed -> this
            kept.isEmpty() -> null
            else -> of(kept)
        }
    }

    private fun fittedColumn(item: Injection, text: CharSequence, indent: Int): Int? {
        val column = item.column
        return when {
            column < 0 || column > text.length -> null
            item.isRowAbove -> column
            column < indent -> indent
            column in 1 until text.length && text[column - 1].isHighSurrogate() && text[column].isLowSurrogate() -> column - 1
            else -> column
        }
    }

    override fun equals(other: Any?): Boolean = other is LineInjections && other.hash == hash && other.items == items

    override fun hashCode(): Int = hash

    companion object {
        fun of(items: List<Injection>): LineInjections =
            LineInjections(items.sortedWith(compareBy<Injection> { it.column }.thenBy { if (it.caretAfter) 0 else 1 }))

        fun of(vararg items: Injection): LineInjections = of(items.asList())
    }
}

/**
 * Between one line's document columns and the positions of its layout, which also holds injected
 * text. Three positions can differ at a column with text injected there, as `x|gy` shows for
 * ghost text `g` at column 1:
 *
 * - [toLayout] is the caret's: on the side of each injection that [Injection.caretAfter] says.
 * - [charIndex] is the character's own, after everything injected at its column.
 * - [rangeEnd] is the end of the character before, before everything injected at the column.
 *
 * So a range of columns goes from [charIndex] to [rangeEnd], and never starts or ends inside
 * injected text. [toDocument] goes back: an injected position snaps to the column it is at. A
 * position past the document's text, as a fold's placeholder is, stays past it by as much.
 *
 * With nothing injected, [Identity] answers with what it is given and allocates nothing.
 */
@Immutable
internal class OffsetMap private constructor(private val items: List<Injection>, val length: Int) {

    private val size = items.size
    private val columns = IntArray(size) { items[it].column }

    /** Where each injection starts in the layout, and one past where it ends. */
    private val starts = IntArray(size)
    private val ends = IntArray(size)

    init {
        var shift = 0
        for (i in 0 until size) {
            starts[i] = columns[i] + shift
            shift += items[i].text.length
            ends[i] = columns[i] + shift
        }
    }

    val isIdentity: Boolean get() = size == 0

    /**
     * Whether any injected text has a line break, so that it adds rows of its own. This does not say
     * whether every row holds document text: under soft wrap, text with no break can fill a row.
     */
    val hasBreaks: Boolean = items.any { '\n' in it.text }

    /** Where a caret at [column] is in the layout. */
    fun toLayout(column: Int): Int {
        if (size == 0) return column
        var i = lowerBound(column)
        while (i < size && columns[i] == column && items[i].caretAfter) i++
        return column + shiftBefore(i)
    }

    /** Where the character at [column] is in the layout; at the end of the text, the caret's place. */
    fun charIndex(column: Int): Int = when {
        size == 0 -> column
        column >= length -> toLayout(column)
        else -> column + shiftBefore(upperBound(column))
    }

    /** One past the character before [column] in the layout: where a range ending at [column] ends. */
    fun rangeEnd(column: Int): Int = if (size == 0) column else column + shiftBefore(lowerBound(column))

    /** The document column of layout position [position]: an injected position gives its column. */
    fun toDocument(position: Int): Int {
        if (size == 0) return position
        // The first injection that does not end at or before the position.
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] <= position) lo = mid + 1 else hi = mid
        }
        return if (lo < size && starts[lo] <= position) columns[lo] else position - shiftBefore(lo)
    }

    /** The injection the character at layout index [index] belongs to, or null for a document character. */
    fun injectionAt(index: Int): Injection? {
        if (size == 0) return null
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] <= index) lo = mid + 1 else hi = mid
        }
        return if (lo < size && starts[lo] <= index) items[lo] else null
    }

    /**
     * Calls [action] with the layout range of every run of document characters in columns
     * [from, to): the range cut at each injection, so that a span over the runs leaves injected
     * text its own style. With [cut] it is cut only at the columns [cut] accepts.
     */
    fun forEachRun(from: Int, to: Int, cut: (column: Int) -> Boolean = { true }, action: (start: Int, end: Int) -> Unit) {
        var start = from
        var i = upperBound(from)
        while (i < size && columns[i] < to) {
            val column = columns[i]
            val next = upperBound(column)
            if (cut(column)) {
                action(charIndex(start), rangeEnd(column))
                start = column
            }
            i = next
        }
        action(charIndex(start), rangeEnd(to))
    }

    /** Calls [action] with the document column of every injection, once per column. */
    fun forEachColumn(action: (Int) -> Unit) {
        var i = 0
        while (i < size) {
            action(columns[i])
            i = upperBound(columns[i])
        }
    }

    /**
     * The visual column after what is injected at document [column], from visual column [visual]
     * before it. A tab reaches the next stop from there, so what is injected counts.
     */
    fun visualColumnAfter(column: Int, visual: Int): Int {
        if (size == 0) return visual
        var result = visual
        for (i in lowerBound(column) until upperBound(column)) {
            val text = items[i].text
            val lastBreak = text.lastIndexOf('\n')
            result = if (lastBreak >= 0) text.length - lastBreak - 1 else result + text.length
        }
        return result
    }

    private fun shiftBefore(index: Int): Int = if (index == 0) 0 else ends[index - 1] - columns[index - 1]

    /** The first injection at or after [column]. */
    private fun lowerBound(column: Int): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (columns[mid] < column) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The first injection after [column]. */
    private fun upperBound(column: Int): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (columns[mid] <= column) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Nothing injected: every column is its own position. */
        val Identity = OffsetMap(emptyList(), 0)

        /** The map of [injections] (already [LineInjections.fitted]) in a line of [length] columns. */
        fun of(injections: LineInjections, length: Int): OffsetMap = OffsetMap(injections.items, length)
    }
}
