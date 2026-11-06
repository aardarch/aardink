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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
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
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CODE = "val x = 1"

/** Code in documentation, in each popup that has any, highlighted by what is above the editor. */
class CodeFenceUiTest {

    /** Marks the start of whatever it is given, so a fence's colours can be told from none. */
    private class FirstWord : IncrementalTokenizer {
        val asked = ArrayList<String>()

        override fun tokenizeFull(text: String): List<Token> {
            synchronized(asked) { asked += text }
            return listOf(Token(0, 3, TokenType.Keyword))
        }

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

        fun wasAsked(text: String): Boolean = synchronized(asked) { text in asked }
    }

    private class Docs(val hover: String = "", val documentation: String? = null, val signatureDoc: String? = null) : LanguageService {
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> =
            listOf(CompletionItem("word", CompletionKind.Variable, "word", documentation = documentation))

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc = HoverDoc("Title", hover)

        override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? = signatureDoc?.let {
            SignatureHelp(listOf(SignatureInformation("f(a)", it, listOf(ParameterInformation("a")))), 0, 0)
        }

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    private fun ComposeUiTest.showEditor(
        state: CodeEditorState,
        service: LanguageService,
        fences: (String) -> IncrementalTokenizer? = { null },
    ) {
        show(fences) { CodeEditorLayout(state = state, languageService = service, modifier = Modifier.size(600.dp, 400.dp)) }
    }

    private fun ComposeUiTest.show(fences: (String) -> IncrementalTokenizer?, content: @Composable () -> Unit) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCodeFenceTokenizers provides fences, content = content)
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.spans(text: String = CODE) =
        onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles

    /** Comes back once the tokenizer, off the main thread, has answered and the answer is drawn. */
    private fun ComposeUiTest.awaitColoured(by: FirstWord, text: String = CODE) {
        waitUntil(timeoutMillis = 5_000) { by.wasAsked(text) }
        waitUntil(timeoutMillis = 5_000) { spans(text).isNotEmpty() }
    }

    private fun ComposeUiTest.hover(state: CodeEditorState) {
        runOnIdle { state.execute(EditorCommand.ShowHover) }
        waitForIdle()
    }

    @Test
    fun `a tagged fence in a hover is highlighted by the tokenizer above the editor`() = runComposeUiTest {
        val kotlin = FirstWord()
        val asked = ArrayList<String>()
        val state = CodeEditorState("hello")
        showEditor(state, Docs(hover = "```kotlin\n$CODE\n```"), { id -> kotlin.also { asked += id } })
        hover(state)
        awaitColoured(kotlin)
        assertEquals(setOf("kotlin"), asked.toSet())
        assertEquals(0..2, spans().single().let { it.start until it.end })
    }

    @Test
    fun `a tagged fence in signature help is highlighted too`() = runComposeUiTest {
        val kotlin = FirstWord()
        val state = CodeEditorState("f(")
        state.selection = TextRange(2)
        // Inside a call's arguments the card comes up on its own.
        showEditor(state, Docs(signatureDoc = "```kotlin\n$CODE\n```"), { kotlin })
        awaitColoured(kotlin)
    }

    @Test
    fun `a tagged fence in a completion's pane is highlighted too`() = runComposeUiTest {
        val kotlin = FirstWord()
        val state = CodeEditorState("")
        showEditor(state, Docs(documentation = "```kotlin\n$CODE\n```"), { kotlin })
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        waitForIdle()
        // The list's row says the same in its one line, uncoloured.
        val inPane = hasText(CODE) and hasAnyAncestor(hasTestTag(EditorTestTags.COMPLETION_DETAIL))
        waitUntil(timeoutMillis = 5_000) { kotlin.wasAsked(CODE) }
        waitUntil(timeoutMillis = 5_000) {
            onNode(inPane).fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles.isNotEmpty()
        }
    }

    @Test
    fun `an untagged fence takes the state's tokenizer`() = runComposeUiTest {
        val own = FirstWord()
        val state = CodeEditorState("hello", tokenizer = own)
        showEditor(state, Docs(hover = "```\n$CODE\n```"), { error("no tag to ask about") })
        hover(state)
        awaitColoured(own)
    }

    @Test
    fun `indented code counts as untagged`() = runComposeUiTest {
        val own = FirstWord()
        val state = CodeEditorState("hello", tokenizer = own)
        showEditor(state, Docs(hover = "    $CODE"))
        hover(state)
        awaitColoured(own)
    }

    @Test
    fun `an untagged fence follows a change of the state's tokenizer`() = runComposeUiTest {
        val before = FirstWord()
        val after = FirstWord()
        val state = CodeEditorState("hello", tokenizer = before)
        showEditor(state, Docs(hover = "```\n$CODE\n```"))
        runOnIdle { state.tokenizer = after }
        waitForIdle()
        hover(state)
        awaitColoured(after)
        assertTrue(!before.wasAsked(CODE))
    }

    @Test
    fun `a tag nothing resolves renders plain, not in the state's language`() = runComposeUiTest {
        val own = FirstWord()
        val state = CodeEditorState("hello", tokenizer = own)
        showEditor(state, Docs(hover = "```cobol\n$CODE\n```"))
        hover(state)
        onNodeWithText(CODE).assertExists()
        waitForIdle()
        assertTrue(spans().isEmpty())
        assertTrue(!own.wasAsked(CODE))
    }

    @Test
    fun `outside an editor a tagged fence still resolves, and an untagged one is plain`() = runComposeUiTest {
        val kotlin = FirstWord()
        show({ kotlin }) { HoverDocCard(HoverDoc("Title", "```kotlin\n$CODE\n```\n\n```\nval y = 2\n```")) }
        awaitColoured(kotlin)
        assertTrue(spans("val y = 2").isEmpty())
        assertTrue(!kotlin.wasAsked("val y = 2"))
    }

    @Test
    fun `a host answering anew each time does not restart the highlighting`() = runComposeUiTest {
        var made = 0
        val state = CodeEditorState("hello")
        showEditor(
            state,
            Docs(hover = "```kotlin\n$CODE\n```"),
            {
                made++
                FirstWord()
            },
        )
        hover(state)
        onNodeWithText(CODE).assertExists()
        waitForIdle()
        assertEquals(1, made)
    }
}
