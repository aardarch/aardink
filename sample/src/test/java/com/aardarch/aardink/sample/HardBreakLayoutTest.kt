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
// LineLayout is internal to :editor; this is the one place that must run it on Android, and :editor
// has no Android test target.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.aardarch.aardink.sample

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.ui.view.Injection
import com.aardarch.aardink.ui.view.LineInjections
import com.aardarch.aardink.ui.view.LineLayout
import com.aardarch.aardink.ui.view.OffsetMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Android half of the inline-layout spike's step S6 (`HardBreakTest` in `:editor`'s skikoTest
 * is desktop and web): a hard break in a line's paragraph is a row, with soft wrap on and off.
 * Robolectric's native graphics run the real `StaticLayout` and Minikin, measured through the
 * same `TextMeasurer` call `EditorView.measureLine` makes, and read through the real `LineLayout`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HardBreakLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var measurer: TextMeasurer

    private val lineHeightPx get() = with(rule.density) { 20.sp.toPx() }

    private val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
    )

    @Before
    fun setUp() {
        rule.setContent { measurer = rememberTextMeasurer(cacheSize = 0) }
        rule.waitForIdle()
    }

    private fun measure(
        text: AnnotatedString,
        wrap: Boolean,
        placeholders: List<AnnotatedString.Range<Placeholder>> = emptyList(),
    ): TextLayoutResult = measurer.measure(
        text = text,
        style = textStyle,
        softWrap = wrap,
        constraints = if (wrap) Constraints(maxWidth = 2_000) else Constraints(),
        placeholders = placeholders,
        skipCache = true,
    )

    private fun check(text: String, wrap: Boolean, rows: List<Int>) {
        val result = measure(AnnotatedString(text), wrap)
        val context = "'${text.replace('\n', '|')}', softWrap = $wrap"
        assertEquals("rows of $context", rows.last() + 1, result.lineCount)
        assertEquals("getLineForOffset of $context", rows, (0..text.length).map { result.getLineForOffset(it) })
        for (row in 1 until result.lineCount) {
            assertTrue("row $row is below the one above in $context", result.getLineTop(row) > result.getLineTop(row - 1))
        }
    }

    @Test
    fun `the text engine makes a row of every hard break, with soft wrap on and off`() {
        for (wrap in listOf(false, true)) {
            check("lens\ncode", wrap, listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1))
            check("lens\n", wrap, listOf(0, 0, 0, 0, 0, 1))
            check("\ncode", wrap, listOf(0, 1, 1, 1, 1, 1))
            check("a\n\nb", wrap, listOf(0, 0, 1, 2, 2))
            check("a\nb\n", wrap, listOf(0, 0, 1, 1, 2))
        }
    }

    @Test
    fun `a placeholder and a hard break live in one paragraph`() {
        for (wrap in listOf(false, true)) {
            val placeholder = Placeholder(4.em, 1.em, PlaceholderVerticalAlign.TextCenter)
            val result = measure(AnnotatedString("lens\n\tx"), wrap, listOf(AnnotatedString.Range(placeholder, 5, 6)))
            assertEquals(2, result.lineCount)
            assertEquals(1, result.getLineForOffset(5))
            assertTrue("the tab has its width, softWrap = $wrap", result.getCursorRect(6).left > result.getCursorRect(5).left + 40f)
        }
    }

    /** [text] laid out with [items] spliced in, as `measureLine` does it, read through [LineLayout]. */
    private fun layoutOf(text: String, wrap: Boolean, vararg items: Injection): LineLayout {
        val injections = LineInjections.of(*items)
        val spliced = StringBuilder()
        var appended = 0
        for (item in injections.items) {
            spliced.append(text, appended, item.column).append(item.text)
            appended = item.column
        }
        spliced.append(text, appended, text.length)
        return LineLayout(measure(AnnotatedString(spliced.toString()), wrap), OffsetMap.of(injections, text.length))
    }

    /** That rows sit at the view's pitch, so `rowShift` has nothing to do, and that it says so. */
    private fun checkPitch(layout: LineLayout, context: String) {
        for (row in 1 until layout.lineCount) {
            assertEquals("pitch at row $row: $context", lineHeightPx, layout.getLineTop(row) - layout.getLineTop(row - 1), 0.5f)
            assertEquals("rowShift at row $row: $context", 0f, layout.rowShift(row, lineHeightPx), 0.5f)
        }
        assertTrue("rows at pitch: $context", layout.rowsAtPitch(lineHeightPx))
        assertFalse("the view's pitch is what is compared: $context", layout.lineCount > 1 && layout.rowsAtPitch(lineHeightPx * 2))
    }

    @Test
    fun `a row above a line, through LineLayout`() {
        for (wrap in listOf(false, true)) {
            val layout: LineLayout = layoutOf("code", wrap, Injection.rowAbove("lens"))
            assertEquals(2, layout.lineCount)
            assertEquals(1, layout.textRow)
            assertEquals(1, layout.lastTextRow)
            assertTrue(layout.isInjectedRow(0))
            assertFalse(layout.isInjectedRow(1))
            for (column in 0..4) assertEquals("column $column", 1, layout.rowFor(column))
            checkPitch(layout, "softWrap = $wrap")
        }
    }

    @Test
    fun `a row above an empty line, through LineLayout`() {
        for (wrap in listOf(false, true)) {
            val layout: LineLayout = layoutOf("", wrap, Injection.rowAbove("lens"))
            assertEquals(2, layout.lineCount)
            assertEquals(1, layout.textRow)
            assertEquals(1, layout.rowFor(0))
            assertFalse(layout.isInjectedRow(1))
            val first = (layoutOf("code", wrap, Injection.rowAbove("lens")) as LineLayout).cursorRect(0)
            assertEquals("softWrap = $wrap", first.top, layout.cursorRect(0).top, 0.5f)
            assertEquals("softWrap = $wrap", first.height, layout.cursorRect(0).height, 0.5f)
        }
    }

    @Test
    fun `rows below a line, and a break inside one, through LineLayout`() {
        for (wrap in listOf(false, true)) {
            val below: LineLayout = layoutOf("code", wrap, Injection(4, "ghost\nrow\nrow"))
            assertEquals(3, below.lineCount)
            assertEquals(listOf(false, true, true), (0..2).map { below.isInjectedRow(it) })
            assertEquals(0, below.rowFor(4))
            checkPitch(below, "below, softWrap = $wrap")

            val inside: LineLayout = layoutOf("before after", wrap, Injection(7, "ghost\nrow\nend "))
            assertEquals(3, inside.lineCount)
            assertEquals(listOf(false, true, false), (0..2).map { inside.isInjectedRow(it) })
            assertEquals(0, inside.rowFor(7))
            assertEquals(2, inside.charRow(7))
            checkPitch(inside, "inside, softWrap = $wrap")
        }
    }

    @Test
    fun `a soft-wrapped line keeps its hard breaks, and wrapping adds rows between them`() {
        val long = "word ".repeat(200).trim()
        val result = measure(AnnotatedString("lens\n$long\nghost"), wrap = true)
        assertTrue("the long line wrapped: ${result.lineCount}", result.lineCount > 3)
        assertEquals(0, result.getLineForOffset(0))
        assertEquals(1, result.getLineForOffset(5))
        assertEquals(result.lineCount - 1, result.getLineForOffset(result.layoutInput.text.length))
    }

    @Test
    fun `unwrapped, a long line stays one row between its hard breaks`() {
        val long = "word ".repeat(200).trim()
        val result = measure(AnnotatedString("lens\n$long\nghost"), wrap = false)
        assertEquals(3, result.lineCount)
    }
}
