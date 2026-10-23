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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import kotlin.test.Test
import kotlin.test.assertEquals

/** The completion list's own filtering and ordering, quick suggestions and commit characters. */
class CompletionUiTest {

    /** Offers three words anywhere, counting how often it is asked. */
    private class Words(override val completionsAreIncomplete: Boolean, val items: List<CompletionItem> = WORDS) : LanguageService {
        var asked = 0
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
            asked++
            return items
        }

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    private fun ComposeUiTest.show(state: CodeEditorState, service: LanguageService, options: EditorOptions = EditorOptions()) {
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = service,
                options = options,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.keys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    /** Types [text] a character at a time, as a keyboard does: more at once is a paste, which closes the list. */
    private fun ComposeUiTest.type(text: String) {
        for (char in text) {
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput(char.toString())
            waitForIdle()
        }
    }

    @Test
    fun `a complete list is asked for once and narrowed as the word grows`() = runComposeUiTest {
        val state = CodeEditorState("")
        val service = Words(completionsAreIncomplete = false)
        show(state, service)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        onNodeWithText("beta").assertExists()
        type("a")
        onNodeWithText("beta").assertDoesNotExist()
        onNodeWithText("alpine").assertExists()
        type("lpi")
        onNodeWithText("alpha").assertDoesNotExist()
        assertEquals(1, service.asked, "not asked again while the word grows")
        keys { pressKey(Key.Enter) }
        assertEquals("alpine", state.document.text)
    }

    @Test
    fun `an incomplete list is asked for again on every letter and not filtered`() = runComposeUiTest {
        val state = CodeEditorState("")
        val service = Words(completionsAreIncomplete = true)
        show(state, service)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        type("z")
        onNodeWithText("beta").assertExists()
        assertEquals(2, service.asked)
    }

    @Test
    fun `quick suggestions open the list on a letter, and only when switched on`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state, Words(completionsAreIncomplete = true), EditorOptions(quickSuggestions = true))
        type("a")
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
    }

    @Test
    fun `without quick suggestions a letter does not open the list`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state, Words(completionsAreIncomplete = true))
        type("a")
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
    }

    @Test
    fun `a commit character accepts the selected item in front of it`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state, Words(completionsAreIncomplete = false))
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        type("al")
        type(".")
        assertEquals("alpha.", state.document.text)
        assertEquals(TextRange(6), state.selection)
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
    }

    @Test
    fun `items are ordered by sortPriority, the service's order kept among equals`() = runComposeUiTest {
        val state = CodeEditorState("")
        val items = listOf(
            CompletionItem("third", CompletionKind.Value, "third", sortPriority = 2),
            CompletionItem("first", CompletionKind.Value, "first", sortPriority = 0),
            CompletionItem("second", CompletionKind.Value, "second", sortPriority = 0),
        )
        show(state, Words(completionsAreIncomplete = true, items = items))
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        keys { pressKey(Key.DirectionDown) }
        keys { pressKey(Key.Enter) }
        assertEquals("second", state.document.text)
    }

    private companion object {
        val WORDS = listOf(
            CompletionItem("alpha", CompletionKind.Variable, "alpha", commitCharacters = setOf('.')),
            CompletionItem("alpine", CompletionKind.Variable, "alpine"),
            CompletionItem("beta", CompletionKind.Variable, "beta", detail = "a letter"),
        )
    }
}
