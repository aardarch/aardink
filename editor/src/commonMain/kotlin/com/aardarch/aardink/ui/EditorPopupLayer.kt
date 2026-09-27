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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a popup of [popupSize] goes for an [anchor] rectangle (in the coordinates of the layout
 * hosting the popup, whose window bounds are [hostBounds]): below the anchor, or above it with
 * [preferAbove]; on the other side when it does not fit there; and moved sideways and clamped so
 * it stays inside the window of [windowSize]. [gap] pixels separate it from the anchor.
 */
internal fun placePopup(
    hostBounds: IntRect,
    anchor: Rect,
    windowSize: IntSize,
    popupSize: IntSize,
    preferAbove: Boolean,
    gap: Int,
): IntOffset {
    val top = hostBounds.top + anchor.top.roundToInt()
    val bottom = hostBounds.top + anchor.bottom.roundToInt()
    val below = bottom + gap
    val above = top - gap - popupSize.height
    val fitsBelow = below + popupSize.height <= windowSize.height
    val fitsAbove = above >= 0
    val y = when {
        preferAbove && fitsAbove -> above

        !preferAbove && fitsBelow -> below

        fitsAbove -> above

        fitsBelow -> below

        // Fits on neither side: the side with more room, clamped into the window.
        top > windowSize.height - bottom -> above.coerceAtLeast(0)

        else -> below.coerceAtMost(max(0, windowSize.height - popupSize.height))
    }
    val x = (hostBounds.left + anchor.left.roundToInt()).coerceIn(0, max(0, windowSize.width - popupSize.width))
    return IntOffset(x, y)
}

/** [placePopup] as a [PopupPositionProvider]: the popup's host is the layout it is composed in. */
internal class AnchoredPopupPositionProvider(private val anchor: Rect, private val preferAbove: Boolean, private val gap: Int) :
    PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = placePopup(anchorBounds, anchor, windowSize, popupContentSize, preferAbove, gap)
}

/**
 * A popup next to [anchor] (in the coordinates of the layout this is composed in, such as the
 * editor's text area): below it, or above with [preferAbove], flipping to the other side and
 * moving sideways as the window requires. Not [focusable] by default, so typing carries on in the
 * editor while it is up.
 */
@Composable
internal fun EditorAnchoredPopup(
    anchor: Rect,
    onDismiss: () -> Unit,
    preferAbove: Boolean = false,
    focusable: Boolean = false,
    content: @Composable () -> Unit,
) {
    val gap = with(LocalDensity.current) { POPUP_GAP.roundToPx() }
    val provider = remember(anchor, preferAbove, gap) { AnchoredPopupPositionProvider(anchor, preferAbove, gap) }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
        content = content,
    )
}

private val POPUP_GAP = 4.dp
