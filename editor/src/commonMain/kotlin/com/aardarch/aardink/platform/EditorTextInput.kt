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
import androidx.compose.ui.platform.PlatformTextInputSession
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry

/**
 * Whether the input method sees a window of the document around the caret rather than all of it;
 * see [EditorImeAdapter]. True where Compose copies the text the input method sees (the web's
 * hidden text area, desktop's input method requests); false on Android, which reads slices.
 */
internal expect val editorImeWindowed: Boolean

/**
 * Connects [adapter] to the platform's input method until the calling coroutine is cancelled:
 * an `InputConnection` on Android, Compose's skiko `PlatformTextInputMethodRequest` on desktop and
 * the web. [geometry] places the input method's candidate window and the web's hidden text area.
 */
internal expect suspend fun PlatformTextInputSession.runEditorTextInput(adapter: EditorImeAdapter, geometry: EditorImeGeometry): Nothing

/**
 * What a key event types, where the platform delivers typing as key events: a hardware keyboard
 * on Android (with dead keys combined), AWT's `KEY_TYPED` on desktop. Never on the web, where
 * typed text arrives through the input method.
 */
internal expect class TypedTextDecoder() {
    /**
     * The text [event] types; "" for a key that is part of typing but types nothing yet (a dead
     * key); null for anything else (a shortcut, a navigation key, a key release).
     */
    fun typedText(event: KeyEvent): String?
}
