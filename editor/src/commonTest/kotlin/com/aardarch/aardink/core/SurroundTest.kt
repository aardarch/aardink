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

/** Typing over a selection with [LanguageService.surroundingClose]: the surround in `applyInput`. */
class SurroundTest {

    private fun stateFor(text: String): CodeEditorState = CodeEditorState(
        initialText = "",
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        loadText(text)
    }

    /** Surrounds with brackets and quotes; [asked] counts how often it is consulted. */
    private class Service(private val indent: String = "") : LanguageService {
        var asked = 0

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = if (charTyped == '(') ")" else null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = indent

        override fun surroundingClose(typed: Char): String? {
            asked++
            return when (typed) {
                '(' -> ")"
                '"' -> "\""
                '<' -> "/>"
                else -> null
            }
        }
    }

    /** Types [text] over the primary selection, as the keyboard does. */
    private fun CodeEditorState.typeOver(text: String, service: LanguageService = Service()) =
        applyInput(selection.min, selection.max, text, service = service)

    @Test
    fun `typing an opener over a selection wraps it and keeps it selected`() {
        val state = stateFor("say hello now")
        state.selection = TextRange(4, 9)
        assertNull(state.typeOver("("), "a surround is not typing at a caret: it asks for no completions")
        assertEquals("say (hello) now", state.text)
        assertEquals(TextRange(5, 10), state.selection)
        state.typeOver("(")
        assertEquals("say ((hello)) now", state.text, "the selection stayed, so it nests")
    }

    @Test
    fun `a backwards selection stays backwards`() {
        val state = stateFor("hello")
        state.selection = TextRange(5, 0)
        state.typeOver("(")
        assertEquals("(hello)", state.text)
        assertEquals(TextRange(6, 1), state.selection)
    }

    @Test
    fun `a closer of several characters goes after the selection`() {
        val state = stateFor("hello")
        state.selection = TextRange(0, 5)
        state.typeOver("<")
        assertEquals("<hello/>", state.text)
        assertEquals(TextRange(1, 6), state.selection)
    }

    @Test
    fun `a surround is one undo step, which brings the selection back`() {
        val state = stateFor("one two")
        state.setSelections(listOf(TextRange(0, 3), TextRange(4, 7)))
        state.typeOver("(")
        assertEquals("(one) (two)", state.text)
        state.undo()
        assertEquals("one two", state.text)
        assertEquals(listOf(TextRange(0, 3), TextRange(4, 7)), state.selections)
        state.redo()
        assertEquals("(one) (two)", state.text)
    }

    @Test
    fun `typing after a surround is its own undo step`() {
        val state = stateFor("one")
        state.selection = TextRange(0, 3)
        state.typeOver("(")
        state.selection = TextRange(5)
        state.typeOver("x")
        state.undo()
        assertEquals("(one)", state.text)
        state.undo()
        assertEquals("one", state.text)
    }

    @Test
    fun `every selection is wrapped, the primary staying primary, whatever its place`() {
        val state = stateFor("aa bb cc")
        state.setSelections(listOf(TextRange(3, 5), TextRange(0, 2), TextRange(6, 8)))
        state.typeOver("(")
        assertEquals("(aa) (bb) (cc)", state.text)
        assertEquals(listOf(TextRange(6, 8), TextRange(1, 3), TextRange(11, 13)), state.selections)
    }

    @Test
    fun `selections that touch are wrapped each on its own`() {
        val state = stateFor("aabb")
        state.setSelections(listOf(TextRange(0, 2), TextRange(2, 4)))
        state.typeOver("(")
        assertEquals("(aa)(bb)", state.text)
        assertEquals(listOf(TextRange(1, 3), TextRange(5, 7)), state.selections)
    }

    @Test
    fun `a selection over several lines is wrapped at its ends`() {
        val state = stateFor("one\ntwo\nthree")
        state.selection = TextRange(2, 9)
        state.typeOver("(")
        assertEquals("on(e\ntwo\nt)hree", state.text)
    }

    @Test
    fun `a character the service does not surround with replaces the selection`() {
        val state = stateFor("hello")
        state.selection = TextRange(0, 5)
        state.typeOver("x")
        assertEquals("x", state.text)
    }

    @Test
    fun `a caret among the selections makes it plain typing`() {
        val state = stateFor("one two")
        state.setSelections(listOf(TextRange(0, 3), TextRange(5)))
        state.typeOver("(")
        assertEquals("( t(wo", state.text, "typed at both: no wrapping")
    }

    @Test
    fun `at a caret the service is not asked`() {
        val state = stateFor("one")
        val service = Service()
        state.selection = TextRange(3)
        state.typeOver("(", service)
        assertEquals(0, service.asked)
        assertEquals("one()", state.text)
    }

    @Test
    fun `selected blanks are replaced, not wrapped`() {
        val state = stateFor("a \t\n b")
        state.selection = TextRange(1, 5)
        state.typeOver("(")
        assertEquals("a(b", state.text)
    }

    @Test
    fun `a quote typed over a quote changes it`() {
        val state = stateFor("x'y")
        state.selection = TextRange(1, 2)
        state.typeOver("\"")
        assertEquals("x\"y", state.text)
    }

    @Test
    fun `a bracket typed over a quote wraps it`() {
        val state = stateFor("x'y")
        state.selection = TextRange(1, 2)
        state.typeOver("(")
        assertEquals("x(')y", state.text)
    }

    @Test
    fun `nothing is wrapped while an input method composes`() {
        val state = stateFor("hello")
        val service = Service()
        state.selection = TextRange(0, 5)
        state.applyInput(0, 5, "(", composing = true, service = service)
        assertEquals("(", state.text)
        assertEquals(0, service.asked)
    }

    @Test
    fun `text of several characters replaces the selection`() {
        val state = stateFor("hello")
        val service = Service()
        state.selection = TextRange(0, 5)
        state.typeOver("((", service)
        assertEquals("((", state.text)
        assertEquals(0, service.asked)
    }

    @Test
    fun `an input method's edit reaching past the selection replaces it`() {
        val state = stateFor("hello")
        state.selection = TextRange(1, 3)
        state.applyInput(0, 3, "(", service = Service())
        assertEquals("(lo", state.text)
    }

    @Test
    fun `without a service the selection is replaced`() {
        val state = stateFor("hello")
        state.selection = TextRange(0, 5)
        state.applyInput(0, 5, "(")
        assertEquals("(", state.text)
    }

    @Test
    fun `what a service starts a line with need not be whitespace`() {
        val state = stateFor("/**")
        state.selection = TextRange(3)
        state.typeOver("\n", Service(indent = " * "))
        assertEquals("/**\n * ", state.text)
        assertEquals(TextRange(7), state.selection)
    }
}
