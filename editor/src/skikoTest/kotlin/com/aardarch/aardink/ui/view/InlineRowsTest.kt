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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The spike's steps S2 (the switch's off state), S5 (soft wrap), S7 (added rows) and S10 (scroll),
 * on desktop and in the browser.
 */
class InlineRowsTest {

    private val lens = LineInjections.of(Injection.rowAbove("3 references", id = "lens"))

    private fun EditorView.selectedTexts(): List<String> =
        state.selections.sortedBy { it.min }.map { state.document.text.substring(it.min, it.max) }

    /** Every offset a caret reaches from the start going down a row at a time, keeping x 0, and the same going up. */
    private fun walk(view: EditorView): Pair<List<Int>, List<Int>> {
        val down = ArrayList<Int>()
        var offset = 0
        repeat(view.lineMap.totalRows + 2) {
            down += view.rowOf(offset)
            val next = view.verticalMove(offset, 1, 0f)
            if (next == offset) return@repeat
            offset = next
        }
        val up = ArrayList<Int>()
        offset = view.state.document.length
        repeat(view.lineMap.totalRows + 2) {
            up += view.rowOf(offset)
            val next = view.verticalMove(offset, -1, 0f)
            if (next == offset) return@repeat
            offset = next
        }
        return down.distinct() to up.distinct()
    }

    /** The rows a caret can be on, from the laid-out lines. */
    private fun textRows(view: EditorView): List<Int> = view.frame.lines.flatMap { visible ->
        val first = view.lineMap.firstRowOf(visible.line)
        (0 until visible.layout.lineCount).filterNot { visible.layout.isInjectedRow(it) }.map { first + it }
    }

    // ── S2: the switch off ───────────────────────────────────────────────────

    @Test
    fun `a line with nothing injected gets the identity map, with the switch off and on`() {
        val off = viewOf("first\nsecond")
        assertSame(OffsetMap.Identity, off.shown(0).layout.map)
        val on = viewOf("first\nsecond", injections = lines(1 to lens))
        assertSame(OffsetMap.Identity, on.shown(0).layout.map)
        assertFalse(on.shown(1).layout.map.isIdentity)
    }

    @Test
    fun `an injection past a line's end leaves it the identity map`() {
        val view = viewOf("ab", injections = lines(0 to LineInjections.of(Injection(9, "gone"))))
        assertSame(OffsetMap.Identity, view.shown(0).layout.map)
    }

    @Test
    fun `a layout is cached with what is injected, and laid out again when that changes`() {
        var injected: LineInjections? = lens
        val view = viewOf("first\nsecond", injections = { if (it == 1) injected else null })
        val before = view.layoutFor(1)
        assertSame(before, view.layoutFor(1), "cached")
        injected = LineInjections.of(Injection.rowAbove("4 references", id = "lens"))
        assertTrue(before !== view.layoutFor(1))
    }

    // ── S5: soft wrap ────────────────────────────────────────────────────────

    private val wrapped = (0 until 400).joinToString("\n") { line -> (0 until 30).joinToString(" ") { "w$line$it" } }

    @Test
    fun `a hint that wraps a line is counted in its rows`() {
        val text = "x".repeat(40) + "\nend"
        val fits = viewOf(text, softWrap = true, width = 800)
        val rows = fits.shown(0).layout.lineCount
        val view = viewOf(
            text,
            softWrap = true,
            injections = lines(0 to LineInjections.of(Injection(20, "h".repeat(400)))),
            width = 800,
        )
        val layout = view.shown(0).layout
        assertTrue(layout.lineCount > rows, "${layout.lineCount} rows against $rows")
        assertEquals(layout.lineCount, view.lineMap.rowsOf(0))
    }

    @Test
    fun `hints arriving above the view move nothing on screen`() {
        // Only lines above the view are hinted: one on screen would rightly grow where it is.
        var hintedBelow = 0
        val hints = LineInjections.of(Injection(10, "h".repeat(120)), Injection(30, ": Int"))
        val view = viewOf(wrapped, softWrap = true, injections = { if (it < hintedBelow) hints else null }, width = 300, height = 200)
        view.scroll.scrollToY(view.contentTop(150))
        view.prepare(300, 200)
        view.prepare(300, 200)
        val firstShown = view.frame.lines.first().line
        assertTrue(firstShown > 100, "the view is well down the document: $firstShown")
        val rowsBefore = view.lineMap.totalRows
        val shown = view.frame.lines.map { it.line }
        val before = shown.map { view.caretRect(view.state.document.lineStart(it)) }

        hintedBelow = firstShown
        view.injectionsChanged(0, firstShown - 1)
        view.prepare(300, 200)

        assertTrue(view.lineMap.totalRows > rowsBefore + firstShown, "the hints added rows above the view")
        val after = shown.map { view.caretRect(view.state.document.lineStart(it)) }
        for (i in shown.indices) {
            assertEquals(before[i].top, after[i].top, 0.5f, "line ${shown[i]} stayed where it was")
            assertEquals(before[i].left, after[i].left, 0.5f, "line ${shown[i]} stayed where it was")
        }
    }

    @Test
    fun `a line on screen gets its hints measured, not estimated`() {
        var injected = false
        val hints = LineInjections.of(Injection(10, "h".repeat(120)))
        val view = viewOf(wrapped, softWrap = true, injections = { if (injected) hints else null }, width = 300, height = 200)
        view.prepare(300, 200)
        injected = true
        view.injectionsChanged()
        view.prepare(300, 200)
        for (visible in view.frame.lines) assertEquals(view.lineMap.rowsOf(visible.line), visible.layout.lineCount)
    }

    @Test
    fun `up and down reach every text row of wrapped lines with hints`() {
        val text = (0 until 12).joinToString("\n") { line -> (0 until 12).joinToString(" ") { "w$line$it" } }
        val hints = LineInjections.of(Injection(4, ": Hint"), Injection(30, "another hint"))
        // Tall enough to lay every line out: off screen, rows are only estimates.
        val view = viewOf(text, softWrap = true, injections = { hints }, width = 300, height = 2_000)
        assertTrue(view.frame.lines.size == 12)
        assertTrue(view.lineMap.totalRows > 24, "the lines wrap: ${view.lineMap.totalRows}")
        val (down, up) = walk(view)
        assertEquals(textRows(view), down)
        assertEquals(textRows(view).reversed(), up)
    }

    @Test
    fun `unwrapped, a lens arriving on a line on screen is counted by the layout pass`() {
        var injected = false
        val view = viewOf("first\nsecond\nthird", injections = { if (injected && it == 1) lens else null })
        assertEquals(3, view.lineMap.totalRows)
        injected = true
        view.injectionsChanged()
        view.prepare(600, 400)
        assertEquals(2, view.lineMap.rowsOf(1), "the row map has the layout's rows")
        assertEquals(4, view.lineMap.totalRows)
        assertEquals(view.shown(2).top, view.contentTop(2))
    }

    @Test
    fun `a hint growing the line cut by the top of the view keeps the lines below it in place`() {
        var hintedBelow = 0
        val hints = LineInjections.of(Injection(10, "h".repeat(120)))
        val view = viewOf(wrapped, softWrap = true, injections = { if (it < hintedBelow) hints else null }, width = 300, height = 200)
        view.scroll.scrollToY(view.contentTop(150))
        view.prepare(300, 200)
        view.prepare(300, 200)
        view.scroll.scrollToY(view.contentTop(view.frame.lines.first().line) + 1.5f * LINE_HEIGHT)
        view.prepare(300, 200)
        val cut = view.frame.lines.first()
        assertTrue(cut.top < view.scroll.scrollY, "the top of the view cuts line ${cut.line}")
        val below = view.frame.lines.drop(1).map { it.line }
        val before = below.map { view.caretRect(view.state.document.lineStart(it)).top }

        hintedBelow = cut.line + 1
        view.injectionsChanged(cut.line, cut.line)
        view.prepare(300, 200)

        assertTrue(view.layoutFor(cut.line).lineCount > cut.layout.lineCount, "the line did grow")
        val after = below.map { view.caretRect(view.state.document.lineStart(it)).top }
        for (i in below.indices) assertEquals(before[i], after[i], 0.5f, "line ${below[i]} stayed where it was")
    }

    @Test
    fun `a column selection takes no row of ghost text below a line`() {
        val view = viewOf("abcdef\nabcdef", injections = lines(0 to LineInjections.of(Injection(6, "ghost\nrow\nrow"))))
        val x = view.shown(1).layout
        val box = ColumnBox(0, x.caretX(1), view.lineMap.totalRows - 1, x.caretX(4))
        view.state.replaceSelections(view.columnSelection(box))
        assertEquals(listOf("bcd", "bcd"), view.selectedTexts(), "and no caret at the end of the first line")
    }

    @Test
    fun `unwrapped, a row the estimate counted and the line could not show is taken back`() {
        val past = LineInjections.of(Injection(99, "ghost\nrow"))
        val view = viewOf("ab\ncd", injections = lines(0 to past))
        assertEquals(1, view.shown(0).layout.lineCount)
        assertEquals(1, view.lineMap.rowsOf(0))
        assertEquals(2, view.lineMap.totalRows)
    }

    // ── S7: added rows ───────────────────────────────────────────────────────

    @Test
    fun `up and down pass over rows above and below lines, and never stick`() {
        val view = viewOf(
            "first\nsecond\n\nfourth\nlast",
            injections = lines(
                0 to lens,
                1 to lens,
                2 to lens,
                3 to LineInjections.of(Injection.rowAbove("a"), Injection.rowAbove("b"), Injection(6, "ghost\nrow")),
                4 to lens,
            ),
        )
        val (down, up) = walk(view)
        assertEquals(textRows(view), down)
        assertEquals(textRows(view).reversed(), up)
        assertEquals(5, down.size)
    }

    @Test
    fun `a page of rows lands on a text row`() {
        val view = viewOf((0 until 60).joinToString("\n") { "line $it" }, injections = { if (it % 2 == 1) lens else null })
        var offset = 0
        repeat(10) {
            offset = view.verticalMove(offset, 7, 0f)
            assertFalse(view.isInjectedRow(view.rowOf(offset)))
        }
    }

    @Test
    fun `a click on a lens hits it, and past its text does not`() {
        val view = viewOf("first\nsecond\nthird", injections = lines(1 to lens))
        val lensRow = view.lineMap.firstRowOf(1)
        val y = PADDING + lensRow * LINE_HEIGHT + 10f
        val hit = view.offsetAt(Offset(PADDING + 5f, y))
        assertEquals("lens", hit.injection?.id)
        assertEquals(1, hit.line)
        assertEquals(view.state.document.lineStart(1), hit.offset, "a lens's column is the line's start")
        assertNull(view.offsetAt(Offset(PADDING + 500f, y)).injection, "past its text")
        assertNull(view.offsetAt(Offset(PADDING + 5f, y + LINE_HEIGHT)).injection, "on the text row")
    }

    @Test
    fun `a click on a lens runs it and leaves the caret alone`() {
        val view = viewOf("first\nsecond\nthird", injections = lines(1 to lens))
        val clicked = ArrayList<Pair<Int, Any?>>()
        view.onInjectionClick = { line, injection -> clicked += line to injection.id }
        view.state.selection = TextRange(2)
        val pointer = EditorPointerHandler(view)
        pointer.press(Offset(PADDING + 5f, PADDING + LINE_HEIGHT + 10f), clicks = 1, shift = false, alt = false)
        pointer.release()
        assertEquals<List<Pair<Int, Any?>>>(listOf(1 to "lens"), clicked)
        assertEquals(TextRange(2), view.state.selection)
    }

    @Test
    fun `a click on a hint with nothing to run puts the caret at its column`() {
        val view = viewOf("val x = 1", injections = lines(0 to LineInjections.of(Injection(5, ": Int"))))
        val layout = view.shown(0).layout
        val pointer = EditorPointerHandler(view)
        val hintX = (layout.charLeft(4) + layout.charLeft(5)) / 2f + layout.caretX(5) - layout.charLeft(4)
        pointer.press(Offset(PADDING + hintX, PADDING + 10f), clicks = 1, shift = false, alt = false)
        pointer.release()
        assertEquals(TextRange(5), view.state.selection)
    }

    @Test
    fun `a line's text is what is pinned, and pinned when it reaches the top`() {
        val text = (0 until 30).joinToString("\n") { "line $it" }
        val ranges = listOf(FoldRange(4, 20))
        val view = viewOf(text, injections = lines(4 to lens), height = 100)
        // Row 4 is the lens, row 5 the text of line 4: with the lens above the top but the text
        // below it, nothing is pinned yet.
        view.scroll.scrollToY(PADDING + 4 * LINE_HEIGHT + 10f)
        view.prepare(600, 100)
        assertEquals(emptyList(), view.stickyLines(ranges))
        view.scroll.scrollToY(PADDING + 6 * LINE_HEIGHT)
        view.prepare(600, 100)
        assertEquals(listOf(StickyLine(4, 0f)), view.stickyLines(ranges))
    }

    @Test
    fun `opening a pinned line brings its text there, not its lens`() {
        val text = (0 until 30).joinToString("\n") { "line $it" }
        val view = viewOf(text, injections = lines(4 to lens), height = 100)
        view.scroll.scrollToY(PADDING + 8 * LINE_HEIGHT)
        view.prepare(600, 100)
        view.openStickyLine(StickyLine(4, 0f))
        view.prepare(600, 100)
        assertEquals(PADDING + 5 * LINE_HEIGHT, view.scroll.scrollY)
        assertEquals(view.state.document.lineStart(4), view.state.selection.start)
    }

    @Test
    fun `a column selection takes no row of a lens`() {
        val view = viewOf("abcdef\nabcdef\nabcdef", injections = lines(1 to lens))
        val x = view.shown(0).layout
        val box = ColumnBox(0, x.caretX(1), view.lineMap.totalRows - 1, x.caretX(4))
        view.state.replaceSelections(view.columnSelection(box))
        assertEquals(listOf("bcd", "bcd", "bcd"), view.selectedTexts())
    }

    @Test
    fun `a column selection that is all on a lens row gets carets on the text rows only`() {
        val view = viewOf("ab\nab\nab", injections = lines(1 to lens))
        val far = view.shown(0).layout.caretX(2) + 300f
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, far, view.lineMap.totalRows - 1, far)))
        assertEquals(3, view.state.selections.size)
        assertTrue(view.state.selections.all { it.collapsed })
    }

    @Test
    fun `the column select keys step over a lens row`() {
        val view = viewOf("abcdef\nabcdef\nabcdef", injections = lines(1 to lens))
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(1)
        controller.execute(KeyBinding(KeyCommand.ColumnSelectDown))
        assertEquals(2, view.state.selections.size, "one press reaches the next text row")
        controller.execute(KeyBinding(KeyCommand.ColumnSelectDown))
        assertEquals(3, view.state.selections.size)
        controller.execute(KeyBinding(KeyCommand.ColumnSelectUp))
        assertEquals(2, view.state.selections.size)
    }

    @Test
    fun `a nearest text row is found both ways`() {
        val view = viewOf("a\nb", injections = lines(0 to lens, 1 to lens))
        assertEquals(1, view.textRowFrom(0, 1))
        assertEquals(1, view.textRowFrom(0, -1), "off the top it turns back")
        assertEquals(3, view.textRowFrom(2, 1))
        assertEquals(1, view.textRowFrom(2, -1))
        assertEquals(3, view.textRowFrom(3, 1))
    }

    @Test
    fun `a closed fold's placeholder is hit past a hint at the end of its line`() {
        val foldState = FoldState().apply {
            updateFoldableRanges(listOf(FoldRange(0, 2)))
            toggle(0)
        }
        val view = viewOf("open {\n  body\n}", foldState = foldState, injections = lines(0 to LineInjections.of(Injection(6, " hint"))))
        val layout = view.shown(0).layout
        val hintEnd = layout.getLineRight(0)
        val y = PADDING + 10f
        assertTrue(view.offsetAt(Offset(PADDING + hintEnd - 2f, y)).onFoldPlaceholder, "the placeholder is after the hint")
        // Well into the hint: its layout positions are past the line, its column is not.
        val onHint = view.offsetAt(Offset(PADDING + layout.caretX(6) + 3 * InlineFixture.charWidth, y))
        assertFalse(onHint.onFoldPlaceholder)
        assertEquals(6, onHint.offset)
        assertNotNull(onHint.injection)
    }

    @Test
    fun `the rows of a hidden line do not count its injections`() {
        val foldState = FoldState().apply {
            updateFoldableRanges(listOf(FoldRange(0, 2)))
            toggle(0)
        }
        val view = viewOf("open {\n  body\n}\nafter", foldState = foldState, injections = { lens })
        // Rows: the fold's lens and text, the next line's lens and text.
        assertEquals(4, view.lineMap.totalRows)
    }

    // ── S10: scroll ──────────────────────────────────────────────────────────

    @Test
    fun `scrolling top to bottom with lenses leaves the height as it was`() {
        val lines = 3_000
        val view = viewOf(
            (0 until lines).joinToString("\n") { "line $it" },
            injections = { if (it % 20 == 0) lens else null },
            height = 300,
        )
        val height = view.scroll.contentHeight
        val rows = view.lineMap.totalRows
        assertEquals(lines + lines / 20, rows)
        var y = 0f
        while (y < height) {
            view.scroll.scrollToY(y)
            view.prepare(600, 300)
            assertEquals(height, view.scroll.contentHeight, "at $y")
            assertEquals(y.coerceAtMost(height - 300f), view.scroll.scrollY, 0.5f, "no jump at $y")
            y += 170f
        }
        assertEquals(rows, view.lineMap.totalRows)
        assertEquals(lines - 1, view.frame.lines.last().line, "the end was reached")
    }

    @Test
    fun `a typed line above a lens moves it with its text`() {
        val text = (0 until 30).joinToString("\n") { "line $it" }
        var lensLine = 10
        val view = viewOf(text, injections = { if (it == lensLine) lens else null })
        val before = view.lineMap.totalRows
        view.state.applyEdit(0, 0, "new first line\n", TextRange(0))
        lensLine = 11
        view.injectionsChanged()
        view.prepare(600, 400)
        assertEquals(before + 1, view.lineMap.totalRows)
        assertEquals(1, view.shown(11).layout.textRow)
        assertEquals(0, view.shown(10).layout.textRow)
    }

    @Test
    fun `an edit on a line off screen keeps its lens counted`() {
        val text = (0 until 200).joinToString("\n") { "line $it" }
        val view = viewOf(text, injections = lines(150 to lens), height = 100)
        val rows = view.lineMap.totalRows
        view.state.applyEdit(view.state.document.lineStart(150), 0, "x", TextRange(0))
        view.prepare(600, 100)
        assertEquals(rows, view.lineMap.totalRows)
    }
}
