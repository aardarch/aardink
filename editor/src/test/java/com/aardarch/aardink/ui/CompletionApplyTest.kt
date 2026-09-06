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
package com.aardarch.aardink.ui

import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompletionApplyTest {

    private fun item(insertText: String, replaceRange: IntRange? = null) = CompletionItem(
        label = insertText,
        kind = CompletionKind.Value,
        insertText = insertText,
        replaceRange = replaceRange,
    )

    @Test
    fun `without a provided range the token before the cursor is replaced`() {
        val text = "val v = fo"
        assertEquals(8 until 10, completionReplaceRange(text, 10, item("forEach")))
    }

    @Test
    fun `a provided range is used verbatim even when it disagrees with the token scan`() {
        val text = "val v = ns:fo"
        // The token scan would stop at ':'; the server means the whole qualified name.
        assertEquals(8 until 13, completionReplaceRange(text, 13, item("ns:forEach", 8 until 13)))
    }

    @Test
    fun `a provided range may extend past the cursor`() {
        val text = "val v = foBar"
        assertEquals(8 until 13, completionReplaceRange(text, 10, item("forEach", 8 until 13)))
    }

    @Test
    fun `an empty provided range inserts without deleting`() {
        val text = "x."
        val range = completionReplaceRange(text, 2, item("size", IntRange.EMPTY))
        assertEquals(0, range.last - range.first + 1)
    }

    @Test
    fun `an out of bounds provided range is clamped to the text`() {
        val text = "abc"
        assertEquals(3 until 3, completionReplaceRange(text, 3, item("x", 5 until 9)))
        assertEquals(0 until 3, completionReplaceRange(text, 3, item("x", -4 until 99)))
    }

    @Test
    fun `a boundary character immediately before the cursor replaces nothing`() {
        val text = "value = "
        assertEquals(8 until 8, completionReplaceRange(text, 8, item("true")))
    }

    @Test
    fun `a member access dot is a boundary so the receiver survives`() {
        val text = "val v = list."
        assertEquals(13 until 13, completionReplaceRange(text, 13, item("map { }")), "nothing to replace after the dot")

        val partial = "val v = list.ma"
        assertEquals(13 until 15, completionReplaceRange(partial, 15, item("map { }")), "only the partial member name")
    }

    @Test
    fun `structural characters are boundaries`() {
        assertEquals(4 until 6, completionReplaceRange("foo(ba", 6, item("bar")))
        assertEquals(7 until 9, completionReplaceRange("foo(a, ba", 9, item("bar")))
        assertEquals(5 until 7, completionReplaceRange("list[ba", 7, item("bar")))
    }

    // A list computed for "val v = fo" is still showing when the user types "r": the item's
    // ranges are addressed to the older text and must follow the keystroke.

    @Test
    fun `a character typed at the end of the replaced token joins it`() {
        val shifted = item("forEach", 8 until 10).shiftedForInsert(at = 10, length = 1, absorbing = true)
        assertEquals(8 until 11, shifted.replaceRange)
    }

    @Test
    fun `an auto-closed character after the cursor is not swallowed`() {
        val shifted = item("forEach", 8 until 10).shiftedForInsert(at = 10, length = 1, absorbing = false)
        assertEquals(8 until 10, shifted.replaceRange)
        val atCursor = item("size", 10 until 10).shiftedForInsert(at = 10, length = 1, absorbing = false)
        assertEquals(10 until 10, atCursor.replaceRange)
    }

    @Test
    fun `an insertion before a range moves the whole range`() {
        val shifted = item("forEach", 8 until 10).shiftedForInsert(at = 3, length = 2, absorbing = true)
        assertEquals(10 until 12, shifted.replaceRange)
    }

    @Test
    fun `additional edits above the keystroke stay put and never absorb it`() {
        val importEdit = TextEdit(0 until 0, "import a.forEach\n")
        val withImport = item("forEach", 8 until 10).copy(additionalEdits = listOf(importEdit))

        val typedBelow = withImport.shiftedForInsert(at = 10, length = 1, absorbing = true)
        assertEquals(listOf(importEdit), typedBelow.additionalEdits)

        // Typing exactly at the import's insertion point must not fold the keystroke into it.
        val typedAtImport = withImport.shiftedForInsert(at = 0, length = 1, absorbing = true)
        assertEquals(listOf(importEdit), typedAtImport.additionalEdits)
        assertEquals(9 until 11, typedAtImport.replaceRange)
    }
}
