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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackedRangesTest {

    private data class Mark(val name: String, val range: IntRange)

    // "one two three": "two" is 4..6, "three" is 8..12.
    private fun tracked(keepsOneCharacter: Boolean): Pair<CodeDocument, TrackedRanges<Mark>> {
        val document = CodeDocument("one two three")
        val ranges = TrackedRanges<Mark>({ it.range }, { mark, range -> mark.copy(range = range) }, keepsOneCharacter)
        document.addChangeListener(ranges)
        ranges.replace(listOf(Mark("two", 4..6), Mark("three", 8..12)))
        return document to ranges
    }

    private fun TrackedRanges<Mark>.texts(document: CodeDocument): List<String> =
        items.map { document.text.substring(it.range.first, it.range.last + 1) }

    @Test
    fun `an insertion before the ranges moves them along`() {
        val (document, ranges) = tracked(keepsOneCharacter = false)
        document.insert(0, ">> ")
        assertEquals(listOf("two", "three"), ranges.texts(document))
    }

    @Test
    fun `an insertion inside a range grows it and one at its end does not`() {
        val (document, ranges) = tracked(keepsOneCharacter = false)
        document.insert(5, "w")
        assertEquals("twwo", ranges.texts(document)[0])
        document.insert(8, "!")
        assertEquals("twwo", ranges.texts(document)[0])
    }

    @Test
    fun `a range whose text is deleted collapses or keeps one character`() {
        val (collapsing, collapsed) = tracked(keepsOneCharacter = false)
        collapsing.delete(4, 3)
        assertTrue(collapsed.items[0].range.isEmpty())
        assertEquals(4, collapsed.items[0].range.first)

        val (keeping, kept) = tracked(keepsOneCharacter = true)
        keeping.delete(4, 3)
        assertEquals(4..4, kept.items[0].range)
    }

    @Test
    fun `an edit after every range leaves the list itself unchanged`() {
        val (document, ranges) = tracked(keepsOneCharacter = false)
        val before = ranges.items
        document.insert(document.length, " four")
        assertSame(before, ranges.items)
        assertSame(before, ranges.given)
    }

    @Test
    fun `replacing the whole text drops the ranges`() {
        val (document, ranges) = tracked(keepsOneCharacter = false)
        document.replaceAll("something else")
        assertTrue(ranges.items.isEmpty())
    }
}
