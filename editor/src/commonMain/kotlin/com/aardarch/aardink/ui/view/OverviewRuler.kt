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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.LineDiffKind
import kotlin.math.ceil
import kotlin.math.floor

/** Which third of the overview ruler a mark is in, as VS Code lays its ruler out. */
internal enum class RulerLane {
    /** Changed lines and the host's decorations. */
    Left,

    /** Find matches and carets. */
    Center,

    /** Diagnostics. */
    Right,
}

/** A mark in the overview ruler: the lines from [firstLine] to [lastLine], in [lane]. */
@Immutable
internal class RulerMark(val firstLine: Int, val lastLine: Int, val color: Color, val lane: RulerLane)

/** The colours the ruler's marks take, the theme's resolved. */
@Immutable
internal class RulerColors(
    val error: Color,
    val warning: Color,
    val info: Color,
    val findMatch: Color,
    val selection: Color,
    val added: Color,
    val modified: Color,
)

/**
 * The overview ruler's marks for a document: its changed lines and the host's decorations (with an
 * overview-ruler colour) on the left, find matches and carets in the middle, diagnostics on the
 * right, the most severe drawn last. The work is per mark (find matches are capped), never per
 * line of the document.
 */
internal fun overviewRulerMarks(
    document: CodeDocument,
    diagnostics: List<Diagnostic>,
    findMatches: List<IntRange>,
    selections: List<TextRange>,
    diff: Map<Int, LineDiffKind>,
    decorations: List<Decoration>,
    colors: RulerColors,
): List<RulerMark> {
    val length = document.length
    fun lineOf(offset: Int) = document.offsetToLineCol(offset.coerceIn(0, length)).first
    val marks = ArrayList<RulerMark>()
    for ((line, kind) in diff) {
        marks += RulerMark(line, line, if (kind == LineDiffKind.Added) colors.added else colors.modified, RulerLane.Left)
    }
    for (decoration in decorations) {
        val color = decoration.style.overviewRulerColor
        if (color == Color.Unspecified) continue
        val first = lineOf(decoration.range.first)
        val last = if (decoration.range.isEmpty()) first else lineOf(decoration.range.last)
        marks += RulerMark(first, last, color, RulerLane.Left)
    }
    for (match in findMatches) {
        val line = lineOf(match.first)
        marks += RulerMark(line, line, colors.findMatch, RulerLane.Center)
    }
    for (selection in selections) {
        val line = lineOf(selection.end)
        marks += RulerMark(line, line, colors.selection, RulerLane.Center)
    }
    for (diagnostic in diagnostics.sortedByDescending { it.severity }) {
        val color = when (diagnostic.severity) {
            DiagnosticSeverity.Error -> colors.error
            DiagnosticSeverity.Warning -> colors.warning
            DiagnosticSeverity.Info -> colors.info
            DiagnosticSeverity.Hint -> continue
        }
        val line = lineOf(diagnostic.range.first)
        marks += RulerMark(line, line, color, RulerLane.Right)
    }
    return marks
}

/**
 * The marks of [marks] in buckets of one pixel row of a ruler [height] pixels tall, per lane: what
 * the ruler draws, so a frame draws runs of rows rather than every mark. [rowsOf] gives the rows a
 * run of lines takes on screen and [totalRows] the document's; a mark is at least [minHeight]
 * pixels tall. A later mark in a lane covers an earlier one.
 */
internal class RulerBuckets(marks: List<RulerMark>, height: Int, totalRows: Int, minHeight: Int, rowsOf: (Int, Int) -> Pair<Int, Int>) {
    val lanes: Array<Array<Color?>> = Array(RulerLane.entries.size) { arrayOfNulls(height.coerceAtLeast(0)) }

    init {
        if (height > 0 && totalRows > 0) {
            val scale = height.toFloat() / totalRows
            for (mark in marks) {
                val (first, end) = rowsOf(mark.firstLine, mark.lastLine)
                val top = floor(first * scale).toInt().coerceIn(0, height - 1)
                val bottom = maxOf(ceil(end * scale).toInt(), top + minHeight).coerceAtMost(height)
                val lane = lanes[mark.lane.ordinal]
                for (y in top until bottom) lane[y] = mark.color
            }
        }
    }
}

/**
 * The overview ruler beside the text ([com.aardarch.aardink.ui.EditorOptions.overviewRuler]): the
 * whole document mapped onto its height, with [marks] where they are and a faint band over what
 * the editor shows. A press scrolls there, putting the line in the middle.
 */
@Composable
internal fun OverviewRuler(
    view: EditorView,
    marks: () -> List<RulerMark>,
    background: Color,
    border: Color,
    visibleRegion: Color,
    modifier: Modifier = Modifier,
) {
    val currentMarks = rememberUpdatedState(marks)
    // The buckets are worked out again only when the marks, the height or the rows change.
    val cache = remember(view) { arrayOfNulls<Any>(4) }
    Box(
        modifier = modifier
            .width(OVERVIEW_RULER_WIDTH)
            .fillMaxHeight()
            .clipToBounds()
            .pointerInput(view) {
                detectTapGestures { position ->
                    val metrics = view.style?.metrics ?: return@detectTapGestures
                    val totalRows = view.totalRows
                    if (totalRows == 0 || size.height == 0) return@detectTapGestures
                    val row = position.y / size.height * totalRows
                    view.scroll.scrollToY(metrics.paddingTop + row * metrics.lineHeight - view.scroll.viewportHeight / 2f)
                }
            }
            .drawBehind {
                drawRect(background)
                // Read so it redraws when the rows change.
                view.frame
                val list = currentMarks.value()
                val height = size.height.toInt()
                val totalRows = view.totalRows
                val buckets = if (cache[0] === list && cache[1] == height && cache[2] == totalRows) {
                    cache[3] as RulerBuckets
                } else {
                    RulerBuckets(list, height, totalRows, 2.dp.roundToPx(), view::rowsOfLines).also {
                        cache[0] = list
                        cache[1] = height
                        cache[2] = totalRows
                        cache[3] = it
                    }
                }
                val laneWidth = size.width / RulerLane.entries.size
                for ((index, lane) in buckets.lanes.withIndex()) {
                    var y = 0
                    while (y < lane.size) {
                        val color = lane[y]
                        if (color == null) {
                            y++
                            continue
                        }
                        var end = y + 1
                        while (end < lane.size && lane[end] == color) end++
                        drawRect(color, Offset(index * laneWidth, y.toFloat()), Size(laneWidth, (end - y).toFloat()))
                        y = end
                    }
                }
                // What the editor shows, as a band.
                val content = view.scroll.contentHeight
                if (content > 0f) {
                    val top = view.scroll.scrollY / content * size.height
                    val bandHeight = (view.scroll.viewportHeight / content * size.height).coerceAtMost(size.height)
                    drawRect(visibleRegion, Offset(0f, top), Size(size.width, bandHeight))
                }
                if (border != Color.Unspecified) drawRect(border, Offset.Zero, Size(1.dp.toPx(), size.height))
            },
    )
}

/** How wide the overview ruler is: VS Code's, about a scrollbar's width. */
internal val OVERVIEW_RULER_WIDTH = 12.dp
