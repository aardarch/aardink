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
import com.aardarch.aardink.core.FoldKind
import kotlin.test.Test
import kotlin.test.assertEquals

class FoldingRegionsTest {

    @Test
    fun `region markers fold, nested, alongside the language's own ranges`() {
        val text = """
            // #region outer
            fun a() {
                // region inner
                val x = 1
                // endregion
            }
            // #endregion
        """.trimIndent()
        val ranges = BuiltInLanguages.Kotlin.foldingProvider.foldableRanges(CodeDocument(text))
        val regions = ranges.filter { it.kind == FoldKind.Region }.map { it.startLine to it.endLine }
        assertEquals(listOf(0 to 6, 2 to 4), regions)
        assertEquals(listOf(1 to 5), ranges.filter { it.kind == null }.map { it.startLine to it.endLine })
    }

    @Test
    fun `xml regions are comments`() {
        val text = "<a>\n<!-- #region -->\n<b/>\n<!-- #endregion -->\n</a>"
        val regions = BuiltInLanguages.Xml.foldingProvider.foldableRanges(CodeDocument(text)).filter { it.kind == FoldKind.Region }
        assertEquals(listOf(1 to 3), regions.map { it.startLine to it.endLine })
    }

    @Test
    fun `indentation folds the more indented lines below, blank lines included`() {
        val text = "a\n  b\n\n  c\n    d\ne\n  f"
        val ranges = IndentationFoldingProvider().foldableRanges(CodeDocument(text))
        assertEquals(listOf(0 to 4, 3 to 4, 5 to 6), ranges.map { it.startLine to it.endLine })
    }
}
