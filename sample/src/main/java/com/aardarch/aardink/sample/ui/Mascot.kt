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
package com.aardarch.aardink.sample.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.sample.R

/**
 * The Aardink mascot, drawn on a transparent background in the current theme's colors.
 *
 * The artwork is line art whose interior fills are painted *over* the outline, so it is split
 * across three drawables that must be stacked in this order: ink outline, paper fills, ink
 * detail. Each layer is tinted separately — a single [ColorFilter] over one drawable would
 * flatten the mascot into a silhouette.
 *
 * [paperColor] defaults to the container colour of the card the mascot currently sits on, which
 * makes the interior fills read as transparent; pass your own when placing it on another surface.
 */
@Composable
fun Mascot(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    inkColor: Color = MaterialTheme.colorScheme.onSurface,
    paperColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Box(modifier = modifier.size(size)) {
        MascotLayer(R.drawable.ic_aardink_mascot_ink_base, inkColor, "Aardink mascot")
        MascotLayer(R.drawable.ic_aardink_mascot_paper, paperColor, null)
        MascotLayer(R.drawable.ic_aardink_mascot_ink_detail, inkColor, null)
    }
}

/** One tinted layer of the mascot, filling the whole [Mascot] box. */
@Composable
private fun BoxScope.MascotLayer(@DrawableRes id: Int, color: Color, contentDescription: String?) {
    Image(
        painter = painterResource(id),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(color),
        modifier = Modifier.matchParentSize(),
    )
}
