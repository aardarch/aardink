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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorUndoManagerTest {

    @Test
    fun `fresh manager has nothing to undo or redo`() {
        val mgr = EditorUndoManager()
        assertFalse(mgr.canUndo)
        assertFalse(mgr.canRedo)
    }

    @Test
    fun `single insert can be undone`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(0, "hello")
        mgr.flushPendingInsert()
        assertTrue(mgr.canUndo)
        assertFalse(mgr.canRedo)
    }

    @Test
    fun `undo returns inverse insert operation`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(5, "world")
        mgr.flushPendingInsert()
        val op = mgr.undo()
        assertNotNull(op)
        val inverse = mgr.inverseOf(op!!)
        assertTrue(inverse is EditorUndoManager.EditOperation.Delete)
        val del = inverse as EditorUndoManager.EditOperation.Delete
        assertEquals(5, del.offset)
        assertEquals(5, del.length)
        assertEquals("world", del.deletedText)
    }

    @Test
    fun `undo enables redo`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(0, "abc")
        mgr.flushPendingInsert()
        mgr.undo()
        assertTrue(mgr.canRedo)
        assertFalse(mgr.canUndo)
    }

    @Test
    fun `redo re-applies the operation`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(0, "hi")
        mgr.flushPendingInsert()
        mgr.undo()
        val op = mgr.redo()
        assertNotNull(op)
        assertTrue(op is EditorUndoManager.EditOperation.Insert)
        assertEquals("hi", (op as EditorUndoManager.EditOperation.Insert).text)
    }

    @Test
    fun `consecutive single-char inserts are batched`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(0, "h")
        mgr.recordInsert(1, "e")
        mgr.recordInsert(2, "l")
        mgr.recordInsert(3, "l")
        mgr.recordInsert(4, "o")
        mgr.flushPendingInsert()
        // Should produce one Insert("hello") not five separate inserts
        val op = mgr.undo()
        assertNotNull(op)
        assertTrue(op is EditorUndoManager.EditOperation.Insert)
        assertEquals("hello", (op as EditorUndoManager.EditOperation.Insert).text)
    }

    @Test
    fun `non-contiguous inserts are not batched`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(0, "a")
        mgr.recordInsert(5, "b") // gap in offset — not contiguous
        mgr.flushPendingInsert()
        // "b" insert: one undo
        assertNotNull(mgr.undo())
        // "a" insert: another undo
        assertNotNull(mgr.undo())
        assertFalse(mgr.canUndo)
    }

    @Test
    fun `new edit clears redo stack`() {
        val mgr = EditorUndoManager()
        mgr.recordInsert(0, "foo")
        mgr.flushPendingInsert()
        mgr.undo()
        assertTrue(mgr.canRedo)
        mgr.recordInsert(0, "bar")
        mgr.flushPendingInsert()
        assertFalse(mgr.canRedo)
    }

    @Test
    fun `undo beyond empty returns null`() {
        val mgr = EditorUndoManager()
        assertNull(mgr.undo())
        assertNull(mgr.undo())
    }

    @Test
    fun `inverse of delete is insert`() {
        val mgr = EditorUndoManager()
        val del = EditorUndoManager.EditOperation.Delete(3, 4, "test")
        val inv = mgr.inverseOf(del)
        assertTrue(inv is EditorUndoManager.EditOperation.Insert)
        val ins = inv as EditorUndoManager.EditOperation.Insert
        assertEquals(3, ins.offset)
        assertEquals("test", ins.text)
    }

    @Test
    fun `batch inverse reverses order`() {
        val mgr = EditorUndoManager()
        val batch = EditorUndoManager.EditOperation.Batch(
            listOf(
                EditorUndoManager.EditOperation.Insert(0, "a"),
                EditorUndoManager.EditOperation.Insert(1, "b"),
            ),
        )
        val inv = mgr.inverseOf(batch) as EditorUndoManager.EditOperation.Batch
        assertEquals(2, inv.operations.size)
        // First inverse op should be for "b" (reversed order)
        val firstInv = inv.operations[0] as EditorUndoManager.EditOperation.Delete
        assertEquals("b", firstInv.deletedText)
    }

    @Test
    fun `max operations limit evicts oldest entries`() {
        val mgr = EditorUndoManager(maxOperations = 3)
        repeat(5) { i ->
            mgr.recordInsert(i * 2, "ab")
            mgr.flushPendingInsert()
        }
        // Only 3 most recent should be retained
        var undoCount = 0
        while (mgr.undo() != null) undoCount++
        assertEquals(3, undoCount)
    }
}
