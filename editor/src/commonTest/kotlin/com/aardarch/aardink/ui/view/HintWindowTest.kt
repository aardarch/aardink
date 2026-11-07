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
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.ui.view.InlineContent.Companion.hintRange
import com.aardarch.aardink.ui.view.InlineContent.Companion.hintWindow
import com.aardarch.aardink.ui.view.InlineContent.Companion.usableHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which lines are asked about, what is asked, and what of an answer is kept. */
class HintWindowTest {

    private fun window(vararg runs: IntRange) = HintWindow(runs.toList())

    private fun closed(vararg lines: IntRange, lineCount: Int = 200_000) =
        FoldLayout.of(lines.map { FoldRange(it.first, it.last) }, lineCount)

    @Test
    fun `the first window is the view and a screen either side`() {
        assertEquals(window(70..130), hintWindow(null, 90..110, 20))
    }

    @Test
    fun `it stops at the top of the document`() {
        assertEquals(window(0..30), hintWindow(null, 0..10, 20))
        assertEquals(window(0..35), hintWindow(null, 5..15, 20))
    }

    @Test
    fun `it stops at the bottom of the document`() {
        assertEquals(window(70..119), hintWindow(null, 90..110, 20, lineCount = 120))
    }

    @Test
    fun `a view inside the window keeps it, to its very edges`() {
        val asked = window(70..130)
        assertSame(asked, hintWindow(asked, 90..110, 20))
        assertSame(asked, hintWindow(asked, 70..90, 20))
        assertSame(asked, hintWindow(asked, 110..130, 20))
    }

    @Test
    fun `a view leaving the window on either side gets a new one around it`() {
        assertEquals(window(91..151), hintWindow(window(70..130), 111..131, 20))
        assertEquals(window(49..109), hintWindow(window(70..130), 69..89, 20))
    }

    // ── With folds closed ────────────────────────────────────────────────────

    @Test
    fun `a long closed fold on screen parts the window around what it hides`() {
        val folds = closed(10..90_000)

        assertEquals(listOf(0..10, 90_001..90_050), hintWindow(null, 0..90_020, 30, folds).runs)
    }

    @Test
    fun `the margin is shown lines, so a fold just past the view does not use it up`() {
        val folds = closed(50..90, 120..5_000)

        assertEquals(listOf(46..50, 91..115), hintWindow(null, 91..110, 5, folds).runs)
        assertEquals(listOf(95..120, 5_001..5_005), hintWindow(null, 100..120, 5, folds).runs)
    }

    @Test
    fun `a fold of a few lines stays inside its run, as one question beats two`() {
        assertEquals(listOf(0..50), hintWindow(null, 0..30, 20, closed(10..12)).runs)
    }

    @Test
    fun `a fold that takes the document to its end asks nothing past it`() {
        val folds = closed(5..99, lineCount = 100)

        assertEquals(listOf(0..5), hintWindow(null, 0..5, 10, folds, lineCount = 100).runs)
    }

    @Test
    fun `a screen of long folds asks a few questions about few lines, however long the document`() {
        val lines = 1_000_000
        val everyFold = (0 until lines step 100).map { it..it + 98 }.toTypedArray()
        val folds = closed(*everyFold, lineCount = lines)

        val window = hintWindow(null, 0..2_900, 30, folds, lineCount = lines)

        assertTrue(window.runs.size <= 8, "${window.runs.size} questions")
        assertEquals(0, window.runs.first().first, "from the top of the view")
        assertTrue(window.runs.sumOf { it.last - it.first + 1 } < 30, "${window.runs}")
    }

    @Test
    fun `a change of folds ends the window, though the view is inside it`() {
        val folds = closed(10..12)
        val asked = hintWindow(null, 0..30, 20, folds)
        assertSame(asked, hintWindow(asked, 0..30, 20, folds), "with the folds as they were")

        val opened = hintWindow(asked, 0..28, 20, FoldLayout.None)

        assertNotSame(asked, opened)
        assertNotEquals(asked, opened, "or nothing would ask about the lines that came into view")
    }

    @Test
    fun `an answer loses what a closed fold hides, in the middle of a run too`() {
        val document = CodeDocument("a\nb\nc\nd\ne")
        val kept = listOf(InlayHint(0, "line 0"), InlayHint(2, "where the fold starts"), InlayHint(8, "line 4"))
        val hidden = listOf(InlayHint(4, "hidden"), InlayHint(7, "the fold's end"))

        assertEquals(kept, usableHints(kept + hidden, document, HintWindow(listOf(0..4), closed(1..3))))
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

        assertEquals(kept, usableHints(kept + outside, document, window(1..2)))
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
                window(0..0),
            ),
        )
    }
}
