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
import com.aardarch.aardink.languages.internal.folding.TagFoldingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagFoldingProviderTest {

    @Test
    fun `pairs open and close tags`() {
        val doc = CodeDocument("<root>\n  <child/>\n</root>\n")
        val ranges = TagFoldingProvider().foldableRanges(doc)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges.first().startLine)
        assertEquals(2, ranges.first().endLine)
    }

    @Test
    fun `self-closing tags do not push the stack`() {
        val doc = CodeDocument("<a>\n<b/>\n<c/>\n</a>\n")
        val ranges = TagFoldingProvider().foldableRanges(doc)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges.first().startLine)
        assertEquals(3, ranges.first().endLine)
    }

    @Test
    fun `comments are skipped`() {
        val doc = CodeDocument("<a>\n<!-- <fake> -->\n</a>\n")
        val ranges = TagFoldingProvider().foldableRanges(doc)
        assertEquals(1, ranges.size)
    }

    @Test
    fun `html void elements treated as self-closing`() {
        val doc = CodeDocument("<div>\n  <br>\n  <img src=\"x\">\n</div>\n")
        val ranges = TagFoldingProvider(htmlMode = true).foldableRanges(doc)
        assertTrue(ranges.any { it.startLine == 0 && it.endLine == 3 })
    }
}
