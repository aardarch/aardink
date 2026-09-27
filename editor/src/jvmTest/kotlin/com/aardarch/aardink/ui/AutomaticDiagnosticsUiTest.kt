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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CodeEditorLayout(diagnostics = null)`: the editor asks its language service once it is up and
 * again when typing pauses, and drops answers for text that has changed since.
 */
class AutomaticDiagnosticsUiTest {

    /**
     * Answers each request only when the test says so, and keeps working on a request the editor
     * has stopped waiting for, as a slow service that ignores cancellation would.
     */
    private class HeldDiagnostics : LanguageService {
        class Request(val text: String, val answer: CompletableDeferred<List<Diagnostic>>)

        val requests = CopyOnWriteArrayList<Request>()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
            val request = Request(document.text, CompletableDeferred())
            requests += request
            return withContext(NonCancellable) { request.answer.await() }
        }

        override val supportsRename: Boolean = false
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = 0
    }

    /** An error on the first [word] in [text]. */
    private fun flag(text: String, word: String): Diagnostic {
        val start = text.indexOf(word)
        return Diagnostic(start until start + word.length, text.take(start).count { it == '\n' }, "no $word here", DiagnosticSeverity.Error)
    }

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        service: LanguageService,
        diagnostics: List<Diagnostic>? = null,
        reported: MutableList<List<Diagnostic>> = mutableListOf(),
    ) {
        // The service runs on the test's own thread, so waitForIdle sees its answers arrive.
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = service,
                diagnostics = diagnostics,
                onDiagnosticsChange = { reported += it },
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    /** Taps the gutter dot on [line], which opens its diagnostic's message. */
    private fun ComposeUiTest.tapGutterDot(line: Int) {
        onNodeWithTag(EditorTestTags.GUTTER).performTouchInput {
            click(Offset(6.dp.toPx(), (8 + 20 * line + 10).dp.toPx()))
        }
        waitForIdle()
    }

    @Test
    fun `the service's diagnostics show as soon as it answers`() = runComposeUiTest {
        val state = CodeEditorState("one bad")
        val service = HeldDiagnostics()
        val reported = mutableListOf<List<Diagnostic>>()
        show(state, service, reported = reported)

        mainClock.advanceTimeBy(600)
        waitUntil { service.requests.size == 1 }
        assertEquals("one bad", service.requests[0].text)
        service.requests[0].answer.complete(listOf(flag("one bad", "bad")))
        waitUntil { reported.size == 1 }

        tapGutterDot(0)
        onNodeWithText("no bad here").assertExists()
    }

    @Test
    fun `an answer for text that has changed since is dropped, and the edit is asked about once typing pauses`() = runComposeUiTest {
        val state = CodeEditorState("one bad")
        val service = HeldDiagnostics()
        val reported = mutableListOf<List<Diagnostic>>()
        show(state, service, reported = reported)
        mainClock.advanceTimeBy(600)
        waitUntil { service.requests.size == 1 }

        // Typed while the service is still working on "one bad".
        state.selection = TextRange(7)
        onNodeWithTag(EditorTestTags.EDITOR).performTextInput("X")
        waitForIdle()
        service.requests[0].answer.complete(listOf(flag("one bad", "bad")))
        waitForIdle()
        assertTrue(reported.isEmpty(), "the answer for the old text is dropped")

        mainClock.advanceTimeBy(600)
        waitUntil { service.requests.size == 2 }
        assertEquals("one badX", service.requests[1].text)
        service.requests[1].answer.complete(listOf(flag("one badX", "badX")))
        waitUntil { reported.size == 1 }
        assertEquals(4..7, reported.single().single().range)
    }

    @Test
    fun `a pause in typing costs one request, not one per keystroke`() = runComposeUiTest {
        val state = CodeEditorState("")
        val service = HeldDiagnostics()
        show(state, service)
        mainClock.advanceTimeBy(600)
        waitUntil { service.requests.size == 1 }
        service.requests[0].answer.complete(emptyList())

        mainClock.autoAdvance = false
        for (char in "abc") {
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput(char.toString())
            mainClock.advanceTimeBy(100)
        }
        mainClock.advanceTimeBy(600)
        mainClock.autoAdvance = true
        waitUntil { service.requests.size == 2 }
        waitForIdle()
        assertEquals(listOf("", "abc"), service.requests.map { it.text })
    }

    @Test
    fun `a list from the host is shown, and the service is not asked`() = runComposeUiTest {
        val state = CodeEditorState("one bad")
        val service = HeldDiagnostics()
        val reported = mutableListOf<List<Diagnostic>>()
        show(state, service, diagnostics = listOf(flag("one bad", "bad")), reported = reported)
        mainClock.advanceTimeBy(600)
        waitForIdle()

        tapGutterDot(0)
        onNodeWithText("no bad here").assertExists()
        assertTrue(service.requests.isEmpty())
        assertTrue(reported.isEmpty(), "the host knows its own list")
    }

    @Test
    fun `the host's diagnostics move down with lines typed above them`() = runComposeUiTest {
        val state = CodeEditorState("one\nbad")
        show(state, HeldDiagnostics(), diagnostics = listOf(flag("one\nbad", "bad")))

        state.selection = TextRange(0)
        onNodeWithTag(EditorTestTags.EDITOR).performTextInput("zero\n")
        waitForIdle()

        tapGutterDot(2)
        onNodeWithText("no bad here").assertExists()
    }
}
