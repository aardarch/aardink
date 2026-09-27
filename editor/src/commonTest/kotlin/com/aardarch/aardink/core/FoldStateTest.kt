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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldStateTest {

    @Test
    fun `toggle folds and unfolds a foldable line`() {
        val state = FoldState()
        state.updateFoldableRanges(listOf(FoldRange(0, 5)))

        assertTrue(state.isFoldable(0))
        assertFalse(state.isFolded(0))

        state.toggle(0)
        assertTrue(state.isFolded(0))

        state.toggle(0)
        assertFalse(state.isFolded(0))
    }

    @Test
    fun `toggle on non-foldable line is a no-op`() {
        val state = FoldState()
        state.updateFoldableRanges(listOf(FoldRange(0, 5)))

        state.toggle(99)
        assertFalse(state.isFolded(99))
        assertEquals(0, state.foldedCount)
    }

    @Test
    fun `updateFoldableRanges prunes folded entries that no longer exist`() {
        val state = FoldState()
        state.updateFoldableRanges(listOf(FoldRange(0, 5), FoldRange(10, 15)))
        state.toggle(0)
        state.toggle(10)
        assertEquals(2, state.foldedCount)

        // The first range disappears (e.g. user deleted the opening tag)
        state.updateFoldableRanges(listOf(FoldRange(10, 15)))

        assertFalse(state.isFolded(0))
        assertTrue(state.isFolded(10))
        assertEquals(1, state.foldedCount)
    }

    @Test
    fun `foldAll folds every foldable range`() {
        val state = FoldState()
        state.updateFoldableRanges(listOf(FoldRange(0, 5), FoldRange(10, 15), FoldRange(20, 25)))

        state.foldAll()
        assertEquals(3, state.foldedCount)
        assertTrue(state.isFolded(0))
        assertTrue(state.isFolded(10))
        assertTrue(state.isFolded(20))
    }

    @Test
    fun `unfoldAll clears all folds`() {
        val state = FoldState()
        state.updateFoldableRanges(listOf(FoldRange(0, 5), FoldRange(10, 15)))
        state.foldAll()

        state.unfoldAll()
        assertEquals(0, state.foldedCount)
    }

    @Test
    fun `folds follow lines added or removed above and inside them`() {
        val state = FoldState()
        state.updateFoldableRanges(listOf(FoldRange(2, 5), FoldRange(10, 15)))
        state.toggle(10)
        // Two lines inserted after line 0: both ranges move down.
        state.onLinesChanged(line = 0, removedLines = 0, addedLines = 2)
        assertEquals(listOf(FoldRange(4, 7), FoldRange(12, 17)), state.foldableRanges)
        assertTrue(state.isFolded(12))
        // A line added inside the first range grows it.
        state.onLinesChanged(line = 5, removedLines = 0, addedLines = 1)
        assertEquals(FoldRange(4, 8), state.foldableRanges[0])
        // The folded range's first line merged into the line above: it is gone.
        state.onLinesChanged(line = 12, removedLines = 1, addedLines = 0)
        assertEquals(0, state.foldedCount)
    }
}
