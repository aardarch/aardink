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
package com.aardarch.editor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.aardarch.editor.core.Diagnostic
import com.aardarch.editor.core.DiagnosticSeverity

/**
 * Draws wavy squiggle underlines for each [Diagnostic] whose range maps to a known character
 * position in [textLayoutResult]. The squiggles are drawn in the calling [DrawScope]'s local
 * coordinate space — add this to a `Modifier.drawBehind` on the same Box as `BasicTextField`.
 *
 * Pass the colours from the active [com.aardarch.editor.core.EditorTheme] so the underlines stay
 * coherent with the rest of the editor's palette.
 */
fun DrawScope.drawSquiggles(
    diagnostics: List<Diagnostic>,
    textLayoutResult: TextLayoutResult?,
    errorColor: Color,
    warningColor: Color,
    infoColor: Color,
) {
    if (textLayoutResult == null || diagnostics.isEmpty()) return
    val textLength = textLayoutResult.layoutInput.text.length
    if (textLength == 0) return

    for (diagnostic in diagnostics) {
        val rangeStart = diagnostic.range.first.coerceIn(0, textLength - 1)
        val rangeEnd = (diagnostic.range.last).coerceIn(rangeStart, textLength - 1)

        val color = when (diagnostic.severity) {
            DiagnosticSeverity.Error -> errorColor
            DiagnosticSeverity.Warning -> warningColor
            DiagnosticSeverity.Info -> infoColor
        }

        val lineIndex = textLayoutResult.getLineForOffset(rangeStart)
        val lineBottom = textLayoutResult.getLineBottom(lineIndex)
        val xStart = textLayoutResult.getBoundingBox(rangeStart).left
        val endBox = textLayoutResult.getBoundingBox(rangeEnd)
        val xEnd = if (endBox.right > xStart) endBox.right else xStart + 20.dp.toPx()

        drawSquiggleLine(xStart, xEnd, lineBottom + 1.dp.toPx(), color)
    }
}

private fun DrawScope.drawSquiggleLine(xStart: Float, xEnd: Float, y: Float, color: Color) {
    if (xEnd <= xStart) return
    val amplitude = 2.dp.toPx()
    val halfPeriod = 4.dp.toPx()
    val path = Path()
    var x = xStart
    var phase = true
    path.moveTo(x, y)
    while (x < xEnd) {
        val nextX = (x + halfPeriod).coerceAtMost(xEnd)
        val controlY = if (phase) y - amplitude else y + amplitude
        path.quadraticTo((x + nextX) / 2f, controlY, nextX, y)
        x = nextX
        phase = !phase
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
