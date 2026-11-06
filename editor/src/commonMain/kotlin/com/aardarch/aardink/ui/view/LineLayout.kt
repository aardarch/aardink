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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * One document line laid out, in document columns. The layout may hold text the document does not
 * ([map]); only this class knows its positions, so every other part of the view asks about
 * columns and rows and cannot land inside injected text. Rows are the layout's own, so
 * [lineCount] counts the rows injected text adds, and [isInjectedRow] tells them apart.
 *
 * This is the only place that reads a [TextLayoutResult] by position.
 */
@Immutable
internal class LineLayout(val result: TextLayoutResult, val map: OffsetMap = OffsetMap.Identity) {

    val lineCount: Int get() = result.lineCount

    val size: IntSize get() = result.size

    /** Rows that hold no column of the document, or null when there are none. */
    private val injectedRows: BooleanArray? = if (map.hasBreaks) findInjectedRows() else null

    /** The first row with a column of the document on it: where the line's number goes. */
    val textRow: Int = injectedRows?.indexOf(false)?.coerceAtLeast(0) ?: 0

    /** The last row with a column of the document on it. */
    val lastTextRow: Int = injectedRows?.lastIndexOf(false)?.coerceAtLeast(0) ?: (result.lineCount - 1)

    /** Rows from [textRow] to [lastTextRow], the band a line's highlight covers. */
    val textRows: Int get() = lastTextRow - textRow + 1

    /** Where row 0 sits: rows added by a hard break keep its place in their own band. */
    private val rowBase: Float = if (injectedRows != null) result.getLineTop(0) else 0f

    /** Whether row [row] holds only injected text, so a caret cannot be on it. */
    fun isInjectedRow(row: Int): Boolean = injectedRows?.getOrNull(row) ?: false

    /** The row a caret at [column] is on. */
    fun rowFor(column: Int): Int = rowAt(map.toLayout(column))

    /** The row the character at [column] is on. */
    fun charRow(column: Int): Int = rowAt(map.charIndex(column))

    /** The caret at [column], in this layout's coordinates. */
    fun cursorRect(column: Int): Rect {
        val position = map.toLayout(column)
        val rect = result.getCursorRect(position)
        if (injectedRows == null || !endsOnEmptyRow(position)) return rect
        // Skia measures a text's empty last row with the default font, not the row's own style:
        // the caret is drawn as it would be on the row above.
        val row = rowAt(position)
        val above = result.getCursorRect(position - 1)
        val pitch = result.getLineTop(row) - result.getLineTop(row - 1)
        return Rect(rect.left, above.top + pitch, rect.right, above.bottom + pitch)
    }

    private fun endsOnEmptyRow(position: Int): Boolean {
        val text = result.layoutInput.text
        return position > 0 && position == text.length && text[position - 1] == '\n' && rowAt(position) > 0
    }

    /** The x a caret at [column] is at. */
    fun caretX(column: Int): Float = result.getHorizontalPosition(map.toLayout(column), usePrimaryDirection = true)

    /** The x the character at [column] starts at: after what is injected before it. */
    fun charLeft(column: Int): Float = result.getHorizontalPosition(map.charIndex(column), usePrimaryDirection = true)

    /** The box of the character at [column]. Not `map(column + 1) - 1`: that can land in injected text. */
    fun charBox(column: Int): Rect = result.getBoundingBox(map.charIndex(column))

    /** The outline of columns [from, to), which leaves out rows of injected text between them. */
    fun rangePath(from: Int, to: Int): Path {
        if (injectedRows == null) return result.getPathForRange(map.charIndex(from), map.rangeEnd(to))
        val path = Path()
        map.forEachRun(from, to, breaksOnly = true) { start, end -> path.addPath(result.getPathForRange(start, end)) }
        return path
    }

    /** The column at [position]: on injected text, the column it is at; past the text, past it. */
    fun columnAt(position: Offset): Int = map.toDocument(result.getOffsetForPosition(position))

    /** The injected text on row [row] under text x [x], or null. */
    fun injectionAt(x: Float, row: Int): Injection? {
        if (map.isIdentity || x < getLineLeft(row) || x > getLineRight(row)) return null
        val y = (getLineTop(row) + getLineBottom(row)) / 2f
        val nearest = result.getOffsetForPosition(Offset(x, y))
        val length = result.layoutInput.text.length
        // The nearest caret place can be after the character under x.
        val index = if (nearest > 0 && (nearest >= length || result.getBoundingBox(nearest).left > x)) nearest - 1 else nearest
        return map.injectionAt(index)
    }

    /** The column row [row] starts at. */
    fun getLineStart(row: Int): Int = map.toDocument(result.getLineStart(row))

    /** The x where the text on row [row] ends, at most [shown] columns in. */
    fun rowEndX(row: Int, shown: Int): Float = caretX(minOf(map.toDocument(result.getLineEnd(row)), shown))

    fun getLineTop(row: Int): Float = result.getLineTop(row)

    fun getLineBottom(row: Int): Float = result.getLineBottom(row)

    fun getLineLeft(row: Int): Float = result.getLineLeft(row)

    fun getLineRight(row: Int): Float = result.getLineRight(row)

    fun getLineBaseline(row: Int): Float = result.getLineBaseline(row)

    /**
     * How far row [row] moves to sit at `row * lineHeight`: the view's row pitch, not the
     * platform's. The row map, the gutter, scrolling and hit-testing all step rows by [lineHeight],
     * but a line's paragraph puts its rows wherever the platform's text engine does, and on some
     * Android devices that is further apart. Row 0 never moves, so a single-row line is drawn as
     * it always was.
     */
    fun rowShift(row: Int, lineHeight: Float): Float = if (row <= 0) 0f else row * lineHeight + rowBase - result.getLineTop(row)

    /** Whether the rows already sit at the view's pitch: then the layout is drawn in one go. */
    fun rowsAtPitch(lineHeight: Float): Boolean = lineCount <= 1 || abs(result.getLineTop(1) - rowBase - lineHeight) < 0.5f

    /**
     * The row of position [position], one of the rows [lineCount] counts. On skiko,
     * `getLineForOffset` reads Skia's line metrics and `lineCount` its line number, and the two
     * can disagree (CI's Linux Chrome, during an input method's composition), so a row taken
     * straight from `getLineForOffset` can be one the layout's line getters reject.
     */
    private fun rowAt(position: Int): Int = result.getLineForOffset(position).coerceIn(0, result.lineCount - 1)

    private fun findInjectedRows(): BooleanArray {
        val injected = BooleanArray(result.lineCount) { true }
        // A row is the text's when a character of the document is on it, or a caret can be: an
        // empty line's text row has no character.
        injected[rowAt(map.toLayout(0))] = false
        map.forEachColumn { injected[rowAt(map.toLayout(it))] = false }
        map.forEachRun(0, map.length) { start, end ->
            if (end > start) for (row in rowAt(start)..rowAt(end - 1)) injected[row] = false
        }
        return injected
    }
}
