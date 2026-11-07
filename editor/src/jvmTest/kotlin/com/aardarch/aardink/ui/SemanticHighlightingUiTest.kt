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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.EditorLimits
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `EditorOptions.semanticHighlighting`: when the editor asks for semantic tokens and what it does with the answer. */
@OptIn(ExperimentalTestApi::class)
class SemanticHighlightingUiTest {

    private val type = NamedTokenType("type.class")

    /** Answers each request only when the test says so, and says when it was called off. */
    private class HeldTokens : LanguageService {
        class Request(val text: String, val context: CoroutineContext) {
            val answer = CompletableDeferred<List<Token>>()
            var cancelled = false
        }

        val requests = CopyOnWriteArrayList<Request>()

        override suspend fun semanticTokens(document: CodeDocument): List<Token> {
            val request = Request(document.text, currentCoroutineContext())
            requests += request
            return try {
                request.answer.await()
            } catch (e: CancellationException) {
                request.cancelled = true
                throw e
            }
        }

        override suspend fun diagnostics(document: CodeDocument) = emptyList<Diagnostic>()

        override val supportsRename: Boolean = false
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    private fun ComposeUiTest.show(state: CodeEditorState, service: LanguageService?, options: () -> EditorOptions = { ON }) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = service,
                options = options(),
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.pause() {
        mainClock.advanceTimeBy(400)
        waitForIdle()
    }

    private fun ComposeUiTest.type(text: String) {
        onNodeWithTag(EditorTestTags.EDITOR).performTextInput(text)
        waitForIdle()
    }

    @Test
    fun `nothing is asked for with the option off`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service, options = { EditorOptions() })

        pause()
        type("x")
        pause()

        assertTrue(service.requests.isEmpty())
        assertNull(state.semanticStore)
    }

    @Test
    fun `the answer becomes the layer`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service)

        pause()
        waitUntil { service.requests.size == 1 }
        assertEquals("word", service.requests[0].text)
        service.requests[0].answer.complete(listOf(Token(0, 4, type)))
        waitUntil { state.semanticStore != null }

        assertEquals(listOf(Token(0, 4, type)), state.semanticStore!!.tokensForLine(0))
    }

    @Test
    fun `the service runs on the state's compute dispatcher`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service)

        pause()
        waitUntil { service.requests.size == 1 }

        assertSame<CoroutineDispatcher>(
            state.computeDispatcher,
            service.requests[0].context[ContinuationInterceptor] as CoroutineDispatcher,
        )
    }

    @Test
    fun `a pause in typing costs one request, not one per keystroke`() = runComposeUiTest {
        val state = CodeEditorState("")
        val service = HeldTokens()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }
        service.requests[0].answer.complete(emptyList())

        mainClock.autoAdvance = false
        for (char in "abc") {
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput(char.toString())
            mainClock.advanceTimeBy(100)
        }
        assertEquals(1, service.requests.size, "still typing")
        mainClock.advanceTimeBy(400)
        mainClock.autoAdvance = true
        waitUntil { service.requests.size == 2 }
        waitForIdle()
        assertEquals(listOf("", "abc"), service.requests.map { it.text })
    }

    @Test
    fun `an edit cancels the request in flight and asks about the new text`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }

        state.selection = TextRange(4)
        type("s")
        assertTrue(service.requests[0].cancelled)

        pause()
        waitUntil { service.requests.size == 2 }
        assertEquals("words", service.requests[1].text)
        service.requests[1].answer.complete(listOf(Token(0, 5, type)))
        waitUntil { state.semanticStore != null }
        assertEquals(listOf(Token(0, 5, type)), state.semanticStore!!.tokensForLine(0))
    }

    @Test
    fun `an answer for text edited behind the state's back is dropped`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }

        // Straight through the document: no text version moves, so nothing is cancelled.
        state.document.insert(0, "x")
        service.requests[0].answer.complete(listOf(Token(0, 4, type)))
        waitForIdle()

        assertEquals(emptyList(), state.semanticStore?.tokensForLine(0).orEmpty())
    }

    @Test
    fun `the layer moves with typing until the next answer`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }
        service.requests[0].answer.complete(listOf(Token(0, 4, type)))
        waitUntil { state.semanticStore != null }

        state.selection = TextRange(0)
        type("ab ")

        assertEquals(listOf(Token(3, 7, type)), state.semanticStore!!.tokensForLine(0))
    }

    @Test
    fun `turning the option off takes the layer away and stops asking`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        var options by mutableStateOf(ON)
        show(state, service, options = { options })
        pause()
        waitUntil { service.requests.size == 1 }
        service.requests[0].answer.complete(listOf(Token(0, 4, type)))
        waitUntil { state.semanticStore != null }

        options = EditorOptions()
        waitForIdle()
        assertNull(state.semanticStore)

        type("x")
        pause()
        assertEquals(1, service.requests.size)
    }

    @Test
    fun `turning the option on asks at once after the pause, with no edit`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        var options by mutableStateOf(EditorOptions())
        show(state, service, options = { options })

        options = ON
        waitForIdle()
        pause()

        assertNotNull(service.requests.singleOrNull(), "asked once")
    }

    @Test
    fun `no language service means no layer`() = runComposeUiTest {
        val state = CodeEditorState("word")
        show(state, service = null)

        pause()

        assertNull(state.semanticStore)
    }

    @Test
    fun `a document past the analysis limit is not asked about, and loses its layer`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldTokens()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }
        service.requests[0].answer.complete(listOf(Token(0, 4, type)))
        waitUntil { state.semanticStore != null }

        val limit = EditorLimits.plainTextFallbackChars
        state.computeOnMainThread = true
        EditorLimits.plainTextFallbackChars = 1
        try {
            state.selection = TextRange(4)
            type("s")
            pause()
            assertEquals(1, service.requests.size)
            assertNull(state.semanticStore)
        } finally {
            EditorLimits.plainTextFallbackChars = limit
        }
    }

    private companion object {
        val ON = EditorOptions(semanticHighlighting = true)
    }
}
