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

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecorationTest {

    // "one two\nthree": "two" is 4..6, "three" is 8..12.
    private fun state() = CodeEditorState("one two\nthree", scope = CoroutineScope(Dispatchers.Unconfined))

    private val red = DecorationStyle(background = Color.Red, gutterColor = Color.Red)
    private val blue = DecorationStyle(underline = Color.Blue, minimapColor = Color.Blue)

    @Test
    fun `each owner's decorations are its own`() {
        val state = state()
        state.setDecorations("assistant", listOf(Decoration(8..12, red)))
        state.setDecorations("preview", listOf(Decoration(4..6, blue)))
        assertEquals(listOf(8..12), state.decorations("assistant").map { it.range })
        // Drawn in order of where they start, whoever set them.
        assertEquals(listOf(4..6, 8..12), state.shownDecorations.map { it.range })
        state.setDecorations("assistant", listOf(Decoration(0..2, red)))
        assertEquals(listOf(0..2), state.decorations("assistant").map { it.range })
        state.clearDecorations("preview")
        assertTrue(state.decorations("preview").isEmpty())
        assertEquals(listOf(0..2), state.shownDecorations.map { it.range })
    }

    @Test
    fun `decorations follow the edits, and a deleted one collapses where it was`() {
        val state = state()
        state.setDecorations("host", listOf(Decoration(4..6, red), Decoration(8..12, blue)))
        state.document.insert(0, ">> ")
        assertEquals(listOf(7..9, 11..15), state.decorations("host").map { it.range })
        state.document.delete(7, 3)
        val collapsed = state.decorations("host").first().range
        assertTrue(collapsed.isEmpty())
        assertEquals(7, collapsed.first)
    }

    @Test
    fun `ranges past the end are clamped, and an empty list clears`() {
        val state = state()
        state.setDecorations("host", listOf(Decoration(10..99, red), Decoration(50 until 50, blue)))
        assertEquals(listOf(10..12, 13 until 13), state.decorations("host").map { it.range })
        state.setDecorations("host", emptyList())
        assertTrue(state.shownDecorations.isEmpty())
    }

    @Test
    fun `replacing the text drops them`() {
        val state = state()
        state.setDecorations("host", listOf(Decoration(4..6, red)))
        state.loadText("new")
        assertTrue(state.decorations("host").isEmpty())
    }

    @Test
    fun `line marks cover the lines a decoration touches, for the colour asked`() {
        val document = CodeDocument("a\nbb\nccc\n")
        val decorations = listOf(Decoration(0..3, red), Decoration(5..7, blue), Decoration(9 until 9, red))
        val gutter = lineMarks(decorations, document) { it.gutterColor }
        assertEquals(listOf(0 to 1, 3 to 3), gutter.map { it.firstLine to it.lastLine })
        val minimap = lineMarks(decorations, document) { it.minimapColor }
        assertEquals(listOf(2 to 2), minimap.map { it.firstLine to it.lastLine })
    }
}
