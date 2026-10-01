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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** The minimap's tiles: what a row looks like, and that a tile is drawn again only when one of its rows changes. */
class MinimapTilesTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)

    private fun viewOf(text: String): EditorView {
        val state = CodeEditorState(initialText = "", tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
            loadText(text)
        }
        return EditorView(state).apply {
            style = ViewStyle(
                measurer = measurer,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
                tokenStyle = { null },
                placeholderStyle = SpanStyle(color = Color.Gray),
                metrics = EditorMetrics(
                    lineHeight = 20f,
                    charWidth = 8f,
                    paddingTop = 8f,
                    paddingBottom = 0f,
                    paddingStart = 8f,
                    paddingEnd = 8f,
                ),
                softWrap = false,
            )
            attach()
            prepare(400, 400)
        }
    }

    private val color: (TokenType) -> Color = { Color.Red }

    private fun PixelMap.drawn(x: Int, y: Int): Boolean = this[x, y].let { it.alpha > 0.5f && it.red > 0.5f }

    @Test
    fun `a row is a bar per run of characters, with the spaces left clear`() {
        val view = viewOf("ab  cd\n\tx")
        val pixels = MinimapTiles(view).tile(0, rowUnits = 2, color = color, tabSize = 4).toPixelMap()
        // Row 0, two pixels high: columns 0-1 and 4-5 drawn, 2-3 and 6 clear.
        for (x in listOf(0, 1, 4, 5)) assertTrue(pixels.drawn(x, 0) && pixels.drawn(x, 1), "column $x")
        for (x in listOf(2, 3, 6)) assertTrue(!pixels.drawn(x, 0), "column $x")
        // Row 1: a tab takes the columns to the next tab stop.
        assertTrue(!pixels.drawn(3, 2))
        assertTrue(pixels.drawn(4, 2))
    }

    @Test
    fun `a tile is drawn again only when one of its rows changes`() {
        val view = viewOf((0 until 600).joinToString("\n") { "line $it" })
        val tiles = MinimapTiles(view)
        tiles.tile(0, 2, color, 4)
        tiles.tile(1, 2, color, 4)
        assertEquals(2, tiles.rendered)
        tiles.tile(0, 2, color, 4)
        assertEquals(2, tiles.rendered, "nothing changed")
        // An edit in tile 1 (rows 256-511) leaves tile 0 as it was.
        view.state.applyEdit(view.state.document.lineStart(300), 0, "x", TextRange(0))
        view.prepare(400, 400)
        tiles.tile(0, 2, color, 4)
        assertEquals(2, tiles.rendered)
        tiles.tile(1, 2, color, 4)
        assertEquals(3, tiles.rendered)
    }

    @Test
    fun `a line added above moves the rows below it, so their tiles are drawn again`() {
        val view = viewOf((0 until 600).joinToString("\n") { "line $it" })
        val tiles = MinimapTiles(view)
        tiles.tile(0, 2, color, 4)
        tiles.tile(1, 2, color, 4)
        view.state.applyEdit(0, 0, "a new first line\n", TextRange(0))
        view.prepare(400, 400)
        tiles.tile(0, 2, color, 4)
        tiles.tile(1, 2, color, 4)
        assertEquals(4, tiles.rendered)
    }

    @Test
    fun `drawing the tiles of a 5,000 line document stays cheap`() {
        val view = viewOf((0 until 5_000).joinToString("\n") { "    fun line$it(x: Int) = x * $it // comment" })
        val tiles = MinimapTiles(view)
        val first = measureTime { for (index in 0 until 20) tiles.tile(index, 2, color, 4) }
        val warm = measureTime { repeat(300) { tiles.tile(17 + it % 3, 2, color, 4) } }
        // Informational on the JVM; the per-frame gate runs on wasm, in tools/vite-smoke/perf.mjs.
        println("minimap: 20 tiles drawn in $first, 300 cached lookups in $warm")
        assertEquals(20, tiles.rendered, "cached tiles are not drawn again")
    }
}
