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

import androidx.compose.ui.input.key.KeyEvent

internal actual class TypedTextDecoder actual constructor() {
    /**
     * AWT's `KEY_TYPED`: the character a key (or a dead key and the key after it) produced. Chords
     * with Ctrl or Cmd type control characters or nothing and are shortcuts; Ctrl+Alt is AltGr on
     * Windows, which types characters such as `@` on many layouts, so it counts as typing.
     */
    actual fun typedText(event: KeyEvent): String? {
        val native = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return null
        if (native.id != java.awt.event.KeyEvent.KEY_TYPED) return null
        val char = native.keyChar
        if (char == java.awt.event.KeyEvent.CHAR_UNDEFINED || Character.isISOControl(char)) return null
        if (native.isMetaDown || (native.isControlDown && !native.isAltDown)) return null
        return char.toString()
    }
}
