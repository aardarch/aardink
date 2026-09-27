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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.TokenType
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Columns of text the minimap shows, one dp each. */
internal const val MINIMAP_COLUMNS = 90

/** Rows of text per cached tile. */
internal const val MINIMAP_TILE_ROWS = 256

/** Above this many rows each row takes one dp instead of two. */
internal const val MINIMAP_SCALE_ROWS = 10_000

/** The minimap's width. */
internal val MINIMAP_WIDTH = MINIMAP_COLUMNS.dp

/**
 * Where things are in the minimap, in pixels, for a document of [totalRows] rows each [rowPx] tall
 * there, shown in an editor whose rows are [lineHeight] tall below [paddingTop], scrolled to
 * [scrollY] of [maxScrollY] in a viewport [viewportHeight] tall; the minimap is [height] tall.
 *
 * As in VS Code, a minimap taller than it has room for scrolls along with the editor, in
 * proportion, so its slider reaches the top and the bottom together with the editor's scroll.
 */
internal class MinimapGeometry(
    val totalRows: Int,
    val rowPx: Float,
    val lineHeight: Float,
    val paddingTop: Float,
    val scrollY: Float,
    val maxScrollY: Float,
    val viewportHeight: Float,
    val height: Float,
) {
    private val contentHeight = totalRows * rowPx

    /** How far the minimap itself is scrolled. */
    val minimapScroll: Float = scrollFor(scrollY)

    /** The slider: over the rows the editor shows. */
    val sliderTop: Float = sliderTopFor(scrollY)
    val sliderHeight: Float = (viewportHeight / lineHeight * rowPx).coerceAtMost(height)

    private fun scrollFor(scroll: Float): Float =
        if (contentHeight <= height || maxScrollY <= 0f) 0f else scroll / maxScrollY * (contentHeight - height)

    private fun sliderTopFor(scroll: Float): Float = (scroll - paddingTop) / lineHeight * rowPx - scrollFor(scroll)

    /** The row at minimap y [y]. */
    fun rowAt(y: Float): Int = floor((y + minimapScroll) / rowPx).toInt().coerceIn(0, (totalRows - 1).coerceAtLeast(0))

    /** The editor scroll that puts the slider's top at [top]: the inverse of [sliderTopFor], which is linear. */
    fun scrollForSliderTop(top: Float): Float {
        val a = sliderTopFor(0f)
        val b = sliderTopFor(maxScrollY.coerceAtLeast(1f))
        if (b == a) return scrollY
        return ((top - a) / (b - a) * maxScrollY.coerceAtLeast(1f)).coerceIn(0f, maxScrollY)
    }
}

/**
 * The minimap's pictures of the text, [MINIMAP_TILE_ROWS] rows to a tile: each row a bar per run
 * of characters in the colour of its token, no glyphs, as VS Code draws its minimap with characters
 * rendered as blocks. A tile is drawn once and kept until the text, the tokens, or which line is on
 * which row change for one of its rows; only tiles on screen are drawn at all.
 */
internal class MinimapTiles(private val view: EditorView) {

    private class Tile(
        val image: ImageBitmap,
        val rowUnits: Int,
        /** Per row: the line, its tokens' identity, and a hash of the text the tile shows of it. */
        val lines: IntArray,
        val tokens: Array<Any?>,
        val hashes: IntArray,
        var documentVersion: Long,
        var tokenVersion: Int,
        var totalRows: Int,
    )

    private val tiles = LinkedHashMap<Int, Tile>()

    /** Tiles drawn so far, for tests. */
    var rendered = 0
        private set

    private val paint = Paint()

    /** The picture of tile [index], [rowUnits] pixels to a row, drawn now if it is missing or stale. */
    fun tile(index: Int, rowUnits: Int, color: (TokenType) -> Color, tabSize: Int): ImageBitmap {
        val state = view.state
        val documentVersion = state.document.version
        val tokenVersion = state.tokenVersion
        val totalRows = view.totalRows
        val cached = tiles.remove(index)
        if (cached != null && cached.rowUnits == rowUnits) {
            val current = cached.documentVersion == documentVersion && cached.tokenVersion == tokenVersion && cached.totalRows == totalRows
            if (current || matches(cached, index)) {
                cached.documentVersion = documentVersion
                cached.tokenVersion = tokenVersion
                cached.totalRows = totalRows
                // Back in as the most recently used.
                tiles[index] = cached
                return cached.image
            }
        }
        val tile = render(index, rowUnits, color, tabSize)
        tiles[index] = tile
        // A few screens of tiles; the least recently drawn go first.
        while (tiles.size > MAX_TILES) tiles.remove(tiles.keys.first())
        return tile.image
    }

    private fun matches(tile: Tile, index: Int): Boolean {
        for (i in 0 until MINIMAP_TILE_ROWS) {
            val row = index * MINIMAP_TILE_ROWS + i
            if (tile.lines[i] != lineOnRow(row)) return false
            val line = tile.lines[i]
            if (line < 0) continue
            if (tile.tokens[i] !== view.state.tokenStore.lineTokens(line) || tile.hashes[i] != hashOf(line)) return false
        }
        return true
    }

    /** The line whose first row is [row], or -1 when [row] continues a wrapped line or is past the end. */
    private fun lineOnRow(row: Int): Int {
        if (row >= view.totalRows) return -1
        val line = view.lineMap.lineAtRow(row)
        return if (view.lineMap.firstRowOf(line) == row) line else -1
    }

    /** A hash of the part of [line] the minimap shows. */
    private fun hashOf(line: Int): Int {
        val document = view.state.document
        val start = document.lineStart(line)
        val end = min(document.lineEnd(line), start + MINIMAP_COLUMNS)
        var hash = end - start
        for (i in start until end) hash = hash * 31 + document[i].code
        return hash
    }

    private fun render(index: Int, rowUnits: Int, color: (TokenType) -> Color, tabSize: Int): Tile {
        rendered++
        val document = view.state.document
        val image = ImageBitmap(MINIMAP_COLUMNS, MINIMAP_TILE_ROWS * rowUnits)
        val canvas = Canvas(image)
        val lines = IntArray(MINIMAP_TILE_ROWS)
        val tokens = arrayOfNulls<Any?>(MINIMAP_TILE_ROWS)
        val hashes = IntArray(MINIMAP_TILE_ROWS)
        for (i in 0 until MINIMAP_TILE_ROWS) {
            val line = lineOnRow(index * MINIMAP_TILE_ROWS + i)
            lines[i] = line
            if (line < 0) continue
            val runs = view.state.tokenStore.lineTokens(line)
            tokens[i] = runs
            hashes[i] = hashOf(line)
            val start = document.lineStart(line)
            val length = document.lineEnd(line) - start
            val top = (i * rowUnits).toFloat()
            var column = 0
            var char = 0
            var token = 0
            var runStart = -1
            var runColor = Color.Unspecified
            fun flush() {
                if (runStart < 0) return
                paint.color = runColor
                canvas.drawRect(runStart.toFloat(), top, column.toFloat(), top + rowUnits, paint)
                runStart = -1
            }
            while (char < length && column < MINIMAP_COLUMNS) {
                val c = document[start + char]
                if (c == ' ' || c == '\t') {
                    flush()
                    column += if (c == '\t') tabSize - column % tabSize else 1
                } else {
                    while (token < runs.size && runs.end(token) <= char) token++
                    val type = if (token < runs.size && runs.start(token) <= char) runs.types[token] else TokenType.Default
                    val next = color(type)
                    if (runStart >= 0 && next != runColor) flush()
                    if (runStart < 0) {
                        runStart = column
                        runColor = next
                    }
                    column++
                }
                char++
            }
            column = column.coerceAtMost(MINIMAP_COLUMNS)
            flush()
        }
        val state = view.state
        return Tile(image, rowUnits, lines, tokens, hashes, state.document.version, state.tokenVersion, view.totalRows)
    }

    /**
     * Draws the minimap for the editor as it is scrolled now: the tiles on screen, scaled from their
     * pixels to dp without smoothing, and the slider over the rows the editor shows.
     */
    fun draw(scope: DrawScope, geometry: MinimapGeometry, rowUnits: Int, color: (TokenType) -> Color, tabSize: Int, slider: Color) =
        with(scope) {
            val scale = geometry.rowPx / rowUnits
            val tileHeight = MINIMAP_TILE_ROWS * geometry.rowPx
            if (geometry.totalRows > 0) {
                val first = (geometry.minimapScroll / tileHeight).toInt()
                val last = ((geometry.minimapScroll + size.height) / tileHeight).toInt().coerceAtMost(
                    (geometry.totalRows - 1) / MINIMAP_TILE_ROWS,
                )
                for (index in first..last) {
                    val image = tile(index, rowUnits, color, tabSize)
                    val y = index * tileHeight - geometry.minimapScroll
                    drawImage(
                        image,
                        dstOffset = IntOffset(0, y.roundToInt()),
                        dstSize = IntSize((MINIMAP_COLUMNS * scale).roundToInt(), tileHeight.roundToInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            drawRect(slider, Offset(0f, geometry.sliderTop), Size(size.width, geometry.sliderHeight))
        }

    private companion object {
        const val MAX_TILES = 16
    }
}

/**
 * The minimap at the side of the text: the whole document in small, in its token colours, with a
 * slider over what the editor shows. A press jumps there, and dragging moves the editor with it.
 */
@Composable
internal fun MinimapView(
    view: EditorView,
    tokenColor: (TokenType) -> Color,
    background: Color,
    slider: Color,
    tabSize: Int,
    modifier: Modifier = Modifier,
) {
    val tiles = remember(view) { MinimapTiles(view) }
    fun geometry(height: Float, density: Float): MinimapGeometry? {
        val metrics = view.style?.metrics ?: return null
        val totalRows = view.totalRows
        val rowDp = if (totalRows > MINIMAP_SCALE_ROWS) 1f else 2f
        return MinimapGeometry(
            totalRows = totalRows,
            rowPx = rowDp * density,
            lineHeight = metrics.lineHeight,
            paddingTop = metrics.paddingTop,
            scrollY = view.scroll.scrollY,
            maxScrollY = view.scroll.maxScrollY,
            viewportHeight = view.scroll.viewportHeight.toFloat(),
            height = height,
        )
    }
    Box(
        modifier = modifier
            .width(MINIMAP_WIDTH)
            .fillMaxHeight()
            .background(background)
            .clipToBounds()
            .pointerInput(view) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = geometry(size.height.toFloat(), density) ?: return@awaitEachGesture
                    // A press off the slider brings the rows there to the middle of the editor first.
                    val grab = if (down.position.y in start.sliderTop..start.sliderTop + start.sliderHeight) {
                        down.position.y - start.sliderTop
                    } else {
                        val row = start.rowAt(down.position.y)
                        val metrics = view.style?.metrics
                        if (metrics != null) {
                            view.scroll.scrollToY(metrics.paddingTop + row * metrics.lineHeight - view.scroll.viewportHeight / 2f)
                        }
                        start.sliderHeight / 2f
                    }
                    down.consume()
                    drag(down.id) { change ->
                        val now = geometry(size.height.toFloat(), density) ?: return@drag
                        view.scroll.scrollToY(now.scrollForSliderTop(change.position.y - grab))
                        change.consume()
                    }
                }
            }
            .drawBehind {
                val geometry = geometry(size.height, density) ?: return@drawBehind
                // Read so the minimap redraws when the text or its colours change.
                view.frame
                view.state.tokenVersion
                val rowUnits = if (geometry.totalRows > MINIMAP_SCALE_ROWS) 1 else 2
                tiles.draw(this, geometry, rowUnits, tokenColor, tabSize, slider)
            },
    )
}
