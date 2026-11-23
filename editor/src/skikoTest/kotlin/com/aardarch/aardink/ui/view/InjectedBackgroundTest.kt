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
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.drawText
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.PAINT_SCALE
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
 * compared with what the text engine paints for the same text with the backgrounds as spans: the
 * two must be the same, so that nothing changes on the desktop and the web.
 */
class InjectedBackgroundTest {

    private val chip = Color(0xFF00FF77)
    private val text = "zero\nfor x in names\ntwo"

    /** Line 1's hint, at its end, after `names`. */
    private val hint = LineInjections.of(Injection(14, ": List<String>", SpanStyle(color = Color(0xFF111111), background = chip)))

    /** A hint whose text mixes directions: `aאב` reads `aבא`, so its last character is not its rightmost. */
    private val mixed = ": aאב"

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

    private fun EditorView.canvas(width: Int, height: Int): PixelMap = paint(width, height) {
        drawEditorContent(frame, EditorDecorations(emptyList(), emptyList(), -1, emptyList(), null), colors)
    }

    private fun EditorView.content(width: Int, height: Int): Rect? = canvas(width, height).bounds(chip)

    /**
     * What the text engine paints for line [line] of this view with [backgrounds] as spans, each a
     * colour over layout characters [start, end) and added in order: the line's own layout input,
     * measured again with the spans, and drawn as the view draws the line's text, [top] in a
     * [width] x [height] canvas: row by row at the view's pitch ([eachRow]), or, as a sticky line
     * is, in one go when not [byRow].
     */
    private fun EditorView.reference(
        line: Int,
        width: Int,
        height: Int,
        top: Float,
        backgrounds: List<Triple<Int, Int, Color>>,
        byRow: Boolean = true,
    ): PixelMap {
        val layout = shown(line).layout
        val input = layout.result.layoutInput
        val spanned = AnnotatedString.Builder(input.text).apply {
            for ((start, end, color) in backgrounds) addStyle(SpanStyle(background = color), start, end)
        }.toAnnotatedString()
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
        }
    }

    /** The box [reference] paints [chip] in, as the background of layout characters [start, end). */
    private fun EditorView.engine(line: Int, start: Int, end: Int, width: Int, height: Int, top: Float, byRow: Boolean = true): Rect? =
        reference(line, width, height, top, listOf(Triple(start, end, chip)), byRow).bounds(chip)

    private fun EditorView.lineTop(line: Int): Float = shown(line).top - frame.scrollY

    /** The pixels of the row [top] in this canvas, row after row. */
    private fun PixelMap.row(top: Float): List<Int> {
        val from = (top * PAINT_SCALE).toInt()
        val to = ((top + LINE_HEIGHT) * PAINT_SCALE).toInt().coerceAtMost(height)
        return buildList { for (y in from until to) for (x in 0 until width) add(buffer[bufferOffset + y * stride + x]) }
    }

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
    fun `where backgrounds overlap, the one added last is the one painted, a transparent one too`() {
        val removed = Color(0x4DFF0000)
        val layered = Injection(
            14,
            ": List<String>",
            SpanStyle(background = chip),
            spans = listOf(
                InjectedSpan(2, 6, SpanStyle(background = removed)),
                InjectedSpan(8, 10, SpanStyle(background = Color.Transparent)),
            ),
        )
        val view = viewOf(text, injections = lines(1 to LineInjections.of(layered)))
        val top = view.lineTop(1)
        val expected = view.reference(
            1,
            300,
            200,
            top,
            listOf(Triple(14, 28, chip), Triple(16, 20, removed), Triple(22, 24, Color.Transparent)),
        )
        assertEquals(expected.row(top), view.canvas(300, 200).row(top))
    }

    @Test
    fun `a hint that mixes directions has its background over all of its characters`() {
        val view = viewOf(text, injections = lines(1 to LineInjections.of(Injection(14, mixed, SpanStyle(background = chip)))))
        val expected = assertNotNull(view.engine(1, 14, 14 + mixed.length, 300, 200, view.lineTop(1)))
        assertEquals(expected, view.content(300, 200))
    }

    @Test
    fun `a background over characters apart on the row, as right-to-left text puts them, is in each place`() {
        // `a` and `א`, with `ב` shown between them.
        val apart = Injection(14, mixed, spans = listOf(InjectedSpan(2, 4, SpanStyle(background = chip))))
        val view = viewOf(text, injections = lines(1 to LineInjections.of(apart)))
        val top = view.lineTop(1)
        val expected = view.reference(1, 300, 200, top, listOf(Triple(16, 18, chip)))
        assertEquals(expected.row(top), view.canvas(300, 200).row(top))
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
