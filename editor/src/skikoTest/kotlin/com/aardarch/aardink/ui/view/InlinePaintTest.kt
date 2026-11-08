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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.paint
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The spike's step S7, for what is drawn from a line's rows rather than from its columns. */
class InlinePaintTest {

    private val lens = LineInjections.of(Injection.rowAbove("3 references"))

    private val text = (0 until 30).joinToString("\n") { "line $it" }

    private fun PixelMap.pixels(): IntArray = IntArray(width * height) { buffer[bufferOffset + (it / width) * stride + it % width] }

    private fun stickyOf(view: EditorView, line: Int): PixelMap =
        paint(300, 20) { drawStickyLines(view, listOf(StickyLine(line, 0f)), Color.White, Color.White) }

    @Test
    fun `a pinned line is drawn as its text row, not its lens`() {
        val plain = stickyOf(viewOf(text), 4)
        val withLens = stickyOf(viewOf(text, injections = lines(4 to lens)), 4)
        val white = Color.White.toArgb()
        assertTrue(plain.pixels().any { it != white }, "the line has ink, so comparing means something")
        assertContentEquals(plain.pixels(), withLens.pixels())
    }

    @Test
    fun `a pinned line keeps what is on its own row and leaves out the rows below it`() {
        val oneRow = stickyOf(viewOf(text, injections = lines(4 to LineInjections.of(Injection(6, "ghost")))), 4)
        val rows = stickyOf(viewOf(text, injections = lines(4 to LineInjections.of(Injection(6, "ghost\nrow")))), 4)
        assertContentEquals(oneRow.pixels(), rows.pixels())
    }

    /** Whether row [row] of the tile has any ink, at [units] pixels to a row. */
    private fun PixelMap.rowDrawn(row: Int, units: Int): Boolean =
        (0 until width).any { x -> this[x, row * units].let { it.alpha > 0.5f && it.red > 0.5f } }

    @Test
    fun `a lens that moves redraws the minimap, though the row count is the same`() {
        var lensLine = 1
        val view = viewOf("first\nsecond\nthird", injections = { if (it == lensLine) lens else null })
        val tiles = MinimapTiles(view)
        val color = { _: TokenType -> Color.Red }
        assertFalse(tiles.tile(0, 2, color, 4).toPixelMap().rowDrawn(1, 2), "the lens is on row 1")
        lensLine = 2
        view.injectionsChanged()
        view.prepare(600, 400)
        val pixels = tiles.tile(0, 2, color, 4).toPixelMap()
        assertEquals(2, tiles.rendered)
        assertTrue(pixels.rowDrawn(1, 2), "line 1 is on row 1 now")
        assertFalse(pixels.rowDrawn(2, 2), "the lens is on row 2")
    }

    @Test
    fun `the minimap leaves a lens row clear and draws the line on its text row`() {
        val view = viewOf("first\nsecond\nthird", injections = lines(1 to lens))
        val pixels = MinimapTiles(view).tile(0, 2, { _: TokenType -> Color.Red }, 4).toPixelMap()
        assertEquals(4, view.totalRows)
        assertTrue(pixels.rowDrawn(0, 2), "line 0")
        assertFalse(pixels.rowDrawn(1, 2), "the lens")
        assertTrue(pixels.rowDrawn(2, 2), "line 1")
        assertTrue(pixels.rowDrawn(3, 2), "line 2")
    }
}
