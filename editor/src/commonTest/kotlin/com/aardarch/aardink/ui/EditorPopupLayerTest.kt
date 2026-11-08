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
package com.aardarch.aardink.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorPopupLayerTest {

    // The editor's text area at (100, 50) in a 1000 × 800 window; a caret row from y 200 to 220.
    private val host = IntRect(100, 50, 900, 750)
    private val window = IntSize(1000, 800)
    private val caret = Rect(40f, 200f, 42f, 220f)
    private val popup = IntSize(300, 120)

    @Test
    fun `below the caret, at its x, when it fits`() {
        assertEquals(IntOffset(140, 50 + 220 + 4), placePopup(host, caret, window, popup, preferAbove = false, gap = 4))
    }

    @Test
    fun `above when asked and it fits`() {
        assertEquals(IntOffset(140, 50 + 200 - 4 - 120), placePopup(host, caret, window, popup, preferAbove = true, gap = 4))
    }

    @Test
    fun `flips above near the bottom of the window`() {
        val low = Rect(40f, 690f, 42f, 710f)
        assertEquals(50 + 690 - 4 - 120, placePopup(host, low, window, popup, preferAbove = false, gap = 4).y)
    }

    @Test
    fun `flips below near the top of the window`() {
        val high = Rect(40f, 10f, 42f, 30f)
        assertEquals(50 + 30 + 4, placePopup(host, high, window, popup, preferAbove = true, gap = 4).y)
    }

    @Test
    fun `moves left to stay inside the window's right edge`() {
        val right = Rect(850f, 200f, 852f, 220f)
        assertEquals(1000 - 300, placePopup(host, right, window, popup, preferAbove = false, gap = 4).x)
    }

    @Test
    fun `too tall for either side, it takes the roomier one, clamped into the window`() {
        val tall = IntSize(300, 600)
        assertEquals(800 - 600, placePopup(host, caret, window, tall, preferAbove = false, gap = 4).y)
    }

    // A list of 300 x 240 below the caret, and a pane of 200 x 100 to go with it.
    private val list = IntRect(IntOffset(140, 274), IntSize(300, 240))
    private val pane = IntSize(200, 100)

    @Test
    fun `a pane goes to the right of the list, level with its top`() {
        assertEquals(IntOffset(444, 274), placeBeside(list, listAboveAnchor = false, window, pane, gap = 4))
    }

    @Test
    fun `a pane goes to the left of a list against the right edge`() {
        val right = IntRect(IntOffset(700, 274), IntSize(300, 240))
        assertEquals(IntOffset(700 - 4 - 200, 274), placeBeside(right, listAboveAnchor = false, window, pane, gap = 4))
    }

    @Test
    fun `beside a list that flipped above the caret, a pane lines up with its bottom`() {
        val flipped = IntRect(IntOffset(140, 300), IntSize(300, 240))
        assertEquals(IntOffset(444, 540 - 100), placeBeside(flipped, listAboveAnchor = true, window, pane, gap = 4))
    }

    @Test
    fun `a pane taller than the room under the list's top moves up into the window`() {
        val low = IntRect(IntOffset(140, 700), IntSize(300, 100))
        assertEquals(800 - 100, placeBeside(low, listAboveAnchor = false, window, pane, gap = 4).y)
    }

    @Test
    fun `with no room at either side a pane goes below a list under the caret`() {
        val narrow = IntSize(400, 800)
        val wide = IntRect(IntOffset(50, 274), IntSize(300, 240))
        assertEquals(IntOffset(50, 514 + 4), placeBeside(wide, listAboveAnchor = false, narrow, pane, gap = 4))
    }

    @Test
    fun `with no room at either side a pane goes above a list over the caret, off the caret's line`() {
        val narrow = IntSize(400, 800)
        val wide = IntRect(IntOffset(50, 274), IntSize(300, 240))
        assertEquals(IntOffset(50, 274 - 4 - 100), placeBeside(wide, listAboveAnchor = true, narrow, pane, gap = 4))
    }

    @Test
    fun `with no room on its own side a pane takes the other`() {
        val narrow = IntSize(400, 800)
        val low = IntRect(IntOffset(50, 600), IntSize(300, 180))
        assertEquals(600 - 4 - 100, placeBeside(low, listAboveAnchor = false, narrow, pane, gap = 4).y)
        val high = IntRect(IntOffset(50, 20), IntSize(300, 180))
        assertEquals(200 + 4, placeBeside(high, listAboveAnchor = true, narrow, pane, gap = 4).y)
    }

    @Test
    fun `wherever the list is, a pane that fits the window stays in it`() {
        val small = IntSize(320, 240)
        val shapes = listOf(IntSize(220, 120), IntSize(300, 230), IntSize(320, 240))
        val panes = listOf(IntSize(100, 40), IntSize(200, 200), IntSize(320, 240))
        for (listSize in shapes) {
            for (paneSize in panes) {
                for (x in 0..(small.width - listSize.width) step 10) {
                    for (y in 0..(small.height - listSize.height) step 10) {
                        for (above in listOf(false, true)) {
                            val at = placeBeside(IntRect(IntOffset(x, y), listSize), above, small, paneSize, gap = 4)
                            val where = "list $listSize at ($x, $y), pane $paneSize at $at"
                            assertTrue(at.x >= 0 && at.x + paneSize.width <= small.width, where)
                            assertTrue(at.y >= 0 && at.y + paneSize.height <= small.height, where)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a pane wider than the window starts at its edge`() {
        val huge = IntSize(1200, 900)
        assertEquals(IntOffset(0, 0), placeBeside(list, listAboveAnchor = false, window, huge, gap = 4))
    }

    @Test
    fun `the provider puts a pane beside where the list really is`() {
        val listSize = IntSize(300, 240)
        val provider = BesidePopupPositionProvider(caret, listSize, gap = 4)
        val at = placePopup(host, caret, window, listSize, preferAbove = false, gap = 4)
        assertEquals(
            IntOffset(at.x + 300 + 4, at.y),
            provider.calculatePosition(host, window, LayoutDirection.Ltr, pane),
        )
    }

    @Test
    fun `the provider sees a list that flipped above the caret`() {
        val listSize = IntSize(300, 240)
        val low = Rect(40f, 690f, 42f, 710f)
        val provider = BesidePopupPositionProvider(low, listSize, gap = 4)
        val at = placePopup(host, low, window, listSize, preferAbove = false, gap = 4)
        assertEquals(
            IntOffset(at.x + 300 + 4, at.y + 240 - 100),
            provider.calculatePosition(host, window, LayoutDirection.Ltr, pane),
        )
    }
}
