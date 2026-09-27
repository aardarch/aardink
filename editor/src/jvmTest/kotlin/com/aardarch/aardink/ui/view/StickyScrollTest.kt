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
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sticky scroll: which lines stay pinned at the top, where, and what a click on one does. */
class StickyScrollTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)

    private val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
    )

    // Line tops are 8 + 20 × line.
    private val code = """
        |class A {
        |    fun b() {
        |        x
        |        y
        |        z
        |    }
        |    fun c() {
        |        w
        |    }
        |}
        |val after = 1
    """.trimMargin() + (1..12).joinToString("") {
        "\nval more$it = $it"
    }

    private val ranges = listOf(FoldRange(0, 9), FoldRange(1, 5), FoldRange(6, 8))

    private fun viewOf(text: String = code, foldState: FoldState? = null): EditorView {
        val state = CodeEditorState(initialText = "", tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
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
                    charWidth = 8f,
                    paddingTop = 8f,
                    paddingBottom = 0f,
                    paddingStart = 8f,
                    paddingEnd = 8f,
                ),
                softWrap = false,
            )
            this.foldState = foldState
            attach()
            prepare(400, 100)
        }
    }

    private fun EditorView.scrolledTo(y: Float): EditorView = apply {
        scroll.scrollToY(y)
        prepare(400, 100)
    }

    @Test
    fun `nothing is pinned at the top of the document`() {
        assertEquals(emptyList(), viewOf().stickyLines(ranges))
    }

    @Test
    fun `the ranges the top of the view is inside are pinned, outermost first`() {
        // Line 2 at the top: inside class A and fun b.
        val sticky = viewOf().scrolledTo(48f).stickyLines(ranges)
        assertEquals(listOf(StickyLine(0, 0f), StickyLine(1, 20f)), sticky)
    }

    @Test
    fun `the end of a range pushes its line up, then the next range takes its place`() {
        // Line 5, the end of fun b, is 28 px down: its sticky line is pushed up to 8.
        assertEquals(listOf(StickyLine(0, 0f), StickyLine(1, 8f)), viewOf().scrolledTo(100f).stickyLines(ranges))
        // Past it, fun c is pinned in its place.
        assertEquals(listOf(StickyLine(0, 0f), StickyLine(6, 20f)), viewOf().scrolledTo(110f).stickyLines(ranges))
    }

    @Test
    fun `past every range nothing is pinned`() {
        assertEquals(emptyList(), viewOf().scrolledTo(210f).stickyLines(ranges))
    }

    @Test
    fun `at most as many lines as asked for`() {
        assertEquals(listOf(StickyLine(0, 0f)), viewOf().scrolledTo(48f).stickyLines(ranges, max = 1))
    }

    @Test
    fun `a closed fold is not pinned, nor a range inside it`() {
        val inner = FoldRange(2, 4)
        val all = listOf(FoldRange(0, 9), FoldRange(1, 5), inner, FoldRange(6, 8))
        val foldState = FoldState().apply {
            updateFoldableRanges(all)
            toggle(1)
        }
        // fun b is closed, so line 6 is the third row: at the top with the view scrolled by two rows.
        assertEquals(listOf(StickyLine(0, 0f), StickyLine(6, 20f)), viewOf(foldState = foldState).scrolledTo(48f).stickyLines(all))
    }

    @Test
    fun `a press on a pinned line scrolls it to where it was drawn and puts the caret at its text`() {
        val view = viewOf().scrolledTo(48f)
        val pointer = EditorPointerHandler(view)
        pointer.stickyLineAt = { view.stickyLineAt(ranges, it) }
        pointer.press(Offset(30f, 25f), clicks = 1, shift = false, alt = false)
        // fun b's line, drawn at 20, is now really there: its top 28 − 8 = 20 below the view's top.
        assertEquals(8f, view.scroll.scrollY)
        assertEquals(TextRange(view.state.document.lineStart(1) + 4), view.state.selection)
    }
}
