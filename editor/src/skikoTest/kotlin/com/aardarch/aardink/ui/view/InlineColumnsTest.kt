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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spike's step S4 on desktop and in the browser: every column of a line reads the geometry of
 * its own character, for an injection at every column. Positions are counted here, not asked of
 * the map. `InlineGeometryTest` checks on the JVM that what is drawn is drawn there.
 */
class InlineColumnsTest {

    private val line = "abcd ef(gh) ij kl mn"
    private val hint = "HINT"

    @Test
    fun `every column reads its own character, for an injection at every column`() {
        for (at in 0..line.length) {
            for (caretAfter in listOf(false, true)) {
                val injected = LineInjections.of(Injection(at, hint, caretAfter = caretAfter))
                val layout = viewOf(line, injections = lines(0 to injected)).shown(0).layout
                val raw = layout.result
                for (column in 0 until line.length) {
                    val context = "column $column, an injection at $at, caretAfter = $caretAfter"
                    val box = raw.getBoundingBox(if (column >= at) column + hint.length else column)
                    assertEquals(box, layout.charBox(column), context)
                    assertEquals(box.left, layout.charLeft(column), 0.5f, context)
                }
                for (column in 0..line.length) {
                    val context = "caret at $column, an injection at $at, caretAfter = $caretAfter"
                    val past = column > at || (column == at && caretAfter)
                    assertEquals(raw.getCursorRect(if (past) column + hint.length else column), layout.cursorRect(column), context)
                }
            }
        }
    }

    @Test
    fun `a click anywhere on a line goes to the column it is at`() {
        val layout = viewOf(line, injections = lines(0 to LineInjections.of(Injection(7, hint)))).shown(0).layout
        val y = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        for (column in 0 until line.length) {
            val x = layout.charBox(column).left + 0.5f
            assertEquals(column, layout.columnAt(Offset(x, y)), "column $column")
        }
    }

    /** The first three characters of a text are one token. */
    private object FirstWord : IncrementalTokenizer {
        override fun tokenizeFull(text: String): List<Token> = listOf(Token(0, 3, TokenType.Keyword))

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = tokenizeFull(text)

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    @Test
    fun `a token's colour goes on its characters and leaves injected text inside it alone`() {
        val red = SpanStyle(color = Color.Red)
        val view = viewOf(
            "val x",
            injections = lines(0 to LineInjections.of(Injection(1, hint))),
            tokenizer = FirstWord,
            tokenStyle = { red },
        )
        val text = view.shown(0).layout.result.layoutInput.text
        assertEquals("v${hint}al x", text.text)
        assertEquals(listOf(0 to 1, 5 to 7), text.spanStyles.filter { it.item == red }.map { it.start to it.end })
    }

    @Test
    fun `a row's end is read in columns, whatever is injected before it`() {
        val injected = LineInjections.of(Injection(2, "ghost\nrow\nend "))
        val layout = viewOf("abcdef", injections = lines(0 to injected)).shown(0).layout
        assertEquals(layout.caretX(2), layout.rowEndX(0, 6), 0.5f, "the first row's text ends at column 2")
        assertEquals(layout.caretX(6), layout.rowEndX(2, 6), 0.5f, "the last row's text ends at the line's end")
    }

    @Test
    fun `a row's end does not count injected text past the row`() {
        val plain = viewOf("abcd").shown(0).layout
        val layout = viewOf("abcd", injections = lines(0 to LineInjections.of(Injection(4, hint)))).shown(0).layout
        assertEquals(plain.rowEndX(0, 4), layout.rowEndX(0, 4), 0.5f, "the hint at the end is not the text's end")
    }
}
