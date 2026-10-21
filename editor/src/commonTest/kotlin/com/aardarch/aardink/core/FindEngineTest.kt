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
import kotlin.test.assertTrue

class FindEngineTest {

    @Test
    fun `findAll returns empty for empty query`() {
        val matches = FindEngine.findAll("hello world", "")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `findAll finds case-insensitive matches by default`() {
        val matches = FindEngine.findAll("Hello hello HELLO", "hello")
        assertEquals(3, matches.size)
        assertEquals(0..4, matches[0])
        assertEquals(6..10, matches[1])
        assertEquals(12..16, matches[2])
    }

    @Test
    fun `findAll respects case sensitivity`() {
        val matches = FindEngine.findAll(
            "Hello hello HELLO",
            "hello",
            FindEngine.Options(caseSensitive = true),
        )
        assertEquals(1, matches.size)
        assertEquals(6..10, matches[0])
    }

    @Test
    fun `findAll respects whole-word option`() {
        val matches = FindEngine.findAll(
            "cat catalog cats cat.",
            "cat",
            FindEngine.Options(wholeWord = true),
        )
        assertEquals(2, matches.size) // "cat" at 0, "cat" before .
        assertEquals(0..2, matches[0])
        assertEquals(17..19, matches[1])
    }

    @Test
    fun `findAll uses regex when enabled`() {
        val matches = FindEngine.findAll(
            "abc 123 def 456",
            """\d+""",
            FindEngine.Options(useRegex = true),
        )
        assertEquals(2, matches.size)
    }

    @Test
    fun `findAll returns empty for invalid regex`() {
        val matches = FindEngine.findAll(
            "anything",
            "[unclosed",
            FindEngine.Options(useRegex = true),
        )
        assertTrue(matches.isEmpty())
    }

    private val regex = FindEngine.Options(useRegex = true)

    /** Replaces every match of [query] in [text] as Replace All does. */
    private fun replaceAll(text: String, query: String, replacement: String, options: FindEngine.Options = regex): String {
        val edits = FindEngine.replaceEdits(text, FindEngine.findAll(text, query, options), query, replacement, options)
        var out = text
        for (edit in edits.sortedByDescending { it.range.first }) {
            out = out.substring(0, edit.range.first) + edit.newText + out.substring(edit.range.last + 1)
        }
        return out
    }

    @Test
    fun `regex replacement expands numbered groups`() {
        assertEquals("b=a, d=c", replaceAll("a=b, c=d", """(\w)=(\w)""", "$2=$1"))
    }

    @Test
    fun `regex replacement expands the whole match`() {
        assertEquals("[ab] [cd]", replaceAll("ab cd", """\w+""", "[$0]"))
        assertEquals("[ab] [cd]", replaceAll("ab cd", """\w+""", "[$&]"))
    }

    @Test
    fun `regex replacement takes two digits only when that group exists`() {
        val elevenGroups = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)"
        assertEquals("k", replaceAll("abcdefghijk", elevenGroups, "$11"))
        assertEquals("x0", replaceAll("x", "(x)", "$10"))
    }

    @Test
    fun `regex replacement leaves a missing group literal and an unmatched one empty`() {
        assertEquals("$5", replaceAll("x", "(x)", "$5"))
        assertEquals("<>", replaceAll("x", "(y)?x", "<$1>"))
    }

    @Test
    fun `regex replacement reads dollar and backslash escapes`() {
        assertEquals("$1", replaceAll("x", "(x)", "$$1"))
        assertEquals("a\nb\tc\\d", replaceAll("abcd", "(b)(c)", "\\n$1\\t$2\\\\"))
    }

    @Test
    fun `literal replacement is never expanded`() {
        assertEquals("$1 \\n", replaceAll("x", "x", "$1 \\n", FindEngine.Options()))
    }

    @Test
    fun `a match the text no longer has is left out`() {
        val query = "(o+)"
        val stale = FindEngine.findAll("foo boo", query, regex)
        val edits = FindEngine.replaceEdits("fxx boo", stale, query, "<$1>", regex)
        assertEquals(listOf(TextEdit(5..6, "<oo>")), edits)
    }
}
