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

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditingCommandsTest {

    /** Applies [edit] to a document of [text] and returns the text and the selections after. */
    private fun apply(text: String, edit: CommandEdit?): Pair<String, List<TextRange>> {
        val document = CodeDocument(text)
        edit ?: return text to emptyList()
        document.applyChanges(edit.changes)
        return document.text to edit.selectionsAfter.ranges
    }

    private fun carets(vararg offsets: Int) = SelectionSet.of(offsets.map { TextRange(it) })

    @Test
    fun `backspace deletes before every caret and a selection whole`() {
        val text = "abc def"
        val edit = EditingCommands.deleteLeft(text, SelectionSet.of(listOf(TextRange(2), TextRange(4, 7))), word = false)
        assertEquals("ac " to listOf(TextRange(1), TextRange(3)), apply(text, edit))
    }

    @Test
    fun `backspace never splits a surrogate pair`() {
        val text = "a😀b"
        val edit = EditingCommands.deleteLeft(text, carets(3), word = false)
        assertEquals("ab" to listOf(TextRange(1)), apply(text, edit))
    }

    @Test
    fun `word deletes stop at word boundaries, and overlapping ones merge`() {
        val text = "val someName = 1"
        assertEquals("val  = 1" to listOf(TextRange(4)), apply(text, EditingCommands.deleteLeft(text, carets(12), word = true)))
        assertEquals("val  = 1" to listOf(TextRange(4)), apply(text, EditingCommands.deleteRight(text, carets(4), word = true)))
        // Two carets inside one word: both word deletes reach its start, and are joined.
        val merged = EditingCommands.deleteLeft(text, carets(8, 12), word = true)!!
        assertEquals(1, merged.changes.size)
    }

    @Test
    fun `delete at the end of the document does nothing`() {
        assertNull(EditingCommands.deleteRight("abc", carets(3), word = false))
        assertNull(EditingCommands.deleteLeft("abc", carets(0), word = false))
    }

    @Test
    fun `copying carets copies their lines whole`() {
        val document = CodeDocument("one\ntwo\nthree")
        val copied = EditingCommands.copy(document, carets(1, 5, 6))
        assertTrue(copied.wholeLines)
        assertEquals("one\ntwo\n", copied.text)
        val ranges = EditingCommands.copy(document, SelectionSet.of(listOf(TextRange(8, 13), TextRange(0, 3))))
        assertFalse(ranges.wholeLines)
        assertEquals("one\nthree", ranges.text)
    }

    @Test
    fun `cutting carets removes their lines`() {
        val document = CodeDocument("one\ntwo\nthree")
        val (copied, edit) = EditingCommands.cut(document, carets(5))!!
        assertEquals("two\n", copied.text)
        document.applyChanges(edit.changes)
        assertEquals("one\nthree", document.text)
    }

    @Test
    fun `paste replaces every selection and leaves the carets after it`() {
        val document = CodeDocument("a b")
        val edit = EditingCommands.paste(document, SelectionSet.of(listOf(TextRange(0, 1), TextRange(2, 3))), "xy", wholeLines = false)
        assertEquals("xy xy" to listOf(TextRange(2), TextRange(5)), apply("a b", edit))
    }

    @Test
    fun `paste spreads one line per selection when the counts match`() {
        val document = CodeDocument("a\nb\nc")
        val edit = EditingCommands.paste(document, carets(1, 3, 5), "1\n2\n3", wholeLines = false)
        assertEquals("a1\nb2\nc3", apply("a\nb\nc", edit).first)
    }

    @Test
    fun `whole lines paste above the caret's line, and CR LF becomes LF`() {
        val document = CodeDocument("one\ntwo")
        val edit = EditingCommands.paste(document, carets(5), "new\r\n", wholeLines = true)
        assertEquals("one\nnew\ntwo" to listOf(TextRange(9)), apply("one\ntwo", edit))
    }

    @Test
    fun `move extends from the anchors and keeps the primary first`() {
        val moved = EditingCommands.move(SelectionSet.of(listOf(TextRange(5), TextRange(1))), extend = true) { it.end + 1 }
        assertEquals(listOf(TextRange(5, 6), TextRange(1, 2)), moved.ranges)
    }
}
