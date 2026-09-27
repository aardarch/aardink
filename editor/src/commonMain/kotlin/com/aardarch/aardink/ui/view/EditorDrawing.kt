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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.ui.drawSquiggleLine
import kotlin.math.max
import kotlin.math.min

/** The colours of everything the view draws besides the text itself. */
@Immutable
internal data class EditorColors(
    val selection: Color,
    val findMatch: Color,
    val currentFindMatch: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val caret: Color,
    val text: Color,
    val lineHighlight: Color,
    /** The fill of a matched bracket's box; its outline is the same colour, stronger. */
    val bracketMatch: Color,
)

/** What is drawn under and over the text, in document offsets. */
internal class EditorDecorations(
    val selections: List<TextRange>,
    val findMatches: List<IntRange>,
    val currentFindMatch: Int,
    val diagnostics: List<Diagnostic>,
    val composition: TextRange?,
    /** Highlight the line of every caret without a selection. */
    val highlightCaretLines: Boolean = false,
    /** The bracket pair to box, as document offsets of the two characters. */
    val bracketMatch: Pair<Int, Int>? = null,
)

/**
 * The viewport's content for [frame]: the caret lines' highlight, selections, find matches and a
 * matched bracket pair's boxes under the text, the text, then squiggles and the input-method
 * composition underline. Carets are drawn separately
 * ([drawCarets]), so their blinking does not redraw the rest.
 */
internal fun DrawScope.drawEditorContent(frame: ViewFrame, decorations: EditorDecorations, colors: EditorColors) {
    val left = frame.textLeft
    val charWidth = frame.metrics.charWidth
    if (decorations.highlightCaretLines) {
        for (line in frame.lines) {
            val caretHere = decorations.selections.any { it.collapsed && it.end in line.start..line.end }
            if (!caretHere) continue
            val rows = line.layout.lineCount * frame.metrics.lineHeight
            drawRect(colors.lineHighlight, Offset(0f, line.top - frame.scrollY), Size(size.width, rows))
        }
    }
    decorations.bracketMatch?.let { (open, close) ->
        drawBracketBox(frame, open, colors, left)
        drawBracketBox(frame, close, colors, left)
    }
    for (line in frame.lines) {
        val top = line.top - frame.scrollY
        for (selection in decorations.selections) {
            if (selection.collapsed || selection.max < line.start || selection.min > line.end) continue
            drawRange(line, selection.min - line.start, selection.max - line.start, colors.selection, left, top)
            // The line break is selected too: show it, so an empty selected line is visible.
            if (selection.max > line.end) {
                val layout = line.layout
                val lastRow = layout.lineCount - 1
                val x = layout.getLineRight(lastRow).coerceAtLeast(0f)
                val rowTop = layout.getLineTop(lastRow)
                drawRect(colors.selection, Offset(left + x, top + rowTop), Size(charWidth * 0.5f, layout.getLineBottom(lastRow) - rowTop))
            }
        }
        // Over the selection, as in VS Code: a match inside a selection stays visible.
        drawFindMatches(line, decorations, colors, left, top)
        drawText(line.layout, topLeft = Offset(left, top))
    }
    for (line in frame.lines) {
        val top = line.top - frame.scrollY
        drawSquiggles(line, decorations.diagnostics, colors, left, top)
        decorations.composition?.let { drawComposition(line, it, colors.text, left, top) }
    }
}

private fun DrawScope.drawFindMatches(line: VisibleLine, decorations: EditorDecorations, colors: EditorColors, left: Float, top: Float) {
    val matches = decorations.findMatches
    if (matches.isEmpty()) return
    // Matches are in document order: find the first that can reach this line.
    var lo = 0
    var hi = matches.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (matches[mid].last < line.start) lo = mid + 1 else hi = mid
    }
    var i = lo
    while (i < matches.size && matches[i].first <= line.end) {
        val match = matches[i]
        val color = if (i == decorations.currentFindMatch) colors.currentFindMatch else colors.findMatch
        drawRange(line, match.first - line.start, match.last + 1 - line.start, color, left, top)
        i++
    }
}

/** Fills columns [from, to) of [line], row by row. */
private fun DrawScope.drawRange(line: VisibleLine, from: Int, to: Int, color: Color, left: Float, top: Float) {
    val a = from.coerceIn(0, line.shownLength)
    val b = to.coerceIn(a, line.shownLength)
    if (b <= a) return
    val path = line.layout.getPathForRange(a, b)
    translate(left, top) { drawPath(path, color) }
}

private fun DrawScope.drawSquiggles(line: VisibleLine, diagnostics: List<Diagnostic>, colors: EditorColors, left: Float, top: Float) {
    if (diagnostics.isEmpty()) return
    val layout = line.layout
    val minWidth = 20.dp.toPx()
    for (diagnostic in diagnostics) {
        val start = diagnostic.range.first
        val end = max(diagnostic.range.last + 1, start)
        if (end < line.start || start > line.end) continue
        if (end == line.start && start < line.start) continue
        val a = (max(start, line.start) - line.start).coerceAtMost(line.shownLength)
        val b = (min(end, line.end) - line.start).coerceIn(a, line.shownLength)
        val color = when (diagnostic.severity) {
            DiagnosticSeverity.Error -> colors.error
            DiagnosticSeverity.Warning -> colors.warning
            DiagnosticSeverity.Info -> colors.info
        }
        val firstRow = layout.getLineForOffset(a)
        val lastRow = layout.getLineForOffset(max(a, b - 1))
        for (row in firstRow..lastRow) {
            val x0 = if (row == firstRow) layout.getHorizontalPosition(a, usePrimaryDirection = true) else layout.getLineLeft(row)
            var x1 = if (row == lastRow && b > a) layout.getBoundingBox(b - 1).right else layout.getLineRight(row)
            if (x1 <= x0) x1 = x0 + minWidth
            drawSquiggleLine(left + x0, left + x1, top + layout.getLineBottom(row) + 1.dp.toPx(), color)
        }
    }
}

private fun DrawScope.drawComposition(line: VisibleLine, composition: TextRange, color: Color, left: Float, top: Float) {
    if (composition.collapsed || composition.max < line.start || composition.min > line.end) return
    val layout = line.layout
    val a = (composition.min - line.start).coerceIn(0, line.shownLength)
    val b = (composition.max - line.start).coerceIn(a, line.shownLength)
    if (b <= a) return
    val stroke = 1.dp.toPx()
    val firstRow = layout.getLineForOffset(a)
    val lastRow = layout.getLineForOffset(b - 1)
    for (row in firstRow..lastRow) {
        val x0 = if (row == firstRow) layout.getHorizontalPosition(a, usePrimaryDirection = true) else layout.getLineLeft(row)
        val x1 = if (row == lastRow) layout.getBoundingBox(b - 1).right else layout.getLineRight(row)
        val y = top + layout.getLineBaseline(row) + 2.dp.toPx()
        drawLine(color, Offset(left + x0, y), Offset(left + x1, y), strokeWidth = stroke)
    }
}

/** A faint box with an outline around the character at [offset], when it is on screen. */
private fun DrawScope.drawBracketBox(frame: ViewFrame, offset: Int, colors: EditorColors, left: Float) {
    val line = frame.lineContaining(offset) ?: return
    val column = offset - line.start
    if (column >= line.shownLength) return
    val box = line.layout.getBoundingBox(column).translate(left, line.top - frame.scrollY)
    drawRect(colors.bracketMatch, box.topLeft, box.size)
    drawRect(
        colors.bracketMatch.copy(alpha = (colors.bracketMatch.alpha * 3f).coerceAtMost(1f)),
        box.topLeft,
        box.size,
        style = Stroke(1.dp.toPx()),
    )
}

/** The line of [frame] that holds [offset], or null when it is not on screen. */
internal fun ViewFrame.lineContaining(offset: Int): VisibleLine? {
    var lo = 0
    var hi = lines.size - 1
    var found: VisibleLine? = null
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].start <= offset) {
            found = lines[mid]
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found?.takeIf { offset <= it.end }
}

/** A caret at the active end of every selection that is on screen. */
internal fun DrawScope.drawCarets(frame: ViewFrame, selections: List<TextRange>, color: Color) {
    val width = 2.dp.toPx()
    val left = frame.textLeft
    for (selection in selections) {
        val line = frame.lineContaining(selection.end) ?: continue
        val column = (selection.end - line.start).coerceAtMost(line.shownLength)
        val rect = line.layout.getCursorRect(column)
        val top = line.top - frame.scrollY
        // Like the text field: the caret starts at its x and is drawn rightwards.
        drawRect(color, Offset(left + rect.left, top + rect.top), Size(width, rect.height))
    }
}
