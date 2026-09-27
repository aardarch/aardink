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
import androidx.compose.ui.platform.Clipboard

/**
 * Whether Ctrl/Cmd+C, X and V go through Compose's `Clipboard`, as on Android and desktop. In the
 * browser they must not: the editor leaves those keys to the browser, which fires `copy`, `cut`
 * and `paste` events that carry the text without a permission prompt ([EditorClipboardEvents]).
 */
internal expect val editorClipboardFromKeys: Boolean

/** Puts [text] on the clipboard as plain text. */
internal expect suspend fun Clipboard.writePlainText(text: String)

/**
 * The clipboard's plain text, or null when it holds none. In the browser this asks for permission,
 * so only an explicit menu action uses it; the keys go through [EditorClipboardEvents].
 */
internal expect suspend fun Clipboard.readPlainText(): String?

/**
 * While [active] (the editor has focus), routes the browser's clipboard events to the editor:
 * [onCopy] and [onCut] return the text to put on the clipboard (null to leave the event to the
 * browser), [onPaste] receives what is pasted. Does nothing outside the browser.
 */
@Composable
internal expect fun EditorClipboardEvents(active: Boolean, onCopy: () -> String?, onCut: () -> String?, onPaste: (String) -> Unit)
