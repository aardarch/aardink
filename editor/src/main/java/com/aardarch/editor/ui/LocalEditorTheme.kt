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

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.aardarch.editor.core.EditorTheme

/**
 * Composition local carrying the active [EditorTheme].
 *
 * Defaults to [EditorThemes.VsCodeDark]. Override for a specific subtree:
 * ```kotlin
 * CompositionLocalProvider(LocalEditorTheme provides EditorThemes.MidnightOcean) {
 *     CodeEditorLayout(state)
 * }
 * ```
 */
val LocalEditorTheme: ProvidableCompositionLocal<EditorTheme> =
    compositionLocalOf { EditorThemes.VsCodeDark }
