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
package com.aardarch.aardink.core.edit

import com.aardarch.aardink.core.TextEdit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinimalEditsTest {

    /** [edits] applied to [text], from the last to the first. */
    private fun apply(text: String, edits: List<TextEdit>): String {
        val builder = StringBuilder(text)
        for (edit in edits.sortedByDescending { it.range.first }) {
            builder.setRange(edit.range.first, edit.range.last + 1, edit.newText)
        }
        return builder.toString()
    }

    @Test
    fun `the same text needs no edit`() {
        assertEquals(emptyList(), MinimalEdits.between("a\nb\n", "a\nb\n"))
    }

    @Test
    fun `one changed character is one one-character edit`() {
        val old = "fun main() {\nprintln(1)\n}\n"
        val new = "fun main() {\nprintln(2)\n}\n"
        assertEquals(listOf(TextEdit(21..21, "2")), MinimalEdits.between(old, new))
    }

    @Test
    fun `changes on separate lines are separate edits, leaving the lines between alone`() {
        val old = "a = 1\nb = 2\nc = 3\nd = 4\n"
        val new = "a  =  1\nb = 2\nc = 3\nd  =  4\n"
        val edits = MinimalEdits.between(old, new)
        assertEquals(2, edits.size)
        assertTrue(edits.all { it.range.last < 6 || it.range.first >= 18 }, "the middle lines are untouched: $edits")
        assertEquals(new, apply(old, edits))
    }

    @Test
    fun `inserted and removed lines`() {
        val old = "one\ntwo\nthree\n"
        assertEquals("one\n1.5\ntwo\nthree\n", apply(old, MinimalEdits.between(old, "one\n1.5\ntwo\nthree\n")))
        assertEquals("one\nthree\n", apply(old, MinimalEdits.between(old, "one\nthree\n")))
        assertEquals("", apply(old, MinimalEdits.between(old, "")))
        assertEquals(old, apply("", MinimalEdits.between("", old)))
    }

    @Test
    fun `a last line without a break`() {
        assertEquals("a\nb", apply("a\nc", MinimalEdits.between("a\nc", "a\nb")))
        assertEquals("a\nb\n", apply("a\nb", MinimalEdits.between("a\nb", "a\nb\n")))
    }

    @Test
    fun `edits always turn the old text into the new one`() {
        val random = Random(42)
        val words = listOf("fun", "val", "{", "}", "x", "y", "  ", "\n", "\n", "(", ")", "=")
        repeat(300) {
            val old = List(random.nextInt(0, 40)) { words.random(random) }.joinToString(" ")
            val new = old.split(' ').map {
                if (random.nextInt(5) ==
                    0
                ) {
                    words.random(random)
                } else {
                    it
                }
            }.joinToString(if (random.nextBoolean()) " " else "  ")
            assertEquals(new, apply(old, MinimalEdits.between(old, new)), "from <$old> to <$new>")
        }
    }

    @Test
    fun `past the line edit limit it is one edit from the first difference to the last`() {
        val old = (0 until MinimalEdits.MAX_LINE_EDITS + 10).joinToString("") { "line $it\n" }
        val new = (0 until MinimalEdits.MAX_LINE_EDITS + 10).joinToString("") { "LINE $it\n" }
        val edits = MinimalEdits.between(old, new)
        assertEquals(1, edits.size)
        assertEquals(new, apply(old, edits))
    }
}
