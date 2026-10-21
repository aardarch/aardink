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

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The events built here carry the code point Compose's web conversion gives them: the DOM `key`
 * when it is one character, else the code of the physical key.
 */
@OptIn(InternalComposeUiApi::class)
class TypedTextDecoderWasmTest {
    private val decoder = TypedTextDecoder()

    private fun down(key: Key, codePoint: Int, ctrl: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, codePoint = codePoint, isCtrlPressed = ctrl)

    private fun keyCode(key: Key) = key.keyCode.toInt()

    @Test
    fun functionKeysTypeNothing() {
        // F8 is code 119, 'w'; F1 112 'p'; F12 123 '{'.
        assertNull(decoder.typedText(down(Key.F8, keyCode(Key.F8))))
        assertNull(decoder.typedText(down(Key.F1, keyCode(Key.F1))))
        assertNull(decoder.typedText(down(Key.F12, keyCode(Key.F12))))
    }

    @Test
    fun navigationAndEditingKeysTypeNothing() {
        assertNull(decoder.typedText(down(Key.Insert, keyCode(Key.Insert)))) // '-'
        assertNull(decoder.typedText(down(Key.Delete, keyCode(Key.Delete)))) // '.'
        assertNull(decoder.typedText(down(Key.PageUp, keyCode(Key.PageUp)))) // '!'
        assertNull(decoder.typedText(down(Key.MoveHome, keyCode(Key.MoveHome)))) // '$'
        assertNull(decoder.typedText(down(Key.DirectionLeft, keyCode(Key.DirectionLeft)))) // '%'
    }

    @Test
    fun aNumpadKeyWithoutNumLockTypesNothing() {
        // DOM key "Insert" on Numpad0: code 96, a backquote.
        assertNull(decoder.typedText(down(Key.NumPad0, keyCode(Key.NumPad0))))
    }

    @Test
    fun aDeadKeyTypesNothing() {
        // AZERTY's ^ is a dead key on the physical [ key: DOM key "Dead", code 219, 'Û'.
        assertNull(decoder.typedText(down(Key.LeftBracket, keyCode(Key.LeftBracket))))
    }

    @Test
    fun charactersAreTyped() {
        assertEquals("w", decoder.typedText(down(Key.W, 'w'.code)))
        assertEquals("{", decoder.typedText(down(Key.LeftBracket, '{'.code)))
        assertEquals("0", decoder.typedText(down(Key.NumPad0, '0'.code)))
        assertEquals("é", decoder.typedText(down(Key.Two, 'é'.code)))
    }

    @Test
    fun charactersWhoseCodeIsTheirKeysCodeAreTyped() {
        assertEquals("W", decoder.typedText(down(Key.W, 'W'.code)))
        assertEquals("7", decoder.typedText(down(Key.Seven, '7'.code)))
        assertEquals(" ", decoder.typedText(down(Key.Spacebar, ' '.code)))
    }

    @Test
    fun shortcutsAndControlCharactersTypeNothing() {
        assertNull(decoder.typedText(down(Key.W, 'w'.code, ctrl = true)))
        assertNull(decoder.typedText(down(Key.Enter, '\r'.code)))
        assertNull(decoder.typedText(down(Key.Tab, '\t'.code)))
    }
}
