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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The skiko request as desktop's input method service uses it: an AWT `InputMethodEvent` becomes
 * an `editText` block (committed text, then composing text), and the service reads the selection
 * and composition back through `state`.
 */
class EditorTextInputRequestTest {

    private fun requestFor(text: String, caret: Int): Pair<CodeEditorState, EditorTextInputRequest> {
        val state = CodeEditorState(initialText = "", tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
            fieldMirror = false
            loadText(text)
            selection = TextRange(caret)
        }
        val geometry = object : EditorImeGeometry {
            override fun caretRectInRoot(): Rect? = Rect(10f, 20f, 12f, 40f)

            override fun boundsInRoot(): Rect? = Rect(0f, 0f, 100f, 100f)

            override fun textOriginInRoot(): Offset? = Offset.Zero

            override fun characterBoundsInRoot(offset: Int): Rect? = null
        }
        return state to EditorTextInputRequest(EditorImeAdapter(state, windowed = true), geometry)
    }

    @Test
    fun `a Pinyin composition, then its commit, as InputMethodEvents deliver them`() {
        val (state, request) = requestFor("val s = ", 8)
        request.editText {
            commitText("", 1)
            setComposingText("ni", 1)
        }
        assertEquals("val s = ni", state.document.text)
        assertEquals(TextRange(8, 10), request.state.composition)
        assertEquals(TextRange(10), request.state.selection)
        request.editText {
            commitText("你", 1)
        }
        assertEquals("val s = 你", state.document.text)
        assertNull(request.state.composition)
        state.undo()
        assertEquals("val s = ", state.document.text)
    }

    @Test
    fun `the state the service reads is the window, with the selection in it`() {
        val text = (0 until 300).joinToString("\n") { "row $it" }
        val caret = text.indexOf("row 150")
        val (_, request) = requestFor(text, caret)
        val window = request.state
        assertEquals(window.text, request.value().text)
        assertEquals(TextRange(window.text.indexOf("row 150")), window.selection)
        assertEquals("row 150", window.subSequence(window.selection.start, window.selection.start + 7).toString())
    }

    @Test
    fun `edit commands and the caret rectangle`() {
        val (state, request) = requestFor("ab", 2)
        request.onEditCommand(listOf(CommitTextCommand("c", 1)))
        assertEquals("abc", state.document.text)
        assertEquals(Rect(10f, 20f, 12f, 40f), request.focusedRectInRoot())
    }
}
