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
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.edit.SelectionSet
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A column (box) selection, from an anchor to an active point, each a row on screen and an x in the
 * text. Rows are what is on screen, as in VS Code: wrapped rows count, folded lines do not.
 */
@Immutable
internal data class ColumnBox(val anchorRow: Int, val anchorX: Float, val activeRow: Int, val activeX: Float)

/**
 * The selections [box] makes: on every row from the anchor's to the active one's, the part between
 * the two x's. A row whose text ends before the box starts gets nothing, as in VS Code, unless
 * every row would get nothing; then each gets a caret at its end. The active row's selection is
 * the primary one, so the view follows it.
 */
internal fun EditorView.columnSelection(box: ColumnBox): SelectionSet {
    val left = min(box.anchorX, box.activeX)
    // Half a character's slack: a row ending right at the box's edge is in it.
    val slack = (style?.metrics?.charWidth ?: 0f) / 2f
    val step = if (box.activeRow >= box.anchorRow) 1 else -1
    val ranges = ArrayList<TextRange>()
    forRows(box, step) { row ->
        if (rowEndX(row) + slack >= left) ranges += TextRange(offsetOnRow(row, box.anchorX), offsetOnRow(row, box.activeX))
    }
    if (ranges.isEmpty()) forRows(box, step) { row -> ranges += TextRange(offsetOnRow(row, Float.MAX_VALUE)) }
    return SelectionSet.of(ranges.asReversed())
}

private inline fun forRows(box: ColumnBox, step: Int, action: (Int) -> Unit) {
    var row = box.anchorRow
    while (true) {
        action(row)
        if (row == box.activeRow) return
        row += step
    }
}

/**
 * Makes column selections, and remembers the last one so that the next Ctrl+Shift+Alt+arrow, or
 * a Shift+Alt+click, carries on from it while nothing else has changed the selections. Otherwise
 * a new box starts from the primary selection: its anchor to its caret.
 */
internal class ColumnSelector(private val view: EditorView) {

    private var last: ColumnBox? = null
    private var made: SelectionSet? = null

    /** The box to carry on from. */
    fun current(): ColumnBox {
        val selections = view.state.currentSelections()
        last?.let { if (made == selections) return it }
        val primary = selections.primary
        return ColumnBox(view.rowOf(primary.start), view.caretX(primary.start), view.rowOf(primary.end), view.caretX(primary.end))
    }

    /** Selects [box]. */
    fun select(box: ColumnBox) {
        view.state.replaceSelections(view.columnSelection(box))
        last = box
        made = view.state.currentSelections()
    }

    /**
     * The text x of viewport x [x], on the grid of character columns: a box drawn with the mouse
     * lines up with the characters, as one made with the keys does.
     */
    fun columnX(x: Float): Float {
        val charWidth = view.style?.metrics?.charWidth?.takeIf { it > 0f } ?: return view.textX(x).coerceAtLeast(0f)
        return (view.textX(x) / charWidth).roundToInt().coerceAtLeast(0) * charWidth
    }
}
