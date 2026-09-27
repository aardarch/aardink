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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.edit.SelectionSet
import com.aardarch.aardink.core.edit.TextNavigator
import kotlin.math.min

/** A line pinned at the top of the text by sticky scroll, drawn at viewport y [top]. */
@Immutable
internal data class StickyLine(val line: Int, val top: Float)

/** The most lines sticky scroll pins, as in VS Code. */
internal const val MAX_STICKY_LINES = 5

/**
 * The first lines of the [ranges] (a language's foldable ranges, sorted by start line, outer ones
 * first) that the top of the view is inside, outermost first, as VS Code's sticky scroll pins
 * them: each stays at the top while any of its range is on screen below it, and is pushed up by
 * the end of its range as that scrolls past.
 */
internal fun EditorView.stickyLines(ranges: List<FoldRange>, max: Int = MAX_STICKY_LINES): List<StickyLine> {
    val metrics = style?.metrics ?: return emptyList()
    val lineCount = state.document.lineCount
    if (ranges.isEmpty() || lineCount == 0 || lineMap.lineCount != lineCount) return emptyList()
    val lineHeight = metrics.lineHeight
    val scrollY = scroll.scrollY
    val sticky = ArrayList<StickyLine>(max)
    var parent: FoldRange? = null
    for (range in ranges) {
        if (sticky.size >= max) break
        val outer = parent
        if (outer != null && (range.startLine <= outer.startLine || range.endLine > outer.endLine)) continue
        if (range.startLine >= lineCount || range.endLine <= range.startLine || lineMap.isHidden(range.startLine)) continue
        val slot = sticky.size * lineHeight
        // Its own line is still where it can be seen, and so is every range that starts later.
        if (contentTop(range.startLine, metrics) - scrollY >= slot) break
        val last = lineMap.visibleLineAtOrBefore(range.endLine.coerceAtMost(lineCount - 1))
        val bottom = contentTop(last, metrics) + lineMap.rowsOf(last) * lineHeight - scrollY
        // Scrolled past already: a later range at this depth may be the one.
        if (bottom <= slot) continue
        sticky += StickyLine(range.startLine, min(slot, bottom - lineHeight))
        parent = range
    }
    return sticky
}

/** The sticky line at viewport point [position], the outermost where they overlap. */
internal fun EditorView.stickyLineAt(ranges: List<FoldRange>, position: Offset): StickyLine? {
    val lineHeight = style?.metrics?.lineHeight ?: return null
    return stickyLines(ranges).firstOrNull { position.y >= it.top && position.y < it.top + lineHeight }
}

/**
 * A click on a sticky line: the view scrolls its line to where the sticky line was drawn, so it
 * seems not to move, and the caret goes to the start of its text.
 */
internal fun EditorView.openStickyLine(sticky: StickyLine) {
    val metrics = style?.metrics ?: return
    scroll.scrollToY(contentTop(sticky.line, metrics) - sticky.top.coerceAtLeast(0f))
    val document = state.document
    val caret = TextNavigator.smartHome(document, document.lineStart(sticky.line))
    state.replaceSelections(SelectionSet.single(TextRange(caret)))
}

/**
 * The sticky lines over the text: each on the editor's background with the first row of its
 * line's layout, and a faint shadow under them all. Deeper lines are drawn first, so one pushed
 * up slides under the one above it.
 */
internal fun DrawScope.drawStickyLines(view: EditorView, sticky: List<StickyLine>, background: Color, shadow: Color) {
    if (sticky.isEmpty()) return
    val frame = view.frame
    val lineHeight = frame.metrics.lineHeight
    for (line in sticky.asReversed()) {
        drawRect(background, Offset(0f, line.top), Size(size.width, lineHeight))
        val layout = view.layoutFor(line.line)
        clipRect(0f, line.top, size.width, line.top + lineHeight) {
            drawText(layout, topLeft = Offset(frame.textLeft, line.top))
        }
    }
    val bottom = sticky.maxOf { it.top } + lineHeight
    drawRect(shadow, Offset(0f, bottom), Size(size.width, 2.dp.toPx()))
}
