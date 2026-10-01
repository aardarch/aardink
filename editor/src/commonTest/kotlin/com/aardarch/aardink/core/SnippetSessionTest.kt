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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A snippet inserted into [CodeEditorState] and filled in: Monaco's snippet session. */
class SnippetSessionTest {

    private fun stateOf(text: String, caret: Int = text.length): CodeEditorState = CodeEditorState(
        initialText = "",
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        loadText(text)
        selection = TextRange(caret)
    }

    /** Types [text] at the primary selection, as the keyboard does. */
    private fun CodeEditorState.type(text: String) {
        for (c in text) {
            val primary = selection
            applyInput(primary.min, primary.max, c.toString())
        }
    }

    @Test
    fun `the first stop is selected, Tab moves on, and the final stop ends the snippet`() {
        val state = stateOf("")
        state.insertSnippet(0 until 0, "fun \${1:name}(\${2:args}) {\$0}")
        assertEquals("fun name(args) {}", state.document.text)
        assertEquals(TextRange(4, 8), state.selection)
        assertTrue(state.moveInSnippet(forward = true))
        assertEquals(TextRange(9, 13), state.selection)
        assertTrue(state.moveInSnippet(forward = false))
        assertEquals(TextRange(4, 8), state.selection)
        state.moveInSnippet(forward = true)
        assertTrue(state.moveInSnippet(forward = true))
        assertEquals(TextRange(16), state.selection)
        assertNull(state.snippet)
        assertFalse(state.moveInSnippet(forward = true), "Tab indents again")
    }

    @Test
    fun `typing replaces a placeholder and the later stops follow`() {
        val state = stateOf("")
        state.insertSnippet(0 until 0, "fun \${1:name}(\${2:args}) {\$0}")
        state.type("greet")
        assertEquals("fun greet(args) {}", state.document.text)
        state.moveInSnippet(forward = true)
        assertEquals(TextRange(10, 14), state.selection)
        state.type("x: Int")
        state.moveInSnippet(forward = true)
        assertEquals("fun greet(x: Int) {}", state.document.text)
        assertEquals(TextRange(19), state.selection)
    }

    @Test
    fun `an empty stop grows as it is typed into`() {
        val state = stateOf("<a >", caret = 3)
        state.insertSnippet(3 until 3, "href=\"\$1\"")
        assertEquals("<a href=\"\">", state.document.text)
        assertEquals(TextRange(9), state.selection)
        state.type("x.html")
        assertNotNull(state.snippet, "still inside the stop")
        assertTrue(state.moveInSnippet(forward = true))
        assertEquals("<a href=\"x.html\">", state.document.text)
        assertEquals(TextRange(16), state.selection)
    }

    @Test
    fun `a stop that occurs twice is edited at both places`() {
        val state = stateOf("")
        state.insertSnippet(0 until 0, "<\${1:div}></\$1>")
        assertEquals(listOf(TextRange(1, 4), TextRange(7, 10)), state.selections)
        val ranges = state.selections
        state.applyInput(ranges[0].min, ranges[0].max, "p")
        assertEquals("<p></p>", state.document.text)
    }

    @Test
    fun `the inserted lines follow the indentation of the line`() {
        val state = stateOf("    ")
        state.indentUnit = "  "
        state.insertSnippet(4 until 4, "if (\$1) {\n\t\$0\n}")
        assertEquals("    if () {\n      \n    }", state.document.text)
        state.moveInSnippet(forward = true)
        assertEquals(TextRange(18), state.selection)
    }

    @Test
    fun `the whole snippet is one undo step, and undo ends it`() {
        val state = stateOf("x")
        state.insertSnippet(1 until 1, "(\${1:a}, \${2:b})")
        state.pushUndoStop()
        state.undo()
        assertEquals("x", state.document.text)
        assertNull(state.snippet)
    }

    @Test
    fun `moving the caret out of the current stop ends the snippet`() {
        val state = stateOf("")
        state.insertSnippet(0 until 0, "f(\${1:a}, \${2:b})")
        state.selection = TextRange(0)
        assertNull(state.snippet)
    }

    @Test
    fun `a snippet with only a final stop places the caret and ends`() {
        val state = stateOf("")
        state.insertSnippet(0 until 0, "{ \$0 }")
        assertEquals(TextRange(2), state.selection)
        assertNull(state.snippet)
    }

    @Test
    fun `the replaced range goes and additional edits land in the same step`() {
        val state = stateOf("pri")
        state.insertSnippet(0 until 3, "println(\$1)", listOf(TextEdit(0 until 0, "import x\n")))
        assertEquals("import x\nprintln()", state.document.text)
        assertEquals(TextRange(17), state.selection)
        assertNotNull(state.snippet)
    }

    @Test
    fun `endSnippet stops stepping`() {
        val state = stateOf("")
        state.insertSnippet(0 until 0, "\${1:a} \${2:b}")
        state.endSnippet()
        assertFalse(state.moveInSnippet(forward = true))
    }
}
