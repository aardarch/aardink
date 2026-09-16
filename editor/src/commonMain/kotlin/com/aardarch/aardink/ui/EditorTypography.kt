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

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The type the editor renders code in — the text area, the gutter, and every code-bearing
 * popup (completion, hover, signature help, find/replace fields, keyboard toolbar).
 *
 * Provide it through [LocalEditorTypography] to swap in a bundled font. `:editor` deliberately
 * bundles none: doing so would add a few hundred KB to every Android consumer and drag
 * `compose.components.resources` into the library's public dependency graph for no Android
 * benefit. Hosts that need a guaranteed font — a web build, where `FontFamily.Monospace`
 * resolves to whatever the browser picks — ship it themselves and provide it here.
 *
 * The defaults reproduce what the editor hardcoded previously, so providing nothing changes
 * nothing.
 */
@Immutable
data class EditorTypography(
    val fontFamily: FontFamily = FontFamily.Monospace,
    val fontSize: TextUnit = 14.sp,
    val lineHeight: TextUnit = 20.sp,
)

/**
 * The [EditorTypography] in effect for the editor beneath this point in the composition.
 *
 * `static` because a font change should re-lay-out the whole editor rather than
 * invalidate individually — it is swapped rarely, if ever.
 */
val LocalEditorTypography: ProvidableCompositionLocal<EditorTypography> =
    staticCompositionLocalOf { EditorTypography() }
