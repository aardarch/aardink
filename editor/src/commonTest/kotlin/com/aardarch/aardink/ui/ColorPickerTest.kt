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

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColorPickerTest {

    @Test
    fun `colours survive the trip through hue, saturation and value`() {
        for (color in listOf(
            Color(0xFFFF0000),
            Color(0xFF00FF00),
            Color(0xFF0000FF),
            Color(0x80123456),
            Color(0xFF808080),
            Color.Black,
            Color.White,
        )) {
            assertEquals(cssHex(color), cssHex(Hsva.of(color).toColor()))
        }
    }

    @Test
    fun `css hex goes both ways`() {
        assertEquals("#123456", cssHex(Color(0xFF123456)))
        assertEquals("#12345680", cssHex(Color(0x80123456)))
        assertEquals(Color(0xFF112233), parseCssHex("#123"))
        assertEquals(Color(0x80123456), parseCssHex("12345680"))
        assertNull(parseCssHex("#12"))
    }
}
