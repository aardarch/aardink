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
import java.awt.Component
import java.awt.event.InputEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The events built here have the shape Compose's desktop pipeline delivers: the AWT event inside
 * Compose's own native event, and a `KEY_TYPED` arriving with type [KeyEventType.Unknown].
 */
@OptIn(InternalComposeUiApi::class)
class TypedTextDecoderJvmTest {
    private val decoder = TypedTextDecoder()
    private val source = object : Component() {}

    private fun event(id: Int, char: Char, key: Key = Key.Unknown, modifiers: Int = 0): KeyEvent {
        val keyCode = if (id == AwtKeyEvent.KEY_TYPED) AwtKeyEvent.VK_UNDEFINED else AwtKeyEvent.getExtendedKeyCodeForChar(char.code)
        val awt = AwtKeyEvent(source, id, 0, modifiers, keyCode, char)
        val type = when (id) {
            AwtKeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            AwtKeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        }
        return KeyEvent(
            key,
            type,
            codePoint = char.code,
            isCtrlPressed = modifiers and InputEvent.CTRL_DOWN_MASK != 0,
            isMetaPressed = modifiers and InputEvent.META_DOWN_MASK != 0,
            isAltPressed = modifiers and InputEvent.ALT_DOWN_MASK != 0,
            nativeEvent = awt,
        )
    }

    private fun typed(char: Char, modifiers: Int = 0) = event(AwtKeyEvent.KEY_TYPED, char, modifiers = modifiers)

    @Test
    fun charactersAreTyped() {
        assertEquals("a", decoder.typedText(typed('a')))
        assertEquals("7", decoder.typedText(typed('7')))
        assertEquals("é", decoder.typedText(typed('é')))
        assertEquals(" ", decoder.typedText(typed(' ')))
    }

    @Test
    fun pressesAndReleasesTypeNothing() {
        assertNull(decoder.typedText(event(AwtKeyEvent.KEY_PRESSED, 'a', Key.A)))
        assertNull(decoder.typedText(event(AwtKeyEvent.KEY_RELEASED, 'a', Key.A)))
    }

    @Test
    fun shortcutsTypeNothing() {
        assertNull(decoder.typedText(typed('a', InputEvent.CTRL_DOWN_MASK)))
        assertNull(decoder.typedText(typed('a', InputEvent.META_DOWN_MASK)))
    }

    @Test
    fun altGrTypes() {
        assertEquals("@", decoder.typedText(typed('@', InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK)))
    }

    @Test
    fun controlCharactersTypeNothing() {
        assertNull(decoder.typedText(typed('\n')))
        assertNull(decoder.typedText(typed('\t')))
        assertNull(decoder.typedText(typed('\b')))
    }

    @Test
    fun anEventWithoutAnAwtEventTypesNothing() {
        assertNull(decoder.typedText(KeyEvent(Key.A, KeyEventType.Unknown, codePoint = 'a'.code)))
    }
}
