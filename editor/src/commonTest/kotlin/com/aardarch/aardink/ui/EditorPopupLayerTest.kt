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
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
