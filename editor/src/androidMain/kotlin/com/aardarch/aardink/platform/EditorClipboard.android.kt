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

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal actual val editorClipboardFromKeys: Boolean = true

internal actual suspend fun Clipboard.writePlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText("code", text)))
}

internal actual suspend fun Clipboard.readPlainText(): String? =
    getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

@Composable
internal actual fun EditorClipboardEvents(active: Boolean, onCopy: () -> String?, onCut: () -> String?, onPaste: (String) -> Unit) = Unit
