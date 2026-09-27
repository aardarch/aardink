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

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Constraints

/**
 * The text area of the editor's own renderer: scrolls with wheel, touch and fling, selects with
 * the pointer, and draws [EditorView.frame] with [decorations] around the text. Carets are drawn
 * on a layer of their own, so their blinking redraws nothing else.
 *
 * [inputModifier] is where focus, keys and text input attach; [overlay] holds what floats over the
 * text (selection handles, the context menu).
 */
@Composable
internal fun EditorViewport(
    view: EditorView,
    pointer: EditorPointerHandler,
    decorations: () -> EditorDecorations,
    carets: () -> List<TextRange>,
    colors: EditorColors,
    softWrap: Boolean,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val overscroll = rememberOverscrollEffect()
    Box(
        modifier = modifier
            .clipToBounds()
            .then(inputModifier)
            .scrollable(view.scroll.vertical, Orientation.Vertical, overscrollEffect = overscroll, reverseDirection = true)
            .scrollable(view.scroll.horizontal, Orientation.Horizontal, enabled = !softWrap, reverseDirection = true)
            .pointerInput(pointer) { editorPointerInput(pointer) }
            .overscroll(overscroll)
            .drawBehind { drawEditorContent(view.frame, decorations(), colors) },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawBehind { drawCarets(view.frame, carets(), colors.caret) },
        )
        overlay()
    }
}

/**
 * Lays out the gutter, the text area and the scrollbars side by side, and runs
 * [EditorView.prepare] for the text area's size in between. Doing it in this layout pass, before
 * anything draws, is what keeps the gutter's line numbers and the text in step: both draw from the
 * frame it produces.
 */
@Composable
internal fun EditorBody(
    view: EditorView,
    gutter: @Composable () -> Unit,
    viewport: @Composable () -> Unit,
    scrollbars: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(gutter, viewport, scrollbars), modifier = modifier) { measurables, constraints ->
        val (gutterMeasurables, viewportMeasurables, scrollbarMeasurables) = measurables
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val boundedHeight = constraints.hasBoundedHeight
        // The gutter sizes itself (its width follows the line count); its intrinsic width says how wide.
        val gutterWidth = (gutterMeasurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0).coerceAtMost(width)
        val viewportWidth = (width - gutterWidth).coerceAtLeast(0)
        val height = if (boundedHeight) {
            constraints.maxHeight
        } else {
            // Unbounded (inside a host's own scrolling column): as tall as the text.
            view.prepare(viewportWidth, 0)
            view.scroll.contentHeight.toInt().coerceAtLeast(constraints.minHeight)
        }
        view.prepare(viewportWidth, height)
        val fixed = Constraints.fixed(viewportWidth, height)
        val viewports = viewportMeasurables.map { it.measure(fixed) }
        val bars = scrollbarMeasurables.map { it.measure(fixed) }
        val gutters = gutterMeasurables.map { it.measure(Constraints.fixed(gutterWidth, height)) }
        layout(width, height) {
            gutters.forEach { it.place(0, 0) }
            viewports.forEach { it.place(gutterWidth, 0) }
            bars.forEach { it.place(gutterWidth, 0) }
        }
    }
}
