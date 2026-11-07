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
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Code lenses as the view shows them, through [InlineContent] with a real text measurer: nothing here sets the hook by hand. */
class CodeLensViewTest {

    private val width = 600
    private val height = 400

    private fun EditorView.lens(
        vararg lenses: CodeLens,
        width: Int = this@CodeLensViewTest.width,
        height: Int = this@CodeLensViewTest.height,
    ) {
        state.publishCodeLenses(lenses.toList())
        prepare(width, height)
    }

    private fun EditorView.laidOut(line: Int): String = layoutFor(line).result.layoutInput.text.text

    private fun EditorView.edit(at: Int, delete: Int, insert: String) {
        state.applyEdit(at, delete, insert, TextRange(at + insert.length))
        prepare(width, height)
    }

    /** Where the [index]th character of [line]'s layout is on screen, in the middle of it. */
    private fun EditorView.pointAt(line: Int, index: Int): Offset {
        val box = layoutFor(line).result.getBoundingBox(index)
        return Offset(PADDING + box.center.x, contentTop(line) + box.center.y - scroll.scrollY)
    }

    private fun EditorView.clickOn(line: Int, text: String, clicks: Int = 1): EditorPointerHandler {
        val pointer = EditorPointerHandler(this)
        val at = pointAt(line, laidOut(line).indexOf(text) + text.length / 2)
        pointer.press(at, clicks = clicks, shift = false, alt = false)
        pointer.release()
        return pointer
    }

    private fun lines(count: Int, indent: String = "") = (0 until count).joinToString("\n") { "${indent}line $it" }

    // ── The row ──────────────────────────────────────────────────────────────

    @Test
    fun `a lens is a row above its line, whatever column it is at`() {
        val view = viewOf("first\nsecond\nthird")

        view.lens(CodeLens(9, "3 references"))

        assertEquals("3 references\nsecond", view.laidOut(1))
        assertEquals(2, view.lineMap.rowsOf(1))
        assertEquals(1, view.layoutFor(1).textRow)
    }

    @Test
    fun `a row starts where its line's text does`() {
        val view = viewOf("first\n    second\n\tthird")

        view.lens(CodeLens(6, "spaces"), CodeLens(17, "a tab"))

        assertEquals("    spaces\n    second", view.laidOut(1))
        assertEquals("\ta tab\n\tthird", view.laidOut(2))
    }

    @Test
    fun `lenses on one line share a row, in the order given, with VS Code's separator`() {
        val view = viewOf("first\nsecond")

        view.lens(CodeLens(6, "2 references"), CodeLens(8, "Run"), CodeLens(10, "Debug"))

        assertEquals("2 references | Run | Debug\nsecond", view.laidOut(1))
        assertEquals(2, view.lineMap.rowsOf(1), "one row, not one each")
    }

    @Test
    fun `a line break in a title shows as a space`() {
        val view = viewOf("first")

        view.lens(CodeLens(0, "two\nrows"))

        assertEquals("two rows\nfirst", view.laidOut(0))
        assertEquals(2, view.lineMap.rowsOf(0))
    }

    @Test
    fun `a title too long for a line is cut, and the row and the line's text stay`() {
        val view = viewOf("first\nsecond")
        val long = "x".repeat(EditorView.MAX_RENDERED_LINE_CHARS * 2)

        view.lens(CodeLens(0, long), CodeLens(1, "lost"), CodeLens(6, "kept"))

        assertEquals(1, view.layoutFor(0).textRow)
        assertTrue(view.laidOut(0).endsWith("x\nfirst"), "the row ends, and the text follows it")
        assertFalse("lost" in view.laidOut(0))
        assertEquals("kept\nsecond", view.laidOut(1))
    }

    @Test
    fun `a line with lens, decoration text and hint at its start keeps the row above all of them`() {
        val view = viewOf("first\nsecond")
        view.state.setDecorations("test", listOf(Decoration(0..5, DecorationStyle(after = "[end]"))))
        view.state.publishInlayHints(listOf(InlayHint(6, "hint ")))

        view.lens(CodeLens(6, "lens"))

        assertEquals("lens\n[end]hint second", view.laidOut(1))
        assertEquals(1, view.layoutFor(1).textRow)
    }

    @Test
    fun `under soft wrap with hidden indentation a row stays above the line`() {
        val text = "first\n        " + "word ".repeat(40)
        val view = viewOf(text, softWrap = true)

        view.lens(CodeLens(6, "one"), CodeLens(7, "two"))

        assertEquals(1, view.layoutFor(1).textRow)
        assertEquals(view.laidOut(1).substringBefore('\n'), "        one | two")
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    @Test
    fun `each lens on a row runs its own click, and the caret stays`() {
        val view = viewOf("first\n  second")
        val clicked = ArrayList<String>()
        view.lens(CodeLens(6, "Run") { clicked += "run" }, CodeLens(8, "Debug") { clicked += "debug" })
        view.state.selection = TextRange(2)

        view.clickOn(1, "Debug")
        view.clickOn(1, "Run")

        assertEquals(listOf("debug", "run"), clicked)
        assertEquals(TextRange(2), view.state.selection)
    }

    @Test
    fun `the separator, the indentation and past the titles run nothing`() {
        val view = viewOf("first\n  second")
        val clicked = ArrayList<String>()
        view.lens(CodeLens(6, "Run") { clicked += "run" }, CodeLens(8, "Debug") { clicked += "debug" })
        val pointer = EditorPointerHandler(view)
        val y = view.pointAt(1, 3).y

        pointer.press(view.pointAt(1, view.laidOut(1).indexOf('|')), clicks = 1, shift = false, alt = false)
        pointer.release()
        pointer.press(Offset(PADDING + 1f, y), clicks = 1, shift = false, alt = false)
        pointer.release()
        pointer.press(Offset(PADDING + 550f, y), clicks = 1, shift = false, alt = false)
        pointer.release()

        assertEquals(emptyList(), clicked)
    }

    @Test
    fun `a double click runs a lens once and selects nothing under it`() {
        val view = viewOf("first\nsecond")
        var clicked = 0
        view.lens(CodeLens(6, "Run") { clicked++ })
        view.state.selection = TextRange(2)

        view.clickOn(1, "Run", clicks = 1)
        view.clickOn(1, "Run", clicks = 2)
        view.clickOn(1, "Run", clicks = 3)

        assertEquals(1, clicked)
        assertEquals(TextRange(2), view.state.selection)
    }

    @Test
    fun `a click after an edit runs what is shown at its new place`() {
        val view = viewOf("first\nsecond\nthird")
        val clicked = ArrayList<String>()
        view.lens(CodeLens(6, "on second") { clicked += "second" }, CodeLens(13, "on third") { clicked += "third" })

        view.edit(0, 0, "new\n")
        view.clickOn(3, "on third")

        assertEquals(listOf("third"), clicked)
    }

    @Test
    fun `a press on a lens does not ask for a place to type, and one on text does`() {
        val view = viewOf("first\nsecond")
        view.lens(CodeLens(6, "Run"))
        val pointer = EditorPointerHandler(view)
        val typing = ArrayList<Boolean>()
        pointer.onPress = { _, wanted -> typing += wanted }

        pointer.press(view.pointAt(1, 1), clicks = 1, shift = false, alt = false, touch = true)
        pointer.release()
        pointer.press(Offset(PADDING + 2f, PADDING + 10f), clicks = 1, shift = false, alt = false, touch = true)
        pointer.release()

        assertEquals(listOf(false, true), typing)
    }

    // ── Edits ────────────────────────────────────────────────────────────────

    @Test
    fun `a lens moves with its text, and goes with it`() {
        val view = viewOf("first\nfun second\nthird")
        view.lens(CodeLens(10, "lens"))

        view.edit(0, 0, "new\n")
        assertEquals("lens\nfun second", view.laidOut(2))
        assertEquals(14, view.state.codeLenses.single().offset)

        view.edit(10, 11, "")
        assertTrue(view.state.codeLenses.isEmpty(), "the text it was on went")
        assertEquals(1, view.lineMap.rowsOf(2))
    }

    @Test
    fun `replacing the document takes every lens`() {
        val view = viewOf("first\nsecond")
        view.lens(CodeLens(6, "lens"))

        view.state.loadText("other\ntext")
        view.prepare(width, height)

        assertTrue(view.state.codeLenses.isEmpty())
        assertEquals(1, view.lineMap.rowsOf(1))
    }

    @Test
    fun `an answer the same as the last changes nothing, whatever its clicks are`() {
        val view = viewOf("first\nsecond")
        var clicked = ""
        view.lens(CodeLens(6, "lens") { clicked = "old" })
        val version = view.injectionVersion

        view.lens(CodeLens(6, "lens") { clicked = "new" })
        assertEquals(version, view.injectionVersion, "the row is the same")

        view.clickOn(1, "lens")
        assertEquals("new", clicked)
    }

    // ── What stays where it is ───────────────────────────────────────────────

    @Test
    fun `a lens arriving above the view moves nothing on screen`() {
        val view = viewOf(lines(400), height = 200)
        view.scroll.scrollToY(view.contentTop(150))
        view.prepare(width, 200)
        view.prepare(width, 200)
        val shown = view.frame.lines.map { it.line }
        val before = shown.map { view.caretRect(view.state.document.lineStart(it)) }
        val rows = view.lineMap.totalRows

        val above = (0 until 100).map { CodeLens(view.state.document.lineStart(it), "lens") }
        view.state.publishCodeLenses(above)
        view.prepare(width, 200)

        assertEquals(rows + 100, view.lineMap.totalRows)
        val after = shown.map { view.caretRect(view.state.document.lineStart(it)) }
        for (i in shown.indices) assertEquals(before[i].top, after[i].top, 0.5f, "line ${shown[i]} stayed where it was")
    }

    @Test
    fun `scrolling top to bottom leaves the height as it was, with rows of several lenses and indentation`() {
        val lines = 3_000
        val view = viewOf(lines(lines, indent = "    "), height = 300)
        val lenses = (0 until lines step 20).flatMap { line ->
            val at = view.state.document.lineStart(line)
            listOf(CodeLens(at, "2 references"), CodeLens(at + 4, "Run"))
        }
        view.state.publishCodeLenses(lenses)
        view.prepare(width, 300)
        val height = view.scroll.contentHeight
        val rows = view.lineMap.totalRows
        assertEquals(lines + lines / 20, rows)

        var y = 0f
        while (y < height) {
            view.scroll.scrollToY(y)
            view.prepare(width, 300)
            assertEquals(height, view.scroll.contentHeight, "at $y")
            assertEquals(y.coerceAtMost(height - 300f), view.scroll.scrollY, 0.5f, "no jump at $y")
            y += 170f
        }
        assertEquals(rows, view.lineMap.totalRows)
        assertEquals(lines - 1, view.frame.lines.last().line, "the end was reached")
    }

    @Test
    fun `a lens wider than the view is counted as what it wraps to before it is laid out`() {
        val narrow = 200
        val view = viewOf(lines(200), softWrap = true, width = narrow, height = 200)
        val line = 150
        view.state.publishCodeLenses(listOf(CodeLens(view.state.document.lineStart(line), "w".repeat(100))))
        view.prepare(narrow, 200)
        val estimated = view.lineMap.rowsOf(line)
        assertTrue(estimated > 2, "it wraps: $estimated")

        assertEquals(view.layoutFor(line).lineCount, estimated)
    }

    @Test
    fun `a lens in a closed fold takes no row, and comes back when it opens`() {
        val folds = FoldState()
        folds.updateFoldableRanges(listOf(FoldRange(0, 3)))
        val view = viewOf(lines(6), foldState = folds)
        val rows = view.lineMap.totalRows
        val hiddenAt = view.state.document.lineStart(2)

        folds.toggle(0)
        view.prepare(width, height)
        val folded = view.lineMap.totalRows
        view.lens(CodeLens(hiddenAt, "lens"))
        assertEquals(folded, view.lineMap.totalRows)

        folds.toggle(0)
        view.prepare(width, height)
        assertEquals(rows + 1, view.lineMap.totalRows)
    }

    // ── A title still to come ────────────────────────────────────────────────

    @Test
    fun `a lens with no title yet keeps its row, runs nothing, and is waiting`() {
        val view = viewOf("first\nsecond")
        var clicked = false

        view.lens(CodeLens(6, "") { clicked = true })

        assertEquals("\nsecond", view.laidOut(1))
        assertEquals(2, view.lineMap.rowsOf(1))
        assertTrue(view.inline.isWaiting(1))
        assertFalse(view.inline.isWaiting(0))
        val pointer = EditorPointerHandler(view)
        pointer.press(Offset(PADDING + 2f, PADDING + LINE_HEIGHT + 10f), clicks = 1, shift = false, alt = false)
        pointer.release()
        assertFalse(clicked)
        assertNull(view.offsetAt(Offset(PADDING + 2f, PADDING + LINE_HEIGHT + 10f)).injection?.id)
    }

    @Test
    fun `its title coming moves nothing`() {
        val view = viewOf(lines(30))
        val at = view.state.document.lineStart(5)
        view.lens(CodeLens(at, ""))
        val rows = view.lineMap.totalRows
        val height = view.scroll.contentHeight
        val below = view.caretRect(view.state.document.lineStart(8)).top

        view.lens(CodeLens(at, "3 references"))

        assertEquals("3 references\nline 5", view.laidOut(5))
        assertEquals(rows, view.lineMap.totalRows)
        assertEquals(height, view.scroll.contentHeight)
        assertEquals(below, view.caretRect(view.state.document.lineStart(8)).top, 0.5f)
        assertFalse(view.inline.isWaiting(5))
    }

    @Test
    fun `one with a title and one without share a row, and the line still waits`() {
        val view = viewOf("first\nsecond")
        var clicked = false

        view.lens(CodeLens(6, ""), CodeLens(7, "Run") { clicked = true })

        assertEquals("Run\nsecond", view.laidOut(1))
        assertTrue(view.inline.isWaiting(1))
        view.clickOn(1, "Run")
        assertTrue(clicked, "its place on the row is the one the title has")
    }
}
