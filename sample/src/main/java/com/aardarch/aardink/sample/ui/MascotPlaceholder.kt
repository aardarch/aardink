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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Placeholder for the Aardink mascot — a stylised aardvark silhouette inside a rounded
 * blob, plus an "Æ" monogram.
 *
 * TODO: replace with final mascot artwork once designed.
 */
@Composable
fun MascotPlaceholder(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val accent = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            // Background blob
            drawRect(color = container, size = this.size)

            // Aardvark silhouette: long snout + rounded head
            val w = this.size.width
            val h = this.size.height
            val body = Path().apply {
                moveTo(w * 0.18f, h * 0.62f)
                cubicTo(
                    w * 0.10f, h * 0.40f,
                    w * 0.30f, h * 0.18f,
                    w * 0.55f, h * 0.28f,
                )
                cubicTo(
                    w * 0.78f, h * 0.36f,
                    w * 0.92f, h * 0.55f,
                    w * 0.82f, h * 0.78f,
                )
                cubicTo(
                    w * 0.70f, h * 0.92f,
                    w * 0.30f, h * 0.92f,
                    w * 0.18f, h * 0.62f,
                )
                close()
            }
            drawPath(
                path = body,
                color = onContainer.copy(alpha = 0.18f),
            )
            // Snout line
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.20f, h * 0.55f)
                    cubicTo(
                        w * 0.05f, h * 0.55f,
                        w * 0.02f, h * 0.78f,
                        w * 0.18f, h * 0.82f,
                    )
                },
                color = onContainer.copy(alpha = 0.35f),
                style = Stroke(width = w * 0.06f),
            )
            // Eye
            drawCircle(
                color = accent,
                radius = w * 0.045f,
                center = Offset(w * 0.55f, h * 0.45f),
            )
            // Ear
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.62f, h * 0.30f)
                    lineTo(w * 0.68f, h * 0.10f)
                    lineTo(w * 0.78f, h * 0.32f)
                    close()
                },
                color = onContainer.copy(alpha = 0.30f),
            )
        }

        Text(
            text = "Æ",
            color = onContainer,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.32f).sp,
        )
    }
}

