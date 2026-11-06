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

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.measurer
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.textStyle
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The spike's step S6, on desktop and in the browser (`sample`'s `HardBreakLayoutTest` is the
 * Android half): a hard break in a line's paragraph is a row, with soft wrap on and off.
 * Compose's behaviour with `softWrap = false` had never been verified, so the first test asks
 * it directly, without the view.
 */
class HardBreakTest {

    private val wraps = listOf(false, true)

    /** What the view relies on from the text engine, for one [text] whose rows are known. */
    private fun checkEngine(text: String, softWrap: Boolean, rows: List<Int>) {
        val result = measurer.measure(
            text = text,
            style = textStyle,
            softWrap = softWrap,
            constraints = if (softWrap) Constraints(maxWidth = 2_000) else Constraints(),
            skipCache = true,
        )
        val context = "'${text.replace('\n', '|')}', softWrap = $softWrap"
        assertEquals(rows.last() + 1, result.lineCount, "rows of $context")
        assertEquals(rows, (0..text.length).map { result.getLineForOffset(it) }, "getLineForOffset of $context")
        for (row in 1 until result.lineCount) {
            assertTrue(result.getLineTop(row) > result.getLineTop(row - 1), "row $row is below the one above in $context")
        }
    }

    @Test
    fun `the text engine makes a row of every hard break, with soft wrap on and off`() {
        for (wrap in wraps) {
            checkEngine("lens\ncode", wrap, listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1))
            checkEngine("lens\n", wrap, listOf(0, 0, 0, 0, 0, 1))
            checkEngine("\ncode", wrap, listOf(0, 1, 1, 1, 1, 1))
            checkEngine("a\n\nb", wrap, listOf(0, 0, 1, 2, 2))
            checkEngine("a\nb\n", wrap, listOf(0, 0, 1, 1, 2))
        }
    }

    @Test
    fun `a placeholder and a hard break live in one paragraph`() {
        for (wrap in wraps) {
            val placeholder = Placeholder(4.em, 1.em, PlaceholderVerticalAlign.TextCenter)
            val result = measurer.measure(
                text = AnnotatedString("lens\n\tx"),
                style = textStyle,
                softWrap = wrap,
                constraints = if (wrap) Constraints(maxWidth = 2_000) else Constraints(),
                placeholders = listOf(AnnotatedString.Range(placeholder, 5, 6)),
                skipCache = true,
            )
            assertEquals(2, result.lineCount)
            assertEquals(1, result.getLineForOffset(5))
            assertTrue(result.getCursorRect(6).left > result.getCursorRect(5).left + 40f, "the tab has its width, softWrap = $wrap")
        }
    }

    /** That lineCount, rowFor and rowShift agree on every line of [view] and with its row map. */
    private fun checkRows(view: EditorView, context: String) {
        for (visible in view.frame.lines) {
            val layout = visible.layout
            assertEquals(view.lineMap.rowsOf(visible.line), layout.lineCount, "the row map has the layout's rows: $context")
            for (column in 0..visible.shownLength) {
                val row = layout.rowFor(column)
                assertTrue(row in 0 until layout.lineCount, "row $row of ${layout.lineCount}: $context")
                assertFalse(layout.isInjectedRow(row), "a caret at $column is on a text row: $context")
                val rect = view.caretRect(visible.start + column)
                val band = visible.top - view.scroll.scrollY + row * LINE_HEIGHT
                assertTrue(rect.top >= band - 4f && rect.top < band + LINE_HEIGHT, "the caret at $column is in its row's band: $context")
            }
            for (row in 0 until layout.lineCount) {
                val top = layout.getLineTop(row) + layout.rowShift(row, LINE_HEIGHT)
                assertEquals(row * LINE_HEIGHT + layout.getLineTop(0), top, 0.5f, "row $row sits at the view's pitch: $context")
            }
        }
    }

    @Test
    fun `a row above a line`() {
        for (wrap in wraps) {
            val view = viewOf("first\nsecond\nthird", wrap, lines(1 to LineInjections.of(Injection.rowAbove("lens"))))
            val layout = view.shown(1).layout
            assertEquals(2, layout.lineCount)
            assertEquals(1, layout.textRow)
            assertEquals(1, layout.lastTextRow)
            assertTrue(layout.isInjectedRow(0))
            assertEquals(1, layout.rowFor(0))
            assertEquals(1, layout.rowFor(6))
            assertEquals(4, view.lineMap.totalRows)
            checkRows(view, "wrap = $wrap")
        }
    }

    @Test
    fun `a row above an empty line keeps the caret on the text row`() {
        for (wrap in wraps) {
            val view = viewOf("first\n\nthird", wrap, lines(1 to LineInjections.of(Injection.rowAbove("lens"))))
            val layout = view.shown(1).layout
            assertEquals(2, layout.lineCount)
            assertEquals(1, layout.textRow)
            assertEquals(1, layout.rowFor(0))
            checkRows(view, "wrap = $wrap")
            // On skiko a text's empty last row is measured with the default font: the caret must
            // be as it is on a line with text, one lens row further down.
            val first = view.caretRect(0)
            val empty = view.caretRect(view.state.document.lineStart(1))
            assertEquals(2 * LINE_HEIGHT, empty.top - first.top, 0.5f, "wrap = $wrap")
            assertEquals(first.height, empty.height, 0.5f, "wrap = $wrap")
        }
    }

    @Test
    fun `rows below a line, as multi-line ghost text`() {
        for (wrap in wraps) {
            val view = viewOf("code\nnext", wrap, lines(0 to LineInjections.of(Injection(4, "ghost\nrow\nrow"))))
            val layout = view.shown(0).layout
            assertEquals(3, layout.lineCount)
            assertEquals(0, layout.textRow)
            assertEquals(0, layout.lastTextRow)
            assertEquals(listOf(false, true, true), (0..2).map { layout.isInjectedRow(it) })
            assertEquals(0, layout.rowFor(4), "the caret stays before the ghost text")
            checkRows(view, "wrap = $wrap")
        }
    }

    @Test
    fun `a break in the middle of a line leaves the rest of it on the last row`() {
        for (wrap in wraps) {
            val view = viewOf("before after", wrap, lines(0 to LineInjections.of(Injection(7, "ghost\nrow\nend "))))
            val layout = view.shown(0).layout
            assertEquals(3, layout.lineCount)
            assertEquals(0, layout.textRow)
            assertEquals(2, layout.lastTextRow)
            assertEquals(listOf(false, true, false), (0..2).map { layout.isInjectedRow(it) })
            assertEquals(0, layout.rowFor(7), "the caret stays before the ghost text")
            assertEquals(2, layout.charRow(7), "the character after it is on the last row")
            checkRows(view, "wrap = $wrap")
        }
    }

    @Test
    fun `a wrapped line with rows above and below it`() {
        val long = (1..40).joinToString(" ") { "w$it" }
        val injected = LineInjections.of(Injection.rowAbove("lens"), Injection(long.length, "ghost\nbelow"))
        val view = viewOf("$long\nend", softWrap = true, injections = lines(0 to injected), width = 200)
        val layout = view.shown(0).layout
        assertTrue(layout.lineCount > 4, "the line wraps: ${layout.lineCount}")
        assertEquals(1, layout.textRow)
        assertEquals(layout.lineCount - 2, layout.lastTextRow)
        assertTrue(layout.isInjectedRow(0))
        assertTrue(layout.isInjectedRow(layout.lineCount - 1))
        checkRows(view, "wrapped")
    }

    @Test
    fun `a row above a line of tabs keeps the tab stops`() {
        for (wrap in wraps) {
            val plain = viewOf("\tx\ny\tz", wrap)
            val view = viewOf("\tx\ny\tz", wrap, { LineInjections.of(Injection.rowAbove("lens")) })
            for (offset in listOf(1, 2, 4, 5, 6)) {
                assertEquals(plain.caretRect(offset).left, view.caretRect(offset).left, 0.5f, "offset $offset, wrap = $wrap")
            }
            checkRows(view, "wrap = $wrap")
        }
    }

    @Test
    fun `a row above an indented line leaves the indentation hidden and in place`() {
        val plain = viewOf("    indented\n\tx", softWrap = true)
        val view = viewOf("    indented\n\tx", softWrap = true, injections = { LineInjections.of(Injection.rowAbove("lens")) })
        for (offset in listOf(0, 2, 4, 8, 13, 14)) {
            assertEquals(plain.caretRect(offset).left, view.caretRect(offset).left, 0.5f, "offset $offset")
        }
        val text = view.shown(0).layout.result.layoutInput.text
        assertEquals("lens\n~~~~indented", text.text)
        // The glyphs are hidden where they are, not where they would be without the row above.
        val hidden = text.spanStyles.filter { it.item.color.alpha == 0f }
        assertEquals(listOf(5 to 9), hidden.map { it.start to it.end })
        checkRows(view, "indented")
    }

    @Test
    fun `a row above in a smaller font keeps the view's pitch`() {
        for (wrap in wraps) {
            val small = LineInjections.of(Injection.rowAbove("lens", SpanStyle(fontSize = 10.sp)))
            val view = viewOf("first\nsecond\nthird", wrap, lines(1 to small))
            checkRows(view, "wrap = $wrap")
            val plain = viewOf("first\nsecond\nthird", wrap)
            val offset = view.state.document.lineStart(1)
            assertEquals(plain.caretRect(offset).top + LINE_HEIGHT, view.caretRect(offset).top, 0.5f, "wrap = $wrap")
            assertEquals(plain.caretRect(offset).height, view.caretRect(offset).height, 0.5f, "wrap = $wrap")
        }
    }

    @Test
    fun `an unwrapped line does not grow sideways for a row above it`() {
        val view = viewOf("x", injections = lines(0 to LineInjections.of(Injection.rowAbove("a rather long lens title"))))
        assertTrue(abs(view.caretRect(1).left - view.caretRect(0).left) < 40f)
        assertTrue(view.shown(0).layout.getLineRight(0) > view.shown(0).layout.getLineRight(1))
    }
}
