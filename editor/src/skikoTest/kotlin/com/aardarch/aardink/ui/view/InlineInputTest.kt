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
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.platform.EditorTextInputRequest
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spike's step S8, on desktop and in the browser: the text an input method and the clipboard
 * see is the document's. Injected text is only ever in a layout. The composition is fed as
 * `EditorTextInputRequestTest` feeds it, the way the skiko input services do.
 */
class InlineInputTest {

    private val lens = Injection.rowAbove("3 references")

    private fun requestFor(view: EditorView, caret: Int): EditorTextInputRequest {
        view.state.selection = TextRange(caret)
        val geometry = object : EditorImeGeometry {
            override fun caretRectInRoot(): Rect? = view.caretRect(view.state.selection.end)

            override fun boundsInRoot(): Rect? = Rect(0f, 0f, 600f, 400f)

            override fun textOriginInRoot(): Offset? = Offset.Zero

            override fun characterBoundsInRoot(offset: Int): Rect? = null
        }
        return EditorTextInputRequest(EditorImeAdapter(view.state, windowed = true), geometry)
    }

    private fun checkComposition(injected: LineInjections, text: String, caret: Int) =
        checkComposition(viewOf(text, injections = lines(0 to injected)), text, caret)

    private fun checkComposition(view: EditorView, text: String, caret: Int) {
        val request = requestFor(view, caret)
        val before = text.substring(0, caret)
        val after = text.substring(caret)

        request.editText {
            commitText("", 1)
            setComposingText("ni", 1)
        }
        view.prepare(600, 400)
        assertEquals("${before}ni$after", view.state.document.text)
        assertEquals(TextRange(caret, caret + 2), request.state.composition)
        assertEquals(TextRange(caret + 2), request.state.selection)
        assertEquals("${before}ni$after", request.state.text.toString(), "the input method's buffer has no injected text")

        request.editText { setComposingText("nih", 1) }
        view.prepare(600, 400)
        assertEquals("${before}nih$after", view.state.document.text)

        request.editText { commitText("你好", 1) }
        view.prepare(600, 400)
        assertEquals("${before}你好$after", view.state.document.text, "the commit landed exactly once")
        assertNull(request.state.composition)
        assertEquals(TextRange(caret + 2), view.state.selection)

        view.state.undo()
        view.prepare(600, 400)
        assertEquals(text, view.state.document.text, "the whole composition is one undo step")
    }

    @Test
    fun `a composition lands once at ghost text`() {
        checkComposition(LineInjections.of(Injection(8, "ghost")), "val s = ", 8)
    }

    @Test
    fun `a composition lands once in front of a hint and after a lens`() {
        checkComposition(LineInjections.of(lens, Injection(5, ": Int", caretAfter = true)), "val s = 1", 5)
    }

    @Test
    fun `a composition lands once on a line with a break in its ghost text`() {
        checkComposition(LineInjections.of(Injection(8, "ghost\nrow")), "val s = rest", 8)
    }

    @Test
    fun `a composition lands once at a real hint, which stays with the text after it`() {
        val view = viewOf("val s = 1")
        view.state.publishInlayHints(listOf(InlayHint(5, ": Int"), InlayHint(8, "n: ")))
        view.prepare(600, 400)

        checkComposition(view, "val s = 1", 5)

        assertEquals(listOf(InlayHint(5, ": Int"), InlayHint(8, "n: ")), view.state.inlayHints, "back where they were after the undo")
    }

    @Test
    fun `a composition lands once at a decoration's text, in front of the before and behind the after`() {
        val view = viewOf("val s = 1")
        val decoration = Decoration(4..4, DecorationStyle(before = "<", after = ">"))
        view.state.setDecorations("test", listOf(decoration))
        view.prepare(600, 400)

        checkComposition(view, "val s = 1", 4)
        checkComposition(view, "val s = 1", 5)

        assertEquals(listOf(decoration), view.state.decorations("test"))
    }

    @Test
    fun `the caret rectangle an input method reads is on the text row`() {
        val view = viewOf("val s = 1", injections = lines(0 to LineInjections.of(lens)))
        val request = requestFor(view, 4)
        val plain = viewOf("val s = 1")
        plain.state.selection = TextRange(4)
        val rect = request.focusedRectInRoot()
        assertEquals(plain.caretRect(4).left, rect?.left ?: -1f, 0.5f)
        assertEquals(plain.caretRect(4).top + InlineFixture.LINE_HEIGHT, rect?.top ?: -1f, 0.5f)
    }

    @Test
    fun `typing at a hint puts the text in the document, not in the hint`() {
        val view = viewOf("val x = 1", injections = lines(0 to LineInjections.of(Injection(5, ": Int"))))
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(5)
        controller.type("y")
        assertEquals("val xy = 1", view.state.document.text)
        assertEquals(TextRange(6), view.state.selection)
    }

    @Test
    fun `a copy over injected text has the document's text only`() {
        val text = "val x = 1\nsecond\nthird"
        val view = viewOf(
            text,
            injections = lines(
                0 to LineInjections.of(lens, Injection(5, ": Int")),
                1 to LineInjections.of(lens, Injection(6, "ghost\nrow")),
            ),
        )
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(0, text.length)
        assertEquals(text, controller.copyText())
        view.state.selection = TextRange(3, 7)
        assertEquals(" x =", controller.copyText())
    }

    @Test
    fun `a cut over injected text removes the document's text only`() {
        val view = viewOf("val x = 1", injections = lines(0 to LineInjections.of(Injection(5, ": Int"))))
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(3, 7)
        assertEquals(" x =", controller.cutText())
        assertEquals("val 1", view.state.document.text)
    }

    @Test
    fun `a column selection over a lens row copies the text rows only`() {
        val view = viewOf("abcdef\nabcdef\nabcdef", injections = lines(1 to LineInjections.of(lens)))
        val x = view.shown(0).layout
        val controller = EditorController(view.state, view)
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, x.caretX(1), view.lineMap.totalRows - 1, x.caretX(3))))
        assertEquals("bc\nbc\nbc", controller.copyText())
    }

    @Test
    fun `a click on a hint and then typing lands in the document at its column`() {
        val view = viewOf("val x = 1", injections = lines(0 to LineInjections.of(Injection(5, ": Int"))))
        val controller = EditorController(view.state, view)
        val layout = view.shown(0).layout
        val pointer = EditorPointerHandler(view)
        // Into the hint: between the caret before it and the character after it.
        val onHint = (layout.caretX(5) + layout.charLeft(5)) / 2f
        assertTrue(layout.charLeft(5) - layout.caretX(5) > 10f, "the hint has width")
        pointer.press(Offset(PADDING + onHint, PADDING + 10f), clicks = 1, shift = false, alt = false)
        pointer.release()
        assertEquals(TextRange(5), view.state.selection)
        controller.type("z")
        assertEquals("val xz = 1", view.state.document.text)
        assertFalse(": Int" in view.state.document.text)
    }
}
