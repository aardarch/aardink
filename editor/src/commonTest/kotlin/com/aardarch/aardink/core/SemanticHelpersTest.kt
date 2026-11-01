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
import com.aardarch.aardink.core.edit.SelectionExpander
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticHelpersTest {

    private fun state(text: String) = CodeEditorState(text, scope = CoroutineScope(Dispatchers.Unconfined))

    private fun grown(text: String, at: Int): List<String> =
        SelectionExpander.ranges(text, at, at).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `the selection grows by word, string, brackets, line and document`() {
        val text = "val x = f(a, \"hello world\")\nnext"
        val at = text.indexOf("world") + 1
        assertEquals(
            listOf(
                "world",
                "hello world",
                "\"hello world\"",
                "a, \"hello world\"",
                "(a, \"hello world\")",
                "val x = f(a, \"hello world\")",
                "val x = f(a, \"hello world\")\n",
                text,
            ),
            grown(text, at),
        )
    }

    @Test
    fun `expand then shrink walks back the same way`() {
        val state = state("call(arg)")
        state.selection = TextRange(6)
        assertTrue(state.execute(EditorCommand.ExpandSelection))
        assertEquals(TextRange(5, 8), state.selection)
        assertTrue(state.execute(EditorCommand.ExpandSelection))
        assertEquals(TextRange(4, 9), state.selection)
        assertTrue(EditorCommand.ShrinkSelection in state.availableCommands)
        assertTrue(state.execute(EditorCommand.ShrinkSelection))
        assertEquals(TextRange(5, 8), state.selection)
        assertTrue(state.execute(EditorCommand.ShrinkSelection))
        assertEquals(TextRange(6), state.selection)
        assertFalse(state.execute(EditorCommand.ShrinkSelection))
    }

    @Test
    fun `moving the caret forgets the growth`() {
        val state = state("call(arg)")
        state.selection = TextRange(6)
        state.execute(EditorCommand.ExpandSelection)
        state.selection = TextRange(1)
        assertFalse(EditorCommand.ShrinkSelection in state.availableCommands)
    }

    @Test
    fun `typing in a linked range writes the name in the others, in one undo step`() {
        val state = state("<div>x</div>")
        state.setLinkedRanges(listOf(1 until 4, 8 until 11), state.document.version)
        state.selection = TextRange(4)
        state.applyInput(4, 4, "s")
        assertEquals("<divs>x</divs>", state.text)
        assertEquals(TextRange(5), state.selection)
        state.applyInput(5, 5, "x")
        assertEquals("<divsx>x</divsx>", state.text)
        state.deleteLeft()
        assertEquals("<divs>x</divs>", state.text)
        state.undo()
        state.undo()
        assertEquals("<div>x</div>", state.text)
    }

    @Test
    fun `an edit in the closing tag moves the caret by what changed before it`() {
        val state = state("<a>x</a>")
        state.setLinkedRanges(listOf(1 until 2, 6 until 7), state.document.version)
        state.selection = TextRange(7)
        state.applyInput(7, 7, "bc")
        assertEquals("<abc>x</abc>", state.text)
        assertEquals(TextRange(11), state.selection)
    }

    @Test
    fun `a space or another edit ends the link`() {
        val state = state("<a>x</a>")
        state.setLinkedRanges(listOf(1 until 2, 6 until 7), state.document.version)
        state.selection = TextRange(2)
        state.applyInput(2, 2, " ")
        assertEquals("<a >x</a>", state.text)
        // Stale now: the next name typed is not mirrored either.
        state.applyInput(1, 1, "b")
        assertEquals("<ba >x</a>", state.text)
    }

    @Test
    fun `ranges for an older document are refused`() {
        val state = state("<a>x</a>")
        val old = state.document.version
        state.document.insert(0, " ")
        state.setLinkedRanges(listOf(1 until 2, 6 until 7), old)
        assertTrue(state.currentLinkedRanges.isEmpty())
    }

    @Test
    fun `urls are found on a line, without trailing punctuation`() {
        val line = "see https://example.com/a?b=1. and http://x.io"
        assertEquals(4 until 29, UrlDetector.urlAt(line, 10))
        assertEquals(4 until 29, UrlDetector.urlAt(line, 29))
        assertEquals(35 until 46, UrlDetector.urlAt(line, 40))
        assertNull(UrlDetector.urlAt(line, 31))
    }
}
