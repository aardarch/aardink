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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal actual fun EditorScrollbars(vertical: ScrollState, horizontal: ScrollState?, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(vertical),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        if (horizontal != null) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontal),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
