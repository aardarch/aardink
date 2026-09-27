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
@file:OptIn(ExperimentalComposeUiApi::class)

package com.aardarch.aardink.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry

internal actual val editorImeWindowed: Boolean = true

internal actual suspend fun PlatformTextInputSession.runEditorTextInput(adapter: EditorImeAdapter, geometry: EditorImeGeometry): Nothing =
    startInputMethod(EditorTextInputRequest(adapter, geometry))

/**
 * The editor's request to Compose's skiko text input (desktop and the web), after foundation's own
 * `SkikoPlatformTextInputMethodRequest`. Everything is in the coordinates of [adapter]'s window
 * around the caret: that is all the web's hidden text area holds, and all a desktop input method
 * queries.
 */
internal class EditorTextInputRequest(private val adapter: EditorImeAdapter, private val geometry: EditorImeGeometry) :
    PlatformTextInputMethodRequest {

    override val value: () -> TextFieldValue = { adapter.value() }

    override val state: TextEditorState = object : TextEditorState {
        override val length: Int get() = adapter.windowLength

        override fun get(index: Int): Char = adapter.text[adapter.windowOffset + index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = adapter.imeText(startIndex, endIndex)

        override val selection: TextRange get() = adapter.imeSelection

        override val composition: TextRange? get() = adapter.imeComposition

        override val text: String get() = adapter.imeText(0, adapter.windowLength).toString()

        override fun toString(): String = text
    }

    override val imeOptions: ImeOptions =
        ImeOptions(singleLine = false, autoCorrect = true, keyboardType = KeyboardType.Text, imeAction = ImeAction.None)

    override val onEditCommand: (List<EditCommand>) -> Unit = { adapter.apply(it) }

    override val onImeAction: ((ImeAction) -> Unit)? = null

    override val textLayoutResult: () -> TextLayoutResult? = { null }

    override val focusedRectInRoot: () -> Rect? = { geometry.caretRectInRoot() }

    override val textFieldRectInRoot: () -> Rect? = { geometry.boundsInRoot() }

    override val textClippingRectInRoot: () -> Rect? = { geometry.boundsInRoot() }

    override val unclippedTextOffsetInRoot: () -> Offset? = { geometry.textOriginInRoot() }

    override val editText: (block: TextEditingScope.() -> Unit) -> Unit = { block ->
        adapter.edit {
            val buffer = this
            val offset = adapter.windowOffset
            object : TextEditingScope {
                override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) =
                    buffer.deleteSurroundingTextInCodePoints(lengthBeforeCursor, lengthAfterCursor)

                override fun setSelection(start: Int, end: Int) = buffer.setSelection(offset + start, offset + end)

                override fun commitText(text: CharSequence, newCursorPosition: Int) = buffer.commitText(text, newCursorPosition)

                override fun setComposingRegion(start: Int, end: Int) = buffer.setComposingRegion(offset + start, offset + end)

                override fun setComposingText(text: CharSequence, newCursorPosition: Int) = buffer.setComposingText(text, newCursorPosition)

                override fun finishComposingText() = buffer.finishComposingText()
            }.block()
        }
    }
}
