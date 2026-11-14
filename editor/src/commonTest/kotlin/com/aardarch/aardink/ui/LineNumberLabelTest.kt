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
package com.aardarch.aardink.ui

import com.aardarch.aardink.ui.view.GutterContent
import com.aardarch.aardink.ui.view.lineLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/** `EditorOptions.lineNumbers`: what each line of the gutter is numbered. */
class LineNumberLabelTest {

    private fun content(style: LineNumberStyle, caretLine: Int) = GutterContent(
        annotations = emptyMap(),
        foldableLines = emptySet(),
        reserveFoldLane = false,
        diffAnnotations = emptyMap(),
        reserveDiffLane = false,
        reserveAnnotationLane = false,
        showLineNumbers = true,
        onToggleFold = {},
        onAnnotationTap = {},
        lineNumbers = style,
        caretLine = { caretLine },
    )

    private fun labels(style: LineNumberStyle, caretLine: Int, lines: IntRange) = lines.map { lineLabel(it, content(style, caretLine)) }

    @Test
    fun `absolute numbers count from one`() {
        assertEquals(listOf("1", "2", "3"), labels(LineNumberStyle.Absolute, caretLine = 1, 0..2))
    }

    @Test
    fun `relative numbers count from the caret's line, which keeps its own`() {
        assertEquals(listOf("2", "1", "3", "1", "2"), labels(LineNumberStyle.Relative, caretLine = 2, 0..4))
    }

    @Test
    fun `interval numbers every tenth line and the caret's`() {
        val labels = labels(LineNumberStyle.Interval, caretLine = 4, 0..20)
        assertEquals(listOf("5", "10", "20"), labels.filter { it.isNotEmpty() })
    }
}
