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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.DiagnosticTag
import com.aardarch.aardink.ui.CursorStyle
import com.aardarch.aardink.ui.RenderWhitespace
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
    /** The dots and arrows of drawn whitespace. */
    val whitespace: Color = Color.Transparent,
    /** Behind a snippet's tab stops while it is being filled in. */
    val snippetTabStop: Color = Color.Transparent,
    /** The editor's background, laid over unnecessary code to fade it. */
    val background: Color = Color.Transparent,
    /** Indent guides, and the one of the block holding the caret. */
    val indentGuide: Color = Color.Transparent,
    val activeIndentGuide: Color = Color.Transparent,
    /** The vertical lines at [EditorDecorations.rulers]. */
    val ruler: Color = Color.Transparent,
    /** Bracket-pair guides by depth; none drawn without them. */
    val bracketGuides: List<Color> = emptyList(),
    /** Behind the text find in selection searches, as Monaco's `editor.findRangeHighlightBackground`. */
    val findScope: Color = Color.Transparent,
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
    /** The symbol under a mouse with Ctrl (Cmd) held, underlined as a link to its definition. */
    val link: TextRange? = null,
    /** Which spaces and tabs get a dot or an arrow. */
    val renderWhitespace: RenderWhitespace = RenderWhitespace.None,
    /** The tab stops of a snippet being filled in. */
    val snippetTabStops: List<TextRange> = emptyList(),
    /** The host's decorations, in order of where they start. */
    val hostDecorations: List<Decoration> = emptyList(),
    /** The colours in the text, each drawn as a swatch in the room inline content leaves before it. */
    val colorSwatches: List<ColorInfo> = emptyList(),
    /** Indentation and bracket pairs around the view; null draws no guides. */
    val guides: Guides? = null,
    val indentGuides: Boolean = false,
    val bracketPairGuides: Boolean = false,
    /** The indent guide to highlight. */
    val activeGuide: ActiveGuide? = null,
    /** Columns with a vertical line. */
    val rulers: List<Int> = emptyList(),
    /** Where find in selection searches, shaded; null for none. */
    val findScope: IntRange? = null,
)

/**
 * The viewport's content for [frame]: the caret lines' highlight, selections, find matches, a
 * matched bracket pair's boxes and drawn whitespace under the text, the text, then squiggles and the input-method
 * composition underline. Carets are drawn separately
 * ([drawCarets]), so their blinking does not redraw the rest.
 */
internal fun DrawScope.drawEditorContent(frame: ViewFrame, decorations: EditorDecorations, colors: EditorColors) {
    val left = frame.textLeft
    val charWidth = frame.metrics.charWidth
    val lineHeight = frame.metrics.lineHeight
    if (decorations.highlightCaretLines) {
        for (line in frame.lines) {
            val caretHere = decorations.selections.any { it.collapsed && it.end in line.start..line.end }
            if (!caretHere) continue
            val rows = line.layout.textRows * lineHeight
            drawRect(colors.lineHighlight, Offset(0f, line.textBandTop(frame)), Size(size.width, rows))
        }
    }
    // The host's decorations that reach the lines on screen: filtered once per frame, not per line.
    val shownDecorations = visibleDecorations(frame, decorations.hostDecorations)
    for (decoration in shownDecorations) {
        val style = decoration.style
        if (!style.wholeLine || style.background == Color.Unspecified) continue
        for (line in frame.lines) {
            if (!decoration.touches(line)) continue
            drawRect(style.background, Offset(0f, line.textBandTop(frame)), Size(size.width, line.layout.textRows * lineHeight))
        }
    }
    decorations.bracketMatch?.let { (open, close) ->
        drawBracketBox(frame, open, colors, left)
        drawBracketBox(frame, close, colors, left)
    }
    drawGuides(frame, decorations, colors)
    for (line in frame.lines) {
        val top = line.top - frame.scrollY
        for (decoration in shownDecorations) {
            val style = decoration.style
            if (style.wholeLine || style.background == Color.Unspecified || decoration.range.isEmpty() ||
                !decoration.touches(line)
            ) {
                continue
            }
            drawRange(
                line,
                decoration.range.first - line.start,
                decoration.range.last + 1 - line.start,
                style.background,
                left,
                top,
                lineHeight,
            )
        }
        decorations.findScope?.let { scope ->
            if (scope.isEmpty() || scope.last < line.start || scope.first > line.end) return@let
            drawRange(line, scope.first - line.start, scope.last + 1 - line.start, colors.findScope, left, top, lineHeight)
        }
        for (stop in decorations.snippetTabStops) {
            if (stop.collapsed || stop.max <= line.start || stop.min > line.end) continue
            drawRange(line, stop.min - line.start, stop.max - line.start, colors.snippetTabStop, left, top, lineHeight)
        }
        for (selection in decorations.selections) {
            if (selection.collapsed || selection.max < line.start || selection.min > line.end) continue
            drawRange(line, selection.min - line.start, selection.max - line.start, colors.selection, left, top, lineHeight)
            // The line break is selected too: show it, so an empty selected line is visible.
            if (selection.max > line.end) {
                val layout = line.layout
                val lastRow = layout.lastTextRow
                val x = layout.getLineRight(lastRow).coerceAtLeast(0f)
                val rowTop = layout.getLineTop(lastRow) + layout.rowShift(lastRow, lineHeight)
                drawRect(
                    colors.selection,
                    Offset(left + x, top + rowTop),
                    Size(charWidth * 0.5f, layout.getLineBottom(lastRow) - layout.getLineTop(lastRow)),
                )
            }
        }
        // Over the selection, as in VS Code: a match inside a selection stays visible.
        drawFindMatches(line, decorations, colors, left, top, lineHeight)
        drawWhitespace(line, decorations.renderWhitespace, decorations.selections, colors.whitespace, left, top, lineHeight)
        eachRow(line.layout, lineHeight, top) { rowTop -> drawText(line.layout.result, topLeft = Offset(left, rowTop)) }
    }
    val shownSwatches = visibleSwatches(frame, decorations.colorSwatches)
    for (line in frame.lines) {
        val top = line.top - frame.scrollY
        drawSquiggles(line, decorations.diagnostics, colors, left, top, lineHeight)
        for (decoration in shownDecorations) {
            val style = decoration.style
            if (decoration.range.isEmpty() || !decoration.touches(line)) continue
            val range = TextRange(decoration.range.first, decoration.range.last + 1)
            if (style.underline != Color.Unspecified) drawUnderline(line, range, style.underline, left, top, lineHeight)
            if (style.border != Color.Unspecified) drawBorder(line, range, style.border, left, top, lineHeight)
        }
        for (swatch in shownSwatches) {
            if (swatch.range.last < line.start || swatch.range.first > line.end) continue
            drawSwatch(line, swatch, left, top, lineHeight)
        }
        decorations.composition?.let { drawUnderline(line, it, colors.text, left, top, lineHeight) }
        decorations.link?.let { drawUnderline(line, it, colors.text, left, top, lineHeight) }
    }
}

/**
 * Runs [block] with the y the text of a row of [layout] is drawn at: once with [top] when the rows
 * already sit at the view's pitch, else once per row in [firstRow]..[lastRow], clipped to that
 * row's own band and moved so the row sits at `row * lineHeight` ([LineLayout.rowShift]).
 */
private inline fun DrawScope.eachRow(
    layout: LineLayout,
    lineHeight: Float,
    top: Float,
    firstRow: Int = 0,
    lastRow: Int = layout.lineCount - 1,
    block: DrawScope.(top: Float) -> Unit,
) {
    if (layout.rowsAtPitch(lineHeight)) {
        block(top)
        return
    }
    for (row in firstRow..lastRow) {
        val shifted = top + layout.rowShift(row, lineHeight)
        clipRect(0f, shifted + layout.getLineTop(row), size.width, shifted + layout.getLineBottom(row)) { block(shifted) }
    }
}

private fun DrawScope.drawFindMatches(
    line: VisibleLine,
    decorations: EditorDecorations,
    colors: EditorColors,
    left: Float,
    top: Float,
    lineHeight: Float,
) {
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
        drawRange(line, match.first - line.start, match.last + 1 - line.start, color, left, top, lineHeight)
        i++
    }
}

/** Fills columns [from, to) of [line], row by row. */
private fun DrawScope.drawRange(line: VisibleLine, from: Int, to: Int, color: Color, left: Float, top: Float, lineHeight: Float) {
    val a = from.coerceIn(0, line.shownLength)
    val b = to.coerceIn(a, line.shownLength)
    if (b <= a) return
    val layout = line.layout
    val path = layout.rangePath(a, b)
    eachRow(layout, lineHeight, top, layout.charRow(a), layout.charRow(b - 1)) { rowTop ->
        translate(left, rowTop) { drawPath(path, color) }
    }
}

private fun DrawScope.drawSquiggles(
    line: VisibleLine,
    diagnostics: List<Diagnostic>,
    colors: EditorColors,
    left: Float,
    top: Float,
    lineHeight: Float,
) {
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
            DiagnosticSeverity.Info, DiagnosticSeverity.Hint -> colors.info
        }
        val unnecessary = DiagnosticTag.Unnecessary in diagnostic.tags
        val deprecated = DiagnosticTag.Deprecated in diagnostic.tags
        val firstRow = layout.charRow(a)
        val lastRow = layout.charRow(max(a, b - 1))
        for (row in firstRow..lastRow) {
            if (layout.isInjectedRow(row)) continue
            val rowTop = top + layout.rowShift(row, lineHeight)
            val x0 = if (row == firstRow) layout.charLeft(a) else layout.getLineLeft(row)
            var x1 = if (row == lastRow && b > a) layout.charBox(b - 1).right else layout.getLineRight(row)
            // A tagged diagnostic shows by its tag, as in VS Code: no box drawn wider than its text.
            if (unnecessary || deprecated) {
                if (x1 > x0 && unnecessary) {
                    // Faded: the background laid over the text, so it is not laid out again.
                    drawRect(
                        colors.background.copy(alpha = UNNECESSARY_FADE),
                        Offset(left + x0, rowTop + layout.getLineTop(row)),
                        Size(
                            x1 - x0,
                            layout.getLineBottom(row) - layout.getLineTop(row),
                        ),
                    )
                }
                if (x1 > x0 && deprecated) {
                    val y = rowTop + (layout.getLineTop(row) + layout.getLineBottom(row)) / 2f
                    drawLine(colors.text, Offset(left + x0, y), Offset(left + x1, y), strokeWidth = 1.dp.toPx())
                }
                if (diagnostic.severity != DiagnosticSeverity.Hint && !unnecessary) {
                    if (x1 <= x0) x1 = x0 + minWidth
                    drawSquiggleLine(left + x0, left + x1, rowTop + layout.getLineBottom(row) + 1.dp.toPx(), color)
                }
                continue
            }
            if (diagnostic.severity == DiagnosticSeverity.Hint) {
                // Three dots under the start, as VS Code draws a hint: only on the line it starts on.
                if (row == firstRow && start >= line.start) drawHintDots(left + x0, rowTop + layout.getLineBottom(row), color)
                continue
            }
            if (x1 <= x0) x1 = x0 + minWidth
            drawSquiggleLine(left + x0, left + x1, rowTop + layout.getLineBottom(row) + 1.dp.toPx(), color)
        }
    }
}

/** VS Code's hint mark: three small dots at [x], just below the text's bottom [y]. */
private fun DrawScope.drawHintDots(x: Float, y: Float, color: Color) {
    val radius = 1.dp.toPx()
    val step = 3.dp.toPx()
    for (i in 0 until 3) drawCircle(color, radius, Offset(x + radius + i * step, y))
}

/** How strongly unnecessary code is faded: VS Code draws it at about 60 % opacity. */
private const val UNNECESSARY_FADE = 0.4f

/** A thin line under [range], as an input method's composition and a link are drawn. */
private fun DrawScope.drawUnderline(line: VisibleLine, range: TextRange, color: Color, left: Float, top: Float, lineHeight: Float) {
    if (range.collapsed || range.max < line.start || range.min > line.end) return
    val layout = line.layout
    val a = (range.min - line.start).coerceIn(0, line.shownLength)
    val b = (range.max - line.start).coerceIn(a, line.shownLength)
    if (b <= a) return
    val stroke = 1.dp.toPx()
    val firstRow = layout.charRow(a)
    val lastRow = layout.charRow(b - 1)
    for (row in firstRow..lastRow) {
        if (layout.isInjectedRow(row)) continue
        val x0 = if (row == firstRow) layout.charLeft(a) else layout.getLineLeft(row)
        val x1 = if (row == lastRow) layout.charBox(b - 1).right else layout.getLineRight(row)
        val y = top + layout.rowShift(row, lineHeight) + layout.getLineBaseline(row) + 2.dp.toPx()
        drawLine(color, Offset(left + x0, y), Offset(left + x1, y), strokeWidth = stroke)
    }
}

/**
 * A colour's swatch, as VS Code's: a square in the colour in the room inline content leaves before
 * it ([SWATCH_ROOM]), over a contrasting edge so a colour close to the background still shows.
 */
private fun DrawScope.drawSwatch(line: VisibleLine, swatch: ColorInfo, left: Float, top: Float, lineHeight: Float) {
    val column = swatch.range.first - line.start
    if (column !in 0..line.shownLength) return
    val (room, row) = line.layout.injectedBoxBefore(column, InlineContent.SwatchSlot) ?: return
    val edge = 1.dp.toPx()
    val side = minOf(room.width * SWATCH_FILL, room.height * SWATCH_FILL)
    val x = left + room.left + (room.width - side) / 2f
    val y = top + line.layout.rowShift(row, lineHeight) + room.top + (room.height - side) / 2f
    drawRect(Color.Gray, Offset(x - edge, y - edge), Size(side + 2 * edge, side + 2 * edge))
    drawRect(swatch.color, Offset(x, y), Size(side, side))
}

/** How much of its room a swatch fills: about VS Code's 0.8 em square in two columns. */
private const val SWATCH_FILL = 0.6f

/** The decorations in [all] (in order of where they start) that reach a line of [frame]. */
internal fun visibleDecorations(frame: ViewFrame, all: List<Decoration>): List<Decoration> {
    if (all.isEmpty() || frame.lines.isEmpty()) return emptyList()
    val from = frame.lines.first().start
    val to = frame.lines.last().end
    val out = ArrayList<Decoration>()
    for (decoration in all) {
        if (decoration.range.first > to) break
        val end = if (decoration.range.isEmpty()) decoration.range.first else decoration.range.last
        if (end >= from) out += decoration
    }
    return out
}

/**
 * The colours in [all] with a character on a line of [frame]: filtered once per frame, so each line
 * looks only at the few on screen. A language service need not list them in order, so all are seen.
 */
internal fun visibleSwatches(frame: ViewFrame, all: List<ColorInfo>): List<ColorInfo> {
    if (all.isEmpty() || frame.lines.isEmpty()) return emptyList()
    val from = frame.lines.first().start
    val to = frame.lines.last().end
    return all.filter { !it.range.isEmpty() && it.range.last >= from && it.range.first <= to }
}

/** Whether this decoration covers a character of [line], or sits on it when it is empty. */
private fun Decoration.touches(line: VisibleLine): Boolean {
    val first = range.first
    val last = if (range.isEmpty()) first else range.last
    return first <= line.end && last >= line.start
}

/** A box around [range] on [line], one per row it spans, as a decoration's border. */
private fun DrawScope.drawBorder(line: VisibleLine, range: TextRange, color: Color, left: Float, top: Float, lineHeight: Float) {
    if (range.collapsed || range.max < line.start || range.min > line.end) return
    val layout = line.layout
    val a = (range.min - line.start).coerceIn(0, line.shownLength)
    val b = (range.max - line.start).coerceIn(a, line.shownLength)
    if (b <= a) return
    val stroke = 1.dp.toPx()
    val firstRow = layout.charRow(a)
    val lastRow = layout.charRow(b - 1)
    for (row in firstRow..lastRow) {
        if (layout.isInjectedRow(row)) continue
        val x0 = if (row == firstRow) layout.charLeft(a) else layout.getLineLeft(row)
        val x1 = if (row == lastRow) layout.charBox(b - 1).right else layout.getLineRight(row)
        val rowTop = top + layout.rowShift(row, lineHeight) + layout.getLineTop(row)
        val height = layout.getLineBottom(row) - layout.getLineTop(row)
        drawRect(color, Offset(left + x0, rowTop), Size(max(x1 - x0, stroke), height), style = Stroke(stroke))
    }
}

/** A faint box with an outline around the character at [offset], when it is on screen. */
private fun DrawScope.drawBracketBox(frame: ViewFrame, offset: Int, colors: EditorColors, left: Float) {
    val line = frame.lineContaining(offset) ?: return
    val column = offset - line.start
    if (column >= line.shownLength) return
    val layout = line.layout
    val shift = layout.rowShift(layout.charRow(column), frame.metrics.lineHeight)
    val box = layout.charBox(column).translate(left, line.top - frame.scrollY + shift)
    drawRect(colors.bracketMatch, box.topLeft, box.size)
    drawRect(
        colors.bracketMatch.copy(alpha = (colors.bracketMatch.alpha * 3f).coerceAtMost(1f)),
        box.topLeft,
        box.size,
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * The rulers, then the indent guides and bracket-pair guides down the lines on screen, each over all
 * of a line's text rows. A guide's x is its column times a character's width: indentation is
 * spaces and tabs, which a monospaced font lays out on that grid.
 */
private fun DrawScope.drawGuides(frame: ViewFrame, decorations: EditorDecorations, colors: EditorColors) {
    val left = frame.textLeft
    val charWidth = frame.metrics.charWidth
    val width = 1.dp.toPx().coerceAtLeast(1f)
    for (column in decorations.rulers) {
        drawRect(colors.ruler, Offset(left + column * charWidth, 0f), Size(width, size.height))
    }
    val guides = decorations.guides ?: return
    val lineHeight = frame.metrics.lineHeight
    val active = decorations.activeGuide
    for (line in frame.lines) {
        val top = line.textBandTop(frame)
        val height = line.layout.textRows * lineHeight
        if (decorations.indentGuides) {
            for (level in 0 until guides.level(line.line)) {
                val strong = active != null && active.level == level && line.line in active.fromLine..active.toLine
                val x = left + level * guides.tabSize * charWidth
                drawRect(if (strong) colors.activeIndentGuide else colors.indentGuide, Offset(x, top), Size(width, height))
            }
        }
        if (decorations.bracketPairGuides && colors.bracketGuides.isNotEmpty()) {
            for (pair in guides.pairs) {
                if (line.line !in pair.fromLine..pair.toLine) continue
                val color = colors.bracketGuides[pair.depth % colors.bracketGuides.size]
                drawRect(color, Offset(left + pair.column * charWidth, top), Size(width, height))
            }
        }
    }
}

/** Viewport y of the first row of this line's text: below any rows injected above it. */
private fun VisibleLine.textBandTop(frame: ViewFrame): Float = top - frame.scrollY + layout.textRow * frame.metrics.lineHeight

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

/** A caret at the active end of every selection that is on screen, in [style]. */
internal fun DrawScope.drawCarets(frame: ViewFrame, selections: List<TextRange>, color: Color, style: CursorStyle = CursorStyle.Line) {
    val left = frame.textLeft
    for (selection in selections) {
        val line = frame.lineContaining(selection.end) ?: continue
        val column = (selection.end - line.start).coerceAtMost(line.shownLength)
        val layout = line.layout
        val rect = layout.cursorRect(column)
        val top = line.top - frame.scrollY + layout.rowShift(layout.rowFor(column), frame.metrics.lineHeight)
        val x = left + rect.left
        val y = top + rect.top
        // The character's width, or a space's at the end of the line, where there is none.
        val charWidth = if (column < line.shownLength) {
            layout.charBox(column).width.takeIf { it > 0f } ?: frame.metrics.charWidth
        } else {
            frame.metrics.charWidth
        }
        when (style) {
            // Like the text field: the caret starts at its x and is drawn rightwards.
            CursorStyle.Line -> drawRect(color, Offset(x, y), Size(2.dp.toPx(), rect.height))

            CursorStyle.LineThin -> drawRect(color, Offset(x, y), Size(1.dp.toPx(), rect.height))

            CursorStyle.Block -> drawRect(color.copy(alpha = color.alpha * BLOCK_CARET_ALPHA), Offset(x, y), Size(charWidth, rect.height))

            CursorStyle.BlockOutline -> {
                val stroke = 1.dp.toPx()
                drawRect(
                    color,
                    Offset(x + stroke / 2f, y + stroke / 2f),
                    Size(charWidth - stroke, rect.height - stroke),
                    style = Stroke(stroke),
                )
            }

            CursorStyle.Underline -> {
                val height = 2.dp.toPx()
                drawRect(color, Offset(x, y + rect.height - height), Size(charWidth, height))
            }

            CursorStyle.UnderlineThin -> {
                val height = 1.dp.toPx()
                drawRect(color, Offset(x, y + rect.height - height), Size(charWidth, height))
            }
        }
    }
}

/** How strongly a [CursorStyle.Block] caret fills the character's box: enough to see, not to hide it. */
private const val BLOCK_CARET_ALPHA = 0.5f
