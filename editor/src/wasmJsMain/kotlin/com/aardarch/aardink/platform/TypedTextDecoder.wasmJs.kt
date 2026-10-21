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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

internal actual class TypedTextDecoder actual constructor() {
    /**
     * While Compose's hidden text area has focus, typed keys never arrive here: Compose keeps them
     * for the text area, and the text reaches the editor through the input method. Only when the
     * page's focus is elsewhere (the canvas, after a touch) does a typed key come as a key event,
     * and then it is typed from here, so it is never typed twice.
     *
     * Compose fills a web key event's code point from the DOM's `key` only when that is a single
     * character. For any other key (`F8`, `Insert`, `PageUp`, a dead key, a numpad key without
     * NumLock) it uses the code of the physical key instead, so F8 (code 119) would type `w`,
     * Insert `-` and a numpad 0 a backquote. A code point equal to its key's code is therefore not
     * typed, except where the two agree for a real character: an upper-case letter on its own
     * key, a digit on its own key, and the space bar.
     */
    actual fun typedText(event: KeyEvent): String? {
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed) return null
        val codePoint = event.utf16CodePoint
        if (codePoint < 0x20 || codePoint == 0x7F) return null
        if (codePoint.toLong() == event.key.keyCode && !typesItsOwnKeyCode(codePoint)) return null
        return if (codePoint <= 0xFFFF) codePoint.toChar().toString() else null
    }

    // The characters whose code is also the code of the key that types them: 'A'..'Z' on Key.A..Z,
    // '0'..'9' on Key.Zero..Nine, ' ' on Key.Spacebar.
    private fun typesItsOwnKeyCode(codePoint: Int): Boolean =
        codePoint == ' '.code || codePoint in 'A'.code..'Z'.code || codePoint in '0'.code..'9'.code
}
