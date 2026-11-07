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
@file:OptIn(ExperimentalComposeUiApi::class)

package com.aardarch.aardink.ui.view

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.platform.EditorTextInputRequest
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ghost text in a real line layout, on desktop and in the browser: one row of it among the characters,
 * more of it as rows of the line, and none of it anywhere but in that layout.
 */
class GhostTextViewTest {

    private val width = 600
    private val height = 400

    private fun EditorView.caretAt(offset: Int) {
        state.selection = TextRange(offset)
        state.inlineCompletions.provided = true
    }

    private fun EditorView.offer(vararg completions: InlineCompletion) {
        val tracker = state.inlineCompletions
        tracker.provided = true
        tracker.askAgain(now = true)
        tracker.answer(assertNotNull(tracker.ask), state.document.version, completions.toList())
        prepare(width, height)
    }

    private fun EditorView.laidOut(line: Int): String = layoutFor(line).result.layoutInput.text.text

    private fun EditorView.typed(text: String) {
        val caret = state.selection.end
        state.applyInput(caret, caret, text)
        prepare(width, height)
    }

    private val EditorView.rows: Int get() = totalRows

    @Test
    fun `one row of it is in the line after the caret, and not in the document`() {
        val view = viewOf("val x = \nnext")
        view.caretAt(8)
        view.offer(InlineCompletion("compute()"))
        assertEquals("val x = compute()", view.laidOut(0))
        assertEquals("val x = \nnext", view.state.document.text)
        assertEquals(1, view.shown(0).layout.result.lineCount, "no row added")
        assertEquals(
            view.layoutFor(0).result.getHorizontalPosition(8, true),
            view.caretRect(8).left - InlineFixture.PADDING,
            "the caret stays in front of it",
        )
    }

    @Test
    fun `what follows the caret stays on the line, after what it brings`() {
        val view = viewOf("foo()")
        view.caretAt(4)
        view.offer(InlineCompletion("bar)"))
        assertEquals("foo(bar)", view.laidOut(0))
    }

    @Test
    fun `several lines of it are rows of the line, with what follows the caret after the last`() {
        val view = viewOf("foo()\nnext")
        view.caretAt(4)
        val before = view.rows
        val nextTop = view.shown(1).top
        view.offer(InlineCompletion("a,\n    b)"))
        assertEquals("foo(a,\n    b)", view.laidOut(0))
        assertEquals(before + 1, view.rows)
        assertEquals(nextTop + LINE_HEIGHT, view.shown(1).top, "the next line moves down a row")
        assertEquals("foo()\nnext", view.state.document.text)
    }

    @Test
    fun `dismissing it takes its rows away`() {
        val view = viewOf("foo()\nnext")
        view.caretAt(4)
        val before = view.rows
        view.offer(InlineCompletion("a\nb\nc)"))
        assertEquals(before + 2, view.rows)
        view.state.inlineCompletions.clear()
        view.prepare(width, height)
        assertEquals(before, view.rows)
        assertEquals("foo()", view.laidOut(0))
    }

    @Test
    fun `moving the caret away takes its rows away, on a line the edit did not touch`() {
        val view = viewOf("zero\nfoo()\nnext")
        view.caretAt(9)
        val before = view.rows
        view.offer(InlineCompletion("a\nb\nc)"))
        view.state.applyTextEdits(listOf(com.aardarch.aardink.core.TextEdit(0 until 0, "above\n")))
        view.prepare(width, height)
        assertNull(view.state.inlineCompletions.shown)
        assertEquals(before + 1, view.rows, "the new line, and none of what ghost text added")
        assertEquals("foo()", view.laidOut(2))
    }

    @Test
    fun `typing through it keeps its rows until they are typed`() {
        val view = viewOf("a\nnext")
        view.caretAt(1)
        val before = view.rows
        view.offer(InlineCompletion("b\nc"))
        assertEquals(before + 1, view.rows)
        view.typed("b")
        assertEquals("ab\nc", view.laidOut(0))
        view.typed("\n")
        assertEquals("c", view.laidOut(1))
        assertEquals("ab\n\nnext", view.state.document.text, "the row is a line of the document, its text still to come")
        assertEquals(before + 1, view.rows)
        view.typed("c")
        assertEquals("c", view.laidOut(1))
        assertNull(view.state.inlineCompletions.shown)
    }

    @Test
    fun `accepting several lines leaves the rows as real lines`() {
        val view = viewOf("foo()\nnext")
        view.caretAt(4)
        val before = view.rows
        view.offer(InlineCompletion("a,\n    b)"))
        assertTrue(view.state.execute(EditorCommand.AcceptInlineCompletion))
        view.prepare(width, height)
        assertEquals("foo(a,\n    b)\nnext", view.state.document.text)
        assertEquals(before + 1, view.rows)
        assertEquals("foo(a,", view.laidOut(0))
        assertEquals("    b)", view.laidOut(1))
    }

    @Test
    fun `under soft wrap it wraps with the line`() {
        val view = viewOf("start", softWrap = true, width = 200)
        view.caretAt(5)
        view.prepare(200, height)
        val before = view.rows
        val long = " and a good deal more than would fit on one row of this narrow view"
        val tracker = view.state.inlineCompletions
        tracker.provided = true
        tracker.askAgain(now = true)
        tracker.answer(assertNotNull(tracker.ask), view.state.document.version, listOf(InlineCompletion(long)))
        view.prepare(200, height)
        assertTrue(view.rows > before, "it took rows of its own")
        tracker.clear()
        view.prepare(200, height)
        assertEquals(before, view.rows)
    }

    @Test
    fun `it comes before a hint at its column, straight after the caret`() {
        val view = viewOf("call(1)")
        view.state.publishInlayHints(listOf(InlayHint(5, "n: ")))
        view.caretAt(5)
        view.offer(InlineCompletion("2 + "))
        assertEquals("call(2 + n: 1)", view.laidOut(0))
    }

    @Test
    fun `a tab in it is shown to its stop, and goes in as a tab`() {
        val view = viewOf("x")
        view.caretAt(1)
        view.offer(InlineCompletion("\ty\n\tz"))
        assertEquals("x   y\n    z", view.laidOut(0))
        view.state.execute(EditorCommand.AcceptInlineCompletion)
        assertEquals("x\ty\n\tz", view.state.document.text)
    }

    @Test
    fun `a carriage return or control character in it never reaches the layout`() {
        val view = viewOf("x")
        view.caretAt(1)
        view.offer(InlineCompletion("a\u0007b"))
        assertEquals("xa b", view.laidOut(0))
    }

    @Test
    fun `a line far past what is drawn shows no ghost it could not draw`() {
        val text = "x".repeat(EditorView.MAX_RENDERED_LINE_CHARS + 10)
        val view = viewOf(text)
        view.caretAt(text.length)
        view.offer(InlineCompletion("y"))
        assertNull(view.state.inlineCompletions.shown)
    }

    @Test
    fun `up and down pass over its rows`() {
        val view = viewOf("foo\nnext")
        view.caretAt(3)
        view.offer(InlineCompletion("\n  one\n  two"))
        val down = view.verticalMove(3, 1, view.caretX(3))
        assertEquals(1, view.state.document.offsetToLineCol(down).first, "the next line, not a row of ghost text")
    }

    @Test
    fun `a click on it leaves the caret on the document`() {
        val view = viewOf("foo")
        view.caretAt(3)
        view.offer(InlineCompletion("barbaz"))
        val hit = view.offsetAt(Offset(view.caretRect(3).left + 30f, view.caretRect(3).center.y))
        assertEquals(3, hit.offset)
    }

    @Test
    fun `selecting all and copying holds none of it`() {
        val view = viewOf("foo")
        view.caretAt(3)
        view.offer(InlineCompletion("bar"))
        view.state.selectAll()
        assertEquals("foo", view.state.copySelections().text)
        assertNull(view.state.inlineCompletions.shown, "a selection takes it away")
        view.prepare(width, height)
        assertEquals("foo", view.laidOut(0))
    }

    // ── An input method, through the real adapter ────────────────────────────

    private fun requestFor(view: EditorView): Pair<EditorTextInputRequest, EditorImeAdapter> {
        val geometry = object : EditorImeGeometry {
            override fun caretRectInRoot(): Rect? = view.caretRect(view.state.selection.end)

            override fun boundsInRoot(): Rect? = Rect(0f, 0f, 600f, 400f)

            override fun textOriginInRoot(): Offset? = Offset.Zero

            override fun characterBoundsInRoot(offset: Int): Rect? = null
        }
        val adapter = EditorImeAdapter(view.state, windowed = true)
        return EditorTextInputRequest(adapter, geometry) to adapter
    }

    @Test
    fun `nothing shows or is asked during a composition, and it is asked after`() {
        val view = viewOf("val s = ")
        view.caretAt(8)
        view.offer(InlineCompletion("nihao"))
        val tracker = view.state.inlineCompletions
        val (request, _) = requestFor(view)

        request.editText {
            commitText("", 1)
            setComposingText("ni", 1)
        }
        view.prepare(width, height)
        assertNull(tracker.shown, "even though what is composed matches what it said")
        assertNull(tracker.ask)
        assertEquals("val s = ni", view.laidOut(0))
        assertFalse(view.state.canExecute(EditorCommand.AcceptInlineCompletion))

        request.editText { setComposingText("nih", 1) }
        assertNull(tracker.ask, "nothing asked while it goes on")

        request.editText { commitText("你好", 1) }
        view.prepare(width, height)
        assertEquals("val s = 你好", view.state.document.text)
        assertEquals(10, tracker.ask?.caret, "asked about what it left")
        assertEquals(false, tracker.ask?.now)
        assertNull(tracker.shown)
    }

    @Test
    fun `text committed with no composition keeps what it matches`() {
        val view = viewOf("val s = ")
        view.caretAt(8)
        view.offer(InlineCompletion("nihao"))
        val (request, _) = requestFor(view)
        request.editText { commitText("n", 1) }
        view.prepare(width, height)
        assertEquals("val s = nihao", view.laidOut(0))
        assertEquals("val s = n", view.state.document.text)
    }

    @Test
    fun `the input method's buffer never holds it`() {
        val view = viewOf("val s = ")
        view.caretAt(8)
        view.offer(InlineCompletion("nihao"))
        val (request, adapter) = requestFor(view)
        assertEquals("val s = ", request.state.text.toString())
        assertEquals("val s = ", adapter.value().text)
    }

    private fun stateOf(view: EditorView): CodeEditorState = view.state

    @Test
    fun `a view that goes away is told no more`() {
        val view = viewOf("x")
        view.caretAt(1)
        view.detach()
        assertTrue(stateOf(view).ghostListeners.isEmpty())
    }
}
