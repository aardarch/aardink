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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * An [EditorView] with a real text measurer, as the inline-layout tests use it on the JVM and in
 * the browser. The font is whatever the platform gives for monospace, and in a headless browser
 * that is not monospace: tests read widths from layouts, never from a character count.
 */
internal object InlineFixture {
    const val LINE_HEIGHT = 20f
    const val PADDING = 8f

    val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr, cacheSize = 0)

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
    )

    val charWidth = measurer.measure("0".repeat(64), textStyle).getLineRight(0) / 64f

    fun stateOf(text: String, tokenizer: IncrementalTokenizer = PlainTextTokenizer) = CodeEditorState(
        initialText = "",
        tokenizer = tokenizer,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        loadText(text)
    }

    fun viewOf(
        text: String,
        softWrap: Boolean = false,
        injections: ((Int) -> LineInjections?)? = null,
        width: Int = 600,
        height: Int = 400,
        foldState: FoldState? = null,
        textStyle: TextStyle = this.textStyle,
        tokenizer: IncrementalTokenizer = PlainTextTokenizer,
        tokenStyle: (TokenType) -> SpanStyle? = { null },
    ): EditorView = EditorView(stateOf(text, tokenizer)).apply {
        style = ViewStyle(
            measurer = measurer,
            textStyle = textStyle,
            tokenStyle = tokenStyle,
            placeholderStyle = SpanStyle(color = Color.Gray),
            metrics = EditorMetrics(
                lineHeight = LINE_HEIGHT,
                charWidth = charWidth,
                paddingTop = PADDING,
                paddingBottom = 0f,
                paddingStart = PADDING,
                paddingEnd = PADDING,
            ),
            softWrap = softWrap,
            tabSize = 4,
            columnWidthEm = charWidth / 14f,
            joinIndentation = true,
        )
        this.foldState = foldState
        this.injections = injections
        attach()
        prepare(width, height)
    }

    /** Injections from a map of line to what it shows. */
    fun lines(vararg lines: Pair<Int, LineInjections>): (Int) -> LineInjections? {
        val byLine = lines.toMap()
        return { byLine[it] }
    }

    /** The line of [view] as [EditorView.frame] has it. */
    fun EditorView.shown(line: Int): VisibleLine = checkNotNull(frame.find(line)) { "line $line is not on screen" }

    /** Pixels per unit of [paint]: a quarter of a pixel is what a 0.5 px check needs to see. */
    const val PAINT_SCALE = 4

    /** What [block] draws on a [width] x [height] canvas, [PAINT_SCALE] times as large. */
    fun paint(width: Int, height: Int, block: DrawScope.() -> Unit): PixelMap {
        val bitmap = ImageBitmap(width * PAINT_SCALE, height * PAINT_SCALE)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(
                width.toFloat() * PAINT_SCALE,
                height.toFloat() * PAINT_SCALE,
            ),
        ) {
            scale(PAINT_SCALE.toFloat(), Offset.Zero) { block() }
        }
        return bitmap.toPixelMap()
    }

    /** The box of the pixels exactly [color] in what [paint] gave, in units of the canvas; null when there are none. */
    fun PixelMap.bounds(color: Color): Rect? {
        val argb = color.toArgb()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = bufferOffset + y * stride
            for (x in 0 until width) {
                if (buffer[row + x] != argb) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                bottom = y
            }
        }
        if (right < 0) return null
        val unit = PAINT_SCALE.toFloat()
        return Rect(left / unit, top / unit, (right + 1) / unit, (bottom + 1) / unit)
    }
}
