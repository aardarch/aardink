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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

internal actual class TypedTextDecoder actual constructor() {
    /**
     * While Compose's hidden text area has focus, typed keys never arrive here: Compose keeps them
     * for the text area, and the text reaches the editor through the input method. Only when the
     * page's focus is elsewhere (the canvas, after a touch) does a typed key come as a key event,
     * and then it is typed from here, so it is never typed twice.
     */
    actual fun typedText(event: KeyEvent): String? {
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed) return null
        val codePoint = event.utf16CodePoint
        if (codePoint <= 0 || codePoint < 0x20 || codePoint == 0x7F) return null
        return if (codePoint <= 0xFFFF) codePoint.toChar().toString() else null
    }
}
