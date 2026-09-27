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
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aardarch.aardink.core

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeEditorStateTest {

    private fun testState(text: String): CodeEditorState = CodeEditorState(
        initialText = "",
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        // Loaded only now: the constructor's first tokenization pass would otherwise start on the
        // default background dispatcher and resume there, racing the test's own edits.
        loadText(text)
    }

    @Test
    fun `applyTextEdits applies multiple edits atomically`() {
        val state = testState("foo bar baz")
        val edits = listOf(
            TextEdit(range = 0..2, newText = "FOO"),
            TextEdit(range = 8..10, newText = "BAZ"),
        )

        state.applyTextEdits(edits)
        assertEquals("FOO bar BAZ", state.text)
    }

    @Test
    fun `applyTextEdits undoes as a single batch`() {
        val state = testState("hello world")
        val edits = listOf(
            TextEdit(range = 0..4, newText = "HELLO"),
            TextEdit(range = 6..10, newText = "WORLD"),
        )

        state.applyTextEdits(edits)
        assertEquals("HELLO WORLD", state.text)

        val undone = state.undo()
        assertNotNull(undone)
        assertEquals("hello world", state.text)
    }

    @Test
    fun `applyTextEdits redo reapplies all edits`() {
        val state = testState("val x = 1")
        val edits = listOf(
            TextEdit(range = 4..4, newText = "y"),
            TextEdit(range = 8..8, newText = "2"),
        )

        state.applyTextEdits(edits)
        assertEquals("val y = 2", state.text)

        state.undo()
        assertEquals("val x = 1", state.text)

        state.redo()
        assertEquals("val y = 2", state.text)
    }

    @Test
    fun `applyTextEdits keeps inserts at one offset in array order`() {
        val state = testState("ac")

        // LSP: when several inserts share a position, the array order is the order they appear in.
        state.applyTextEdits(
            listOf(
                TextEdit(range = 1 until 1, newText = "b1"),
                TextEdit(range = 1 until 1, newText = "b2"),
                TextEdit(range = 1 until 1, newText = "b3"),
            ),
        )

        assertEquals("ab1b2b3c", state.text)
    }

    @Test
    fun `applyTextEdits undoes a same-offset insert batch in one step`() {
        val state = testState("ac")
        state.applyTextEdits(
            listOf(
                TextEdit(range = 1 until 1, newText = "b1"),
                TextEdit(range = 1 until 1, newText = "b2"),
            ),
        )

        assertEquals("ab1b2c", state.text)
        assertEquals("ac", state.undo())
    }

    @Test
    fun `applyTextEdits clamps a selection the batch left out of bounds`() {
        val state = testState("value = someLongIdentifier")
        state.selection = TextRange(26)

        // A rename that shortens the tail past the cursor; an unclamped selection would make the
        // TextFieldValue the layout rebuilds afterwards throw.
        state.applyTextEdits(listOf(TextEdit(range = 8..25, newText = "x")))

        assertEquals("value = x", state.text)
        assertEquals(TextRange(9), state.selection)
    }

    @Test
    fun `applyTextEdits leaves an in-bounds selection alone`() {
        val state = testState("foo bar baz")
        state.selection = TextRange(4, 7)

        state.applyTextEdits(listOf(TextEdit(range = 8..10, newText = "BAZ")))

        assertEquals(TextRange(4, 7), state.selection)
    }

    @Test
    fun `applyTextEdits carries the caret past edits made above it`() {
        val state = testState("foo(); foo(); bar")
        state.selection = TextRange(14)

        // Renaming both calls grows the text before the caret; the caret must still sit before "bar",
        // not at offset 14 inside the second renamed identifier.
        state.applyTextEdits(listOf(TextEdit(0..2, "foobarbaz"), TextEdit(7..9, "foobarbaz")))

        assertEquals("foobarbaz(); foobarbaz(); bar", state.text)
        assertEquals(TextRange(26), state.selection)
    }

    @Test
    fun `applyTextEdits snaps a caret inside a replaced range to the end of the replacement`() {
        val state = testState("import x\nfo")
        state.selection = TextRange(11)

        // An auto-import completion: the symbol replaces the token at the caret, the import goes above.
        state.applyTextEdits(listOf(TextEdit(0 until 0, "import a.forEach\n"), TextEdit(9..10, "forEach")))

        assertEquals("import a.forEach\nimport x\nforEach", state.text)
        assertEquals(TextRange(state.text.length), state.selection)
    }

    @Test
    fun `requestRename records the offset and clearRename consumes it`() {
        val state = testState("val value = 1")

        state.requestRename(6)
        assertEquals(CodeEditorState.Rename(6), state.pendingRename)

        state.clearRename()
        assertNull(state.pendingRename)
    }

    @Test
    fun `requestRename defaults to the cursor and clamps out of bounds offsets`() {
        val state = testState("val value = 1")
        state.selection = TextRange(4)

        state.requestRename()
        assertEquals(CodeEditorState.Rename(4), state.pendingRename)

        state.requestRename(999)
        assertEquals(CodeEditorState.Rename(13), state.pendingRename)
    }

    @Test
    fun `a new state starts with the caret at the start, as after loadText`() {
        val state = CodeEditorState(initialText = "hello", scope = CoroutineScope(Dispatchers.Unconfined))
        assertEquals(TextRange(0), state.selection)
    }

    // ── 0.6: selections and Monaco-style undo ──────────────────────────────────

    @Test
    fun `setSelections keeps the primary first and merges overlaps`() {
        val state = testState("abcdefgh")
        state.setSelections(listOf(TextRange(6), TextRange(0, 2), TextRange(1, 3)))
        assertEquals(listOf(TextRange(6), TextRange(0, 3)), state.selections)
        assertEquals(TextRange(6), state.selection)
        state.selection = TextRange(1)
        assertEquals(listOf(TextRange(1)), state.selections)
    }

    @Test
    fun `undo restores the selections from before the edit`() {
        val state = testState("one two")
        state.setSelections(listOf(TextRange(4, 7)))
        state.applyEdit(4, 3, "2", TextRange(5))
        state.undo()
        assertEquals("one two", state.text)
        assertEquals(TextRange(4, 7), state.selection)
        state.redo()
        assertEquals(TextRange(5), state.selection)
    }

    @Test
    fun `canUndo and canRedo follow the history`() {
        val state = testState("x")
        assertFalse(state.canUndo)
        state.applyEdit(1, 0, "y", TextRange(2))
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)
        state.undo()
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)
        state.loadText("new")
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun `alternativeVersionId tells a saved text from a changed one`() {
        val state = testState("saved")
        val saved = state.alternativeVersionId
        state.applyEdit(5, 0, "!", TextRange(6))
        assertTrue(state.alternativeVersionId != saved)
        state.undo()
        assertEquals(saved, state.alternativeVersionId)
    }

    @Test
    fun `lastChangeKind reports edits, undo, redo and flush`() {
        val state = testState("a")
        assertEquals(EditChangeKind.Flush, state.lastChangeKind)
        state.applyEdit(1, 0, "b", TextRange(2))
        assertEquals(EditChangeKind.Edit, state.lastChangeKind)
        state.undo()
        assertEquals(EditChangeKind.Undo, state.lastChangeKind)
        state.redo()
        assertEquals(EditChangeKind.Redo, state.lastChangeKind)
        state.loadText("c")
        assertEquals(EditChangeKind.Flush, state.lastChangeKind)
    }

    @Test
    fun `applyTextEdits carries every selection through the batch`() {
        val state = testState("aaa bbb ccc")
        state.setSelections(listOf(TextRange(9), TextRange(5)))
        state.applyTextEdits(listOf(TextEdit(range = 0 until 0, newText = ">>")))
        assertEquals(listOf(TextRange(11), TextRange(7)), state.selections)
    }

    @Test
    fun `line commands work through the state and undo in one step`() {
        val state = testState("a\nb\nc").apply { tokenizer = SlashCommentTokenizer }
        state.selection = TextRange(2)
        state.toggleComment()
        assertEquals("a\n// b\nc", state.text)
        state.moveLines(up = true)
        assertEquals("// b\na\nc", state.text)
        state.undo()
        assertEquals("a\n// b\nc", state.text)
        state.deleteLines()
        assertEquals("a\nc", state.text)
        state.copyLines(down = true)
        assertEquals("a\nc\nc", state.text)
    }

    @Test
    fun `add next occurrence selects the word, then the next match`() {
        val state = testState("val x = x + x")
        state.selection = TextRange(4)
        assertTrue(state.addNextOccurrence())
        assertEquals(listOf(TextRange(4, 5)), state.selections)
        assertTrue(state.addNextOccurrence())
        assertEquals(TextRange(8, 9), state.selection)
        assertTrue(state.selectAllOccurrences())
        assertEquals(3, state.selections.size)
    }

    private object SlashCommentTokenizer : IncrementalTokenizer {
        override fun tokenizeFull(text: String): List<Token> = emptyList()
        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()
        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
        override val commentSyntax: CommentSyntax = CommentSyntax(line = "//")
    }
}
