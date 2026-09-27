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

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BracketTest {

    /** Strings are "…" and comments run from // to the end of the line. */
    private object Strings : IncrementalTokenizer {
        override fun tokenizeFull(text: String): List<Token> = buildList {
            Regex("\"[^\"\\n]*\"|//[^\\n]*").findAll(text).forEach {
                add(Token(it.range.first, it.range.last + 1, if (it.value.startsWith("//")) TokenType.Comment else TokenType.StringLiteral))
            }
        }

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = tokenizeFull(text)

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private fun stateOf(text: String) = CodeEditorState(
        initialText = "",
        tokenizer = Strings,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        loadText(text)
    }

    @Test
    fun `depth at each line start counts brackets outside strings and comments`() {
        val state = stateOf("fun a() {\n    if (x) {\n        \"{\" // (\n    }\n}\nend")
        assertEquals(listOf(0, 1, 2, 2, 1, 0), (0 until 6).map { state.brackets.depthAtLineStart(it) })
    }

    @Test
    fun `depths follow edits above`() {
        val state = stateOf("a\nb\nc")
        assertEquals(0, state.brackets.depthAtLineStart(2))
        state.applyEdit(0, 0, "{\n", TextRange(2))
        assertEquals(1, state.brackets.depthAtLineStart(3))
        state.applyEdit(0, 2, "", TextRange(0))
        assertEquals(0, state.brackets.depthAtLineStart(2))
    }

    @Test
    fun `a closing bracket never takes the depth below zero`() {
        val state = stateOf(")\n)\nx")
        assertEquals(0, state.brackets.depthAtLineStart(2))
    }

    @Test
    fun `the pair at the caret matches across lines, skipping strings`() {
        val text = "f(a, \")\", g(b))\nx"
        val state = stateOf(text)
        state.selection = TextRange(2) // just after the first (
        assertEquals(BracketMatcher.Match(1, 14), state.matchingBracket())
        state.selection = TextRange(15) // just after the last )
        assertEquals(BracketMatcher.Match(1, 14), state.matchingBracket())
        state.selection = TextRange(4)
        assertNull(state.matchingBracket())
    }

    @Test
    fun `no match inside a string or past the search limit`() {
        val state = stateOf("\"(\" x")
        state.selection = TextRange(2)
        assertNull(state.matchingBracket())
        val far = "(" + "x".repeat(BracketMatcher.LIMIT + 10) + ")"
        assertNull(BracketMatcher.find(far, 1) { true })
    }

    @Test
    fun `tab indents by the configured unit and shift tab removes it`() {
        val state = stateOf("a\nb")
        state.indentUnit = "\t"
        state.selection = TextRange(0, 3)
        state.indentSelection()
        assertEquals("\ta\n\tb", state.text)
        state.outdentSelection()
        assertEquals("a\nb", state.text)
        state.indentUnit = "  "
        state.selection = TextRange(0)
        state.indentSelection()
        assertEquals("  a\nb", state.text)
    }
}
