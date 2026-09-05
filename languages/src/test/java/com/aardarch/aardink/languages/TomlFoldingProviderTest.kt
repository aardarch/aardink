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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.languages.internal.folding.TomlFoldingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TomlFoldingProviderTest {

    @Test
    fun `folds table sections`() {
        val src = """
            [versions]
            agp = "8.9.0"
            kotlin = "2.1.10"

            [libraries]
            core = "1.0"
        """.trimIndent()
        val doc = CodeDocument(src)
        val ranges = TomlFoldingProvider.foldableRanges(doc)
        assertTrue(ranges.isNotEmpty())
        assertEquals(0, ranges[0].startLine)
        assertEquals(3, ranges[0].endLine)
    }

    @Test
    fun `folds multiline inline arrays`() {
        val src = """
            arr = [
                "one",
                "two",
                "three"
            ]
        """.trimIndent()
        val doc = CodeDocument(src)
        val ranges = TomlFoldingProvider.foldableRanges(doc)
        assertTrue(ranges.any { it.startLine == 0 && it.endLine == 4 })
    }

    @Test
    fun `brackets inside ordinary strings are data, not folds`() {
        val src = """
            open = "["
            middle = "plain"
            close = "]"
        """.trimIndent()
        val ranges = TomlFoldingProvider.foldableRanges(CodeDocument(src))
        assertTrue(ranges.isEmpty(), "a bracket in a quoted value opens nothing: was $ranges")
    }

    @Test
    fun `a hash inside a string does not comment out the rest of its line`() {
        // If the '#' were read as a comment marker the '[' after it would be skipped, and the
        // array below would never be seen as opening a fold.
        val src = """
            note = "#" # real comment
            list = [
                1,
            ]
        """.trimIndent()
        val ranges = TomlFoldingProvider.foldableRanges(CodeDocument(src))
        assertTrue(ranges.any { it.startLine == 1 && it.endLine == 3 }, "was $ranges")
    }

    @Test
    fun `a literal string keeps its backslashes`() {
        // No escapes in '...', so the closing quote is the very next one and the '[' stays data.
        val src = """
            path = 'C:\'
            other = "["
        """.trimIndent()
        val ranges = TomlFoldingProvider.foldableRanges(CodeDocument(src))
        assertTrue(ranges.isEmpty(), "was $ranges")
    }
}
