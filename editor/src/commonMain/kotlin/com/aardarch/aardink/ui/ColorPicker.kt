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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** A colour as hue (0 to 360), saturation, value and alpha (0 to 1 each), as the picker edits it. */
@Immutable
internal data class Hsva(val hue: Float, val saturation: Float, val value: Float, val alpha: Float) {
    fun toColor(): Color {
        val c = value * saturation
        val h = (hue % 360f + 360f) % 360f / 60f
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = value - c
        return Color(r + m, g + m, b + m, alpha)
    }

    companion object {
        fun of(color: Color): Hsva {
            val max = maxOf(color.red, color.green, color.blue)
            val min = minOf(color.red, color.green, color.blue)
            val delta = max - min
            val hue = when {
                delta == 0f -> 0f
                max == color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
                max == color.green -> 60f * ((color.blue - color.red) / delta + 2f)
                else -> 60f * ((color.red - color.green) / delta + 4f)
            }.let { if (it < 0f) it + 360f else it }
            return Hsva(hue, if (max == 0f) 0f else delta / max, max, color.alpha)
        }
    }
}

/** [color] as CSS hex: `#rrggbb`, or `#rrggbbaa` when it is not opaque. */
internal fun cssHex(color: Color): String {
    fun byte(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    val alpha = if (color.alpha < 1f) byte(color.alpha) else ""
    return "#" + byte(color.red) + byte(color.green) + byte(color.blue) + alpha
}

/** CSS hex (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, the `#` optional) as a colour; null for anything else. */
internal fun parseCssHex(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    if (hex.any { it.digitToIntOrNull(16) == null }) return null
    val full = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val alpha = if (full.length == 8) full.substring(6).toLong(16) else 0xFF
    return Color((alpha shl 24 or full.substring(0, 6).toLong(16)).toInt())
}

/**
 * The colour picker a colour swatch opens: saturation and value in a square, hue and alpha on
 * bars below it, the colour as hex in a field, and the colour before and after side by side.
 * Apply (or Enter in the field) picks the colour ([onPick]); Escape or Cancel closes ([onDismiss]).
 */
@Composable
internal fun ColorPickerCard(initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var hsva by remember(initial) { mutableStateOf(Hsva.of(initial)) }
    var hexText by remember(initial) { mutableStateOf(cssHex(initial)) }
    fun update(next: Hsva) {
        hsva = next
        hexText = cssHex(next.toColor())
    }
    val current = hsva.toColor()
    EditorChromeTheme {
        Surface(
            modifier = modifier
                .width(240.dp)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .testTag(EditorTestTags.COLOR_PICKER)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) true.also { onDismiss() } else false
                },
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Before and after.
                Row(Modifier.fillMaxWidth().height(20.dp)) {
                    Box(Modifier.weight(1f).height(20.dp).background(initial))
                    Box(Modifier.weight(1f).height(20.dp).background(current))
                }
                // Saturation across, value down, at the hue.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .pointerInput(Unit) {
                            dragPositions { position ->
                                update(
                                    hsva.copy(
                                        saturation = (position.x / size.width).coerceIn(0f, 1f),
                                        value = 1f - (position.y / size.height).coerceIn(0f, 1f),
                                    ),
                                )
                            }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Hsva(hsva.hue, 1f, 1f, 1f).toColor())))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val at = Offset(hsva.saturation * size.width, (1f - hsva.value) * size.height)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = at, style = Stroke(2.dp.toPx()))
                }
                // Hue.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(Unit) {
                            dragPositions { position -> update(hsva.copy(hue = (position.x / size.width).coerceIn(0f, 1f) * 360f)) }
                        },
                ) {
                    drawRect(Brush.horizontalGradient((0..6).map { Hsva(it * 60f, 1f, 1f, 1f).toColor() }))
                    drawThumb(hsva.hue / 360f)
                }
                // Alpha.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .pointerInput(Unit) {
                            dragPositions { position -> update(hsva.copy(alpha = (position.x / size.width).coerceIn(0f, 1f))) }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(current.copy(alpha = 0f), current.copy(alpha = 1f))))
                    drawThumb(hsva.alpha)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            parseCssHex(text)?.let { hsva = Hsva.of(it) }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = LocalEditorTypography.current.fontFamily,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag(EditorTestTags.COLOR_PICKER_HEX)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                    true.also { onPick(hsva.toColor()) }
                                } else {
                                    false
                                }
                            },
                    )
                    Box(Modifier.size(22.dp).background(current, RoundedCornerShape(4.dp)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(hsva.toColor()) }) { Text("Apply") }
                }
            }
        }
    }
}

/** A thin upright marker at [fraction] of a bar's width. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(fraction: Float) {
    val x = fraction.coerceIn(0f, 1f) * size.width
    val width = 3.dp.toPx()
    drawRect(Color.White, Offset(x - width / 2f, 0f), Size(width, size.height))
    drawRect(Color.Black, Offset(x - width / 2f, 0f), Size(width, size.height), style = Stroke(1f))
}

/** Calls [onPosition] with the press and every move of a drag, consuming them. */
private suspend fun PointerInputScope.dragPositions(onPosition: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPosition(down.position)
        down.consume()
        drag(down.id) { change ->
            onPosition(change.position)
            change.consume()
        }
    }
}
