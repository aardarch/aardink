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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.core.diff.LineChange
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.bounds
import com.aardarch.aardink.ui.view.InlineFixture.paint
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The 0.12 diff spike (`docs/spikes/0.12-diff-rows.md`): a diff's rows on the inline layout of 0.9,
 * on desktop and in the browser. D1 deleted lines inline, D2 after the last line, D3 side by side
 * line for line, D4 edits move the rows, D5 side by side under soft wrap.
 */
class DiffRowsTest {

    private val removed = Color(0xFFFF0011)
    private val filler = Color(0xFF00FF12)
    private val keyword = SpanStyle(color = Color(0xFF0000F3))

    private object Words : IncrementalTokenizer {
        private fun words(text: String) =
            Regex("[a-z]+").findAll(text).map { Token(it.range.first, it.range.last + 1, TokenType.Keyword) }.toList()

        override fun tokenizeFull(text: String): List<Token> = words(text)

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = words(text)

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private val colors = EditorColors(
        selection = Color(0xFF000001),
        findMatch = Color(0xFF000002),
        currentFindMatch = Color(0xFF000003),
        error = Color(0xFF000004),
        warning = Color(0xFF000005),
        info = Color(0xFF000006),
        caret = Color(0xFF000007),
        text = Color(0xFF000008),
        lineHighlight = Color(0xFF000009),
        bracketMatch = Color(0xFF00000A),
    )

    private fun EditorView.publish(rows: Map<Int, List<Injection>>, width: Int = 600, height: Int = 400) {
        state.publishDiffRows(rows)
        prepare(width, height)
    }

    /** Every offset a caret reaches going down from the start, a row at a time. */
    private fun EditorView.caretWalk(): List<Int> {
        val offsets = ArrayList<Int>()
        var offset = 0
        repeat(lineMap.totalRows + 2) {
            offsets += offset
            val next = verticalMove(offset, 1, 0f)
            if (next == offset) return offsets
            offset = next
        }
        return offsets
    }

    // ── D1: deleted lines inline ─────────────────────────────────────────────

    @Test
    fun `deleted lines are rows above the line after them, out of reach of the caret`() {
        val original = InlineFixture.stateOf("alpha\nbeta\ngamma\ndelta")
        val view = viewOf("alpha\ndelta")
        val rows = DiffRows.inline(listOf(LineChange(1, 3, 1, 1, null)), original, view.state.document, { null }, removed, tabSize = 4)
        view.publish(rows)

        val layout = view.shown(1).layout
        assertEquals(2, layout.textRow, "two deleted lines above delta")
        assertEquals(3, view.lineMap.rowsOf(1))
        assertEquals(4, view.lineMap.totalRows)
        assertEquals(listOf(0, 6, 11), view.caretWalk(), "Down from alpha goes straight to delta, then to its end")
        assertEquals(PADDING + 3 * LINE_HEIGHT, view.textTop(1))
        assertEquals("alpha\ndelta", view.state.document.text, "the document holds none of the rows")
    }

    @Test
    fun `a deleted line keeps its token colours, on the band of its background`() {
        val original = InlineFixture.stateOf("keep\nfun x() {}\nkeep", tokenizer = Words)
        // Every word a token, each coloured as a keyword here.
        val rows = DiffRows.inline(listOf(LineChange(1, 2, 1, 1, null)), original, InlineFixture.stateOf("keep\nkeep").document, {
            keyword
        }, removed, 4)
        val injection = rows.getValue(1).single()
        assertEquals("fun x() {}\n", injection.text)
        assertEquals(listOf(0 to 3, 4 to 5), injection.spans.map { it.start to it.end }, "fun and x")
        assertTrue(injection.spans.all { it.style == keyword })
        assertTrue(injection.isRowAbove)
    }

    @Test
    fun `the band of deleted rows is painted across the whole width on exactly their rows`() {
        val original = InlineFixture.stateOf("alpha\nbeta\ngamma\ndelta")
        val view = viewOf("alpha\ndelta", width = 300, height = 200)
        view.publish(DiffRows.inline(listOf(LineChange(1, 3, 1, 1, null)), original, view.state.document, { null }, removed, 4), 300, 200)
        val pixels = paint(300, 200) {
            drawEditorContent(view.frame, EditorDecorations(emptyList(), emptyList(), -1, emptyList(), null), colors)
        }
        val band = assertNotNull(pixels.bounds(removed))
        assertEquals(0f, band.left)
        assertEquals(300f, band.right)
        assertEquals(PADDING + LINE_HEIGHT, band.top, 0.5f)
        assertEquals(PADDING + 3 * LINE_HEIGHT, band.bottom, 0.5f)
    }

    // ── D2: after the last line ──────────────────────────────────────────────

    @Test
    fun `lines deleted at the end are rows after the last line`() {
        val original = InlineFixture.stateOf("alpha\nbeta\ngamma")
        val view = viewOf("alpha")
        view.publish(DiffRows.inline(listOf(LineChange(1, 3, 1, 1, null)), original, view.state.document, { null }, removed, 4), 300, 200)
        assertEquals(0, view.shown(0).layout.textRow)
        assertEquals(3, view.lineMap.rowsOf(0))
        assertEquals(listOf(0, 5), view.caretWalk(), "Down from alpha goes to its end: no row below it takes a caret")
        val pixels = paint(300, 200) {
            drawEditorContent(view.frame, EditorDecorations(emptyList(), emptyList(), -1, emptyList(), null), colors)
        }
        val band = assertNotNull(pixels.bounds(removed))
        assertEquals(PADDING + LINE_HEIGHT, band.top, 0.5f)
        assertEquals(PADDING + 3 * LINE_HEIGHT, band.bottom, 0.5f)
    }

    // ── D3: side by side, line for line ──────────────────────────────────────

    private val originalText = (0 until 60).joinToString("\n") { "line $it" }
    private val modifiedText = (0 until 60).filter { it !in 10..14 && it != 30 }.flatMap { line ->
        when (line) {
            20 -> listOf("line 20 changed", "added a", "added b")
            40 -> listOf("inserted", "line 40")
            else -> listOf("line $line")
        }
    }.joinToString("\n")

    /** The blocks of [originalText] against [modifiedText], worked out by hand. */
    private val blocks = listOf(
        LineChange(10, 15, 10, 10, null),
        LineChange(20, 21, 15, 18, null),
        LineChange(30, 31, 27, 27, null),
        LineChange(40, 40, 36, 37, null),
    )

    /** Each unchanged original line and the modified line it is. */
    private fun unchangedPairs(): List<Pair<Int, Int>> {
        val pairs = ArrayList<Pair<Int, Int>>()
        var o = 0
        var m = 0
        for (block in blocks + LineChange(60, 60, 55, 55, null)) {
            while (o < block.originalStart) pairs += o++ to m++
            o = block.originalEnd
            m = block.modifiedEnd
        }
        return pairs
    }

    @Test
    fun `filler rows keep the sides line for line, every unchanged line at one height`() {
        val left = viewOf(originalText, height = 4000)
        val right = viewOf(modifiedText, height = 4000)
        val (fillLeft, fillRight) = DiffRows.sideBySide(blocks, left.state.document, right.state.document, filler)
        left.publish(fillLeft, height = 4000)
        right.publish(fillRight, height = 4000)
        assertEquals(left.lineMap.totalRows, right.lineMap.totalRows)
        for ((o, m) in unchangedPairs()) assertEquals(left.textTop(o), right.textTop(m), "original $o, modified $m")
    }

    @Test
    fun `one scroll position shows the same unchanged lines at the same height on both sides`() {
        val left = viewOf(originalText, height = 200)
        val right = viewOf(modifiedText, height = 200)
        val (fillLeft, fillRight) = DiffRows.sideBySide(blocks, left.state.document, right.state.document, filler)
        left.publish(fillLeft, height = 200)
        right.publish(fillRight, height = 200)
        for (y in listOf(0f, 157f, 400f, 777f)) {
            left.scroll.scrollToY(y)
            right.scroll.scrollToY(y)
            left.prepare(600, 200)
            right.prepare(600, 200)
            val shownLeft = left.frame.lines.associate { it.line to it.top - left.frame.scrollY }
            val shownRight = right.frame.lines.associate { it.line to it.top - right.frame.scrollY }
            for ((o, m) in unchangedPairs()) {
                val a = shownLeft[o] ?: continue
                val b = shownRight[m] ?: continue
                assertEquals(
                    a + left.shown(o).layout.textRow * LINE_HEIGHT,
                    b + right.shown(m).layout.textRow * LINE_HEIGHT,
                    "at $y: $o and $m",
                )
            }
        }
    }

    // ── D4: edits move the rows ──────────────────────────────────────────────

    @Test
    fun `an edit above moves the rows with their line, and one on their line keeps those above it`() {
        val original = InlineFixture.stateOf("alpha\nbeta\ndelta")
        val view = viewOf("alpha\ndelta")
        view.publish(DiffRows.inline(listOf(LineChange(1, 2, 1, 1, null)), original, view.state.document, { null }, removed, 4))
        view.state.executeEdits(listOf(TextEdit(0 until 0, "new\n")))
        view.prepare(600, 400)
        assertEquals(setOf(2), view.state.diffRows.keys)
        assertEquals(1, view.shown(2).layout.textRow)
        val at = view.state.document.lineStart(2) + 2
        view.state.executeEdits(listOf(TextEdit(at until at, "X")))
        view.prepare(600, 400)
        assertEquals(1, view.shown(2).layout.textRow, "rows above a line stay with an edit inside it")
    }

    // ── D5: side by side under soft wrap ─────────────────────────────────────

    private val wrappingOriginal = (0 until 40).joinToString("\n") { line ->
        if (line % 3 ==
            0
        ) {
            "w$line " + "word ".repeat(30)
        } else {
            "short $line"
        }
    }
    private val wrappingModified = wrappingOriginal.lines().filterIndexed { line, _ -> line != 7 }.joinToString("\n")

    private fun EditorView.ownRows(line: Int): Int =
        lineMap.rowsOf(line) - (state.diffRows[line]?.sumOf { it.text.count { c -> c == '\n' } } ?: 0)

    @Test
    fun `under soft wrap, panes of different widths line up once the rows on screen are measured`() {
        val leftWidth = 320
        val rightWidth = 520
        val left = viewOf(wrappingOriginal, softWrap = true, width = leftWidth, height = 600)
        val right = viewOf(wrappingModified, softWrap = true, width = rightWidth, height = 600)
        val wrapBlocks = listOf(LineChange(7, 8, 7, 7, null))
        var passes = 0
        do {
            val (fillLeft, fillRight) = DiffRows.alignedByRows(
                wrapBlocks,
                left.state.document,
                right.state.document,
                filler,
                { left.ownRows(it) },
                { right.ownRows(it) },
            )
            val before = left.state.diffRows to right.state.diffRows
            left.publish(fillLeft, leftWidth, 600)
            right.publish(fillRight, rightWidth, 600)
            passes++
        } while ((left.state.diffRows to right.state.diffRows) != before && passes < 5)
        assertTrue(passes <= 3, "settled in $passes passes")
        val pairs = (0 until 40).filter { it != 7 }.map { it to if (it < 7) it else it - 1 }
        for ((o, m) in pairs) {
            if (left.frame.find(o) == null || right.frame.find(m) == null) continue
            assertEquals(left.textTop(o), right.textTop(m), "original $o, modified $m")
        }
        assertEquals(left.lineMap.totalRows, right.lineMap.totalRows)
    }

    // ── D7: a computed diff, end to end ──────────────────────────────────────

    @Test
    fun `a computed diff lines up two real texts side by side, and inline shows every deleted line`() {
        val originalText = (0 until 200).joinToString("\n") { "val v$it = compute($it)" }
        val modifiedText = originalText.lines().mapIndexedNotNull { line, text ->
            when {
                line % 17 == 3 -> null
                line % 23 == 5 -> "$text // changed\nval extra$line = 0"
                else -> text
            }
        }.joinToString("\n")
        val diff = com.aardarch.aardink.core.diff.DiffComputer.compute(originalText, modifiedText)
        val left = viewOf(originalText, height = 6000)
        val right = viewOf(modifiedText, height = 6000)
        val (fillLeft, fillRight) = DiffRows.sideBySide(diff.changes, left.state.document, right.state.document, filler)
        left.publish(fillLeft, height = 6000)
        right.publish(fillRight, height = 6000)
        assertEquals(left.lineMap.totalRows, right.lineMap.totalRows)
        var o = 0
        var m = 0
        for (change in diff.changes + LineChange(200, 200, right.state.document.lineCount, right.state.document.lineCount, null)) {
            while (o < change.originalStart) assertEquals(left.textTop(o++), right.textTop(m++))
            o = change.originalEnd
            m = change.modifiedEnd
        }

        val inline = viewOf(modifiedText, height = 6000)
        inline.publish(DiffRows.inline(diff.changes, left.state, inline.state.document, { null }, removed, 4), height = 6000)
        val deletedLines = diff.changes.sumOf { it.originalEnd - it.originalStart }
        assertEquals(inline.state.document.lineCount + deletedLines, inline.lineMap.totalRows)
    }

    @Test
    fun `inline, the characters a change removed are marked inside the deleted row`() {
        val original = InlineFixture.stateOf("keep\nprintln(start)\nkeep")
        val modified = InlineFixture.stateOf("keep\nprintln(begin)\nkeep").document
        // "start" is offsets 13 until 18 of the original.
        val change = LineChange(1, 2, 1, 2, listOf(com.aardarch.aardink.core.diff.CharChange(13, 18, 13, 18)))
        val marked = Color(0x4DFF0000)
        val row = DiffRows.inline(listOf(change), original, modified, { null }, removed, 4, removedText = marked).getValue(1).single()
        assertEquals(listOf(8 to 13), row.spans.filter { it.style.background == marked }.map { it.start to it.end })
    }
}
