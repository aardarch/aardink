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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Shared by the editor's gutter (EditorGutterView): the lanes, their widths and what they draw.

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFoldTriangle(
    centerX: Float,
    centerY: Float,
    expanded: Boolean,
    color: Color,
) {
    val s = 4.dp.toPx()
    val path = Path().apply {
        if (expanded) {
            moveTo(centerX - s, centerY - s / 2f)
            lineTo(centerX + s, centerY - s / 2f)
            lineTo(centerX, centerY + s)
        } else {
            moveTo(centerX - s / 2f, centerY - s)
            lineTo(centerX + s, centerY)
            lineTo(centerX - s / 2f, centerY + s)
        }
        close()
    }
    drawPath(path, color = color.copy(alpha = 0.7f))
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotationDot(
    kind: GutterAnnotationKind,
    centerX: Float,
    centerY: Float,
) {
    drawCircle(color = kind.color, radius = 4.dp.toPx(), center = Offset(centerX, centerY))
}

// ── Width calculation ─────────────────────────────────────────────────────────

internal val DIFF_LANE_WIDTH = 4.dp
internal val FOLD_LANE_WIDTH = 16.dp
internal val ANNOTATION_LANE_WIDTH = 12.dp

/** Breathing room between the rightmost lane and the start of the line-number text. */
private val LANE_TO_NUMBER_GAP = 2.dp

/**
 * Width of the gutter column, sized to hold [lineCount]'s digits plus whatever lanes are shown.
 *
 * The digit width is measured with [textMeasurer] against the actual [textStyle] rather than
 * estimated. It used to be `EditorDefaults.fontSize.value * 0.7f`: a fixed advance-ratio guess
 * that also treated an `sp` scalar as if it were `dp`, so it ignored the user's font scale. That
 * was survivable only while the font was hardcoded to `FontFamily.Monospace` at a fixed size;
 * now that a host can provide [EditorTypography], a guess would clip the line numbers of any
 * font whose digits are wider than 0.7 em.
 *
 * Digits are measured as a run of [EditorDefaults.GUTTER_MIN_DIGITS]-or-more "0"s rather than one
 * "0" scaled up, so letter spacing between digits is included.
 */
@Composable
internal fun rememberGutterWidth(
    lineCount: Int,
    hasDiffLane: Boolean,
    hasFoldLane: Boolean,
    hasAnnotationLane: Boolean,
    showLineNumbers: Boolean,
    density: Density,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
): Dp = remember(lineCount, hasDiffLane, hasFoldLane, hasAnnotationLane, showLineNumbers, density, textStyle) {
    val digits = lineCount.toString().length.coerceAtLeast(EditorDefaults.GUTTER_MIN_DIGITS)
    with(density) {
        val numbersWidth = if (showLineNumbers) {
            textMeasurer.measure("0".repeat(digits), textStyle).size.width.toDp().value
        } else {
            0f
        }
        val lanesExtra = (if (hasDiffLane) DIFF_LANE_WIDTH.value else 0f) +
            (if (hasFoldLane) FOLD_LANE_WIDTH.value else 0f) +
            (if (hasAnnotationLane) ANNOTATION_LANE_WIDTH.value else 0f)
        val hasAnyLane = hasDiffLane || hasFoldLane || hasAnnotationLane
        val laneToNumberGap = if (showLineNumbers && hasAnyLane) LANE_TO_NUMBER_GAP.value else 0f
        val totalDp = numbersWidth + EditorDefaults.gutterPaddingHorizontal.value * 2 + lanesExtra + laneToNumberGap
        totalDp.dp
    }
}

// ── Annotation kinds ──────────────────────────────────────────────────────────

/**
 * Severity classification for gutter annotation dots.
 */
enum class GutterAnnotationKind(val color: Color) {
    Error(Color(0xFFFF6B6B)),
    Warning(Color(0xFFFFD93D)),
    Info(Color(0xFF6BCB77)),
}
