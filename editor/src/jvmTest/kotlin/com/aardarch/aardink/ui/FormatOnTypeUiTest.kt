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
@file:OptIn(ExperimentalTestApi::class)

package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `EditorOptions.formatOnType`: when the service is asked, and how its answer lands. */
class FormatOnTypeUiTest {

    /** Takes a `}` back to the start of its line, the indentation before it removed. */
    private class Outdent(val gate: CompletableDeferred<Unit>? = null) : LanguageService {
        val asked = mutableListOf<Pair<Int, Char>>()

        @Volatile var answered = false

        override val triggerCharacters: Set<Char> = emptySet()
        override val formatOnTypeCharacters: Set<Char> = setOf('}')

        override suspend fun formatOnType(document: CodeDocument, offset: Int, typed: Char, options: FormattingOptions): List<TextEdit> {
            asked += offset to typed
            gate?.await()
            val line = document.offsetToLineCol(offset - 1).first
            val start = document.lineStart(line)
            val indent = document.lineText(line).takeWhile { it == ' ' }.length
            answered = true
            return if (indent == 0) emptyList() else listOf(TextEdit(start until start + indent, ""))
        }

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    private fun ComposeUiTest.show(state: CodeEditorState, service: LanguageService, options: EditorOptions) {
        setContent {
            CodeEditorLayout(state = state, languageService = service, options = options, modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
    }

    private fun ComposeUiTest.type(text: String) {
        for (char in text) {
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput(char.toString())
            waitForIdle()
        }
    }

    private fun stateAtEnd(text: String) = CodeEditorState(text).apply { selection = TextRange(text.length) }

    @Test
    fun `a listed character is formatted as an undo step of its own`() = runComposeUiTest {
        val state = stateAtEnd("a {\n    ")
        val service = Outdent()
        show(state, service, EditorOptions(formatOnType = true))
        type("}")
        waitUntil(timeoutMillis = 5_000) { state.document.text == "a {\n}" }
        assertEquals(listOf(9 to '}'), service.asked, "asked with the caret after the character")
        assertEquals(TextRange(5), state.selection)
        state.undo()
        assertEquals("a {\n    }", state.document.text, "undo takes the formatting back and keeps what was typed")
        state.undo()
        assertEquals("a {\n    ", state.document.text)
    }

    @Test
    fun `nothing is asked while the option is off, or for a character the service does not list`() = runComposeUiTest {
        val state = stateAtEnd("a {\n    ")
        val service = Outdent()
        show(state, service, EditorOptions())
        type("}")
        assertEquals("a {\n    }", state.document.text)
        assertTrue(service.asked.isEmpty())

        val other = stateAtEnd("    ")
        val listening = Outdent()
        setContent {
            CodeEditorLayout(
                other,
                languageService = listening,
                options = EditorOptions(formatOnType = true),
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        type(";")
        assertTrue(listening.asked.isEmpty())
    }

    @Test
    fun `an answer about text that has since changed is dropped`() = runComposeUiTest {
        val state = stateAtEnd("a {\n    ")
        val gate = CompletableDeferred<Unit>()
        val service = Outdent(gate)
        show(state, service, EditorOptions(formatOnType = true))
        type("}")
        waitUntil(timeoutMillis = 5_000) { service.asked.isNotEmpty() }
        state.applyEdit(0, 0, "// ", TextRange(3))
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) { service.answered }
        waitForIdle()
        assertEquals("// a {\n    }", state.document.text)
    }
}
