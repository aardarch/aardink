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
package com.aardarch.aardink.core

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The editor's input path: [CodeEditorState.applyInput] and the key commands.
 */
class EditorInputTest {

    private fun stateFor(text: String): CodeEditorState = CodeEditorState(
        initialText = "",
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        loadText(text)
    }

    private object Service : LanguageService {
        override val supportsRename: Boolean = false
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = if (charTyped == '(') ")" else null

        override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = 4
    }

    private fun CodeEditorState.type(text: String) {
        for (c in text) {
            val primary = selection
            applyInput(primary.min, primary.max, c.toString(), service = Service)
        }
    }

    @Test
    fun `typing replaces the selection and reports the typed character`() {
        val state = stateFor("hello")
        state.selection = TextRange(0, 5)
        val typed = state.applyInput(0, 5, "x")
        assertEquals("x", state.text)
        assertEquals(TextRange(1), state.selection)
        assertNull(typed, "replacing a selection is not typing at a caret")
        val next = state.applyInput(1, 1, "y")
        assertEquals('y', next?.char)
        assertEquals(1, next?.offset)
    }

    @Test
    fun `typing at several carets types at each, with the typing rules at each`() {
        val state = stateFor("a\nb")
        state.setSelections(listOf(TextRange(1), TextRange(3)))
        state.type("(")
        assertEquals("a()\nb()", state.text)
        assertEquals(listOf(TextRange(2), TextRange(6)), state.selections)
        state.undo()
        assertEquals("a\nb", state.text)
        assertEquals(listOf(TextRange(1), TextRange(3)), state.selections)
    }

    @Test
    fun `enter indents the new line`() {
        val state = stateFor("x")
        state.selection = TextRange(1)
        state.type("\n")
        assertEquals("x\n    ", state.text)
        assertEquals(TextRange(6), state.selection)
    }

    @Test
    fun `a composition is one undo step and gets no typing rules`() {
        val state = stateFor("")
        // What an input method's rewrites of one composing word look like after diffing.
        state.applyInput(0, 0, "n", composing = true, service = Service)
        state.applyInput(1, 1, "i", composing = true, service = Service)
        state.applyInput(0, 2, "你", composing = true, service = Service)
        state.applyInput(1, 1, "(", composing = true, service = Service)
        assertEquals("你(", state.text)
        state.undo()
        assertEquals("", state.text)
    }

    @Test
    fun `words typed undo one at a time`() {
        val state = stateFor("")
        state.type("one two")
        state.undo()
        assertEquals("one", state.text)
    }

    @Test
    fun `backspaces group, and a word delete stands alone`() {
        val state = stateFor("one two three")
        state.selection = TextRange(13)
        repeat(3) { state.deleteLeft() }
        assertEquals("one two th", state.text)
        state.deleteLeft(word = true)
        assertEquals("one two ", state.text)
        state.undo()
        assertEquals("one two th", state.text)
        state.undo()
        assertEquals("one two three", state.text)
    }

    @Test
    fun `cut, copy and paste go through the clipboard text`() {
        val state = stateFor("one\ntwo")
        state.selection = TextRange(1)
        val copied = state.copySelections()
        assertEquals("one\n", copied.text)
        val cut = state.cutSelections()!!
        assertEquals("two", state.text)
        state.paste(cut.text, cut.wholeLines)
        assertEquals("one\ntwo", state.text)
    }

    @Test
    fun `selections are the state's own, and caret moves keep the primary first`() {
        val state = stateFor("abcdef")
        state.setSelections(listOf(TextRange(4), TextRange(1)))
        assertEquals(listOf(TextRange(4), TextRange(1)), state.selections)
        state.moveSelections(extend = true) { it.end + 1 }
        assertEquals(listOf(TextRange(4, 5), TextRange(1, 2)), state.selections)
    }

    @Test
    fun `input edits are not external edits, commands are`() {
        val state = stateFor("abc")
        val before = state.externalEditVersion
        state.applyInput(3, 3, "d")
        assertEquals(before, state.externalEditVersion)
        state.deleteLeft()
        assertEquals(before + 1, state.externalEditVersion)
    }
}
