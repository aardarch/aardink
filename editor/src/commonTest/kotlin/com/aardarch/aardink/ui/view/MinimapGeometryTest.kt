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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinimapGeometryTest {

    /** An editor of [rows] rows of 20 px under 8 px of padding, 400 px tall, scrolled to [scrollY]; a 300 px minimap at 2 px a row. */
    private fun geometry(rows: Int, scrollY: Float): MinimapGeometry {
        val maxScrollY = (8f + rows * 20f - 400f).coerceAtLeast(0f)
        return MinimapGeometry(
            totalRows = rows,
            rowPx = 2f,
            lineHeight = 20f,
            paddingTop = 8f,
            scrollY = scrollY.coerceIn(0f, maxScrollY),
            maxScrollY = maxScrollY,
            viewportHeight = 400f,
            height = 300f,
        )
    }

    private fun assertClose(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 0.01f, "expected $expected, was $actual")

    @Test
    fun `a document that fits does not scroll the minimap, and the slider follows the editor`() {
        val g = geometry(rows = 100, scrollY = 208f)
        assertEquals(0f, g.minimapScroll)
        assertClose(20f, g.sliderTop) // 10 rows down
        assertClose(40f, g.sliderHeight) // the editor shows 20 rows
    }

    @Test
    fun `a taller one scrolls with the editor so the slider reaches both ends with it`() {
        val top = geometry(rows = 1_000, scrollY = 0f)
        assertEquals(0f, top.minimapScroll)
        val bottom = geometry(rows = 1_000, scrollY = Float.MAX_VALUE)
        assertClose(2_000f - 300f, bottom.minimapScroll)
        assertClose(300f, bottom.sliderTop + bottom.sliderHeight)
    }

    @Test
    fun `the scroll for a slider position undoes the slider position for a scroll`() {
        for (scroll in listOf(0f, 1_234f, 9_000f, 19_608f)) {
            val g = geometry(rows = 1_000, scrollY = scroll)
            assertClose(g.scrollY, g.scrollForSliderTop(g.sliderTop))
        }
    }

    @Test
    fun `a point in the minimap is the row drawn there`() {
        val g = geometry(rows = 1_000, scrollY = Float.MAX_VALUE)
        assertEquals(850, g.rowAt(0f))
        assertEquals(999, g.rowAt(299f))
    }
}
