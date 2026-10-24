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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CodeEditorState]'s content and selection events. */
class ChangeEventsTest {

    private fun stateFor(text: String): CodeEditorState = CodeEditorState(
        initialText = text,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply { computeDispatcher = Dispatchers.Unconfined }

    /** [changes] applied in order to [old], as a host mirroring the document would. */
    private fun replay(old: String, changes: List<ContentChange>): String {
        var text = old
        for (change in changes) {
            text = if (change.isReset) {
                change.insertedText
            } else {
                text.substring(0, change.offset) + change.insertedText + text.substring(change.offset + change.deletedLength)
            }
        }
        return text
    }

    /** Records every batch; [check] asserts each batch rebuilds the text it led to. */
    private class Recorder(val state: CodeEditorState) {
        val batches = mutableListOf<List<ContentChange>>()
        var mirror = state.text

        fun check(replay: (String, List<ContentChange>) -> String) {
            assertEquals(state.text, batches.fold(mirror) { text, batch -> replay(text, batch) })
        }

        init {
            state.addContentListener { batches += it }
        }
    }

    @Test
    fun `typing reports an insert with its position and text`() {
        val state = stateFor("ab\ncd")
        val batches = mutableListOf<List<ContentChange>>()
        state.addContentListener { batches += it }
        state.selection = TextRange(4)
        state.applyInput(4, 4, "X")
        val change = batches.single().single()
        assertEquals(ContentChange(4, 0, "X", 1, 1, 1, 1, change.version, EditChangeKind.Edit), change)
        assertTrue(change.version > 0)
        assertEquals(1, state.textVersion)
    }

    @Test
    fun `a replacement across lines reports the old end of what it replaced`() {
        val state = stateFor("ab\ncd\nef")
        val batches = mutableListOf<List<ContentChange>>()
        state.addContentListener { batches += it }
        state.applyEdit(1, 5, "X", TextRange(2))
        val changes = batches.single()
        val delete = changes.first { it.deletedLength > 0 }
        assertEquals(1, delete.offset)
        assertEquals(5, delete.deletedLength)
        assertEquals(0 to 1, delete.startLine to delete.startColumn)
        assertEquals(2 to 0, delete.oldEndLine to delete.oldEndColumn)
        assertEquals("aXef", replay("ab\ncd\nef", changes))
    }

    @Test
    fun `every kind of edit replays to the new text`() {
        val state = stateFor("one\ntwo\nthree")
        val recorder = Recorder(state)
        state.setSelections(listOf(TextRange(3), TextRange(7)))
        state.applyInput(3, 3, "!")
        state.applyTextEdits(listOf(TextEdit(0..2, "ONE"), TextEdit(9..12, "THREE")))
        state.executeEdits(listOf(TextEdit(0 until 0, "// head\n")))
        state.selection = TextRange(0)
        state.paste("a\nb\n")
        state.deleteLines()
        state.undo()
        state.undo()
        state.redo()
        recorder.check(::replay)
        assertEquals(8, recorder.batches.size)
    }

    @Test
    fun `undo, redo and a new text say what they are`() {
        val state = stateFor("a")
        val kinds = mutableListOf<EditChangeKind>()
        state.addContentListener { batch -> kinds += batch.map { it.kind }.distinct().single() }
        state.applyInput(1, 1, "b")
        state.undo()
        state.redo()
        state.loadText("new\ntext")
        assertEquals(listOf(EditChangeKind.Edit, EditChangeKind.Undo, EditChangeKind.Redo, EditChangeKind.Flush), kinds)
    }

    @Test
    fun `a new text is one reset change describing the whole old text`() {
        val state = stateFor("old\nline two")
        val batches = mutableListOf<List<ContentChange>>()
        state.addContentListener { batches += it }
        state.loadText("new")
        val reset = batches.single().single()
        assertTrue(reset.isReset)
        assertEquals("new", reset.insertedText)
        assertEquals(12, reset.deletedLength)
        assertEquals(1 to 8, reset.oldEndLine to reset.oldEndColumn)
    }

    @Test
    fun `a removed listener is not called, and an edit with nothing to change reports nothing`() {
        val state = stateFor("abc")
        var calls = 0
        val listener: (List<ContentChange>) -> Unit = { calls++ }
        state.addContentListener(listener)
        state.executeEdits(listOf(TextEdit(0..2, "abc")))
        assertEquals(0, calls)
        state.applyEdit(0, 0, "x", TextRange(1))
        state.removeContentListener(listener)
        state.applyEdit(0, 0, "y", TextRange(1))
        assertEquals(1, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `contentChanges is a flow of the same batches`() = runTest(UnconfinedTestDispatcher()) {
        val state = stateFor("")
        val collected = mutableListOf<List<ContentChange>>()
        val job = launch { state.contentChanges().take(2).toList(collected) }
        state.applyEdit(0, 0, "a", TextRange(1))
        state.applyEdit(1, 0, "b", TextRange(2))
        job.join()
        assertEquals(listOf("a", "b"), collected.map { it.single().insertedText })
    }

    @Test
    fun `selection changes say why`() {
        val state = stateFor("hello world")
        val events = mutableListOf<Pair<List<TextRange>, SelectionChangeReason>>()
        state.addSelectionListener { ranges, reason -> events += ranges to reason }
        state.selection = TextRange(5)
        state.applyInput(5, 5, "!")
        state.undo()
        state.withSelectionReason(SelectionChangeReason.Keyboard) { state.moveSelections(extend = false) { 0 } }
        state.selection = TextRange(0)
        assertEquals(
            listOf(
                listOf(TextRange(5)) to SelectionChangeReason.Api,
                listOf(TextRange(6)) to SelectionChangeReason.Edit,
                listOf(TextRange(5)) to SelectionChangeReason.Undo,
                listOf(TextRange(0)) to SelectionChangeReason.Keyboard,
            ),
            events,
            "setting the selection it already has reports nothing",
        )
    }

    @Test
    fun `a snapshot is public, read-only and stays as it was`() {
        val state = stateFor("abc")
        val snapshot = state.document.snapshot()
        state.applyEdit(0, 0, "x", TextRange(1))
        assertEquals("abc", snapshot.text)
        assertEquals(snapshot, snapshot.snapshot())
        kotlin.test.assertFailsWith<IllegalStateException> { snapshot.insert(0, "y") }
    }
}
