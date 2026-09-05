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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
}
