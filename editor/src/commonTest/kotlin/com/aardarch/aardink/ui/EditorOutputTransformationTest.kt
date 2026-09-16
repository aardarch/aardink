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
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aardarch.aardink.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the live render path. PR 1 replaced the `VisualTransformation` chain with this class
 * but left it untested — only the deprecated [applyFolding] it superseded had coverage, so the
 * code that actually paints the editor had none.
 *
 * `transformOutput` is driven through [TextFieldState.edit], which hands out the same
 * [androidx.compose.foundation.text.input.TextFieldBuffer] the framework passes during a real
 * output pass, and then commits it so the transformed text is readable. Style spans are not
 * asserted here: they are applied to the buffer and not observable from the committed state.
 * What *is* asserted is the text transformation — folding — plus the ordering and clamping
 * rules that decide which folds apply at all.
 */
class EditorOutputTransformationTest {

    private fun transform(
        text: String,
        foldedRanges: List<FoldRange> = emptyList(),
        syntaxColoredText: AnnotatedString? = null,
        matches: List<IntRange> = emptyList(),
        currentMatchIndex: Int = -1,
    ): String {
        val transformation = EditorOutputTransformation(
            syntaxColoredText = syntaxColoredText,
            textColor = Color.Black,
            matches = matches,
            currentMatchIndex = currentMatchIndex,
            matchHighlight = Color.Yellow,
            currentMatchHighlight = Color.Red,
            foldedRanges = foldedRanges,
            document = CodeDocument(text),
            placeholderStyle = SpanStyle(color = Color.Gray),
        )
        val state = TextFieldState(text)
        state.edit { with(transformation) { transformOutput() } }
        return state.text.toString()
    }

    @Test
    fun `text is unchanged when nothing is folded`() {
        val text = "one\ntwo\nthree\n"
        assertEquals(text, transform(text))
    }

    @Test
    fun `a folded range collapses to its placeholder`() {
        // Lines 0..2 fold: everything from the end of line 0 to the end of line 2 is replaced.
        val text = "a\nb\nc\nd\n"
        assertEquals("a ...\nd\n", transform(text, listOf(FoldRange(0, 2, "..."))))
    }

    @Test
    fun `two sibling folds both collapse`() {
        // The second fold is edited into an already-shortened buffer, so the running offset
        // shift has to be right or it lands in the wrong place.
        val text = "a\nb\nc\nd\ne\nf\n"
        val folds = listOf(FoldRange(0, 1, "A"), FoldRange(2, 3, "B"))
        assertEquals("a A\nc B\ne\nf\n", transform(text, folds))
    }

    @Test
    fun `folds are applied in line order regardless of input order`() {
        val text = "a\nb\nc\nd\ne\nf\n"
        val ordered = listOf(FoldRange(0, 1, "A"), FoldRange(2, 3, "B"))
        assertEquals(transform(text, ordered), transform(text, ordered.reversed()))
    }

    @Test
    fun `a fold nested inside an already folded range is skipped`() {
        // The outer fold already hides these lines; applying the inner one too would delete
        // text that is no longer there.
        val text = "a\nb\nc\nd\ne\n"
        val folds = listOf(FoldRange(0, 3, "OUT"), FoldRange(1, 2, "IN"))
        assertEquals("a OUT\ne\n", transform(text, folds))
    }

    @Test
    fun `a fold whose lines no longer exist is ignored rather than throwing`() {
        // Folding runs on a 200 ms debounce, so a fold range can outlive the text it described.
        val text = "a\nb\n"
        assertEquals(text, transform(text, listOf(FoldRange(5, 9, "stale"))))
    }

    @Test
    fun `stale syntax colouring does not corrupt the text`() {
        // effectiveAnnotatedText can be one tokenization tick behind. The transformation falls
        // back to a uniform colour; either way the text itself must be untouched.
        val text = "hello\n"
        val stale = AnnotatedString("completely different text")
        assertEquals(text, transform(text, syntaxColoredText = stale))
    }

    @Test
    fun `find match highlighting does not alter the text`() {
        val text = "foo bar foo\n"
        assertEquals(text, transform(text, matches = listOf(0..2, 8..10), currentMatchIndex = 1))
    }

    @Test
    fun `out of range find matches are clamped rather than throwing`() {
        val text = "short\n"
        assertEquals(text, transform(text, matches = listOf(0..999, -5..2), currentMatchIndex = 0))
    }

    @Test
    fun `folding and highlighting compose without corrupting the text`() {
        val text = "a\nb\nc\nd\n"
        val result = transform(
            text,
            foldedRanges = listOf(FoldRange(0, 2, "...")),
            matches = listOf(0..0),
            currentMatchIndex = 0,
        )
        assertEquals("a ...\nd\n", result)
    }
}
