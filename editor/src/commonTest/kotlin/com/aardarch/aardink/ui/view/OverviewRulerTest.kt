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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.LineDiffKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverviewRulerTest {

    private val colors = RulerColors(
        error = Color.Red,
        warning = Color.Yellow,
        info = Color.Blue,
        findMatch = Color.Magenta,
        selection = Color.White,
        added = Color.Green,
        modified = Color.Cyan,
    )

    // Ten lines of "line N".
    private val document = CodeDocument((0 until 10).joinToString("\n") { "line $it" })

    private fun offsetOfLine(line: Int) = document.lineStart(line)

    @Test
    fun `each source has its lane and its line`() {
        val marks = overviewRulerMarks(
            document = document,
            diagnostics = listOf(
                Diagnostic(offsetOfLine(2)..offsetOfLine(2) + 3, "w", DiagnosticSeverity.Warning),
                Diagnostic(offsetOfLine(3)..offsetOfLine(3) + 3, "e", DiagnosticSeverity.Error),
                Diagnostic(offsetOfLine(4)..offsetOfLine(4) + 3, "h", DiagnosticSeverity.Hint),
            ),
            findMatches = listOf(offsetOfLine(5)..offsetOfLine(5) + 1),
            selections = listOf(TextRange(offsetOfLine(6))),
            diff = mapOf(7 to LineDiffKind.Added),
            decorations = listOf(
                Decoration(offsetOfLine(8)..offsetOfLine(9), DecorationStyle(overviewRulerColor = Color.Gray)),
                Decoration(offsetOfLine(1)..offsetOfLine(1), DecorationStyle(background = Color.Gray)),
            ),
            colors = colors,
        )
        val summary = marks.map { Triple(it.lane, it.firstLine to it.lastLine, it.color) }
        assertEquals(
            listOf(
                Triple(RulerLane.Left, 7 to 7, Color.Green),
                Triple(RulerLane.Left, 8 to 9, Color.Gray),
                Triple(RulerLane.Center, 5 to 5, Color.Magenta),
                Triple(RulerLane.Center, 6 to 6, Color.White),
                // Hints are not marked; errors come last, over warnings.
                Triple(RulerLane.Right, 2 to 2, Color.Yellow),
                Triple(RulerLane.Right, 3 to 3, Color.Red),
            ),
            summary,
        )
    }

    @Test
    fun `buckets map rows onto pixels, a later mark over an earlier one`() {
        val marks = listOf(
            RulerMark(0, 0, Color.Yellow, RulerLane.Right),
            RulerMark(0, 0, Color.Red, RulerLane.Right),
            RulerMark(5, 9, Color.Green, RulerLane.Left),
        )
        // One row per line, a 100-pixel ruler: 10 pixels a line.
        val buckets = RulerBuckets(marks, height = 100, totalRows = 10, minHeight = 2) { a, b -> a to b + 1 }
        val right = buckets.lanes[RulerLane.Right.ordinal]
        assertEquals(Color.Red, right[0])
        assertEquals(Color.Red, right[9])
        assertNull(right[10])
        val left = buckets.lanes[RulerLane.Left.ordinal]
        assertNull(left[49])
        assertEquals(Color.Green, left[50])
        assertEquals(Color.Green, left[99])
    }

    @Test
    fun `a mark on a document taller than the ruler keeps its minimum height`() {
        val buckets =
            RulerBuckets(listOf(RulerMark(500, 500, Color.Red, RulerLane.Right)), height = 100, totalRows = 1000, minHeight = 2) { a, b ->
                a to b + 1
            }
        val right = buckets.lanes[RulerLane.Right.ordinal]
        assertEquals(listOf(50, 51), right.indices.filter { right[it] != null })
    }
}
