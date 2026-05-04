package com.aardarch.editor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
