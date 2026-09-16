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

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws scrollbars for the editor's scroll containers, where the platform expects them.
 *
 * Android shows none: it never had them, and adding them would be a visible regression for
 * existing consumers. Desktop and web draw a vertical one, plus a horizontal one when
 * [horizontal] is non-null (soft wrap off).
 *
 * Lives under `platform/` because that is the only package `expect`/`actual` may live in,
 * even for a composable.
 */
@Composable
internal expect fun EditorScrollbars(vertical: ScrollState, horizontal: ScrollState?, modifier: Modifier)
