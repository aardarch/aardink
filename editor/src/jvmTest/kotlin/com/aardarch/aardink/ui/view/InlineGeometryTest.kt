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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.ui.RenderWhitespace
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.PAINT_SCALE
import com.aardarch.aardink.ui.view.InlineFixture.bounds
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.paint
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spike's step S4: everything drawn over a line stays on its characters either side of what
 * is injected. The drawing code runs for real into a bitmap, and each mark's box is read back by
 * its colour. Where it should be comes from the raw layout, at an index counted here from the
 * scene, so a wrong [OffsetMap] cannot agree with itself. On the JVM only: a sweep of renders is
 * more than a browser test's two seconds; `InlineColumnsTest` runs the positions there too.
 */
class InlineGeometryTest {

    private val width = 420
    private val height = 120

    private val colors = EditorColors(
        selection = Color(0xFFFF0001),
        findMatch = Color(0xFF00FF02),
        currentFindMatch = Color(0xFF00FF03),
        error = Color(0xFF0000F4),
        warning = Color(0xFF0000F5),
        info = Color(0xFF0000F6),
        caret = Color(0xFFFFFF07),
        text = Color(0xFFFF00F8),
        lineHighlight = Color(0xFF00FFF9),
        bracketMatch = Color(0xFF80000A),
        whitespace = Color(0xFF00800B),
    )
    private val borderColor = Color(0xFF00008C)
    private val swatchColor = Color(0xFF80800D)

    /** Glyphs would hide and blend with what is drawn under them: only the marks are wanted. */
    private val noGlyphs = InlineFixture.textStyle.copy(color = Color.Transparent)

    /**
     * A line and what is injected into it, with [items]' columns as [LineInjections.fitted]
     * leaves them. Written out rather than taken from the map: this is what the map is tested against.
     */
    private inner class Scene(
        val name: String,
        val text: String,
        val items: List<Injection>,
        val softWrap: Boolean = false,
        val viewWidth: Int = width,
        /** What the host asked for, when it is not what fits. */
        val asked: List<Injection> = items,
    ) {
        val view = viewOf(text, softWrap, lines(0 to LineInjections.of(asked)), viewWidth, height, textStyle = noGlyphs)
        val layout get() = view.shown(0).layout
        val raw: TextLayoutResult get() = layout.result

        /** The layout index of the character at [column]: everything injected at or before it comes first. */
        fun charAt(column: Int): Int = column + items.filter { it.column <= column }.sumOf { it.text.length }

        /** The layout index of a caret at [column]: what is injected at it comes first only if the caret goes after it. */
        fun caretAt(column: Int): Int =
            column + items.filter { it.column < column || (it.column == column && it.caretAfter) }.sumOf { it.text.length }

        /** Where columns [from, to) are on screen, when they are on one row. */
        fun span(from: Int, to: Int): Rect {
            val first = raw.getBoundingBox(charAt(from))
            val last = raw.getBoundingBox(charAt(to - 1))
            return Rect(PADDING + first.left, PADDING + first.top, PADDING + last.right, PADDING + last.bottom)
        }

        /** The band of the view's row the character at [column] is on. */
        fun band(column: Int): ClosedFloatingPointRange<Float> {
            val top = PADDING + raw.getLineForOffset(charAt(column)) * LINE_HEIGHT
            return top..top + LINE_HEIGHT
        }

        fun onOneRow(from: Int, to: Int) = raw.getLineForOffset(charAt(from)) == raw.getLineForOffset(charAt(to - 1))

        fun draw(decorations: EditorDecorations): PixelMap = paint(width, height) { drawEditorContent(view.frame, decorations, colors) }

        fun drawCaret(column: Int): PixelMap = paint(width, height) { drawCarets(view.frame, listOf(TextRange(column)), colors.caret) }
    }

    private val line = "abcd ef(gh) ij kl mn"

    private fun scenes(): List<Scene> {
        val ghost = Injection(7, "HINT")
        val typeHint = Injection(7, ": Int", caretAfter = true)
        val lens = Injection.rowAbove("lens")
        return listOf(
            Scene("ghost text", line, listOf(ghost)),
            Scene("a hint the caret goes after", line, listOf(typeHint)),
            Scene("a lens and a hint", line, listOf(lens, ghost)),
            Scene("two hints", line, listOf(Injection(2, "A"), ghost, Injection(14, "BB"))),
            Scene("a break in the line", line, listOf(Injection(7, "ghost\nrow\nend "))),
            Scene(
                "wrapped",
                "abcd ef(gh) ij kl mn op qr st uv wx yz ab cd",
                listOf(Injection(7, "HINT HINT HINT")),
                softWrap = true,
                viewWidth = 180,
            ),
            Scene(
                "hidden indentation",
                "    abcd ef gh ij",
                listOf(Injection(4, "HINT")),
                softWrap = true,
                asked = listOf(Injection(2, "HINT")),
            ),
        )
    }

    /** Ranges around the injection at [column]: touching it on each side, across it, and away from it. */
    private fun rangesAround(column: Int, length: Int): List<Pair<Int, Int>> = listOf(
        column - 1 to column,
        column to column + 1,
        column - 3 to column,
        column to column + 3,
        column - 2 to column + 2,
        0 to 2,
        length - 3 to length - 1,
    ).map { (from, to) -> from.coerceIn(0, length - 1) to to.coerceIn(1, length) }.filter { (from, to) -> to > from }

    private fun Scene.injectedAt(): Int = items.last { !it.isRowAbove }.column

    private fun PixelMap.boxOf(color: Color, context: String): Rect = assertNotNull(bounds(color), "nothing drawn: $context")

    /** That [box] covers x's [expected], grown by [overhang] each side, and is on the row of [scene]'s [column]. */
    private fun assertOn(scene: Scene, column: Int, box: Rect, expected: Rect, context: String, overhang: Float = 0f) {
        assertEquals(expected.left - overhang, box.left, 0.5f, "left of $context")
        assertEquals(expected.right + overhang, box.right, 0.5f, "right of $context")
        val band = scene.band(column)
        assertTrue(box.top >= band.start - 2f && box.bottom <= band.endInclusive + 5f, "$box is on row ${band.start}: $context")
    }

    private fun forEachRange(block: (scene: Scene, from: Int, to: Int, context: String) -> Unit) {
        for (scene in scenes()) {
            for ((from, to) in rangesAround(scene.injectedAt(), scene.text.length)) {
                if (scene.onOneRow(from, to)) block(scene, from, to, "$from..$to with ${scene.name}")
            }
        }
    }

    private fun decorations(
        selections: List<TextRange> = emptyList(),
        findMatches: List<IntRange> = emptyList(),
        diagnostics: List<Diagnostic> = emptyList(),
        composition: TextRange? = null,
        bracketMatch: Pair<Int, Int>? = null,
        renderWhitespace: RenderWhitespace = RenderWhitespace.None,
        hostDecorations: List<Decoration> = emptyList(),
        colorSwatches: List<ColorInfo> = emptyList(),
    ) = EditorDecorations(
        selections = selections,
        findMatches = findMatches,
        currentFindMatch = -1,
        diagnostics = diagnostics,
        composition = composition,
        bracketMatch = bracketMatch,
        renderWhitespace = renderWhitespace,
        hostDecorations = hostDecorations,
        colorSwatches = colorSwatches,
    )

    @Test
    fun `a caret stays on its column`() {
        for (scene in scenes()) {
            for (column in 0..scene.text.length) {
                val context = "caret at $column with ${scene.name}"
                val expected = PADDING + scene.raw.getCursorRect(scene.caretAt(column)).left
                val box = scene.drawCaret(column).boxOf(colors.caret, context)
                assertEquals(expected, box.left, 0.5f, context)
                assertEquals(2f, box.width, 0.5f, context)
            }
        }
    }

    @Test
    fun `a selection stays on its characters`() = forEachRange { scene, from, to, context ->
        val box = scene.draw(decorations(selections = listOf(TextRange(from, to)))).boxOf(colors.selection, context)
        assertOn(scene, from, box, scene.span(from, to), context)
    }

    @Test
    fun `a find match stays on its characters`() = forEachRange { scene, from, to, context ->
        val box = scene.draw(decorations(findMatches = listOf(from until to))).boxOf(colors.findMatch, context)
        assertOn(scene, from, box, scene.span(from, to), context)
    }

    @Test
    fun `a squiggle stays on its characters`() = forEachRange { scene, from, to, context ->
        val diagnostic = Diagnostic(from until to, "wrong", DiagnosticSeverity.Error)
        val box = scene.draw(decorations(diagnostics = listOf(diagnostic))).boxOf(colors.error, context)
        // Its stroke's round caps reach past the ends.
        assertOn(scene, from, box, scene.span(from, to), context, overhang = 0.75f)
    }

    @Test
    fun `a border stays on its characters`() = forEachRange { scene, from, to, context ->
        val border = Decoration(from until to, DecorationStyle(border = borderColor))
        val box = scene.draw(decorations(hostDecorations = listOf(border))).boxOf(borderColor, context)
        assertOn(scene, from, box, scene.span(from, to), context, overhang = 0.5f)
    }

    @Test
    fun `a colour swatch is drawn in the room injected before its colour`() {
        for (base in scenes()) {
            val columns = listOf(0, base.injectedAt(), base.text.length - 2).filter { it in 0..base.text.length - 2 }.distinct()
            for (column in columns) {
                val room = Injection(column, SWATCH_ROOM, id = InlineContent.SwatchSlot)
                val scene = Scene("${base.name} and a swatch at $column", base.text, base.items + room, base.softWrap, base.viewWidth)
                // Inside hidden indentation the room moves to its end; a colour never starts in whitespace.
                if (scene.layout.injectedBoxBefore(column, InlineContent.SwatchSlot) == null) continue
                val swatch = ColorInfo(column until column + 2, swatchColor)
                val box = scene.draw(decorations(colorSwatches = listOf(swatch))).boxOf(swatchColor, scene.name)
                val start = scene.charAt(column) - SWATCH_ROOM.length
                val first = scene.raw.getBoundingBox(start)
                val last = scene.raw.getBoundingBox(start + SWATCH_ROOM.length - 1)
                assertTrue(box.left > PADDING + first.left && box.right < PADDING + last.right, "$box in the room: ${scene.name}")
                val top = PADDING + scene.raw.getLineForOffset(start) * LINE_HEIGHT
                assertTrue(box.top >= top && box.bottom <= top + LINE_HEIGHT, "$box on the room's row: ${scene.name}")
            }
        }
    }

    @Test
    fun `a colour with no room before it gets no swatch`() {
        for (scene in scenes()) {
            val drawn = scene.draw(decorations(colorSwatches = listOf(ColorInfo(0 until 2, swatchColor))))
            assertNull(drawn.bounds(swatchColor), scene.name)
        }
    }

    @Test
    fun `a composition's underline stays on its characters`() = forEachRange { scene, from, to, context ->
        val box = scene.draw(decorations(composition = TextRange(from, to))).boxOf(colors.text, context)
        assertOn(scene, from, box, scene.span(from, to), context)
    }

    @Test
    fun `a bracket's box stays on its character`() {
        for (scene in scenes()) {
            val injected = scene.injectedAt()
            for (column in listOf(injected - 1, injected, injected + 1)) {
                val context = "bracket at $column with ${scene.name}"
                val box = scene.draw(decorations(bracketMatch = column to column)).boxOf(colors.bracketMatch, context)
                assertOn(scene, column, box, scene.span(column, column + 1), context, overhang = 0.5f)
            }
        }
    }

    @Test
    fun `a space's dot stays on its space, either side of what is injected`() {
        // The space at column 4 is right after the injection, then right before it.
        for (at in listOf(4, 5)) {
            val scene = Scene("an injection at $at", "abcd efgh", listOf(Injection(at, "HINT")))
            val marks = decorations(selections = listOf(TextRange(4, 5)), renderWhitespace = RenderWhitespace.Selection)
            val box = scene.draw(marks).boxOf(colors.whitespace, scene.name)
            assertEquals(scene.span(4, 5).center.x, box.center.x, 0.5f, scene.name)
        }
    }

    @Test
    fun `a tab's arrow stays in its tab, and a tab counts the columns injected before it`() {
        val tab = 2
        for (hint in listOf("H", "HI", "HIN", "HINT", "HINTS")) {
            val scene = Scene("a hint of ${hint.length}", "ab\tcd", listOf(Injection(1, hint)))
            val marks = decorations(selections = listOf(TextRange(tab, tab + 1)), renderWhitespace = RenderWhitespace.Selection)
            val box = scene.draw(marks).boxOf(colors.whitespace, scene.name)
            val span = scene.span(tab, tab + 1)
            assertTrue(box.left >= span.left - 0.5f && box.right <= span.right + 0.5f, "$box is inside $span: ${scene.name}")
            // Column of the tab: "a", the hint, "b". Its blank reaches the next stop of 4.
            val columns = 4 - (2 + hint.length) % 4
            assertEquals(columns * InlineFixture.charWidth, span.width, 0.5f, scene.name)
        }
    }

    @Test
    fun `a selection across a break leaves the injected row between its text rows clear`() {
        val scene = Scene("a break in the line", line, listOf(Injection(7, "ghost\nrow\nend ")))
        val pixels = scene.draw(decorations(selections = listOf(TextRange(5, 9))))
        val box = pixels.boxOf(colors.selection, scene.name)
        assertEquals(PADDING, box.top, 1f, "from the first text row")
        assertEquals(PADDING + 3 * LINE_HEIGHT, box.bottom, 1f, "to the last")
        val rowPx = LINE_HEIGHT.toInt() * PAINT_SCALE
        val top = PADDING.toInt() * PAINT_SCALE
        val middle = (top + rowPx + 8 until top + 2 * rowPx - 8).flatMap { y -> (0 until pixels.width).map { x -> pixels[x, y] } }
        assertTrue(middle.none { it == colors.selection }, "the row of only injected text is not selected")
        // Columns 5 and 6 on the first row; 7 and 8 on the last, after the injected text there.
        val spans = listOf(scene.span(5, 7), scene.span(7, 9))
        assertEquals(spans.minOf { it.left }, box.left, 0.5f)
        assertEquals(spans.maxOf { it.right }, box.right, 0.5f)
    }

    @Test
    fun `a selection across a hint that fills rows leaves those rows clear`() {
        val hint = Injection(3, "h".repeat(120))
        val scene = Scene("a hint filling rows", "abcdefgh", listOf(hint), softWrap = true, viewWidth = 300)
        val raw = scene.raw
        val firstRow = raw.getLineForOffset(scene.charAt(2))
        val lastRow = raw.getLineForOffset(scene.charAt(3))
        assertTrue(lastRow - firstRow > 1, "the hint fills whole rows: $firstRow to $lastRow")
        val pixels = scene.draw(decorations(selections = listOf(TextRange(1, 5))))
        val box = pixels.boxOf(colors.selection, scene.name)
        assertEquals(PADDING + firstRow * LINE_HEIGHT, box.top, 1f, "from the first text row")
        assertEquals(PADDING + (lastRow + 1) * LINE_HEIGHT, box.bottom, 1f, "to the last")
        val rowPx = LINE_HEIGHT.toInt() * PAINT_SCALE
        val top = PADDING.toInt() * PAINT_SCALE
        for (row in firstRow + 1 until lastRow) {
            val band = (top + row * rowPx + 8 until top + (row + 1) * rowPx - 8).flatMap { y ->
                (0 until pixels.width).map { x -> pixels[x, y] }
            }
            assertTrue(band.none { it == colors.selection }, "row $row of only injected text is not selected")
        }
    }

    @Test
    fun `a whole-line highlight and a selected line's end stay on the text rows`() {
        val scene = Scene("a lens", line, listOf(Injection.rowAbove("lens")))
        val highlighted = EditorDecorations(listOf(TextRange(2)), emptyList(), -1, emptyList(), null, highlightCaretLines = true)
        val box = scene.draw(highlighted).boxOf(colors.lineHighlight, scene.name)
        assertEquals(PADDING + LINE_HEIGHT, box.top, 0.5f, "not on the lens row")
        assertEquals(PADDING + 2 * LINE_HEIGHT, box.bottom, 0.5f, "only the text row")
    }

    @Test
    fun `a lens row starts where the text of its tab-indented line does`() {
        for (text in listOf("\t\tfunc main()", "  \tfunc main()", "    func main()")) {
            val view = viewOf(text)
            view.state.publishCodeLenses(listOf(CodeLens(0, "Run")))
            view.prepare(width, height)

            val layout = view.layoutFor(0).result
            val laidOut = layout.layoutInput.text.text
            val title = layout.getBoundingBox(laidOut.indexOf("Run")).left
            val code = layout.getBoundingBox(laidOut.indexOf("func")).left

            assertTrue(code > 0f, "the text is indented")
            assertEquals(code, title, 0.5f, "under ${text.takeWhile { it.isWhitespace() }.length} characters of indentation")
        }
    }
}
