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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFFE3E2DB)
private val Brand = Color(0xFF447821)

/**
 * Interim mascot placeholder derived from `sample/aardink-placeholder.svg`:
 * a grey-on-green-bordered circle with the stacked monospaced wordmark "aard" / "ink".
 * Will be replaced once a proper mascot is designed.
 */
@Composable
fun MascotPlaceholder(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val strokeWidth = w * (3f / 512f)
            val radius = (minOf(w, h) - strokeWidth) / 2f
            val center = Offset(w / 2f, h / 2f)
            drawCircle(color = Background, radius = radius, center = center)
            drawCircle(
                color = Brand,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "aard",
                color = Brand,
                fontFamily = FontFamily.Monospace,
                fontSize = (size.value * 0.30f).sp,
                lineHeight = (size.value * 0.32f).sp,
            )
            Text(
                text = "ink",
                color = Brand,
                fontFamily = FontFamily.Monospace,
                fontSize = (size.value * 0.30f).sp,
                lineHeight = (size.value * 0.32f).sp,
            )
        }
    }
}
