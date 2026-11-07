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

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.charWidth
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spike's steps S5 and S7 where injected text is under the caret's x, or fills whole rows:
 * Up, Down and column selections reach every row a caret can be on and no other, from any x.
 *
 * What is expected comes from the carets themselves, offset by offset, so a row wrongly called
 * injected or wrongly called text cannot agree with itself here.
 */
class InlineCaretRowsTest {

    /** Rows on screen a caret is on. Asks nothing about injected rows. */
    private fun caretRows(view: EditorView): List<Int> = (0..view.state.document.length).map { view.rowOf(it) }.distinct()

    /** The offsets a caret stops at, going [step] rows at a time from [start] and keeping [x]. */
    private fun walk(view: EditorView, start: Int, step: Int, x: Float): List<Int> {
        val offsets = arrayListOf(start)
        repeat(view.totalRows + 2) {
            val next = view.verticalMove(offsets.last(), step, x)
            if (next == offsets.last()) return offsets
            offsets += next
        }
        return offsets
    }

    /** Wide enough for about [chars] characters of a line. */
    private fun wide(chars: Int): Int = (2 * PADDING + chars * charWidth).toInt()

    /** Down from the start and Up from the end, from x's left of, on, and past what is injected. */
    private fun assertWalks(view: EditorView) {
        val rows = caretRows(view)
        for (chars in listOf(0, 1, 3, 6, 9, 14, 300)) {
            val x = chars * charWidth
            val down = walk(view, 0, 1, x)
            assertEquals(rows, down.map { view.rowOf(it) }.distinct(), "down from x $x: $down")
            assertEquals(view.state.document.length, down.last(), "down from x $x reaches the end: $down")
            val up = walk(view, view.state.document.length, -1, x)
            assertEquals(rows.reversed(), up.map { view.rowOf(it) }.distinct(), "up from x $x: $up")
            assertEquals(0, up.last(), "up from x $x reaches the start: $up")
        }
    }

    /** A column of carets down the whole document takes one row each, and only rows a caret can be on. */
    private fun assertColumnSelection(view: EditorView) {
        val rows = caretRows(view)
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, 0f, view.totalRows - 1, 0f)))
        val selected = view.state.selections.map { view.rowOf(it.min) }.sorted()
        assertEquals(rows, selected, "at x 0: ${view.state.selections}")
        val x = 2 * charWidth
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, x, view.totalRows - 1, 2 * x)))
        for (range in view.state.selections) {
            assertTrue(view.rowOf(range.min) in rows, "no selection on a row of only injected text: $range")
            assertEquals(view.rowOf(range.min), view.rowOf(range.max), "a row's selection stays on it: $range")
        }
    }

    /** That the scene has rows no caret can be on: without them it proves nothing. */
    private fun assertHasRowsWithoutCaret(view: EditorView) =
        assertTrue(caretRows(view).size < view.totalRows, "rows ${caretRows(view)} of ${view.totalRows}")

    /** A row is injected exactly when no character of the document and no caret is on it. */
    private fun assertRowsSorted(layout: LineLayout, context: String) {
        val raw = layout.result
        val length = layout.map.length
        val carets = (0..length).map { raw.getLineForOffset(layout.map.toLayout(it)) }
        val chars = (0 until length).map { raw.getLineForOffset(layout.map.charIndex(it)) }
        val text = (carets + chars).toSet()
        for (row in 0 until layout.lineCount) {
            assertEquals(row !in text, layout.isInjectedRow(row), "row $row: $context")
        }
        assertEquals(text.min(), layout.textRow, "first text row: $context")
        assertEquals(text.max(), layout.lastTextRow, "last text row: $context")
    }

    // ── Injected text under x ────────────────────────────────────────────────

    @Test
    fun `up and down pass ghost text that breaks in the middle of a line`() {
        val ghost = LineInjections.of(Injection(4, "first,\nsecond"))
        val view = viewOf("foo()\nnext line", injections = lines(0 to ghost))
        assertWalks(view)
        assertEquals(listOf(0, 5, 6, 15), walk(view, 0, 1, 0f))
    }

    @Test
    fun `an arrow key keeps its x past ghost text, and gets there`() {
        val ghost = LineInjections.of(Injection(4, "first,\nsecond"))
        val view = viewOf("foo()\nnext line", injections = lines(0 to ghost))
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(1)
        val stops = List(3) {
            controller.execute(KeyBinding(KeyCommand.LineDown))
            view.state.selection.end
        }
        assertEquals(listOf(5, 7, 15), stops, "the ghost's second row, line 1 at the caret's x, the end")
    }

    @Test
    fun `shift and down selects past ghost text`() {
        val ghost = LineInjections.of(Injection(4, "first,\nsecond"))
        val view = viewOf("foo()\nnext line", injections = lines(0 to ghost))
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(4)
        repeat(2) { controller.execute(KeyBinding(KeyCommand.LineDown, select = true)) }
        assertEquals(TextRange(4, view.state.document.lineStart(1) + 4), view.state.selection)
    }

    @Test
    fun `up and down pass a hint that crosses a wrap`() {
        val hint = LineInjections.of(Injection(12, " : Map<String, Int>"))
        val view = viewOf("val x = 1234 more\nnext line", softWrap = true, injections = lines(0 to hint), width = wide(20), height = 2_000)
        assertTrue(view.shown(0).layout.lineCount > 1, "it wraps")
        assertWalks(view)
    }

    @Test
    fun `up and down pass text the caret goes after, from below it`() {
        val text = "first line\nsecond line\nlast"
        val above = LineInjections.of(Injection(4, "top\nbottom ", caretAfter = true))
        val view = viewOf(text, injections = lines(1 to above))
        assertWalks(view)
        val start = view.state.document.lineStart(1)
        // x over the injected first row: its column's caret is on the row below it.
        val up = view.verticalMove(start + 8, -1, view.caretX(start + 8))
        assertEquals(view.rowOf(start + 8) - 1, view.rowOf(up), "a row up, from $up")
    }

    @Test
    fun `up and down pass rows of text between two injections`() {
        val both = LineInjections.of(Injection(2, "one\ntwo "), Injection(5, " three\nfour\nfive "))
        val view = viewOf("abcdefgh\nnext", injections = lines(0 to both))
        assertWalks(view)
        assertColumnSelection(view)
    }

    // ── Rows of injected text from soft wrap ─────────────────────────────────

    @Test
    fun `a trailing hint on a row of its own is passed over`() {
        val hint = LineInjections.of(Injection(12, " : Map<String, Int>"))
        val view = viewOf("val x = 1234\nnext line", softWrap = true, injections = lines(0 to hint), width = wide(20), height = 2_000)
        assertRowsSorted(view.shown(0).layout, "a trailing hint")
        assertWalks(view)
        assertColumnSelection(view)
    }

    @Test
    fun `ghost text filling rows after a line is passed over`() {
        val ghost = LineInjections.of(Injection(3, "ghost text ".repeat(30)))
        val view = viewOf("abc\nnext", softWrap = true, injections = lines(0 to ghost), width = 300, height = 2_000)
        assertHasRowsWithoutCaret(view)
        assertRowsSorted(view.shown(0).layout, "ghost at the end")
        assertEquals(0, view.shown(0).layout.lastTextRow, "the line's band is its own row")
        assertWalks(view)
        assertColumnSelection(view)
    }

    @Test
    fun `a hint filling rows in the middle of a line is passed over`() {
        val hint = LineInjections.of(Injection(3, "h".repeat(120)))
        val view = viewOf("abcdefgh\nnext", softWrap = true, injections = lines(0 to hint), width = 300, height = 2_000)
        assertHasRowsWithoutCaret(view)
        assertRowsSorted(view.shown(0).layout, "a hint mid-line")
        assertWalks(view)
        assertColumnSelection(view)
    }

    @Test
    fun `text the caret goes after, filling the first rows, moves the line's number down`() {
        val before = LineInjections.of(Injection(0, "h".repeat(120), caretAfter = true))
        val view = viewOf("abcd\nnext", softWrap = true, injections = lines(0 to before), width = 300, height = 2_000)
        assertHasRowsWithoutCaret(view)
        val layout = view.shown(0).layout
        assertRowsSorted(layout, "rows before the text")
        assertTrue(layout.textRow > 0, "the text row is ${layout.textRow}")
        assertWalks(view)
        assertColumnSelection(view)
    }

    @Test
    fun `a row with a character but no caret is passed over`() {
        // Rows "ax", "ybp", "qc": column 1's caret is before the first injection, and column 2's
        // after the second, so the b has a row and no caret is on it.
        val both = LineInjections.of(Injection(1, "x\ny"), Injection(2, "p\nq", caretAfter = true))
        val view = viewOf("abc\nnext", injections = lines(0 to both))
        val layout = view.shown(0).layout
        assertEquals(3, layout.lineCount)
        assertRowsSorted(layout, "the b's row")
        assertEquals(false, layout.isInjectedRow(1), "a character of the document is on it")
        assertEquals(false, layout.hasCaret(1))
        assertHasRowsWithoutCaret(view)
        assertWalks(view)
        assertColumnSelection(view)
    }

    @Test
    fun `on a row of text between injections, x finds the nearer end of the text`() {
        // Rows "abx", "y cd long", "more e"... with the caret columns of the middle row 3 and 4.
        val both = LineInjections.of(Injection(2, "x\ny "), Injection(5, " long\nmore ", caretAfter = true))
        val view = viewOf("abcde", injections = lines(0 to both))
        val layout = view.shown(0).layout
        assertEquals(3, layout.lineCount)
        assertEquals(3, layout.columnOnRow(0f, 1), "over what comes first on the row")
        assertEquals(4, layout.columnOnRow(layout.getLineRight(1) - 1f, 1), "over what comes last on the row")
        assertEquals(3, view.verticalMove(0, 1, 0f))
        assertEquals(4, view.verticalMove(0, 1, layout.getLineRight(1) - 1f))
    }

    @Test
    fun `every row is sorted, wherever the text lands against the wrap`() {
        val text = "abcdef ghijkl mnopqr"
        for (width in listOf(90, 130, 200, 300)) {
            for (column in listOf(0, 3, 7, 13, 20)) {
                for (caretAfter in listOf(false, true)) {
                    for (injected in listOf(" : Hint", "h".repeat(60), "two words ".repeat(12), "ghost\nrow")) {
                        val line = LineInjections.of(Injection(column, injected, caretAfter = caretAfter))
                        val view = viewOf(text, softWrap = true, injections = lines(0 to line), width = width, height = 2_000)
                        assertRowsSorted(view.shown(0).layout, "$injected at $column, caretAfter $caretAfter, width $width")
                    }
                }
            }
        }
    }

    @Test
    fun `a line with hints that does not wrap asks nothing about its rows`() {
        val view = viewOf("val x = 1", injections = lines(0 to LineInjections.of(Injection(5, ": Int"))))
        val layout = view.shown(0).layout
        assertEquals(1, layout.lineCount)
        assertTrue(layout.hasCaret(0))
        assertEquals(5, layout.columnOnRow(layout.caretX(5) + 1f, 0))
    }

    @Test
    fun `a wrapped line with nothing injected moves as it did`() {
        val text = (0 until 40).joinToString(" ") { "w$it" } + "\nnext"
        val plain = viewOf(text, softWrap = true, width = 300, height = 2_000)
        val hinted = viewOf(text, softWrap = true, injections = { null }, width = 300, height = 2_000)
        val x = 5 * charWidth
        assertEquals(walk(plain, 0, 1, x), walk(hinted, 0, 1, x))
        assertEquals(walk(plain, text.length, -1, x), walk(hinted, text.length, -1, x))
        assertEquals(caretRows(plain), walk(plain, 0, 1, x).map { plain.rowOf(it) }.distinct())
    }
}
