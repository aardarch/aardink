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
// Compose Multiplatform 1.12 has no public way to tear down a ComposeViewport (W-1): its window
// is internal, and this file alone reaches it, as Compose itself reaches into Skiko. Compose 1.13
// disposes a viewport taken out of the page on its own, and then this file can go.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.aardarch.aardink.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.LocalComposeWindow

/**
 * What disposes the Compose window the calling composition is in: its window listeners, its
 * scene, its recomposer and its Skia layer. Only inside a `ComposeViewport`, which provides it.
 *
 * Without it, a viewport taken out of the page still answers the window's `resize`, and each
 * answer sets Skia up again on its canvas: once that canvas's WebGL context is lost (given back
 * on dispose, or dropped by the browser when too many are active), that throws where nothing
 * catches it.
 */
@Composable
internal fun composeWindowDisposal(): () -> Unit {
    val window = LocalComposeWindow.current ?: return {}
    return { window.dispose() }
}
