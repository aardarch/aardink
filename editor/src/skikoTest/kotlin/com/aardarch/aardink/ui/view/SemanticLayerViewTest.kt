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
package com.aardarch.aardink.ui.view

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.EditorLimits
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.view.InlineFixture.bounds
import com.aardarch.aardink.ui.view.InlineFixture.paint
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the semantic layer does to a line's layout, with a real text measurer. */
class SemanticLayerViewTest {

    private val coloured = NamedTokenType("type.class")
    private val otherColoured = NamedTokenType("function.method")
    private val uncoloured = NamedTokenType("nothing.the.theme.knows")

    /** Every word is a keyword, which the theme below draws in red. */
    private object Words : IncrementalTokenizer {
        private fun words(text: String) =
            Regex("[a-z]+").findAll(text).map { Token(it.range.first, it.range.last + 1, TokenType.Keyword) }.toList()

        override fun tokenizeFull(text: String): List<Token> = words(text)

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = words(text)

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private val style: (TokenType) -> SpanStyle? = { type ->
        when (type) {
            TokenType.Keyword -> SpanStyle(color = Color.Red)
            coloured -> SpanStyle(color = Color.Green)
            otherColoured -> SpanStyle(color = Color.Blue)
            else -> null
        }
    }

    private fun view(text: String) = viewOf(text, tokenizer = Words, tokenStyle = style)

    private suspend fun EditorView.answer(vararg tokens: Token) {
        state.applySemanticTokens(tokens.toList(), state.document.snapshot())
        prepare(600, 400)
    }

    private fun drawn(view: EditorView, line: Int = 0): PixelMap =
        paint(300, 20) { drawStickyLines(view, listOf(StickyLine(line, 0f)), Color.White, Color.White) }

    @Test
    fun `a semantic token's colour wins over the syntax's, on its characters only`() = runTest {
        val view = view("word plain")
        assertNotNull(drawn(view).bounds(Color.Red), "drawn in the syntax colour first")
        assertNull(drawn(view).bounds(Color.Green))

        view.answer(Token(0, 4, coloured))

        val pixels = drawn(view)
        val semantic = assertNotNull(pixels.bounds(Color.Green), "the token is in its colour")
        val syntax = assertNotNull(pixels.bounds(Color.Red), "the rest keeps the syntax's")
        assertTrue(semantic.right <= syntax.left, "$semantic is left of $syntax")
    }

    @Test
    fun `a semantic token over several syntax tokens wins on all of them`() = runTest {
        // It starts before the words, and text draws the span that starts last.
        val view = view("  one two three")

        view.answer(Token(0, 15, coloured))

        val pixels = drawn(view)
        assertNotNull(pixels.bounds(Color.Green))
        assertNull(pixels.bounds(Color.Red))
    }

    @Test
    fun `a syntax token wider than a semantic one keeps the characters outside it`() = runTest {
        val view = view("wordword")

        view.answer(Token(2, 6, coloured))

        val pixels = drawn(view)
        val semantic = assertNotNull(pixels.bounds(Color.Green))
        val syntax = assertNotNull(pixels.bounds(Color.Red))
        assertTrue(syntax.left < semantic.left && semantic.right < syntax.right, "$syntax on both sides of $semantic")
    }

    @Test
    fun `a semantic token with no colour keeps the syntax's`() = runTest {
        val view = view("word")

        view.answer(Token(0, 4, uncoloured))

        assertNotNull(drawn(view).bounds(Color.Red))
    }

    @Test
    fun `bracket colours stay over a semantic token`() = runTest {
        val view = view("(a)")
        view.style = view.style!!.copy(bracketStyles = listOf(SpanStyle(color = Color.Magenta)))
        view.prepare(600, 400)

        view.answer(Token(0, 3, coloured))

        val pixels = drawn(view)
        assertNotNull(pixels.bounds(Color.Magenta), "the brackets")
        assertNotNull(pixels.bounds(Color.Green), "the name between them")
    }

    @Test
    fun `an answer lays out only the lines it changes`() = runTest {
        val view = view("one\ntwo\nthree")
        view.answer(Token(0, 3, coloured), Token(4, 7, coloured), Token(8, 13, coloured))
        val first = view.layoutFor(0)
        val second = view.layoutFor(1)
        val third = view.layoutFor(2)

        view.answer(Token(0, 3, coloured), Token(4, 7, otherColoured), Token(8, 13, coloured))

        assertSame(first, view.layoutFor(0))
        assertNotSame(second, view.layoutFor(1))
        assertSame(third, view.layoutFor(2))
    }

    @Test
    fun `an edit lays out only the line it touches, as without the layer`() = runTest {
        val view = view("one\ntwo\nthree")
        view.answer(Token(0, 3, coloured), Token(8, 13, coloured))
        val first = view.layoutFor(0)
        val third = view.layoutFor(2)

        view.state.document.insert(5, "x")
        view.prepare(600, 400)

        assertSame(first, view.layoutFor(0))
        assertSame(third, view.layoutFor(2))
    }

    @Test
    fun `a layer with nothing on a line costs that line no layout`() = runTest {
        val view = view("one\ntwo")
        val second = view.layoutFor(1)

        view.answer(Token(0, 3, coloured))

        assertSame(second, view.layoutFor(1))
    }

    @Test
    fun `clearing the layer brings the syntax colours back`() = runTest {
        val view = view("word")
        view.answer(Token(0, 4, coloured))
        assertNull(drawn(view).bounds(Color.Red))

        view.state.clearSemanticTokens()
        view.prepare(600, 400)

        assertNotNull(drawn(view).bounds(Color.Red))
        assertNull(drawn(view).bounds(Color.Green))
    }

    @Test
    fun `a new answer reaches the frame`() = runTest {
        val view = view("word")
        val before = view.frame

        view.answer(Token(0, 4, coloured))

        assertNotSame(before.lines.single().layout, view.frame.lines.single().layout)
    }

    @Test
    fun `a document past the analysis limit shows no layer`() = runTest {
        val view = view("word")
        view.answer(Token(0, 4, coloured))
        val limit = EditorLimits.plainTextFallbackChars
        view.state.computeOnMainThread = true
        EditorLimits.plainTextFallbackChars = 1
        try {
            assertEquals(emptyList(), view.layoutFor(0).result.layoutInput.text.spanStyles)
        } finally {
            EditorLimits.plainTextFallbackChars = limit
        }
    }
}
