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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * Where the editor is scrolled to, in pixels of content, on both axes. [vertical] and
 * [horizontal] plug into `Modifier.scrollable`, which gives wheel, touch drag, fling and Android
 * overscroll; the desktop and web scrollbars read the same numbers.
 *
 * The view keeps what is on screen in place when rows above it change (a fold opens, a wrapped
 * line is measured, an edit above adds lines) by moving [scrollY] with them; see [EditorView].
 */
@Stable
internal class EditorScrollController {

    var scrollY by mutableFloatStateOf(0f)
        private set

    var scrollX by mutableFloatStateOf(0f)
        private set

    var viewportWidth by mutableIntStateOf(0)
        internal set

    var viewportHeight by mutableIntStateOf(0)
        internal set

    /** Height of everything that can be scrolled to: every row, plus the padding around them. */
    var contentHeight by mutableFloatStateOf(0f)
        internal set

    /** Width of the widest line seen so far (plus padding); it only grows. 0 with soft wrap on. */
    var contentWidth by mutableFloatStateOf(0f)
        internal set

    val maxScrollY: Float get() = (contentHeight - viewportHeight).coerceAtLeast(0f)

    val maxScrollX: Float get() = (contentWidth - viewportWidth).coerceAtLeast(0f)

    val vertical = ScrollableState { delta ->
        val target = (scrollY + delta).coerceIn(0f, maxScrollY)
        val consumed = target - scrollY
        scrollY = target
        consumed
    }

    val horizontal = ScrollableState { delta ->
        val target = (scrollX + delta).coerceIn(0f, maxScrollX)
        val consumed = target - scrollX
        scrollX = target
        consumed
    }

    fun scrollToY(y: Float) {
        scrollY = y.coerceIn(0f, maxScrollY)
    }

    fun scrollToX(x: Float) {
        scrollX = x.coerceIn(0f, maxScrollX)
    }

    /** Sets [scrollY] without clamping: the view does it while the content height is being worked out. */
    internal fun placeY(y: Float) {
        scrollY = y.coerceAtLeast(0f)
    }

    /** Sets [scrollX], already clamped by the view. */
    internal fun placeX(x: Float) {
        scrollX = x.coerceAtLeast(0f)
    }

    suspend fun animateScrollToY(y: Float) {
        vertical.animateScrollBy(y.coerceIn(0f, maxScrollY) - scrollY, spring(stiffness = Spring.StiffnessMediumLow))
    }

    /**
     * Scrolls the least distance that brings [rect] (content coordinates) into view, keeping
     * [marginX] and [marginY] pixels between it and the edges where there is room.
     */
    fun reveal(rect: Rect, marginX: Float, marginY: Float) {
        val height = viewportHeight.toFloat()
        val width = viewportWidth.toFloat()
        if (height > 0f) {
            val my = marginY.coerceAtMost(((height - rect.height) / 2f).coerceAtLeast(0f))
            when {
                rect.top - my < scrollY -> scrollToY(rect.top - my)
                rect.bottom + my > scrollY + height -> scrollToY(rect.bottom + my - height)
            }
        }
        if (width > 0f) {
            val mx = marginX.coerceAtMost(((width - rect.width) / 2f).coerceAtLeast(0f))
            when {
                rect.left - mx < scrollX -> scrollToX(rect.left - mx)
                rect.right + mx > scrollX + width -> scrollToX(rect.right + mx - width)
            }
        }
    }
}
