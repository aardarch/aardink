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
import kotlin.test.assertNull

/** Find in a scope, preserve case and the search history, below the panel. */
class FindExtrasTest {

    @Test
    fun `a scoped search finds only matches that lie wholly inside it`() = runTest {
        val text = "ab ab ab ab"
        val found = FindEngine.findAllCooperatively(text, "ab", FindEngine.Options(), limit = 100, scope = 2..6)
        assertEquals(listOf(3..4), found.matches, "the match at 6 ends past the scope")
        val wider = FindEngine.findAllCooperatively(text, "ab", FindEngine.Options(), limit = 100, scope = 0..10)
        assertEquals(4, wider.matches.size)
    }

    @Test
    fun `a scoped regex sees the text around the scope`() = runTest {
        val found = FindEngine.findAllCooperatively("xab ab", """\bab""", FindEngine.Options(useRegex = true), limit = 100, scope = 1..5)
        assertEquals(listOf(4..5), found.matches)
    }

    @Test
    fun `preserve case follows the matched text's case`() {
        assertEquals("BAR", FindEngine.withCaseOf("FOO", "bar"))
        assertEquals("bar", FindEngine.withCaseOf("foo", "BaR"))
        assertEquals("Bar", FindEngine.withCaseOf("Foo", "bar"))
        assertEquals("bAR", FindEngine.withCaseOf("fOO", "BAR"))
        assertEquals("Bar-Baz", FindEngine.withCaseOf("Foo-Qux", "bar-baz"), "part by part")
        assertEquals("bar_BAZ", FindEngine.withCaseOf("foo_QUX", "Bar_baz"))
        assertEquals("Bar-baz-qux", FindEngine.withCaseOf("Foo-Qux", "bar-baz-qux"), "parts that differ in number: as a whole")
        assertEquals("bar", FindEngine.withCaseOf("123", "bar"), "no letters: as written")
    }

    @Test
    fun `preserve case applies to plain and regex replacements`() {
        val plain = FindEngine.replaceEdits("Foo foo", listOf(0..2, 4..6), "foo", "bar", FindEngine.Options(), preserveCase = true)
        assertEquals(listOf(TextEdit(0..2, "Bar"), TextEdit(4..6, "bar")), plain)
        val regex = FindEngine.replaceEdits(
            "FOO",
            listOf(0..2),
            "f(o+)",
            "b$1r",
            FindEngine.Options(useRegex = true),
            preserveCase = true,
        )
        assertEquals(listOf(TextEdit(0..2, "BOOR")), regex)
    }

    @Test
    fun `the history keeps each entry once, newest first, and gives back what was typed`() {
        val history = SearchHistory()
        history.add("one")
        history.add("two")
        history.add("one")
        history.add("")
        assertEquals(listOf("one", "two"), history.items)

        assertEquals("two", history.older("one"), "the newest is what the field holds, so it is skipped")
        assertNull(history.older("two"))
        assertEquals("one", history.newer())
    }

    @Test
    fun `stepping back past the newest gives back the draft`() {
        val history = SearchHistory()
        history.add("one")
        history.add("two")
        assertEquals("two", history.older("dra"))
        assertEquals("one", history.older("two"))
        assertEquals("two", history.newer())
        assertEquals("dra", history.newer())
        assertNull(history.newer())
    }

    @Test
    fun `the history keeps at most fifty`() {
        val history = SearchHistory()
        repeat(60) { history.add("q$it") }
        assertEquals(SearchHistory.MAX, history.items.size)
        assertEquals("q59", history.items.first())
    }
}
