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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.ui.RenderWhitespace
import kotlin.math.max
import kotlin.math.min

/**
 * The columns of [text] (the first [length] characters of one line) whose space or tab [mode]
 * draws, in order. [selected] holds the line's selected columns as half-open ranges, for
 * [RenderWhitespace.Selection]. The rules are VS Code's.
 */
internal fun whitespaceColumns(
    text: CharSequence,
    length: Int,
    mode: RenderWhitespace,
    selected: List<IntRange> = emptyList(),
): List<Int> {
    if (mode == RenderWhitespace.None) return emptyList()
    val end = min(length, text.length)
    val columns = ArrayList<Int>()
    when (mode) {
        RenderWhitespace.None -> Unit

        RenderWhitespace.All -> for (i in 0 until end) if (text[i].isDrawnWhitespace()) columns += i

        RenderWhitespace.Trailing -> {
            var i = end
            while (i > 0 && text[i - 1].isDrawnWhitespace()) i--
            for (c in i until end) columns += c
        }

        RenderWhitespace.Boundary -> for (i in 0 until end) {
            if (!text[i].isDrawnWhitespace()) continue
            // A single space between two other characters is a word gap, not worth a dot.
            val single = text[i] == ' ' &&
                i > 0 && !text[i - 1].isDrawnWhitespace() &&
                i + 1 < end && !text[i + 1].isDrawnWhitespace()
            if (!single) columns += i
        }

        RenderWhitespace.Selection -> for (range in selected) {
            for (i in max(0, range.first)..min(end - 1, range.last)) if (text[i].isDrawnWhitespace()) columns += i
        }
    }
    return columns
}

private fun Char.isDrawnWhitespace(): Boolean = this == ' ' || this == '\t'

/** The selected columns of [line], half-open ranges turned inclusive, for [whitespaceColumns]. */
private fun selectedColumns(line: VisibleLine, selections: List<TextRange>): List<IntRange> {
    var columns: ArrayList<IntRange>? = null
    for (selection in selections) {
        if (selection.collapsed || selection.max <= line.start || selection.min > line.end) continue
        val from = max(selection.min, line.start) - line.start
        val to = min(selection.max, line.end) - line.start
        if (to > from) (columns ?: ArrayList<IntRange>().also { columns = it }) += from until to
    }
    return columns.orEmpty()
}

/**
 * Dots for the spaces and arrows for the tabs of [line] that [mode] picks. Each mark is placed in
 * its own character's box, so a tab's arrow spans whatever width the tab was laid out at.
 */
internal fun DrawScope.drawWhitespace(
    line: VisibleLine,
    mode: RenderWhitespace,
    selections: List<TextRange>,
    color: Color,
    left: Float,
    top: Float,
    lineHeight: Float,
) {
    if (mode == RenderWhitespace.None || color.alpha == 0f) return
    val selected = if (mode == RenderWhitespace.Selection) selectedColumns(line, selections) else emptyList()
    if (mode == RenderWhitespace.Selection && selected.isEmpty()) return
    val text = line.layout.layoutInput.text
    val columns = whitespaceColumns(text, line.shownLength, mode, selected)
    if (columns.isEmpty()) return
    val radius = max(1f, lineHeight * 0.06f)
    val stroke = max(1f, 1.dp.toPx())
    for (column in columns) {
        val box = line.layout.getBoundingBox(column)
        val x0 = left + box.left
        // Off screen sideways: nothing to draw, and on a long unwrapped line most marks are.
        if (x0 > size.width || left + box.right < 0f) continue
        val y = top + (box.top + box.bottom) / 2f
        if (text[column] == ' ') {
            drawCircle(color, radius, Offset(left + (box.left + box.right) / 2f, y))
        } else {
            val inset = max(box.width * 0.15f, stroke)
            val head = min(box.height * 0.2f, (box.width - 2 * inset) / 2f).coerceAtLeast(stroke)
            val x1 = left + box.right - inset
            drawLine(color, Offset(x0 + inset, y), Offset(x1, y), stroke)
            drawLine(color, Offset(x1 - head, y - head), Offset(x1, y), stroke)
            drawLine(color, Offset(x1 - head, y + head), Offset(x1, y), stroke)
        }
    }
}
