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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.ui.EditorKeyBinding
import com.aardarch.aardink.ui.EditorKeyBindings
import com.aardarch.aardink.ui.KeyChord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editing commands of 0.8, run as their keys run them: through [CodeEditorState.execute]. */
class EditingCommandsTest {

    private object Slashes : IncrementalTokenizer {
        override val commentSyntax = CommentSyntax(line = "//", blockStart = "/*", blockEnd = "*/")

        override fun tokenizeFull(text: String): List<Token> = emptyList()

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private fun state(text: String, selection: TextRange = TextRange(0)) =
        CodeEditorState(text, tokenizer = Slashes, scope = CoroutineScope(Dispatchers.Unconfined)).also { it.selection = selection }

    @Test
    fun `join lines joins with a single space`() {
        val state = state("a  \n    b\nc", TextRange(1))
        assertTrue(state.execute(EditorCommand.JoinLines))
        assertEquals("a b\nc", state.text)
        val block = state("one\ntwo\n\nthree", TextRange(0, 12))
        block.execute(EditorCommand.JoinLines)
        assertEquals("one twothree", block.text)
    }

    @Test
    fun `sort lines up and down`() {
        val state = state("c\na\nb", TextRange(0, 5))
        state.execute(EditorCommand.SortLines())
        assertEquals("a\nb\nc", state.text)
        state.execute(EditorCommand.SortLines(descending = true))
        assertEquals("c\nb\na", state.text)
    }

    @Test
    fun `case of a selection, or of the word at the caret`() {
        val state = state("hello world", TextRange(7))
        state.execute(EditorCommand.UpperCase)
        assertEquals("hello WORLD", state.text)
        state.selection = TextRange(0, 3)
        state.execute(EditorCommand.UpperCase)
        state.execute(EditorCommand.LowerCase)
        assertEquals("hello WORLD", state.text)
    }

    @Test
    fun `trailing whitespace goes from every line`() {
        val state = state("a  \n\tb\t\nc")
        state.execute(EditorCommand.TrimTrailingWhitespace)
        assertEquals("a\n\tb\nc", state.text)
        assertFalse(state.execute(EditorCommand.TrimTrailingWhitespace))
    }

    @Test
    fun `a line is inserted below or above, indented as the caret's`() {
        val state = state("  one\ntwo", TextRange(3))
        state.execute(EditorCommand.InsertLineBelow)
        assertEquals("  one\n  \ntwo", state.text)
        assertEquals(TextRange(8), state.selection)
        state.execute(EditorCommand.InsertLineAbove)
        assertEquals("  one\n  \n  \ntwo", state.text)
        assertEquals(TextRange(8), state.selection)
    }

    @Test
    fun `select line grows by a line with each repeat`() {
        val state = state("a\nb\nc", TextRange(0))
        state.execute(EditorCommand.SelectLine)
        assertEquals(TextRange(0, 2), state.selection)
        state.execute(EditorCommand.SelectLine)
        assertEquals(TextRange(0, 4), state.selection)
    }

    @Test
    fun `line comments are added and removed, and a block comment wraps the selection`() {
        val state = state("a\n  b", TextRange(0, 5))
        state.execute(EditorCommand.AddLineComment)
        assertEquals("// a\n//   b", state.text)
        state.execute(EditorCommand.RemoveLineComment)
        assertEquals("a\n  b", state.text)
        val block = state("x = 1", TextRange(4, 5))
        block.execute(EditorCommand.BlockComment)
        assertEquals("x = /* 1 */", block.text)
        block.execute(EditorCommand.BlockComment)
        assertEquals("x = 1", block.text)
        val caret = state("", TextRange(0))
        caret.execute(EditorCommand.BlockComment)
        assertEquals("/*  */", caret.text)
        assertEquals(TextRange(3), caret.selection)
    }

    @Test
    fun `carets go to the ends of the selected lines`() {
        val state = state("ab\ncde\nf", TextRange(1, 5))
        state.execute(EditorCommand.CursorsAtLineEnds)
        assertEquals(listOf(TextRange(2), TextRange(6)), state.selections)
    }

    @Test
    fun `jump to the matching bracket and back`() {
        val state = state("f(a, b)", TextRange(2))
        assertTrue(state.execute(EditorCommand.JumpToBracket))
        assertEquals(TextRange(6), state.selection)
        state.execute(EditorCommand.JumpToBracket)
        assertEquals(TextRange(1), state.selection)
    }

    @Test
    fun `cursor undo goes back through the selections`() {
        val state = state("abcdef", TextRange(0))
        state.selection = TextRange(3)
        state.selection = TextRange(5)
        assertTrue(state.execute(EditorCommand.CursorUndo))
        assertEquals(TextRange(3), state.selection)
        state.execute(EditorCommand.CursorUndo)
        assertEquals(TextRange(0), state.selection)
    }

    @Test
    fun `skip occurrence moves the last selection on, change all selects every one`() {
        val state = state("x x x", TextRange(0, 1))
        state.execute(EditorCommand.SkipOccurrence)
        assertEquals(listOf(TextRange(2, 3)), state.selections)
        val all = state("ab x ab", TextRange(1))
        all.execute(EditorCommand.ChangeAllOccurrences)
        assertEquals(2, all.selections.size)
    }

    @Test
    fun `chords parse from text, and a binding is one key or two`() {
        assertEquals(KeyChord(Key.S, ctrlCmd = true, shift = true), KeyChord.parse("CtrlCmd+Shift+S"))
        assertEquals(KeyChord(Key.Enter, winCtrl = true, alt = true), KeyChord.parse("Ctrl+Alt+Enter"))
        assertNull(KeyChord.parse("Ctrl+Nothing"))
        assertFailsWith<IllegalArgumentException> { EditorKeyBinding(emptyList(), EditorCommand.Undo) }
    }

    @Test
    fun `no two default bindings share their keys`() {
        for (isMac in listOf(false, true)) {
            val chords = EditorKeyBindings.defaults(isMac).map { it.chord }
            assertEquals(chords.size, chords.toSet().size, "duplicates for isMac = $isMac")
        }
    }
}
