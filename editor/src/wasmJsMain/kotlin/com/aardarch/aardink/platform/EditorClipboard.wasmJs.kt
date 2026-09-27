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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.Event
import kotlin.js.Promise

internal actual val editorClipboardFromKeys: Boolean = false

internal actual suspend fun Clipboard.writePlainText(text: String) {
    setClipEntry(ClipEntry.withPlainText(text))
}

internal actual suspend fun Clipboard.readPlainText(): String? = runCatching {
    readClipboardText().await<JsString>().toString()
}.getOrNull()

private fun readClipboardText(): Promise<JsString> = js("navigator.clipboard.readText()")

/**
 * Listens on the document, where the events bubble to from Compose's hidden text area (or its
 * clipboard target when the editor is read-only), and only while the editor has focus. The text
 * travels in the event itself, so no permission prompt appears in any browser.
 */
@Composable
internal actual fun EditorClipboardEvents(active: Boolean, onCopy: () -> String?, onCut: () -> String?, onPaste: (String) -> Unit) {
    if (!active) return
    val copy = rememberUpdatedState(onCopy)
    val cut = rememberUpdatedState(onCut)
    val paste = rememberUpdatedState(onPaste)
    DisposableEffect(Unit) {
        val copyListener: (Event) -> Unit = { event ->
            val text = copy.value()
            if (text != null && event is ClipboardEvent) {
                event.clipboardData?.setData("text/plain", text)
                event.preventDefault()
            }
        }
        val cutListener: (Event) -> Unit = { event ->
            val text = cut.value()
            if (text != null && event is ClipboardEvent) {
                event.clipboardData?.setData("text/plain", text)
                event.preventDefault()
            }
        }
        val pasteListener: (Event) -> Unit = { event ->
            if (event is ClipboardEvent) {
                val text = event.clipboardData?.getData("text/plain").orEmpty()
                event.preventDefault()
                if (text.isNotEmpty()) paste.value(text)
            }
        }
        document.addEventListener("copy", copyListener)
        document.addEventListener("cut", cutListener)
        document.addEventListener("paste", pasteListener)
        onDispose {
            document.removeEventListener("copy", copyListener)
            document.removeEventListener("cut", cutListener)
            document.removeEventListener("paste", pasteListener)
        }
    }
}
