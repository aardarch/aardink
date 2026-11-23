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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.drawText
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.bounds
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.paint
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The backgrounds of injected text, such as an inlay hint's, which the view paints itself rather
 * than leave to the text engine: Compose 1.13 on Android paints a span's background over less than
 * its row on a line laid out with no width limit (#54). Here, on Skia, what the view paints is
 * compared with what the text engine paints for the same text with the background as a span: the
 * two must be the same, so that nothing changes on the desktop and the web.
 */
class InjectedBackgroundTest {

    private val chip = Color(0xFF00FF77)
    private val text = "zero\nfor x in names\ntwo"

    /** Line 1's hint, at its end, after `names`. */
    private val hint = LineInjections.of(Injection(14, ": List<String>", SpanStyle(color = Color(0xFF111111), background = chip)))

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

    private fun EditorView.content(width: Int, height: Int): Rect? = paint(width, height) {
        drawEditorContent(frame, EditorDecorations(emptyList(), emptyList(), -1, emptyList(), null), colors)
    }.bounds(chip)

    /**
     * What the text engine paints for line [line] of this view with [chip] as the background of
     * layout characters [start, end): the line's own layout input, measured again with the span, and
     * drawn as the view draws the line's text, [top] in a [width] x [height] canvas: row by row at
     * the view's pitch ([eachRow]), or, as a sticky line is, in one go when not [byRow].
     */
    private fun EditorView.engine(line: Int, start: Int, end: Int, width: Int, height: Int, top: Float, byRow: Boolean = true): Rect? {
        val layout = shown(line).layout
        val input = layout.result.layoutInput
        val spanned = AnnotatedString.Builder(input.text).apply { addStyle(SpanStyle(background = chip), start, end) }.toAnnotatedString()
        val reference = InlineFixture.measurer.measure(
            text = spanned,
            style = input.style,
            overflow = input.overflow,
            softWrap = input.softWrap,
            maxLines = input.maxLines,
            placeholders = input.placeholders,
            constraints = input.constraints,
            skipCache = true,
        )
        return paint(width, height) {
            if (byRow) {
                eachRow(layout, LINE_HEIGHT, top) { rowTop -> drawText(reference, topLeft = Offset(frame.textLeft, rowTop)) }
            } else {
                drawText(reference, topLeft = Offset(frame.textLeft, top))
            }
        }.bounds(chip)
    }

    private fun EditorView.lineTop(line: Int): Float = shown(line).top - frame.scrollY

    @Test
    fun `a hint's background is the view's to paint, not the text engine's`() {
        val layout = viewOf(text, injections = lines(1 to hint)).shown(1).layout
        val spans = layout.result.layoutInput.text.spanStyles
        assertTrue(spans.isNotEmpty(), "the hint keeps its colour")
        assertTrue(spans.none { it.item.background.isSpecified }, "no span has a background")
    }

    @Test
    fun `a hint's background is where the text engine painted it, wrapped or not`() {
        for (softWrap in listOf(false, true)) {
            val view = viewOf(text, softWrap = softWrap, injections = lines(1 to hint), width = 300, height = 200)
            val expected = assertNotNull(view.engine(1, 14, 28, 300, 200, view.lineTop(1)), "softWrap = $softWrap")
            assertEquals(expected, view.content(300, 200), "softWrap = $softWrap")
        }
    }

    @Test
    fun `a background over part of injected text is where the text engine painted it, as a diff's removed characters have it`() {
        val spanned = Injection(14, ": List<String>", spans = listOf(InjectedSpan(2, 6, SpanStyle(background = chip))))
        val view = viewOf(text, injections = lines(1 to LineInjections.of(spanned)))
        val expected = assertNotNull(view.engine(1, 16, 20, 300, 200, view.lineTop(1)))
        assertEquals(expected, view.content(300, 200))
    }

    @Test
    fun `a hint that wraps has its background on each of its rows`() {
        val view = viewOf(text, softWrap = true, injections = lines(1 to hint), width = 160, height = 200)
        assertTrue(view.shown(1).layout.lineCount > 1, "the line wraps")
        val expected = assertNotNull(view.engine(1, 14, 28, 160, 200, view.lineTop(1)))
        assertTrue(expected.height > LINE_HEIGHT, "the text engine painted it on two rows")
        assertEquals(expected, view.content(160, 200))
    }

    @Test
    fun `a pinned line keeps its hint's background`() {
        val view = viewOf(text, injections = lines(1 to hint))
        val pinned = paint(300, 20) { drawStickyLines(view, listOf(StickyLine(1, 0f)), Color.White, Color.White) }.bounds(chip)
        val expected = assertNotNull(view.engine(1, 14, 28, 300, 20, 0f, byRow = false))
        assertEquals(expected, pinned)
    }
}
