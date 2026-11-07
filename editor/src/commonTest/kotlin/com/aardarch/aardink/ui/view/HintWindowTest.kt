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

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.ui.view.InlineContent.Companion.hintRange
import com.aardarch.aardink.ui.view.InlineContent.Companion.hintWindow
import com.aardarch.aardink.ui.view.InlineContent.Companion.usableHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which lines are asked about, what is asked, and what of an answer is kept. */
class HintWindowTest {

    @Test
    fun `the first window is the view and a screen either side`() {
        assertEquals(70..130, hintWindow(null, 90..110, 20))
    }

    @Test
    fun `it stops at the top of the document`() {
        assertEquals(0..30, hintWindow(null, 0..10, 20))
        assertEquals(0..35, hintWindow(null, 5..15, 20))
    }

    @Test
    fun `a view inside the window keeps it, to its very edges`() {
        val window = 70..130
        assertEquals(window, hintWindow(window, 90..110, 20))
        assertEquals(window, hintWindow(window, 70..90, 20))
        assertEquals(window, hintWindow(window, 110..130, 20))
    }

    @Test
    fun `a view leaving the window on either side gets a new one around it`() {
        assertEquals(91..151, hintWindow(70..130, 111..131, 20))
        assertEquals(49..109, hintWindow(70..130, 69..89, 20))
    }

    @Test
    fun `a closed fold on screen does not widen the margin by the lines it hides`() {
        assertEquals(0..90_030, hintWindow(null, 0..90_000, 30))
    }

    @Test
    fun `the range is whole lines, up to where the last one ends`() {
        val document = CodeDocument("ab\ncde\nf")

        assertEquals(3 until 6, hintRange(document, 1..1))
        assertEquals(0 until 8, hintRange(document, 0..2))
    }

    @Test
    fun `lines past the end of the document are its last`() {
        val document = CodeDocument("ab\ncde\nf")

        assertEquals(3 until 8, hintRange(document, 1..50))
        assertTrue(hintRange(CodeDocument(""), 0..50).isEmpty())
    }

    @Test
    fun `an answer keeps what is in the window, at a line's end too`() {
        val document = CodeDocument("ab\ncd\nef\ngh")

        val kept = listOf(InlayHint(3, "start of the window"), InlayHint(5, "end of its first line"), InlayHint(8, "end of the window"))
        val outside = listOf(InlayHint(2, "the line before"), InlayHint(9, "the line after"))

        assertEquals(kept, usableHints(kept + outside, document, 1..2))
    }

    @Test
    fun `an answer loses what is off the document or shows nothing`() {
        val document = CodeDocument("ab")
        val kept = InlayHint(2, "end of the document")

        assertEquals(
            listOf(kept),
            usableHints(
                listOf(InlayHint(-1, "x"), InlayHint(3, "x"), InlayHint(0, ""), InlayHint(0, "  "), kept),
                document,
                0..0,
            ),
        )
    }
}
