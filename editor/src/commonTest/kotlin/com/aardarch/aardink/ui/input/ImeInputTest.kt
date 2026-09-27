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
package com.aardarch.aardink.ui.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.edit.TextChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImeWindowBufferTest {

    private fun bufferOf(text: String, selection: TextRange, composition: TextRange? = null) =
        ImeWindowBuffer(CodeDocument(text)).apply { begin(selection, composition) }

    @Test
    fun `commit replaces the selection and places the caret after it`() {
        val buffer = bufferOf("hello world", TextRange(6, 11))
        buffer.commitText("there", 1)
        assertEquals("hello there", buffer.toString())
        assertEquals(TextRange(11), buffer.selection)
        assertEquals(TextChange(6, 11, "there"), buffer.change())
    }

    @Test
    fun `a rewritten composing word becomes the one character it added`() {
        val buffer = bufferOf("say hel", TextRange(7), composition = TextRange(4, 7))
        buffer.setComposingText("hell", 1)
        assertEquals(TextRange(4, 8), buffer.composition)
        assertEquals(TextChange(7, 7, "l"), buffer.change())
    }

    @Test
    fun `newCursorPosition counts from the end of the text when positive, else from its start`() {
        val after = bufferOf("ab", TextRange(1))
        after.commitText("XY", 2)
        assertEquals(TextRange(4), after.selection)
        val before = bufferOf("ab", TextRange(1))
        before.commitText("XY", 0)
        assertEquals(TextRange(1), before.selection)
    }

    @Test
    fun `composing region, finish and backspace over a composition`() {
        val buffer = bufferOf("one two", TextRange(7))
        buffer.setComposingRegion(4, 7)
        assertEquals(TextRange(4, 7), buffer.composition)
        buffer.backspace()
        assertEquals("one ", buffer.toString())
        assertNull(buffer.composition)
        buffer.setComposingText("x", 1)
        buffer.finishComposingText()
        assertNull(buffer.composition)
        assertEquals("one x", buffer.toString())
    }

    @Test
    fun `delete surrounding text moves the composition with it`() {
        val buffer = bufferOf("abcdef", TextRange(3), composition = TextRange(4, 6))
        buffer.deleteSurroundingText(2, 1)
        assertEquals("aef", buffer.toString())
        assertEquals(TextRange(1), buffer.selection)
        assertEquals(TextRange(1, 3), buffer.composition)
    }

    @Test
    fun `code point deletes never split a surrogate pair`() {
        val buffer = bufferOf("a😀b😀", TextRange(3))
        buffer.deleteSurroundingTextInCodePoints(1, 2)
        assertEquals("a", buffer.toString())
    }

    @Test
    fun `reads see the batch's edits, and an unchanged text is no change`() {
        val buffer = bufferOf("0123456789", TextRange(5))
        buffer.commitText("ab", 1)
        assertEquals("01234ab56789", buffer.subSequence(0, buffer.length).toString())
        assertEquals('a', buffer[5])
        assertEquals('9', buffer[11])
        buffer.setSelection(5, 7)
        buffer.commitText("ab", 1)
        assertEquals(TextChange(5, 5, "ab"), buffer.change())
        val same = bufferOf("xyz", TextRange(1))
        same.commitText("", 1)
        assertNull(same.change())
    }
}

class EditorImeAdapterTest {

    private fun stateOf(text: String) =
        CodeEditorState(initialText = "", tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
            fieldMirror = false
            loadText(text)
        }

    private class Recorder : EditorImeAdapter.Listener {
        var restarts = 0
        var selections = 0

        override fun restartInput() {
            restarts++
        }

        override fun updateSelection(selection: TextRange, composition: TextRange?) {
            selections++
        }
    }

    @Test
    fun `a batch reaches the state as one edit`() {
        val state = stateOf("fun")
        state.selection = TextRange(3)
        val adapter = EditorImeAdapter(state, windowed = false)
        adapter.beginBatch()
        adapter.edit { commitText(" ", 1) }
        adapter.edit { commitText("x", 1) }
        assertEquals("fun", state.text, "nothing lands before the batch ends")
        adapter.endBatch()
        assertEquals("fun x", state.text)
        assertEquals(TextRange(5), state.selection)
    }

    @Test
    fun `a composition is underlined, then committed as one undo step`() {
        val state = stateOf("")
        val adapter = EditorImeAdapter(state, windowed = false)
        adapter.apply(listOf(SetComposingTextCommand("n", 1)))
        adapter.apply(listOf(SetComposingTextCommand("ni", 1)))
        assertEquals(TextRange(0, 2), adapter.composition)
        adapter.apply(listOf(CommitTextCommand("你", 1)))
        assertNull(adapter.composition)
        assertEquals("你", state.text)
        state.undo()
        assertEquals("", state.text)
    }

    @Test
    fun `typing rules that change more than the input method did restart it`() {
        val state = stateOf("")
        val adapter = EditorImeAdapter(state, windowed = false)
        adapter.service = { AutoCloseParens }
        val recorder = Recorder()
        adapter.listener = recorder
        adapter.apply(listOf(CommitTextCommand("(", 1)))
        assertEquals("()", state.text)
        assertEquals(1, recorder.restarts)
        adapter.apply(listOf(CommitTextCommand("a", 1)))
        assertEquals(1, recorder.restarts)
        assertTrue(recorder.selections > 0)
    }

    @Test
    fun `an edit from elsewhere ends the composition and restarts the input method`() {
        val state = stateOf("abc")
        state.selection = TextRange(3)
        val adapter = EditorImeAdapter(state, windowed = false)
        val recorder = Recorder()
        adapter.listener = recorder
        adapter.apply(listOf(SetComposingTextCommand("d", 1)))
        state.applyEdit(0, 0, "X", TextRange(0))
        adapter.onEditorChanged()
        assertNull(adapter.composition)
        assertEquals(1, recorder.restarts)
    }

    @Test
    fun `the caret leaving the composition finishes it`() {
        val state = stateOf("abc")
        state.selection = TextRange(3)
        val adapter = EditorImeAdapter(state, windowed = false)
        adapter.apply(listOf(SetComposingTextCommand("d", 1)))
        state.selection = TextRange(0)
        adapter.onEditorChanged()
        assertNull(adapter.composition)
        assertEquals("abcd", state.text)
    }

    @Test
    fun `the web sees a window around the caret, in its own coordinates`() {
        val text = (0 until 200).joinToString("\n") { "line $it" }
        val state = stateOf(text)
        val caret = state.document.lineStart(100) + 2
        state.selection = TextRange(caret)
        val adapter = EditorImeAdapter(state, windowed = true)
        val value = adapter.value()
        assertTrue(value.text.length < text.length / 3, "a window, not the document: ${value.text.length}")
        assertTrue(value.text.startsWith("line 80"), value.text.take(20))
        assertEquals(TextRange(caret - adapter.windowOffset), value.selection)
        // Commands arrive in window coordinates.
        adapter.apply(listOf(SetSelectionCommand(0, 0), CommitTextCommand("> ", 1)))
        assertEquals("> line 80", state.document.lineText(80))
    }

    @Test
    fun `the window stays put during a composition, and moves after it`() {
        val text = (0 until 400).joinToString("\n") { "line $it" }
        val state = stateOf(text)
        state.selection = TextRange(state.document.lineStart(100))
        val adapter = EditorImeAdapter(state, windowed = true)
        val start = adapter.windowOffset
        adapter.apply(listOf(SetComposingTextCommand("x", 1)))
        state.selection = TextRange(state.document.lineStart(300))
        assertEquals(start, adapter.windowOffset, "no move while composing")
        adapter.apply(listOf(FinishComposingTextCommand()))
        assertTrue(adapter.windowOffset > start, "moved to the caret once the composition ended")
    }

    @Test
    fun `backspace and delete surrounding arrive as deletions that group for undo`() {
        val state = stateOf("abcd")
        state.selection = TextRange(4)
        val adapter = EditorImeAdapter(state, windowed = false)
        adapter.apply(listOf(BackspaceCommand()))
        adapter.apply(listOf(DeleteSurroundingTextCommand(1, 0)))
        assertEquals("ab", state.text)
        state.undo()
        assertEquals("abcd", state.text)
    }

    @Test
    fun `a composing region over existing text keeps the text`() {
        val state = stateOf("hello")
        state.selection = TextRange(5)
        val adapter = EditorImeAdapter(state, windowed = false)
        adapter.apply(listOf(SetComposingRegionCommand(0, 5)))
        assertEquals(TextRange(0, 5), adapter.composition)
        assertEquals("hello", state.text)
    }

    private object AutoCloseParens : com.aardarch.aardink.core.LanguageService {
        override val supportsRename: Boolean = false
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int) = emptyList<com.aardarch.aardink.core.CompletionItem>()

        override suspend fun diagnostics(document: CodeDocument) = emptyList<com.aardarch.aardink.core.Diagnostic>()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): com.aardarch.aardink.core.HoverDoc? = null

        override suspend fun format(document: CodeDocument): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = if (charTyped == '(') ")" else null

        override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = 0
    }
}
