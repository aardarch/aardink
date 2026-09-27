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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [EditorView]'s geometry with a real text measurer: hit-testing, carets, movement, scrolling. */
class EditorViewTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)

    private val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
    )

    private val charWidth = measurer.measure("0".repeat(64), textStyle).getLineRight(0) / 64f

    private fun viewOf(text: String, softWrap: Boolean = false, foldState: FoldState? = null): EditorView {
        val state = CodeEditorState(initialText = "", tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
            fieldMirror = false
            loadText(text)
        }
        return EditorView(state).apply {
            style = ViewStyle(
                measurer = measurer,
                textStyle = textStyle,
                tokenStyles = emptyMap(),
                placeholderStyle = SpanStyle(color = Color.Gray),
                metrics = EditorMetrics(
                    lineHeight = 20f,
                    charWidth = charWidth,
                    paddingTop = 8f,
                    paddingBottom = 0f,
                    paddingStart = 8f,
                    paddingEnd = 8f,
                ),
                softWrap = softWrap,
            )
            this.foldState = foldState
            attach()
        }
    }

    private fun lines(count: Int) = (0 until count).joinToString("\n") { "line number $it" }

    @Test
    fun `a point in the text maps to the character under it`() {
        val view = viewOf(lines(20))
        view.prepare(400, 200)
        // Row 2, between characters 3 and 4.
        val hit = view.offsetAt(Offset(8f + 3.4f * charWidth, 8f + 2 * 20f + 10f))
        assertEquals(2, hit.line)
        assertEquals(view.state.document.lineStart(2) + 3, hit.offset)
    }

    @Test
    fun `the caret sits at its column and row`() {
        val view = viewOf(lines(20))
        view.prepare(400, 200)
        val rect = view.caretRect(view.state.document.lineStart(3) + 5)
        assertTrue(abs(rect.left - (8f + 5 * charWidth)) < 1f, "caret x was ${rect.left}")
        assertEquals(8f + 3 * 20f, rect.top, 0.5f)
    }

    @Test
    fun `vertical movement steps over a closed fold`() {
        val folds = FoldState().apply {
            updateFoldableRanges(listOf(FoldRange(1, 5)))
            toggle(1)
        }
        val view = viewOf(lines(10), foldState = folds)
        view.prepare(400, 400)
        val document = view.state.document
        val below = view.verticalMove(document.lineStart(1) + 2, rows = 1)
        assertEquals(6, document.offsetToLineCol(below).first)
        val above = view.verticalMove(below, rows = -1)
        assertEquals(1, document.offsetToLineCol(above).first)
    }

    @Test
    fun `with soft wrap, vertical movement goes row by row inside a line`() {
        val long = (1..40).joinToString(" ") { "w$it" }
        val view = viewOf("$long\nend", softWrap = true)
        view.prepare(200, 400)
        assertTrue(view.lineMap.rowsOf(0) > 2, "the long line wraps")
        val next = view.verticalMove(0, rows = 1)
        assertEquals(0, view.state.document.offsetToLineCol(next).first, "still on the long line, one row down")
        assertTrue(next > 0)
    }

    @Test
    fun `lines inserted above the view do not move what is on screen`() {
        val view = viewOf(lines(500))
        view.prepare(400, 200)
        view.scroll.scrollToY(8f + 100 * 20f)
        view.prepare(400, 200)
        assertEquals(100, view.frame.lines.first().line)
        view.state.applyEdit(0, 0, "a\nb\nc\n", TextRange(0))
        view.prepare(400, 200)
        assertEquals(103, view.frame.lines.first().line)
        assertEquals("line number 100", view.state.document.lineText(view.frame.lines.first().line))
    }

    @Test
    fun `a reveal request scrolls the offset into view`() {
        val view = viewOf(lines(1000))
        view.prepare(400, 200)
        view.requestReveal(view.state.document.lineStart(700), center = true)
        view.prepare(400, 200)
        assertTrue(view.frame.lines.any { it.line == 700 }, "line 700 is on screen: ${view.frame.lines.map { it.line }}")
        val top = view.frame.lines.first().line
        assertTrue(top in 690..699, "centred rather than at an edge, top line was $top")
    }

    @Test
    fun `a very long line is laid out only up to the render limit`() {
        val view = viewOf("x".repeat(EditorView.MAX_RENDERED_LINE_CHARS * 2))
        view.prepare(400, 200)
        val line = view.frame.lines.single()
        assertEquals(EditorView.MAX_RENDERED_LINE_CHARS, line.shownLength)
        assertEquals(EditorView.MAX_RENDERED_LINE_CHARS, line.layout.layoutInput.text.length)
    }
}
