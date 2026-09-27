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

/** Column (box) selections: the rows and columns they take, and the mouse and keys that make them. */
class ColumnSelectionTest {

    private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)

    private val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
    )

    private val charWidth = measurer.measure("0".repeat(64), textStyle).getLineRight(0) / 64f

    private fun viewOf(text: String, foldState: FoldState? = null): EditorView {
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
                    charWidth = charWidth,
                    paddingTop = 8f,
                    paddingBottom = 0f,
                    paddingStart = 8f,
                    paddingEnd = 8f,
                ),
                softWrap = false,
            )
            this.foldState = foldState
            attach()
            prepare(600, 400)
        }
    }

    /** The viewport point at [column] on [row]. */
    private fun at(row: Int, column: Int) = Offset(8f + column * charWidth, 8f + row * 20f + 10f)

    private fun EditorView.selectedTexts(): List<String> =
        state.selections.sortedBy { it.min }.map { state.document.text.substring(it.min, it.max) }

    @Test
    fun `a box takes the same columns on every row, the active row's first`() {
        val view = viewOf("abcdef\nabcdef\nabcdef")
        val set = view.columnSelection(ColumnBox(0, 1 * charWidth, 2, 4 * charWidth))
        assertEquals(listOf(TextRange(15, 18), TextRange(1, 4), TextRange(8, 11)), set.ranges)
    }

    @Test
    fun `a box drawn upwards and leftwards keeps its direction`() {
        val view = viewOf("abcdef\nabcdef\nabcdef")
        val set = view.columnSelection(ColumnBox(2, 4 * charWidth, 0, 1 * charWidth))
        assertEquals(TextRange(4, 1), set.primary, "the active row is the top one, its caret at the left")
        assertEquals(3, set.ranges.size)
    }

    @Test
    fun `a row whose text ends before the box is left out`() {
        val view = viewOf("abcdef\nab\nabcdef")
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, 3 * charWidth, 2, 5 * charWidth)))
        assertEquals(listOf("de", "de"), view.selectedTexts())
    }

    @Test
    fun `a row ending inside the box gets what it has`() {
        val view = viewOf("abcdef\nabcd\nabcdef")
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, 2 * charWidth, 2, 5 * charWidth)))
        assertEquals(listOf("cde", "cd", "cde"), view.selectedTexts())
    }

    @Test
    fun `past the end of every row, each row gets a caret at its end`() {
        val view = viewOf("a\nbb\nc")
        val set = view.columnSelection(ColumnBox(0, 6 * charWidth, 2, 8 * charWidth))
        assertEquals(listOf(TextRange(6), TextRange(1), TextRange(4)), set.ranges)
    }

    @Test
    fun `folded lines are not rows`() {
        val foldState = FoldState().apply {
            updateFoldableRanges(listOf(FoldRange(1, 2)))
            toggle(1)
        }
        val view = viewOf("abc\nabc\nabc\nabc", foldState)
        view.prepare(600, 400)
        view.state.replaceSelections(view.columnSelection(ColumnBox(0, 0f, 2, 1 * charWidth)))
        val lines = view.state.selections.map { view.state.document.offsetToLineCol(it.min).first }.sorted()
        assertEquals(listOf(0, 1, 3), lines)
    }

    @Test
    fun `shift alt click makes a box from the caret, and dragging grows it`() {
        val view = viewOf("abcdef\nabcdef\nabcdef\nabcdef")
        val pointer = EditorPointerHandler(view)
        view.state.selection = TextRange(1)
        pointer.press(at(row = 2, column = 3), clicks = 1, shift = true, alt = true)
        assertEquals(listOf("bc", "bc", "bc"), view.selectedTexts())
        pointer.drag(at(row = 3, column = 5))
        pointer.release()
        assertEquals(listOf("bcde", "bcde", "bcde", "bcde"), view.selectedTexts())
    }

    @Test
    fun `a middle drag makes a box from where it was pressed`() {
        val view = viewOf("abcdef\nabcdef\nabcdef")
        val pointer = EditorPointerHandler(view)
        pointer.columnPress(at(row = 1, column = 2))
        pointer.drag(at(row = 2, column = 4))
        pointer.release()
        assertEquals(listOf("cd", "cd"), view.selectedTexts())
    }

    @Test
    fun `alt drag adds a selection and keeps the carets there were`() {
        val view = viewOf("abcdef\nabcdef")
        val pointer = EditorPointerHandler(view)
        view.state.selection = TextRange(0)
        pointer.press(at(row = 1, column = 1), clicks = 1, shift = false, alt = true)
        pointer.drag(at(row = 1, column = 4))
        pointer.release()
        assertEquals(listOf(TextRange(0), TextRange(8, 11)), view.state.selections)
    }

    @Test
    fun `alt click on a caret removes it`() {
        val view = viewOf("abcdef\nabcdef")
        val pointer = EditorPointerHandler(view)
        view.state.setSelections(listOf(TextRange(0), TextRange(9)))
        pointer.press(at(row = 1, column = 2), clicks = 1, shift = false, alt = true)
        pointer.release()
        assertEquals(listOf(TextRange(0)), view.state.selections)
    }

    @Test
    fun `ctrl shift alt arrows grow a box from the caret, and carry on from the last one`() {
        val view = viewOf("abcdef\nabcdef\nabcdef")
        val controller = EditorController(view.state, view)
        view.state.selection = TextRange(1)
        controller.execute(KeyBinding(EditorCommand.ColumnSelectDown))
        controller.execute(KeyBinding(EditorCommand.ColumnSelectDown))
        controller.execute(KeyBinding(EditorCommand.ColumnSelectRight))
        controller.execute(KeyBinding(EditorCommand.ColumnSelectRight))
        assertEquals(listOf("bc", "bc", "bc"), view.selectedTexts())
        controller.execute(KeyBinding(EditorCommand.ColumnSelectUp))
        assertEquals(listOf("bc", "bc"), view.selectedTexts())
        // Anything else moving the selections starts the next box afresh, from the primary one.
        view.state.selection = TextRange(9)
        controller.execute(KeyBinding(EditorCommand.ColumnSelectDown))
        assertEquals(listOf(TextRange(16), TextRange(9)), view.state.selections)
    }

    @Test
    fun `the keys and the mouse carry on each other's box`() {
        val view = viewOf("abcdef\nabcdef\nabcdef")
        val controller = EditorController(view.state, view)
        controller.pointer.columnPress(at(row = 0, column = 1))
        controller.pointer.drag(at(row = 1, column = 3))
        controller.pointer.release()
        controller.execute(KeyBinding(EditorCommand.ColumnSelectDown))
        assertEquals(listOf("bc", "bc", "bc"), view.selectedTexts())
    }
}
