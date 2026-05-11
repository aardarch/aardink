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
import com.aardarch.aardink.languages.internal.folding.BraceFoldingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BraceFoldingProviderTest {

    @Test
    fun `pairs simple top-level braces`() {
        val doc = CodeDocument("class Foo {\n  val x = 1\n}\n")
        val ranges = BraceFoldingProvider().foldableRanges(doc)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges.first().startLine)
        assertEquals(2, ranges.first().endLine)
    }

    @Test
    fun `nested braces produce nested ranges`() {
        val doc = CodeDocument("a {\n  b {\n    c\n  }\n}\n")
        val ranges = BraceFoldingProvider().foldableRanges(doc)
        assertEquals(2, ranges.size)
        assertTrue(ranges.any { it.startLine == 0 && it.endLine == 4 })
        assertTrue(ranges.any { it.startLine == 1 && it.endLine == 3 })
    }

    @Test
    fun `braces inside strings are ignored`() {
        val doc = CodeDocument("val s = \"{not a fold}\"\nclass A {\n  x\n}\n")
        val ranges = BraceFoldingProvider().foldableRanges(doc)
        assertEquals(1, ranges.size)
        assertEquals(1, ranges.first().startLine)
    }

    @Test
    fun `braces inside line comments are ignored`() {
        val doc = CodeDocument("// { fake\nclass A {\n  x\n}\n")
        val ranges = BraceFoldingProvider().foldableRanges(doc)
        assertEquals(1, ranges.size)
    }

    @Test
    fun `single-line braces below minLines are excluded`() {
        val doc = CodeDocument("val x = { 1 }\n")
        val ranges = BraceFoldingProvider(minLines = 1).foldableRanges(doc)
        assertTrue(ranges.isEmpty())
    }
}
