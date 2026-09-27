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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A wavy underline from [xStart] to [xEnd] at [y]: a diagnostic's squiggle. */
internal fun DrawScope.drawSquiggleLine(xStart: Float, xEnd: Float, y: Float, color: Color) {
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
