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
package com.aardarch.aardink.platform

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aardarch.aardink.ui.view.EditorScrollController
import kotlin.math.max

@Composable
internal actual fun EditorViewScrollbars(scroll: EditorScrollController, horizontal: Boolean, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        VerticalScrollbar(
            adapter = remember(scroll) { VerticalAdapter(scroll) },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        if (horizontal) {
            HorizontalScrollbar(
                adapter = remember(scroll) { HorizontalAdapter(scroll) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private class VerticalAdapter(private val scroll: EditorScrollController) : ScrollbarAdapter {
    override val scrollOffset: Double get() = scroll.scrollY.toDouble()
    override val contentSize: Double get() = max(scroll.contentHeight, scroll.viewportHeight.toFloat()).toDouble()
    override val viewportSize: Double get() = scroll.viewportHeight.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        scroll.scrollToY(scrollOffset.toFloat())
    }
}

private class HorizontalAdapter(private val scroll: EditorScrollController) : ScrollbarAdapter {
    override val scrollOffset: Double get() = scroll.scrollX.toDouble()
    override val contentSize: Double get() = max(scroll.contentWidth, scroll.viewportWidth.toFloat()).toDouble()
    override val viewportSize: Double get() = scroll.viewportWidth.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        scroll.scrollToX(scrollOffset.toFloat())
    }
}
