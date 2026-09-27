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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.aardarch.aardink.core.LineDiffKind
import com.aardarch.aardink.ui.ANNOTATION_LANE_WIDTH
import com.aardarch.aardink.ui.DIFF_LANE_WIDTH
import com.aardarch.aardink.ui.EditorDefaults
import com.aardarch.aardink.ui.EditorTestTags
import com.aardarch.aardink.ui.FOLD_LANE_WIDTH
import com.aardarch.aardink.ui.GutterAnnotationKind
import com.aardarch.aardink.ui.LocalEditorTypography
import com.aardarch.aardink.ui.drawAnnotationDot
import com.aardarch.aardink.ui.drawFoldTriangle
import com.aardarch.aardink.ui.rememberGutterWidth

/** What the gutter shows per line, and what tapping it does. */
internal class GutterContent(
    val annotations: Map<Int, GutterAnnotationKind>,
    val foldableLines: Set<Int>,
    val diffAnnotations: Map<Int, LineDiffKind>,
    val showLineNumbers: Boolean,
    val onToggleFold: (Int) -> Unit,
    val onAnnotationTap: (Int) -> Unit,
)

/**
 * The line-number gutter of the editor's own renderer: the lanes of the text-field one (diff bar,
 * fold triangle, diagnostic dot, line number), drawn for the lines of [EditorView.frame] only. It
 * scrolls with the text because it reads the same frame, and a wheel or drag over it scrolls the
 * editor.
 */
@Composable
internal fun EditorGutterView(
    view: EditorView,
    lineCount: Int,
    background: Color,
    foreground: Color,
    content: GutterContent,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val typography = LocalEditorTypography.current
    val textStyle = remember(foreground, typography) {
        TextStyle(
            fontFamily = typography.fontFamily,
            fontSize = typography.fontSize,
            lineHeight = typography.lineHeight,
            color = foreground,
        )
    }
    val hasDiffLane = content.diffAnnotations.isNotEmpty()
    val hasFoldLane = content.foldableLines.isNotEmpty()
    val hasAnnotationLane = content.annotations.isNotEmpty()
    val width =
        rememberGutterWidth(lineCount, hasDiffLane, hasFoldLane, hasAnnotationLane, content.showLineNumbers, density, measurer, textStyle)
    val diffLane = with(density) { if (hasDiffLane) DIFF_LANE_WIDTH.toPx() else 0f }
    val foldLane = with(density) { if (hasFoldLane) FOLD_LANE_WIDTH.toPx() else 0f }
    val annotationLane = with(density) { if (hasAnnotationLane) ANNOTATION_LANE_WIDTH.toPx() else 0f }
    val padding = with(density) { EditorDefaults.gutterPaddingHorizontal.toPx() }
    // Line numbers are laid out once per label and style, not once per frame.
    val numbers = remember(textStyle, measurer) { HashMap<String, TextLayoutResult>() }
    val current = rememberUpdatedState(content)

    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(background)
            .clipToBounds()
            .testTag(EditorTestTags.GUTTER)
            .scrollable(view.scroll.vertical, Orientation.Vertical, reverseDirection = true)
            .pointerInput(view, diffLane, foldLane, annotationLane) {
                detectTapGestures { position ->
                    val frame = view.frame
                    val hit = frame.lines.lastOrNull { it.top - frame.scrollY <= position.y } ?: return@detectTapGestures
                    val tapped = current.value
                    when {
                        position.x in diffLane..diffLane + foldLane && hit.line in tapped.foldableLines -> tapped.onToggleFold(hit.line)

                        position.x in diffLane + foldLane..diffLane + foldLane + annotationLane && hit.line in tapped.annotations ->
                            tapped.onAnnotationTap(hit.line)
                    }
                }
            }
            .drawBehind {
                val frame = view.frame
                val lineHeight = frame.metrics.lineHeight
                val folds = view.foldLayout
                val shown = current.value
                for (line in frame.lines) {
                    val top = line.top - frame.scrollY
                    val rowsHeight = line.layout.lineCount * lineHeight
                    val diff = shown.diffAnnotations[line.line]
                    if (diff != null && diffLane > 0f) {
                        drawRect(
                            color = when (diff) {
                                LineDiffKind.Added -> Color(0xFF4CAF50)
                                LineDiffKind.Modified -> Color(0xFF2196F3)
                            },
                            topLeft = Offset(0f, top + 1f),
                            size = Size(diffLane, rowsHeight - 2f),
                        )
                    }
                    if (shown.showLineNumbers) {
                        val label = (line.line + 1).toString()
                        if (numbers.size > MAX_CACHED_NUMBERS) numbers.clear()
                        val number = numbers.getOrPut(label) { measurer.measure(label, textStyle) }
                        drawText(
                            number,
                            topLeft = Offset(
                                size.width - padding - number.size.width,
                                top + (lineHeight - number.size.height) / 2f,
                            ),
                        )
                    }
                    if (foldLane > 0f && line.line in shown.foldableLines) {
                        drawFoldTriangle(
                            centerX = diffLane + foldLane / 2f,
                            centerY = top + lineHeight / 2f,
                            expanded = folds.startingAt(line.line) == null,
                            color = foreground,
                        )
                    }
                    val annotation = shown.annotations[line.line]
                    if (annotation != null && annotationLane > 0f) {
                        drawAnnotationDot(annotation, centerX = diffLane + foldLane + annotationLane / 2f, centerY = top + lineHeight / 2f)
                    }
                }
            },
    )
}

/** Line-number layouts kept at once: a few screens of them. */
private const val MAX_CACHED_NUMBERS = 512
