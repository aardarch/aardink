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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
