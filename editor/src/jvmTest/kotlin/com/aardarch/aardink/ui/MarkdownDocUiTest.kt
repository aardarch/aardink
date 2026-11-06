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
package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEXT = "hello world\nsecond line\nthird line\nfourth line\nfifth line"

/** Markdown as the hover and signature cards draw it, and where their links go. */
@OptIn(ExperimentalTestApi::class)
class MarkdownDocUiTest {

    /** A [UriHandler] that fails the test by remembering: nothing may open a link past the host. */
    private class RecordingUriHandler : UriHandler {
        val opened = ArrayList<String>()

        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun ComposeUiTest.show(uriHandler: UriHandler = RecordingUriHandler(), content: @Composable () -> Unit) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler, content = content)
            }
        }
        waitForIdle()
    }

    @Test
    fun `the hover card draws each block`() = runComposeUiTest {
        val content = "# Heading\n\nSome *text*.\n\n- first\n- second\n\n```kotlin\nval x = 1\n```"
        show { HoverDocCard(HoverDoc("title", content)) }
        onNodeWithText("Heading").assertExists()
        onNodeWithText("Some text.").assertExists()
        onAllNodesWithText("•").assertCountEquals(2)
        onNodeWithText("first").assertExists()
        onNodeWithText("second").assertExists()
        onNodeWithText("val x = 1").assertExists()
    }

    @Test
    fun `an ordered list counts from its start`() = runComposeUiTest {
        show { HoverDocCard(HoverDoc("title", "3. a\n4. b")) }
        onNodeWithText("3.").assertExists()
        onNodeWithText("4.").assertExists()
    }

    @Test
    fun `raw HTML shows as written`() = runComposeUiTest {
        show { HoverDocCard(HoverDoc("title", "<b>bold</b>")) }
        onNodeWithText("<b>bold</b>").assertExists()
    }

    @Test
    fun `a link in a hover card goes to the host's handler and never to the platform`() = runComposeUiTest {
        val opened = ArrayList<String>()
        val uris = RecordingUriHandler()
        show(uris) { HoverDocCard(HoverDoc("title", "[the docs](https://a.dev/x)"), onOpenLink = { opened += it }) }
        onNodeWithText("the docs").performClick()
        waitForIdle()
        assertEquals(listOf("https://a.dev/x"), opened)
        assertTrue(uris.opened.isEmpty())
    }

    @Test
    fun `a link under a diagnostic goes to the host's handler`() = runComposeUiTest {
        val opened = ArrayList<String>()
        val warning = Diagnostic(0..0, "a warning", DiagnosticSeverity.Warning)
        show {
            EditorHoverCard(listOf(warning), HoverDoc("title", "[the docs](u)"), onQuickFix = null, onOpenLink = { opened += it })
        }
        onNodeWithText("the docs").performClick()
        waitForIdle()
        assertEquals(listOf("u"), opened)
    }

    @Test
    fun `a link in signature help goes to the host's handler`() = runComposeUiTest {
        val opened = ArrayList<String>()
        val uris = RecordingUriHandler()
        val help = SignatureHelp(
            listOf(SignatureInformation("foo(a: Int)", parameters = listOf(ParameterInformation("a: Int", "See [the docs](u)")))),
        )
        show(uris) { SignatureHelpCard(help, onOpenLink = { opened += it }) }
        onNodeWithText("See the docs").performMouseInput { click(Offset(width - 4f, height / 2f)) }
        waitForIdle()
        assertEquals(listOf("u"), opened)
        assertTrue(uris.opened.isEmpty())
    }

    @Test
    fun `signature help shows the signature's documentation as Markdown`() = runComposeUiTest {
        val help = SignatureHelp(listOf(SignatureInformation("foo()", documentation = "**Bold** words")))
        show { SignatureHelpCard(help) }
        onNodeWithText("Bold words").assertExists()
    }

    @Test
    fun `a card with no handler shows a link as its text, and a click opens nothing`() = runComposeUiTest {
        val uris = RecordingUriHandler()
        show(uris) { HoverDocCard(HoverDoc("title", "[the docs](https://a.dev/x)")) }
        onNodeWithText("the docs").performMouseInput { click() }
        waitForIdle()
        assertTrue(uris.opened.isEmpty())
    }

    @Test
    fun `long documentation scrolls inside the card instead of growing past the window`() = runComposeUiTest {
        val content = (1..200).joinToString("\n\n") { "Paragraph $it" }
        show { HoverDocCard(HoverDoc("title", content), Modifier.testTag("card")) }
        val height = onNodeWithTag("card").getBoundsInRoot().height
        assertTrue(height < 320.dp, "the card is $height tall")
    }

    @Test
    fun `an example stays plain text`() = runComposeUiTest {
        show { HoverDocCard(HoverDoc("title", "content", example = "*a* _b_")) }
        onNodeWithText("*a* _b_").assertExists()
    }

    @Test
    fun `a title stays plain text`() = runComposeUiTest {
        show { HoverDocCard(HoverDoc("<a_b_c> *x*", "content")) }
        onNodeWithText("<a_b_c> *x*").assertExists()
    }

    /** A service whose hover is [answer], and whose signature help is [signature] inside any call. */
    private class HoverService(private val signature: SignatureHelp? = null, private val answer: suspend () -> HoverDoc?) :
        LanguageService {
        override suspend fun completions(document: CodeDocument, offset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = answer()

        override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? = signature

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    private val linkedDoc = HoverDoc(title = "Docs for a word", content = "[the docs](https://a.dev/x)")

    private val linked = HoverService { linkedDoc }

    private val silent = HoverService { null }

    private fun ComposeUiTest.showEditor(uris: UriHandler, onOpenLink: (String) -> Unit) {
        val state = CodeEditorState(TEXT)
        show(uris) {
            CodeEditorLayout(
                state = state,
                languageService = linked,
                onOpenLink = onOpenLink,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
    }

    private fun ComposeUiTest.assertNoLinkIn(text: String) {
        val shown = onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(shown.getLinkAnnotations(0, shown.length).isEmpty(), "$text is drawn as a link")
    }

    /** A mouse resting on the first word, long enough for the card. */
    private fun ComposeUiTest.restOnWord() {
        mainClock.autoAdvance = false
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 10f, 8.dp.toPx() + 10.dp.toPx())) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
    }

    /** A mouse resting where nothing answers: the service has no hover to give, however long it stays. */
    private fun ComposeUiTest.restBeside() {
        mainClock.autoAdvance = false
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 10f, 8.dp.toPx() + 10.dp.toPx())) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertDoesNotExist()
    }

    /** The mouse leaves the word for the card below it: it crosses the text between them first. */
    private fun ComposeUiTest.crossTowardsCard() {
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 12f, 8.dp.toPx() + 14.dp.toPx())) }
        mainClock.advanceTimeBy(100)
        waitForIdle()
    }

    @Test
    fun `a mouse can cross from the word to the card and open its link`() = runComposeUiTest {
        val opened = ArrayList<String>()
        val uris = RecordingUriHandler()
        showEditor(uris) { opened += it }
        restOnWord()
        crossTowardsCard()
        onNodeWithText("the docs").performMouseInput { moveTo(center) }
        // Past when the card would have gone, and before the mouse's last spot could bring it back.
        mainClock.advanceTimeBy(300)
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        onNodeWithText("the docs").performMouseInput { click() }
        mainClock.advanceTimeBy(100)
        waitForIdle()
        assertEquals(listOf("https://a.dev/x"), opened)
        assertTrue(uris.opened.isEmpty())
        onNodeWithText("Docs for a word").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test
    fun `with no host handler a link in a hover card is its text, and a click leaves the card up`() = runComposeUiTest {
        val state = CodeEditorState(TEXT)
        show { CodeEditorLayout(state, languageService = linked, modifier = Modifier.size(600.dp, 400.dp)) }
        restOnWord()
        assertNoLinkIn("the docs")
        onNodeWithText("the docs").performMouseInput {
            moveTo(center)
            click()
        }
        mainClock.advanceTimeBy(100)
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
        mainClock.autoAdvance = true
    }

    @Test
    fun `with no host handler a link in signature help is its text`() = runComposeUiTest {
        val help = SignatureHelp(listOf(SignatureInformation("foo(a: Int)", documentation = "See [the docs](u)")))
        val state = CodeEditorState("foo(")
        state.setSelections(listOf(TextRange(4)))
        show { CodeEditorLayout(state, languageService = HoverService(help) { null }, modifier = Modifier.size(600.dp, 400.dp)) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertNoLinkIn("See the docs")
    }

    @Test
    fun `an answer that lands as the mouse moves on still takes the card down`() = runComposeUiTest {
        val answer = CompletableDeferred<HoverDoc?>()
        var asked = 0
        val service = HoverService {
            asked++
            if (asked == 1) answer.await() else null
        }
        // The fetch resumes on the UI's own queue, so the order of what follows is the test's.
        val state = CodeEditorState(TEXT).apply { computeDispatcher = Dispatchers.Unconfined }
        // After the editor has seen this move and before anything could react to it, the answer
        // comes: the window before the fetch is cancelled for the mouse having moved.
        val answerOnMove = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.type == PointerEventType.Move && asked == 1) answer.complete(linkedDoc)
                }
            }
        }
        show { CodeEditorLayout(state, languageService = service, modifier = Modifier.size(600.dp, 400.dp).then(answerOnMove)) }
        mainClock.autoAdvance = false
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 10f, 8.dp.toPx() + 10.dp.toPx())) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(1, asked)
        crossTowardsCard()
        onNodeWithText("Docs for a word").assertExists()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test
    fun `a card asked for from the keyboard stays under a parked mouse`() = runComposeUiTest {
        var keyboard = false
        val service = HoverService { linkedDoc.takeIf { keyboard } }
        val state = CodeEditorState(TEXT).apply { computeDispatcher = Dispatchers.Unconfined }
        show { CodeEditorLayout(state, languageService = service, modifier = Modifier.size(600.dp, 400.dp)) }
        restBeside()
        keyboard = true
        state.execute(EditorCommand.ShowHover)
        mainClock.advanceTimeBy(100)
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
        mainClock.autoAdvance = true
    }

    @Test
    fun `a new language service takes the old one's card down`() = runComposeUiTest {
        var service by mutableStateOf<LanguageService>(linked)
        val state = CodeEditorState(TEXT)
        show { CodeEditorLayout(state, languageService = service, modifier = Modifier.size(600.dp, 400.dp)) }
        restOnWord()
        service = silent
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test
    fun `an answer from the old language service does not bring a card up`() = runComposeUiTest {
        val answer = CompletableDeferred<HoverDoc?>()
        var service by mutableStateOf<LanguageService>(HoverService { answer.await() })
        val state = CodeEditorState(TEXT).apply { computeDispatcher = Dispatchers.Unconfined }
        show { CodeEditorLayout(state, languageService = service, modifier = Modifier.size(600.dp, 400.dp)) }
        mainClock.autoAdvance = false
        state.execute(EditorCommand.ShowHover)
        mainClock.advanceTimeBy(100)
        waitForIdle()
        service = silent
        mainClock.advanceTimeBy(100)
        waitForIdle()
        answer.complete(linkedDoc)
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test
    fun `the completion row shows documentation as one line of plain text`() = runComposeUiTest {
        val item = CompletionItem("wrap", CompletionKind.Property, "wrap", documentation = "Uses a\\_b *only* here\n\nand [more](u)")
        show { CompletionList(listOf(item), selected = -1, onAccept = {}) }
        onNodeWithText("Uses a_b only here").assertExists()
    }

    @Test
    fun `a mouse that moves on takes the card down`() = runComposeUiTest {
        showEditor(RecordingUriHandler()) {}
        restOnWord()
        crossTowardsCard()
        mainClock.advanceTimeBy(400)
        waitForIdle()
        onNodeWithText("Docs for a word").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test
    fun `a mouse that leaves the card takes it down`() = runComposeUiTest {
        showEditor(RecordingUriHandler()) {}
        restOnWord()
        crossTowardsCard()
        onNodeWithText("the docs").performMouseInput {
            moveTo(center)
            moveBy(Offset(0f, 2_000f))
        }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        onNodeWithText("Docs for a word").assertDoesNotExist()
        mainClock.autoAdvance = true
    }
}
