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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.bounds
import com.aardarch.aardink.ui.view.InlineFixture.paint
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
 * The first real host of [EditorView.injections]: the text and colours of decorations, and inlay
 * hints, through [InlineContent] with a real text measurer. Nothing here sets the hook by hand.
 */
class InlineContentTest {

    private val width = 600
    private val height = 400

    private fun EditorView.laidOut(line: Int = 0): String = layoutFor(line).result.layoutInput.text.text

    private fun EditorView.decorate(range: IntRange, before: String? = null, after: String? = null, color: Color = Color.Unspecified) {
        state.setDecorations("test", listOf(Decoration(range, DecorationStyle(before = before, after = after, inlineColor = color))))
        prepare(width, height)
    }

    private fun EditorView.hint(vararg hints: InlayHint) {
        state.publishInlayHints(hints.toList())
        prepare(width, height)
    }

    private fun EditorView.edit(at: Int, delete: Int, insert: String) {
        state.applyEdit(at, delete, insert, TextRange(at + insert.length))
        prepare(width, height)
    }

    private fun EditorView.rowsOf(line: Int) = lineMap.rowsOf(line)

    // ── Nothing inline ───────────────────────────────────────────────────────

    @Test
    fun `a view with nothing inline stays without the hook`() {
        val view = viewOf("val x = 1")
        view.state.setDecorations("plain", listOf(Decoration(0..2, DecorationStyle(background = Color.Red))))
        view.prepare(width, height)

        assertNull(view.injections, "an editor that shows nothing inline costs what it did")
        assertSame(OffsetMap.Identity, view.shown(0).layout.map)
    }

    @Test
    fun `the first thing to show brings the hook, and an empty text is nothing to show`() {
        val view = viewOf("val x = 1")
        view.decorate(0..2, before = "", after = "")
        assertNull(view.injections)

        view.decorate(0..2, after = "!")

        assertEquals("val! x = 1", view.laidOut())
    }

    @Test
    fun `a change to a decoration that is not inline bumps nothing`() {
        val view = viewOf("val x = 1")
        view.decorate(0..2, after = "!")
        val version = view.injectionVersion

        view.state.setDecorations("plain", listOf(Decoration(0..2, DecorationStyle(background = Color.Red))))
        view.state.setDecorations("plain", listOf(Decoration(0..3, DecorationStyle(background = Color.Red))))
        view.state.clearDecorations("plain")

        assertEquals(version, view.injectionVersion)
    }

    @Test
    fun `an answer the same as the last changes nothing`() {
        val view = viewOf("val x = 1")
        view.hint(InlayHint(5, ": Int"))
        val version = view.injectionVersion

        view.state.publishInlayHints(listOf(InlayHint(5, ": Int")))
        view.inline.setStyles(null, null)

        assertEquals(version, view.injectionVersion)
    }

    @Test
    fun `decorations set before the view attached are shown from the start`() {
        val state = InlineFixture.stateOf("val x = 1")
        state.setDecorations("early", listOf(Decoration(4..4, DecorationStyle(before = "<"))))
        val view = EditorView(state)
        view.style = viewOf("").style
        view.attach()
        view.prepare(width, height)

        assertEquals("val <x = 1", view.laidOut())
    }

    @Test
    fun `a detached view hears no more of the decorations`() {
        val view = viewOf("val x = 1")
        view.detach()
        view.state.setDecorations("late", listOf(Decoration(4..4, DecorationStyle(before = "<"))))

        assertNull(view.injections)
        assertTrue(view.state.inlineListeners.isEmpty())
    }

    // ── A decoration's text ──────────────────────────────────────────────────

    @Test
    fun `before and after stand around the range`() {
        val view = viewOf("val x = 1")

        view.decorate(4..4, before = "<", after = ">")

        assertEquals("val <x> = 1", view.laidOut())
        assertEquals("val x = 1", view.state.document.text)
    }

    @Test
    fun `an empty range shows both at its place, before first, with a caret in front of them`() {
        val view = viewOf("val x = 1")

        view.decorate(4 until 4, before = "<", after = ">")

        assertEquals("val <>x = 1", view.laidOut())
        assertEquals(4, view.shown(0).layout.map.toLayout(4))
        view.edit(4, 0, "y")
        assertEquals("val y<>x = 1", view.laidOut())
    }

    @Test
    fun `a caret is on the side of each text that typing lands on`() {
        val view = viewOf("val x = 1")
        val controller = EditorController(view.state, view)
        view.decorate(4..4, before = "<", after = ">")
        val map = view.shown(0).layout.map
        assertEquals(4, map.toLayout(4), "in front of before")
        assertEquals("val <x>".length, map.toLayout(5), "behind after")

        view.state.selection = TextRange(5)
        controller.type("y")
        view.prepare(width, height)
        assertEquals("val <x>y = 1", view.laidOut(), "typed at the range's end, it stays out of the range")
        assertEquals(4..4, view.state.decorations("test").single().range)

        view.state.selection = TextRange(4)
        controller.type("z")
        view.prepare(width, height)
        assertEquals("val z<x>y = 1", view.laidOut(), "typed at its start, it moves the range along")
        assertEquals(5..5, view.state.decorations("test").single().range)
    }

    @Test
    fun `a line break shows as a space, and adds no row`() {
        val view = viewOf("val x = 1")

        view.decorate(4..4, after = "a\nb\r\nc")

        assertEquals("val xa b  c = 1", view.laidOut())
        assertEquals(1, view.lineMap.totalRows)
    }

    @Test
    fun `text follows its range through edits above it, on its line and across lines`() {
        val view = viewOf("first\nval x = 1\nlast")
        view.decorate(10..10, before = "<", after = ">")
        assertEquals("val <x> = 1", view.laidOut(1))

        view.edit(0, 0, "zero\n")
        assertEquals("val <x> = 1", view.laidOut(2), "it moved down with its line")
        assertEquals("zero", view.laidOut(0))

        view.edit(view.state.document.lineStart(2), 0, "// ")
        assertEquals("// val <x> = 1", view.laidOut(2), "further along the line")

        view.edit(view.state.document.lineStart(2) - 1, 1, "")
        assertEquals("first// val <x> = 1", view.laidOut(1), "up, when its line joined the one above")
    }

    @Test
    fun `text stays where its range collapsed, and goes when the whole text is replaced`() {
        val view = viewOf("val x = 1")
        view.decorate(4..4, before = "<", after = ">")

        view.edit(4, 1, "")
        assertEquals("val <> = 1", view.laidOut())

        view.state.loadText("other")
        view.prepare(width, height)
        assertEquals("other", view.laidOut())
    }

    @Test
    fun `clearing an owner takes its text and leaves another's`() {
        val view = viewOf("val x = 1")
        view.state.setDecorations("a", listOf(Decoration(0..2, DecorationStyle(after = "<a>"))))
        view.state.setDecorations("b", listOf(Decoration(4..4, DecorationStyle(after = "<b>"))))
        view.prepare(width, height)
        assertEquals("val<a> x<b> = 1", view.laidOut())

        view.state.clearDecorations("a")
        view.prepare(width, height)

        assertEquals("val x<b> = 1", view.laidOut())
    }

    @Test
    fun `decorations and hints on one line are one answer`() {
        val view = viewOf("val x = 1")
        view.state.setDecorations("test", listOf(Decoration(0..2, DecorationStyle(after = "<a>"))))
        view.hint(InlayHint(5, ": Int"))

        assertEquals("val<a> x: Int = 1", view.laidOut())

        view.hint()
        assertEquals("val<a> x = 1", view.laidOut())
    }

    @Test
    fun `what changes while an edit has left the answer stale is still told to the view`() {
        val view = viewOf(longLines, softWrap = true, width = 300, height = 200)
        val line = view.state.document.lineStart(200)
        view.state.publishInlayHints(listOf(InlayHint(0, "h")))
        view.prepare(300, 200)
        view.state.applyEdit(0, 0, "x", TextRange(1))

        view.state.publishInlayHints(listOf(InlayHint(line + 1, long)))

        assertTrue(view.rowsOf(200) > 3, "the rows heard of it: ${view.rowsOf(200)}")
    }

    @Test
    fun `a decoration's text and a hint at one column keep one order`() {
        val view = viewOf("ab")
        view.state.setDecorations("test", listOf(Decoration(1..1, DecorationStyle(before = "<"))))
        view.hint(InlayHint(1, "h"))

        assertEquals("a<hb", view.laidOut())
    }

    // ── The rows, when an edit moves what is shown ───────────────────────────

    private val longLines = (0 until 300).joinToString("\n") { "line $it" }

    private val long = "x".repeat(400)

    /** Lines added above the text's own line: the old offset now falls on one of them. */
    private val newLines = "\n".repeat(50)

    @Test
    fun `a decoration has moved by the time the view splices its rows`() {
        val view = viewOf(longLines, softWrap = true, width = 300, height = 200)
        val lineStart = view.state.document.lineStart(200)
        // Set after the view attached, which is what used to leave it listening after the view.
        view.state.setDecorations("test", listOf(Decoration(lineStart + 2..lineStart + 2, DecorationStyle(after = long))))
        view.prepare(300, 200)
        assertTrue(view.rowsOf(200) > 3, "it wraps: ${view.rowsOf(200)}")

        view.state.applyEdit(lineStart, 0, newLines, TextRange(lineStart))

        assertEquals(1, view.rowsOf(202), "what it left behind is not counted")
        assertTrue(view.rowsOf(250) > 3, "it is counted on the line it moved with: ${view.rowsOf(250)}")
    }

    @Test
    fun `a hint has moved by the time the view splices its rows`() {
        val view = viewOf(longLines, softWrap = true, width = 300, height = 200)
        val lineStart = view.state.document.lineStart(200)
        view.state.publishInlayHints(listOf(InlayHint(lineStart + 2, long)))
        view.prepare(300, 200)
        assertTrue(view.rowsOf(200) > 3, "it wraps: ${view.rowsOf(200)}")

        view.state.applyEdit(lineStart, 0, newLines, TextRange(lineStart))

        assertEquals(1, view.rowsOf(202), "what it left behind is not counted")
        assertTrue(view.rowsOf(250) > 3, "it is counted on the line it moved with: ${view.rowsOf(250)}")
    }

    @Test
    fun `lines that changed are estimated, and lines that did not are left as measured`() {
        val view = viewOf(longLines, softWrap = true, width = 300, height = 200)
        val first = view.state.document.lineStart(100)
        val second = view.state.document.lineStart(200)
        view.state.publishInlayHints(listOf(InlayHint(first, long)))
        view.prepare(300, 200)
        val rowsOfFirst = view.rowsOf(100)
        assertTrue(rowsOfFirst > 3)

        view.state.publishInlayHints(listOf(InlayHint(first, long), InlayHint(second, long)))

        assertEquals(rowsOfFirst, view.rowsOf(100))
        assertTrue(view.rowsOf(200) > 3)

        view.state.publishInlayHints(listOf(InlayHint(second, long)))
        assertEquals(1, view.rowsOf(100), "a line losing its hint is a line that changed")
    }

    @Test
    fun `hints arriving above the view move nothing on screen`() {
        val text = (0 until 300).joinToString("\n") { "line $it with some more words on it" }
        val view = viewOf(text, softWrap = true, width = 300, height = 200)
        view.scroll.scrollToY(view.contentTop(150))
        view.prepare(300, 200)
        view.prepare(300, 200)
        val firstShown = view.frame.lines.first().line
        assertTrue(firstShown > 100, "the view is well down the document: $firstShown")
        val shown = view.frame.lines.map { it.line }
        fun places() = shown.map { view.caretRect(view.state.document.lineStart(it)) }
        val rowsBefore = view.lineMap.totalRows
        val before = places()

        // The first hints: they bring the hook, and a row map built again from estimates.
        val hints = (0 until firstShown).map { InlayHint(view.state.document.lineStart(it) + 2, long) }
        view.state.publishInlayHints(hints)
        view.prepare(300, 200)
        view.prepare(300, 200)

        assertTrue(view.lineMap.totalRows > rowsBefore + firstShown, "they added rows above the view")
        for ((i, place) in places().withIndex()) {
            assertEquals(before[i].top, place.top, 0.5f, "line ${shown[i]} stayed where it was")
            assertEquals(before[i].left, place.left, 0.5f, "line ${shown[i]} stayed where it was")
        }

        // Then an answer with fewer: through the rows it changed, with the hook there.
        val rowsAfter = view.lineMap.totalRows
        view.state.publishInlayHints(hints.drop(firstShown / 2))
        view.prepare(300, 200)

        assertTrue(view.lineMap.totalRows < rowsAfter, "they took rows away above the view")
        for ((i, place) in places().withIndex()) {
            assertEquals(before[i].top, place.top, 0.5f, "line ${shown[i]} stayed where it was")
        }
    }

    @Test
    fun `a hint past where a long line is cut is not asked for, so it counts for no row`() {
        val text = "x".repeat(EditorView.MAX_RENDERED_LINE_CHARS + 2_000) + "\nnext"
        val view = viewOf(text, softWrap = true, width = 300, height = 200)
        val cut = EditorView.MAX_RENDERED_LINE_CHARS

        view.state.publishInlayHints(listOf(InlayHint(cut + 1, long)))
        assertNull(view.inline.lookup(0), "it could never be laid out")

        view.state.publishInlayHints(listOf(InlayHint(cut, "h")))
        assertEquals(cut, view.inline.lookup(0)?.items?.single()?.column, "the column it is cut at can still show one")
    }

    @Test
    fun `no column an answer gives is past its line`() {
        val view = viewOf("ab\ncd")

        view.state.publishInlayHints(listOf(InlayHint(2, "end"), InlayHint(5, "last")))

        assertEquals(2, view.inline.lookup(0)?.items?.single()?.column)
        assertEquals(2, view.inline.lookup(1)?.items?.single()?.column)
    }

    // ── Hints ────────────────────────────────────────────────────────────────

    @Test
    fun `a hint moves with the text around it, and stays in front of what is typed at it`() {
        val view = viewOf("first\nval x = 1")
        view.hint(InlayHint(11, ": Int"))
        assertEquals("val x: Int = 1", view.laidOut(1))

        view.edit(0, 0, "zero\n")
        assertEquals("val x: Int = 1", view.laidOut(2))
        assertEquals(16, view.state.inlayHints.single().offset)

        view.edit(16, 0, "yz")
        assertEquals("val xyz: Int = 1", view.laidOut(2), "at the end of the word it is about, as the caret is")
        assertEquals("val xyz".length, view.layoutFor(2).map.toLayout(7), "the caret stays in front of it")
    }

    @Test
    fun `a hint in deleted text goes with it, and one at its edge stays`() {
        val view = viewOf("abcdefgh")
        view.hint(InlayHint(2, "edge"), InlayHint(3, "in"), InlayHint(5, "in"), InlayHint(6, "other"))

        view.edit(2, 4, "")

        assertEquals(listOf(InlayHint(2, "edge"), InlayHint(2, "other")), view.state.inlayHints)
        assertEquals("abedgeothercdgh".replace("cd", ""), view.laidOut())
    }

    @Test
    fun `replacing the whole text drops the hints`() {
        val view = viewOf("val x = 1")
        view.hint(InlayHint(5, ": Int"))

        view.state.loadText("other")
        view.prepare(width, height)

        assertEquals("other", view.laidOut())
        assertTrue(view.state.inlayHints.isEmpty())
    }

    @Test
    fun `a hint's style is its own, and a new style lays the line out again`() {
        val view = viewOf("val x = 1")
        val first = SpanStyle(color = Color.Blue)
        val second = SpanStyle(color = Color.Green)
        view.inline.setStyles(first, null)
        view.hint(InlayHint(5, ": Int"))
        assertEquals(first, view.inline.lookup(0)?.items?.single()?.style)

        view.inline.setStyles(second, null)
        view.prepare(width, height)

        assertEquals(second, view.shown(0).layout.map.injectionAt(5)?.style)
    }

    // ── Around real hints ────────────────────────────────────────────────────

    @Test
    fun `a click on a hint puts the caret at its column, with no injection to report`() {
        val view = viewOf("val x = 1")
        view.hint(InlayHint(5, ": Int"))
        val layout = view.shown(0).layout
        val onHint = (layout.caretX(5) + layout.charLeft(5)) / 2f
        assertTrue(layout.charLeft(5) - layout.caretX(5) > 10f, "the hint has width")
        var clicked = false
        view.onInjectionClick = { _, _ -> clicked = true }
        val pointer = EditorPointerHandler(view)

        pointer.press(Offset(PADDING + onHint, PADDING + 10f), clicks = 1, shift = false, alt = false)
        pointer.release()

        assertEquals(TextRange(5), view.state.selection)
        assertFalse(clicked)
    }

    @Test
    fun `up and down go through hints that wrap, row by row`() {
        val view = viewOf("first\n" + "second line\nthird", softWrap = true, width = 300, height = 200)
        view.hint(InlayHint(9, long))
        view.prepare(300, 200)
        assertTrue(view.rowsOf(1) > 3, "the hint fills rows")

        val down = view.verticalMove(2, 1)
        assertEquals(1, view.state.document.offsetToLineCol(down).first)
        var at = down
        var steps = 0
        while (view.state.document.offsetToLineCol(at).first == 1 && steps++ < 20) at = view.verticalMove(at, 1)

        assertEquals(2, view.state.document.offsetToLineCol(at).first, "the caret got through to the next line")
        assertTrue(steps < 4, "without stopping on the rows only the hint is on: $steps")
    }

    @Test
    fun `copy and cut take the document's text`() {
        val view = viewOf("val x = 1\nsecond")
        view.state.setDecorations("test", listOf(Decoration(0..2, DecorationStyle(before = "<", after = ">"))))
        view.hint(InlayHint(5, ": Int"))
        val controller = EditorController(view.state, view)

        view.state.selection = TextRange(0, view.state.document.length)
        assertEquals("val x = 1\nsecond", controller.copyText())

        view.state.selection = TextRange(3, 7)
        assertEquals(" x =", controller.cutText())
        assertEquals("val 1\nsecond", view.state.document.text)
    }

    @Test
    fun `a selection and a hint's text are in different places on the row`() {
        val view = viewOf("val x = 1")
        view.hint(InlayHint(5, ": Int"))
        val layout = view.shown(0).layout

        val box = layout.rangePath(0, 5).getBounds()

        assertEquals(layout.charLeft(0), box.left, 0.5f)
        assertEquals(layout.caretX(5), box.right, 0.5f, "it stops where the hint starts")
    }

    // ── Colours ──────────────────────────────────────────────────────────────

    private object Words : IncrementalTokenizer {
        private fun words(text: String) =
            Regex("[a-z]+").findAll(text).map { Token(it.range.first, it.range.last + 1, TokenType.Keyword) }.toList()

        override fun tokenizeFull(text: String): List<Token> = words(text)

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = words(text)

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private val tokenStyle: (TokenType) -> SpanStyle? = { type -> if (type == TokenType.Keyword) SpanStyle(color = Color.Red) else null }

    private fun colored(text: String) = viewOf(text, tokenizer = Words, tokenStyle = tokenStyle)

    private fun drawn(view: EditorView, line: Int = 0): PixelMap =
        paint(300, 20) { drawStickyLines(view, listOf(StickyLine(line, 0f)), Color.White, Color.White) }

    /** The layout ranges of the spans in [color] on [line]. */
    private fun EditorView.spans(color: Color, line: Int = 0): List<IntRange> = layoutFor(line).result.layoutInput.text.spanStyles.filter {
        it.item.color == color
    }.map { it.start until it.end }.sortedBy { it.first }

    @Test
    fun `inlineColor wins over the syntax's colour, on the range's characters only`() {
        val view = colored("word plain")
        assertNull(drawn(view).bounds(Color.Green))

        view.decorate(0..3, color = Color.Green)

        val pixels = drawn(view)
        val inline = assertNotNull(pixels.bounds(Color.Green), "the range is in its colour")
        val syntax = assertNotNull(pixels.bounds(Color.Red), "the rest keeps the syntax's")
        assertTrue(inline.right <= syntax.left, "$inline is left of $syntax")
    }

    @Test
    fun `inlineColor over several tokens wins on all of them, though it starts before they do`() {
        val view = colored("  one two three")

        view.decorate(0..14, color = Color.Green)

        val pixels = drawn(view)
        assertNotNull(pixels.bounds(Color.Green))
        assertNull(pixels.bounds(Color.Red), "text draws the span that starts last")
    }

    @Test
    fun `inlineColor colours before and after too, and leaves a hint in the range alone`() {
        val view = viewOf("abcdef")
        view.inline.setStyles(SpanStyle(color = Color.Blue), null)
        view.hint(InlayHint(3, "h"))

        view.decorate(1..4, before = "<", after = ">", color = Color.Green)

        assertEquals("a<bchde>f", view.laidOut())
        assertEquals(listOf(1..1, 2..3, 5..6, 7..7), view.spans(Color.Green))
        assertEquals(listOf(4..4), view.spans(Color.Blue))
    }

    @Test
    fun `text with no colour of its own has the faint style`() {
        val view = viewOf("abc")
        val faint = SpanStyle(color = Color.Gray)
        view.inline.setStyles(null, faint)

        view.decorate(1..1, after = ">")

        assertEquals(listOf(2..2), view.spans(Color.Gray))
    }

    @Test
    fun `a colour over several lines covers all of them, cut at each line's ends`() {
        val view = viewOf("first\nsecond\nthird\nfourth")

        view.decorate(3..14, color = Color.Green)

        assertEquals(listOf(3..4), view.spans(Color.Green, 0))
        assertEquals(listOf(0..5), view.spans(Color.Green, 1))
        assertEquals(listOf(0..1), view.spans(Color.Green, 2))
        assertEquals(emptyList(), view.spans(Color.Green, 3))
    }

    @Test
    fun `a colour over several lines goes when it is cleared, and moves with an edit`() {
        val view = viewOf("first\nsecond\nthird")
        view.decorate(3..14, color = Color.Green)

        view.edit(0, 0, "x\n")
        assertEquals(emptyList(), view.spans(Color.Green, 0))
        assertEquals(listOf(3..4), view.spans(Color.Green, 1))
        assertEquals(listOf(0..5), view.spans(Color.Green, 2))

        val version = view.injectionVersion
        view.state.clearDecorations("test")
        assertTrue(view.injectionVersion > version, "no row changes, and the text is still drawn again")
        view.prepare(width, height)
        assertEquals(emptyList(), view.spans(Color.Green, 2))
    }

    @Test
    fun `a colour that adds nothing costs a line nothing in columns`() {
        val view = viewOf("abcdef")

        view.decorate(1..2, color = Color.Green)

        assertSame(OffsetMap.Identity, view.shown(0).layout.map, "no text is injected")
        assertEquals("abcdef", view.laidOut())
    }

    @Test
    fun `a colour leaves hidden indentation hidden`() {
        val view = viewOf("    word", softWrap = true)

        view.decorate(1..6, color = Color.Green)

        assertEquals(listOf(4..6), view.spans(Color.Green))
    }

    @Test
    fun `a line whose colour changes is laid out again, and a line whose colour did not is not`() {
        val view = viewOf("first\nsecond")
        view.decorate(0..1, color = Color.Green)
        val second = view.shown(1).layout

        view.decorate(0..1, color = Color.Blue)

        assertEquals(listOf(0..1), view.spans(Color.Blue))
        assertSame(second, view.shown(1).layout)
    }

    private val state: (String) -> CodeEditorState = InlineFixture::stateOf

    @Test
    fun `a state without a view moves its decorations all the same`() {
        val state = state("abc")
        state.setDecorations("test", listOf(Decoration(1..1, DecorationStyle(after = ">"))))

        state.applyEdit(0, 0, "x", TextRange(1))

        assertEquals(2..2, state.decorations("test").single().range)
    }
}
