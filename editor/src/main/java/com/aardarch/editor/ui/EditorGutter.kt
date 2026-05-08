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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aardarch.editor.core.FoldRange
import com.aardarch.editor.core.LineDiffKind

/**
 * Canvas-based line-number gutter with fold triangles, annotation dots, and diff indicators.
 *
 * Lane layout (left → right):
 *   1. Diff lane (4 dp) — colored bar for modified/added lines; omitted when [diffAnnotations] is empty.
 *   2. Fold lane (16 dp) — collapse/expand triangle; omitted when [foldableLines] is empty.
 *   3. Annotation lane (12 dp) — severity dot; omitted when [annotations] is empty.
 *   4. Line-number text — right-aligned.
 *
 * @param annotations Per-line severity dot. Tap triggers [onAnnotationTap].
 * @param diffAnnotations Per-line diff kind for the left colored bar.
 * @param foldableLines 0-based line indices that have a foldable region.
 * @param foldedLines Subset of [foldableLines] currently collapsed.
 * @param onToggleFold Called with the line index when a fold triangle is tapped.
 * @param onAnnotationTap Called with the line index when an annotation dot is tapped.
 */
@Composable
fun EditorGutter(
    lineCount: Int,
    scrollState: ScrollState,
    lineHeightPx: Float,
    topPaddingPx: Float,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    annotations: Map<Int, GutterAnnotationKind> = emptyMap(),
    foldableLines: Set<Int> = emptySet(),
    foldedRanges: List<FoldRange> = emptyList(),
    onToggleFold: (Int) -> Unit = {},
    diffAnnotations: Map<Int, LineDiffKind> = emptyMap(),
    onAnnotationTap: (Int) -> Unit = {},
    showLineNumbers: Boolean = true,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val hasDiffLane = diffAnnotations.isNotEmpty()
    val hasFoldLane = foldableLines.isNotEmpty()
    val hasAnnotationLane = annotations.isNotEmpty()

    val diffLaneWidthPx = with(density) { if (hasDiffLane) DIFF_LANE_WIDTH.toPx() else 0f }
    val foldLaneWidthPx = with(density) { if (hasFoldLane) FOLD_LANE_WIDTH.toPx() else 0f }
    val annotLaneWidthPx = with(density) { if (hasAnnotationLane) ANNOTATION_LANE_WIDTH.toPx() else 0f }

    val gutterWidth = rememberGutterWidth(lineCount, hasDiffLane, hasFoldLane, hasAnnotationLane, showLineNumbers, density)

    val textStyle = remember(foreground) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = EditorDefaults.fontSize,
            lineHeight = EditorDefaults.lineHeight,
            color = foreground,
        )
    }

    val foldedStartLines = remember(foldedRanges) { foldedRanges.map { it.startLine }.toHashSet() }
    val hiddenLines = remember(foldedRanges) {
        foldedRanges.flatMapTo(HashSet()) { (it.startLine + 1)..it.endLine }
    }

    Box(
        modifier = modifier
            .width(gutterWidth)
            .fillMaxHeight()
            .background(background)
            .verticalScroll(scrollState)
            .pointerInput(foldableLines, annotations, lineHeightPx, topPaddingPx, hiddenLines) {
                detectTapGestures { offset ->
                    val visualRow = ((offset.y + scrollState.value - topPaddingPx) / lineHeightPx)
                        .toInt().coerceAtLeast(0)
                    // Map visual row → original line index, skipping hidden lines
                    var row = 0
                    var lineIndex = 0
                    while (lineIndex < lineCount) {
                        if (lineIndex !in hiddenLines) {
                            if (row == visualRow) break
                            row++
                        }
                        lineIndex++
                    }
                    lineIndex = lineIndex.coerceIn(0, lineCount - 1)
                    val foldStart = diffLaneWidthPx
                    val foldEnd = foldStart + foldLaneWidthPx
                    val annotStart = foldEnd
                    val annotEnd = annotStart + annotLaneWidthPx
                    when {
                        offset.x in foldStart..foldEnd && lineIndex in foldableLines ->
                            onToggleFold(lineIndex)

                        offset.x in annotStart..annotEnd && annotations.containsKey(lineIndex) ->
                            onAnnotationTap(lineIndex)
                    }
                }
            }
            .drawWithContent {
                drawContent()
                drawGutterLines(
                    textMeasurer = textMeasurer,
                    lineCount = lineCount,
                    lineHeightPx = lineHeightPx,
                    topPaddingPx = topPaddingPx,
                    scrollOffsetPx = scrollState.value.toFloat(),
                    textStyle = textStyle,
                    annotations = annotations,
                    foldableLines = foldableLines,
                    foldedStartLines = foldedStartLines,
                    hiddenLines = hiddenLines,
                    foldForeground = foreground,
                    diffAnnotations = diffAnnotations,
                    diffLaneWidthPx = diffLaneWidthPx,
                    foldLaneWidthPx = foldLaneWidthPx,
                    annotLaneWidthPx = annotLaneWidthPx,
                    gutterWidth = size.width,
                    paddingHorizontalPx = with(density) { EditorDefaults.gutterPaddingHorizontal.toPx() },
                    showLineNumbers = showLineNumbers,
                )
            },
    )
}

// ── Drawing ───────────────────────────────────────────────────────────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGutterLines(
    textMeasurer: TextMeasurer,
    lineCount: Int,
    lineHeightPx: Float,
    topPaddingPx: Float,
    scrollOffsetPx: Float,
    textStyle: TextStyle,
    annotations: Map<Int, GutterAnnotationKind>,
    foldableLines: Set<Int>,
    foldedStartLines: Set<Int>,
    hiddenLines: Set<Int>,
    foldForeground: Color,
    diffAnnotations: Map<Int, LineDiffKind>,
    diffLaneWidthPx: Float,
    foldLaneWidthPx: Float,
    annotLaneWidthPx: Float,
    gutterWidth: Float,
    paddingHorizontalPx: Float,
    showLineNumbers: Boolean,
) {
    val maxY = size.height
    var visualRow = 0
    for (lineIndex in 0 until lineCount) {
        if (lineIndex in hiddenLines) continue // inside a folded region — not visible

        val lineTop = topPaddingPx + visualRow * lineHeightPx - scrollOffsetPx
        if (lineTop + lineHeightPx < 0f) {
            visualRow++
            continue
        }
        if (lineTop > maxY) break

        // 1. Diff bar (leftmost, full line height minus 1 px padding each side)
        val diffKind = diffAnnotations[lineIndex]
        if (diffKind != null && diffLaneWidthPx > 0f) {
            drawRect(
                color = when (diffKind) {
                    LineDiffKind.Added -> Color(0xFF4CAF50)
                    LineDiffKind.Modified -> Color(0xFF2196F3)
                },
                topLeft = Offset(0f, lineTop + 1f),
                size = Size(diffLaneWidthPx, lineHeightPx - 2f),
            )
        }

        // 2. Line number (right-aligned)
        if (showLineNumbers) {
            val label = (lineIndex + 1).toString()
            val measured = textMeasurer.measure(label, textStyle)
            val numX = gutterWidth - paddingHorizontalPx - measured.size.width
            val numY = lineTop + (lineHeightPx - measured.size.height) / 2f
            drawText(measured, topLeft = Offset(numX, numY))
        }

        // 3. Fold triangle
        if (lineIndex in foldableLines && foldLaneWidthPx > 0f) {
            drawFoldTriangle(
                centerX = diffLaneWidthPx + foldLaneWidthPx / 2f,
                centerY = lineTop + lineHeightPx / 2f,
                expanded = lineIndex !in foldedStartLines,
                color = foldForeground,
            )
        }

        // 4. Annotation dot
        val annotation = annotations[lineIndex]
        if (annotation != null && annotLaneWidthPx > 0f) {
            drawAnnotationDot(
                kind = annotation,
                centerX = diffLaneWidthPx + foldLaneWidthPx + annotLaneWidthPx / 2f,
                centerY = lineTop + lineHeightPx / 2f,
            )
        }

        visualRow++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFoldTriangle(
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotationDot(
    kind: GutterAnnotationKind,
    centerX: Float,
    centerY: Float,
) {
    drawCircle(color = kind.color, radius = 4.dp.toPx(), center = Offset(centerX, centerY))
}

// ── Width calculation ─────────────────────────────────────────────────────────

private val DIFF_LANE_WIDTH = 4.dp
private val FOLD_LANE_WIDTH = 16.dp
private val ANNOTATION_LANE_WIDTH = 12.dp

/** Breathing room between the rightmost lane and the start of the line-number text. */
private val LANE_TO_NUMBER_GAP = 2.dp

@Composable
private fun rememberGutterWidth(
    lineCount: Int,
    hasDiffLane: Boolean,
    hasFoldLane: Boolean,
    hasAnnotationLane: Boolean,
    showLineNumbers: Boolean,
    density: Density,
): Dp = remember(lineCount, hasDiffLane, hasFoldLane, hasAnnotationLane, showLineNumbers) {
    val digits = lineCount.toString().length.coerceAtLeast(EditorDefaults.GUTTER_MIN_DIGITS)
    with(density) {
        val charWidth = EditorDefaults.fontSize.value * 0.7f
        val lanesExtra = (if (hasDiffLane) DIFF_LANE_WIDTH.value else 0f) +
            (if (hasFoldLane) FOLD_LANE_WIDTH.value else 0f) +
            (if (hasAnnotationLane) ANNOTATION_LANE_WIDTH.value else 0f)
        val hasAnyLane = hasDiffLane || hasFoldLane || hasAnnotationLane
        val laneToNumberGap = if (showLineNumbers && hasAnyLane) LANE_TO_NUMBER_GAP.value else 0f
        val numbersWidth = if (showLineNumbers) digits * charWidth else 0f
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
