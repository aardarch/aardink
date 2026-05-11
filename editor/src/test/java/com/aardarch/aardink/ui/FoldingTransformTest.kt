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

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FoldingTransformTest {

    private val style = SpanStyle()

    private fun transform(text: String, folds: List<FoldRange>): String {
        val doc = CodeDocument(text)
        return applyFolding(AnnotatedString(text), folds, doc, style).text.text
    }

    @Test
    fun `multi-line fold preserves trailing newline so next line stays on its own line`() {
        val src = "<a>\n  <b/>\n</a>\n<c/>\n"
        val out = transform(src, listOf(FoldRange(0, 2)))
        assertEquals("<a> …\n<c/>\n", out)
    }

    @Test
    fun `fold ending on last line of document works without out-of-bounds`() {
        val src = "<a>\n  <b/>\n</a>"
        val out = transform(src, listOf(FoldRange(0, 2)))
        assertEquals("<a> …", out)
    }

    @Test
    fun `two adjacent folds each preserve their trailing newline`() {
        val src = "<a>\n  x\n</a>\n<b>\n  y\n</b>\n"
        val out = transform(src, listOf(FoldRange(0, 2), FoldRange(3, 5)))
        assertEquals("<a> …\n<b> …\n", out)
    }

    @Test
    fun `originalToTransformedOffset matches applyFolding's offset mapping for line starts`() {
        val src = "<a>\n  <b/>\n</a>\n<c/>\n<d/>\n"
        val doc = CodeDocument(src)
        val folds = listOf(FoldRange(0, 2))
        val tt = applyFolding(AnnotatedString(src), folds, doc, style)
        for (line in 0 until doc.lineCount) {
            val orig = doc.lineStart(line)
            val viaHelper = originalToTransformedOffset(doc, folds, orig)
            val viaMapping = tt.offsetMapping.originalToTransformed(orig)
            assertEquals(viaMapping, viaHelper, "line=$line orig=$orig")
        }
    }

    @Test
    fun `offset mapping round-trips for the line after a fold`() {
        val src = "<a>\n  <b/>\n</a>\n<c/>\n"
        val doc = CodeDocument(src)
        val folds = listOf(FoldRange(0, 2))
        val tt = applyFolding(AnnotatedString(src), folds, doc, style)

        val origStartOfNextLine = doc.lineStart(3)
        val transformed = tt.offsetMapping.originalToTransformed(origStartOfNextLine)
        val roundTrip = tt.offsetMapping.transformedToOriginal(transformed)
        assertEquals(origStartOfNextLine, roundTrip)
        assertTrue(transformed in 0..tt.text.length)
    }
}
