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

class CodeEditorStateTest {

    private fun testState(text: String): CodeEditorState = CodeEditorState(
        initialText = text,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
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

    // ---- textFieldState mirroring -------------------------------------------------------
    //
    // CodeDocument stays the canonical text and undo model; textFieldState is what the
    // BasicTextField renders and receives IME input into. If the two drift, the editor shows
    // something other than what it will save. PR 1 introduced the field and asserted nothing
    // about it -- no test in the module so much as mentioned textFieldState.

    @Test
    fun `textFieldState tracks the document through applyEdit`() {
        val state = testState("hello")
        state.applyEdit(5, 0, " world", TextRange(11))

        assertEquals(state.document.text, state.textFieldState.text.toString())
        assertEquals("hello world", state.textFieldState.text.toString())
    }

    @Test
    fun `textFieldState tracks the document through applyTextEdits`() {
        val state = testState("foo bar baz")
        state.applyTextEdits(
            listOf(
                TextEdit(range = 0..2, newText = "FOO"),
                TextEdit(range = 8..10, newText = "BAZ"),
            ),
        )

        assertEquals(state.document.text, state.textFieldState.text.toString())
    }

    @Test
    fun `textFieldState tracks the document through undo and redo`() {
        val state = testState("hello")
        state.applyEdit(5, 0, " world", TextRange(11))
        assertEquals(state.document.text, state.textFieldState.text.toString())

        state.undo()
        assertEquals("hello", state.document.text)
        assertEquals(state.document.text, state.textFieldState.text.toString())

        state.redo()
        assertEquals("hello world", state.document.text)
        assertEquals(state.document.text, state.textFieldState.text.toString())
    }

    @Test
    fun `textFieldState tracks the document through loadText`() {
        val state = testState("old content")
        state.loadText("entirely new content")

        assertEquals("entirely new content", state.document.text)
        assertEquals(state.document.text, state.textFieldState.text.toString())
    }

    @Test
    fun `loadText clears the field's own undo history`() {
        // Ctrl+Z is intercepted and routed through EditorUndoManager, so the field's built-in
        // stack is never used -- but if loadText left it populated, a platform-level undo
        // gesture could resurrect text from a previously loaded document.
        val state = testState("first document")
        state.applyEdit(5, 0, "X", TextRange(6))
        state.loadText("second document")

        assertFalse(
            state.textFieldState.undoState.canUndo,
            "loadText must clear the field's undo history",
        )
    }

    @Test
    fun `selection reads through to the field`() {
        val state = testState("hello world")
        state.applyTextEdits(listOf(TextEdit(range = 0..4, newText = "HELLO")))

        assertEquals(state.textFieldState.selection, state.selection)
    }
}
