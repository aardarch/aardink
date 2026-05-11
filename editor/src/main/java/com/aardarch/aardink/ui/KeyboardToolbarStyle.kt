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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual styling for [KeyboardToolbarRow]. All buttons (icon and character) share the
 * same shape and size so the toolbar reads as a single, consistent surface.
 *
 * Use [KeyboardToolbarDefaults.style] to derive a style from the active [MaterialTheme],
 * and [copy] to override individual fields.
 */
@Immutable
data class KeyboardToolbarStyle(
    val background: Color,
    val iconColor: Color,
    val accentColor: Color,
    val charColor: Color,
    val disabledAlpha: Float,
    val buttonShape: Shape,
    val buttonSize: Dp,
    val iconSize: Dp,
    val itemSpacing: Dp,
    val sectionSpacing: Dp,
    val contentPadding: PaddingValues,
    val charFontFamily: FontFamily,
    val charFontSize: TextUnit,
)

/** Defaults for [KeyboardToolbarStyle]. */
object KeyboardToolbarDefaults {

    @Composable
    @ReadOnlyComposable
    fun style(
        background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
        iconColor: Color = MaterialTheme.colorScheme.onSurface,
        accentColor: Color = MaterialTheme.colorScheme.primary,
        charColor: Color = MaterialTheme.colorScheme.onSurface,
        disabledAlpha: Float = 0.38f,
        buttonShape: Shape = RoundedCornerShape(8.dp),
        buttonSize: Dp = 40.dp,
        iconSize: Dp = 20.dp,
        itemSpacing: Dp = 4.dp,
        sectionSpacing: Dp = 12.dp,
        contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        charFontFamily: FontFamily = FontFamily.Monospace,
        charFontSize: TextUnit = 16.sp,
    ): KeyboardToolbarStyle = KeyboardToolbarStyle(
        background = background,
        iconColor = iconColor,
        accentColor = accentColor,
        charColor = charColor,
        disabledAlpha = disabledAlpha,
        buttonShape = buttonShape,
        buttonSize = buttonSize,
        iconSize = iconSize,
        itemSpacing = itemSpacing,
        sectionSpacing = sectionSpacing,
        contentPadding = contentPadding,
        charFontFamily = charFontFamily,
        charFontSize = charFontSize,
    )
}
