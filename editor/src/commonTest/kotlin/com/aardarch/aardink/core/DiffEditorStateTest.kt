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

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffEditorStateTest {

    private suspend fun diff(original: String, modified: String): DiffEditorState =
        DiffEditorState(CodeEditorState(original), CodeEditorState(modified)).also { it.recompute(TextDiffOptions()) }

    @Test
    fun `a state has no changes until a diff is worked out, then those of the two texts`() = runTest {
        val state = DiffEditorState(CodeEditorState("a\nb\nc"), CodeEditorState("a\nB\nc\nd"))
        assertTrue(state.changes.isEmpty())
        state.recompute(TextDiffOptions())
        assertEquals(listOf(1 until 2, 3 until 4), state.changes.map { it.modifiedLines })
        assertEquals(listOf(1 until 2, 3 until 3), state.changes.map { it.originalLines })
        assertFalse(state.hitTimeout)
    }

    @Test
    fun `a diff listener is told of each diff until it is removed`() = runTest {
        val state = DiffEditorState(CodeEditorState("a\nb"), CodeEditorState("a\nc"))
        val told = mutableListOf<TextDiffResult>()
        val listener: (TextDiffResult) -> Unit = { told += it }
        state.addDiffListener(listener)
        state.recompute(TextDiffOptions())
        assertEquals(1, told.size)
        assertEquals(state.changes, told.single().changes)
        state.modified.loadText("a\nb")
        state.recompute(TextDiffOptions())
        assertEquals(2, told.size)
        assertTrue(told.last().identical)
        state.removeDiffListener(listener)
        state.recompute(TextDiffOptions())
        assertEquals(2, told.size)
    }

    @Test
    fun `changeAt finds the change a line is in, on either side`() = runTest {
        // Line 1 is modified, original line 3 is deleted, and modified line 4 is inserted.
        val state = diff("a\nb\nc\nd\ne", "a\nB\nc\ne\nf")
        val modified = state.changeAt(1)
        assertNotNull(modified)
        assertEquals(1 until 2, modified.originalLines)
        assertNull(state.changeAt(0))
        assertNull(state.changeAt(2))
        assertEquals(modified, state.changeAt(1, modifiedSide = false))
        val deleted = state.changeAt(3, modifiedSide = false)
        assertNotNull(deleted)
        assertTrue(deleted.modifiedLines.isEmpty())
        // A deletion has no modified lines: it is at the line after them.
        assertEquals(deleted, state.changeAt(deleted.modifiedLines.first))
    }

    @Test
    fun `revert puts the original's lines back as one undo step`() = runTest {
        val state = diff("a\nb\nc\nd", "a\nB1\nB2\nc\nd")
        val before = state.modified.text
        state.revert(state.changes.single())
        assertEquals("a\nb\nc\nd", state.modified.text)
        assertEquals(before, state.modified.undo())
        assertEquals(before, state.modified.text)
        assertFalse(state.modified.canUndo)
    }

    @Test
    fun `reverting an insertion takes its lines away, wherever they are`() = runTest {
        val inMiddle = diff("a\nc", "a\nb\nc")
        inMiddle.revert(inMiddle.changes.single())
        assertEquals("a\nc", inMiddle.modified.text)

        val atEnd = diff("a\nb", "a\nb\nc\nd")
        atEnd.revert(atEnd.changes.single())
        assertEquals("a\nb", atEnd.modified.text)

        val atStart = diff("c", "a\nb\nc")
        atStart.revert(atStart.changes.single())
        assertEquals("c", atStart.modified.text)

        val everything = diff("", "a\nb")
        everything.revert(everything.changes.single())
        assertEquals("", everything.modified.text)
    }

    @Test
    fun `reverting a deletion puts the lines back, wherever they were`() = runTest {
        val inMiddle = diff("a\nb\nc", "a\nc")
        inMiddle.revert(inMiddle.changes.single())
        assertEquals("a\nb\nc", inMiddle.modified.text)

        val atEnd = diff("a\nb\nc", "a")
        atEnd.revert(atEnd.changes.single())
        assertEquals("a\nb\nc", atEnd.modified.text)

        val atStart = diff("a\nb\nc", "c")
        atStart.revert(atStart.changes.single())
        assertEquals("a\nb\nc", atStart.modified.text)
    }

    @Test
    fun `reverting every change gives back the original`() = runTest {
        val original = (0 until 30).joinToString("\n") { "line $it" }
        val modified = original.lines().mapIndexedNotNull { i, line ->
            when {
                i % 7 == 2 -> null
                i % 5 == 1 -> "$line changed\nand one more"
                else -> line
            }
        }.joinToString("\n")
        val state = diff(original, modified)
        assertTrue(state.changes.size > 3)
        // From the last change up, so that the lines of the earlier ones stay where the diff has them.
        for (change in state.changes.asReversed()) {
            state.revert(change)
            assertFalse(state.isCurrent)
            state.recompute(TextDiffOptions())
        }
        state.recompute(TextDiffOptions())
        assertEquals(original, state.modified.text)
    }

    @Test
    fun `accept puts the modified lines into the original as one undo step on the original`() = runTest {
        val state = diff("a\nb\nc", "a\nB\nc\nd")
        val before = state.original.text
        state.accept(state.changes.first())
        assertEquals("a\nB\nc", state.original.text)
        assertEquals("a\nB\nc\nd", state.modified.text)
        assertFalse(state.modified.canUndo)
        assertEquals(before, state.original.undo())
    }

    @Test
    fun `revert and accept do nothing on a read-only side or once the text has moved on`() = runTest {
        val state = diff("a\nb", "a\nc")
        val change = state.changes.single()
        state.modified.readOnly = true
        state.revert(change)
        assertEquals("a\nc", state.modified.text)
        state.modified.readOnly = false
        state.original.readOnly = true
        state.accept(change)
        assertEquals("a\nb", state.original.text)
        state.original.readOnly = false

        state.modified.loadText("x\na\nc")
        state.revert(change)
        assertEquals("x\na\nc", state.modified.text)
    }

    @Test
    fun `an answer for text that was edited meanwhile is dropped`() = runTest {
        val state = DiffEditorState(CodeEditorState("a\nb"), CodeEditorState("a\nc"))
        state.recompute(TextDiffOptions())
        val first = state.changes
        state.modified.loadText("a\nb")
        assertFalse(state.isCurrent)
        assertEquals(first, state.changes)
        state.recompute(TextDiffOptions())
        assertTrue(state.isCurrent)
        assertTrue(state.changes.isEmpty())
    }
}
