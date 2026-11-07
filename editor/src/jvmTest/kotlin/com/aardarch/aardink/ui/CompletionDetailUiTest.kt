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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
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
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.Token
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pane beside the completion list: what it shows, where it goes, and what ends it. */
class CompletionDetailUiTest {

    private class Words(val items: List<CompletionItem>, override val completionsAreIncomplete: Boolean) : LanguageService {
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = items

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    /** Counts how often a fence is coloured, whichever pass it asks for. */
    private class CountingFence : IncrementalTokenizer {
        val passes = AtomicInteger()

        override fun tokenizeFull(text: String): List<Token> {
            passes.incrementAndGet()
            return emptyList()
        }

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
            passes.incrementAndGet()
            return emptyList()
        }

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private fun word(label: String, detail: String? = null, documentation: String? = null) =
        CompletionItem(label, CompletionKind.Variable, label, detail = detail, documentation = documentation)

    private val alpha = word("alpha", detail = "val alpha: Int", documentation = "The *first* letter.\n\n- with\n- a list")
    private val beta = word("beta", documentation = "The second letter.")
    private val gamma = word("gamma")

    private fun ComposeUiTest.show(
        items: List<CompletionItem>,
        text: String = "",
        onOpenLink: ((String) -> Unit)? = null,
        fences: (String) -> IncrementalTokenizer? = { null },
        askedOncePerWord: Boolean = false,
    ): CodeEditorState {
        val state = CodeEditorState(text)
        state.selection = TextRange(text.length)
        setContent {
            val service = Words(items, completionsAreIncomplete = !askedOncePerWord)
            val modifier = Modifier.size(900.dp, 600.dp)
            MaterialTheme {
                CompositionLocalProvider(LocalCodeFenceTokenizers provides fences) {
                    if (onOpenLink == null) {
                        CodeEditorLayout(state = state, languageService = service, modifier = modifier)
                    } else {
                        CodeEditorLayout(state = state, languageService = service, onOpenLink = onOpenLink, modifier = modifier)
                    }
                }
            }
        }
        waitForIdle()
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
        return state
    }

    /** Answers resolve with [answer], which may wait; what it was asked about is in [asked]. */
    private class Resolving(val items: List<CompletionItem>, val answer: suspend (CompletionItem) -> CompletionItem) :
        LanguageService by Words(items, completionsAreIncomplete = false) {
        val asked = mutableListOf<String>()

        override suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
            asked += item.label
            return answer(item)
        }
    }

    private fun ComposeUiTest.showResolving(service: Resolving, text: String, caret: Int = text.length): CodeEditorState {
        val state = CodeEditorState(text)
        state.selection = TextRange(caret)
        setContent {
            MaterialTheme { CodeEditorLayout(state = state, languageService = service, modifier = Modifier.size(900.dp, 600.dp)) }
        }
        waitForIdle()
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
        return state
    }

    private fun ComposeUiTest.keys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    private fun ComposeUiTest.inPane(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag(EditorTestTags.COMPLETION_DETAIL)))

    private fun ComposeUiTest.boundsOf(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow

    @Test
    fun `the pane shows the selected item's detail and its documentation as Markdown`() = runComposeUiTest {
        show(listOf(alpha, beta))
        inPane("val alpha: Int").assertExists()
        onNodeWithText("The first letter.").assertExists()
        onNodeWithText("with").assertExists()
        onNodeWithText("a list").assertExists()
    }

    @Test
    fun `the language service's answer reaches the pane`() = runComposeUiTest {
        showResolving(Resolving(listOf(gamma)) { it.copy(documentation = "Known once *resolved*.") }, "")
        onNodeWithText("Known once resolved.").assertExists()
    }

    @Test
    fun `a service that fails to resolve leaves the item as it is`() = runComposeUiTest {
        val state = showResolving(Resolving(listOf(alpha)) { error("no answer") }, "")
        inPane("val alpha: Int").assertExists()
        keys { pressKey(Key.Enter) }
        assertEquals("alpha", state.text)
    }

    @Test
    fun `an import known only once resolved goes in with the word accepted`() = runComposeUiTest {
        val import = TextEdit(0..-1, "import a\n")
        val state = showResolving(Resolving(listOf(gamma)) { it.copy(additionalEdits = listOf(import)) }, "x ")
        keys { pressKey(Key.Enter) }
        assertEquals("import a\nx gamma", state.text)
        assertEquals(TextRange("import a\nx gamma".length), state.selection, "the caret stays after the word")
    }

    @Test
    fun `the word does not wait for the answer, and an edit past it moves with it`() = runComposeUiTest {
        val answer = CompletableDeferred<CompletionItem>()
        val state = showResolving(Resolving(listOf(gamma)) { answer.await() }, "x \nend", caret = 2)
        keys { pressKey(Key.Enter) }
        assertEquals("x gamma\nend", state.text, "the word went in without the answer")
        answer.complete(gamma.copy(additionalEdits = listOf(TextEdit(3..2, "// "))))
        waitForIdle()
        assertEquals("x gamma\n// end", state.text)
    }

    @Test
    fun `text that moved on goes without the late edit`() = runComposeUiTest {
        val answer = CompletableDeferred<CompletionItem>()
        val state = showResolving(Resolving(listOf(gamma)) { answer.await() }, "x ")
        keys { pressKey(Key.Enter) }
        state.applyEdit(0, 0, "y", TextRange(1))
        answer.complete(gamma.copy(additionalEdits = listOf(TextEdit(0..-1, "import a\n"))))
        waitForIdle()
        assertEquals("yx gamma", state.text)
    }

    @Test
    fun `an edit inside the word replaced has nothing left to edit`() = runComposeUiTest {
        val state = showResolving(Resolving(listOf(gamma)) { it.copy(additionalEdits = listOf(TextEdit(2..3, "z"))) }, "x ga")
        keys { pressKey(Key.Enter) }
        assertEquals("x gamma", state.text)
    }

    @Test
    fun `an item with edits of its own is not asked about on accepting`() = runComposeUiTest {
        val own = gamma.copy(additionalEdits = listOf(TextEdit(0..-1, "own\n")))
        val service = Resolving(listOf(own)) { it }
        val state = showResolving(service, "")
        val before = service.asked.size
        keys { pressKey(Key.Enter) }
        assertEquals("own\ngamma", state.text)
        assertEquals(before, service.asked.size)
    }

    @Test
    fun `the pane follows the selection`() = runComposeUiTest {
        show(listOf(alpha, beta))
        onNodeWithText("a list").assertExists()
        keys { pressKey(Key.DirectionDown) }
        onNodeWithText("a list").assertDoesNotExist()
        inPane("The second letter.").assertExists()
        keys { pressKey(Key.DirectionUp) }
        onNodeWithText("a list").assertExists()
    }

    @Test
    fun `the row keeps its one line beside the pane`() = runComposeUiTest {
        show(listOf(gamma, word("delta", documentation = "One *line*.\n\nAnd another paragraph.")))
        onNodeWithText("One line.").assertExists()
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertDoesNotExist()
    }

    @Test
    fun `an item with nothing to say has no pane`() = runComposeUiTest {
        show(listOf(gamma, word("blank", detail = " ", documentation = "\n")))
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertDoesNotExist()
        keys { pressKey(Key.DirectionDown) }
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertDoesNotExist()
    }

    @Test
    fun `each item's documentation starts at its top, however far the last was scrolled`() = runComposeUiTest {
        val long = (1..40).joinToString("\n\n") { "Paragraph $it." }
        show(listOf(word("first", documentation = "First. $long"), word("second", documentation = "Second. $long")))
        val top = inPane("First. Paragraph 1.").fetchSemanticsNode().boundsInWindow.top
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).performMouseInput {
            moveTo(center)
            scroll(10f)
        }
        waitForIdle()
        assertTrue(inPane("First. Paragraph 1.").fetchSemanticsNode().boundsInWindow.top < top, "the pane scrolled")
        keys { pressKey(Key.DirectionDown) }
        assertEquals(top, inPane("Second. Paragraph 1.").fetchSemanticsNode().boundsInWindow.top)
    }

    @Test
    fun `typing on keeps the pane as it is, for an item that says what it replaces`() = runComposeUiTest {
        val fence = CountingFence()
        val long = (1..40).joinToString("\n\n") { "Paragraph $it." }
        val ranged = word("alpha", documentation = "Top. $long\n\n```kotlin\nval x = 1\n```").copy(replaceRange = 0..1)
        // Asked once per word, a keystroke re-addresses the items the list already has.
        val state = show(listOf(ranged), text = "al", fences = { fence }, askedOncePerWord = true)
        waitUntil(timeoutMillis = 5_000) { fence.passes.get() > 0 }
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).performMouseInput {
            moveTo(center)
            scroll(10f)
        }
        waitForIdle()
        val scrolled = inPane("Top. Paragraph 1.").fetchSemanticsNode().boundsInWindow.top
        onNodeWithTag(EditorTestTags.EDITOR).performTextInput("p")
        waitForIdle()
        assertEquals("alp", state.document.text)
        assertEquals(scrolled, inPane("Top. Paragraph 1.").fetchSemanticsNode().boundsInWindow.top, "the pane kept its place")
        assertEquals(1, fence.passes.get(), "the fence kept its colours")
    }

    @Test
    fun `a keystroke neither resolves the item again nor loses what resolving said`() = runComposeUiTest {
        val asked = ArrayList<CompletionItem>()
        var item by mutableStateOf(gamma.copy(replaceRange = 0..1))
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 600.dp)) {
                    CompletionPopup(
                        anchor = Rect(10f, 10f, 12f, 30f),
                        items = listOf(item),
                        selected = 0,
                        onAccept = {},
                        onDismiss = {},
                        resolve = {
                            asked += it
                            // A second answer would take a round trip, with the pane gone meanwhile.
                            if (asked.size > 1) awaitCancellation()
                            it.copy(documentation = "Now it says.")
                        },
                        onOpenLink = null,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Now it says.").assertExists()
        item = item.shiftedForInsert(2, 1, absorbing = true)
        waitForIdle()
        assertEquals(1, asked.size)
        onNodeWithText("Now it says.").assertExists()
    }

    @Test
    fun `overloads of one name are different items to the pane`() = runComposeUiTest {
        fun overload(detail: String) = word("alpha", detail = detail).copy(replaceRange = 0..1)
        show(listOf(overload("fun alpha(a: Int)"), overload("fun alpha(b: String)")), text = "al")
        inPane("fun alpha(a: Int)").assertExists()
        keys { pressKey(Key.DirectionDown) }
        inPane("fun alpha(b: String)").assertExists()
    }

    @Test
    fun `the pane goes with the list`() = runComposeUiTest {
        show(listOf(alpha))
        keys { pressKey(Key.Escape) }
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertDoesNotExist()
    }

    @Test
    fun `a press on the pane leaves the list up, and a press elsewhere takes both down`() = runComposeUiTest {
        val state = show(listOf(alpha))
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).performMouseInput { click(center) }
        waitForIdle()
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertExists()
        assertEquals("", state.document.text, "a press on the pane accepts nothing")
        onNodeWithTag(EditorTestTags.EDITOR).assertIsFocused()
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(Offset(width - 20f, height - 20f)) }
        waitForIdle()
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertDoesNotExist()
    }

    @Test
    fun `typing still goes to the editor with the pane up`() = runComposeUiTest {
        val state = show(listOf(alpha, beta))
        keys { pressKey(Key.Enter) }
        assertEquals("alpha", state.document.text)
    }

    @Test
    fun `a link in the pane goes to the host and closes the list`() = runComposeUiTest {
        val opened = ArrayList<String>()
        show(listOf(word("linked", documentation = "[the docs](https://a.dev/x)")), onOpenLink = { opened += it })
        inPane("the docs").performClick()
        waitForIdle()
        assertEquals(listOf("https://a.dev/x"), opened)
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
    }

    @Test
    fun `with no host handler a link in the pane is its text`() = runComposeUiTest {
        show(listOf(word("linked", documentation = "[the docs](https://a.dev/x)")))
        val shown = inPane("the docs").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(shown.getLinkAnnotations(0, shown.length).isEmpty())
    }

    @Test
    fun `the pane sits to the right of the list when there is room`() = runComposeUiTest {
        show(listOf(alpha))
        val list = boundsOf(EditorTestTags.COMPLETION_LIST)
        val pane = boundsOf(EditorTestTags.COMPLETION_DETAIL)
        assertTrue(pane.left >= list.right, "list $list, pane $pane")
        assertEquals(list.top, pane.top, "level with the list")
    }

    @Test
    fun `at the window's right edge the pane moves to the left of the list, on screen`() = runComposeUiTest {
        // The caret ends a line as wide as the window, so the list is against its right edge.
        show(listOf(alpha), text = "x".repeat(200))
        val list = boundsOf(EditorTestTags.COMPLETION_LIST)
        val pane = boundsOf(EditorTestTags.COMPLETION_DETAIL)
        assertTrue(pane.right <= list.left, "list $list, pane $pane")
        assertTrue(pane.left >= 0f, "pane $pane")
    }

    @Test
    fun `a selection arriving late still shows, and a later one is not overwritten by it`() = runComposeUiTest {
        val answers = mapOf(alpha to CompletableDeferred<CompletionItem>(), beta to CompletableDeferred())
        var selected by mutableIntStateOf(0)
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 600.dp)) {
                    CompletionPopup(
                        anchor = Rect(10f, 10f, 12f, 30f),
                        items = listOf(alpha, beta),
                        selected = selected,
                        onAccept = {},
                        onDismiss = {},
                        resolve = { answers.getValue(it).await() },
                        onOpenLink = null,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("The first letter.").assertExists()
        selected = 1
        waitForIdle()
        answers.getValue(alpha).complete(alpha.copy(documentation = "Too late."))
        waitForIdle()
        onNodeWithText("Too late.").assertDoesNotExist()
        inPane("The second letter.").assertExists()
        answers.getValue(beta).complete(beta.copy(documentation = "The *resolved* letter."))
        waitForIdle()
        onNodeWithText("The resolved letter.").assertExists()
    }

    @Test
    fun `an item that says nothing until resolved gets its pane then`() = runComposeUiTest {
        val answer = CompletableDeferred<CompletionItem>()
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 600.dp)) {
                    CompletionPopup(
                        anchor = Rect(10f, 10f, 12f, 30f),
                        items = listOf(gamma),
                        selected = 0,
                        onAccept = {},
                        onDismiss = {},
                        resolve = { answer.await() },
                        onOpenLink = null,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(EditorTestTags.COMPLETION_DETAIL).assertDoesNotExist()
        answer.complete(gamma.copy(documentation = "Now it says."))
        waitForIdle()
        onNodeWithText("Now it says.").assertExists()
    }
}
