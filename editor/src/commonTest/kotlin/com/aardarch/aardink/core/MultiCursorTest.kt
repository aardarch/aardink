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
import kotlin.test.assertTrue

/** Editing with several selections: every command at each of them, and undo as one step that brings them all back. */
class MultiCursorTest {

    private object SlashComments : IncrementalTokenizer {
        override fun tokenizeFull(text: String): List<Token> = emptyList()

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

        override val commentSyntax: CommentSyntax = CommentSyntax(line = "//")
    }

    private fun stateOf(text: String) = CodeEditorState(
        initialText = "",
        tokenizer = SlashComments,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        loadText(text)
    }

    private fun CodeEditorState.type(text: String) {
        for (c in text) {
            val primary = selection
            applyInput(primary.min, primary.max, c.toString())
        }
    }

    @Test
    fun `a word typed at three carets is one undo step that brings the three carets back`() {
        val state = stateOf("a\nb\nc")
        val carets = listOf(TextRange(3), TextRange(1), TextRange(5))
        state.setSelections(carets)
        state.type("xyz")
        assertEquals("axyz\nbxyz\ncxyz", state.text)
        state.undo()
        assertEquals("a\nb\nc", state.text)
        assertEquals(carets, state.selections, "the primary caret is still first")
        state.redo()
        assertEquals("axyz\nbxyz\ncxyz", state.text)
        assertEquals(3, state.selections.size)
    }

    @Test
    fun `backspace at every caret, then undo`() {
        val state = stateOf("ab\ncd\nef")
        state.setSelections(listOf(TextRange(2), TextRange(5), TextRange(8)))
        state.deleteLeft()
        assertEquals("a\nc\ne", state.text)
        state.undo()
        assertEquals("ab\ncd\nef", state.text)
        assertEquals(listOf(TextRange(2), TextRange(5), TextRange(8)), state.selections)
    }

    @Test
    fun `ctrl slash with carets on several lines comments each line once`() {
        val state = stateOf("one\ntwo\nthree")
        // Two carets on the first line and one on the last: two lines, each commented once.
        state.setSelections(listOf(TextRange(1), TextRange(2), TextRange(10)))
        state.toggleComment()
        assertEquals("// one\ntwo\n// three", state.text)
        state.toggleComment()
        assertEquals("one\ntwo\nthree", state.text)
        state.toggleComment()
        state.undo()
        assertEquals("one\ntwo\nthree", state.text)
        assertEquals(3, state.selections.size)
    }

    @Test
    fun `occurrences selected with ctrl D are replaced together, and undo selects them again`() {
        val state = stateOf("val x = x + x")
        state.selection = TextRange(4)
        assertTrue(state.addNextOccurrence())
        assertTrue(state.addNextOccurrence())
        assertTrue(state.addNextOccurrence())
        assertEquals(3, state.selections.size)
        state.type("y")
        assertEquals("val y = y + y", state.text)
        state.undo()
        assertEquals("val x = x + x", state.text)
        assertEquals(listOf(TextRange(4, 5), TextRange(8, 9), TextRange(12, 13)), state.selections.sortedBy { it.min })
    }

    @Test
    fun `paste at several carets pastes at each as one step`() {
        val state = stateOf("a\nb")
        state.setSelections(listOf(TextRange(1), TextRange(3)))
        state.paste("!", wholeLines = false)
        assertEquals("a!\nb!", state.text)
        state.undo()
        assertEquals("a\nb", state.text)
    }

    @Test
    fun `line moves take every selected line`() {
        val state = stateOf("1\n2\n3\n4")
        state.setSelections(listOf(TextRange(2), TextRange(6)))
        state.moveLines(up = true)
        assertEquals("2\n1\n4\n3", state.text)
        state.undo()
        assertEquals("1\n2\n3\n4", state.text)
    }
}
