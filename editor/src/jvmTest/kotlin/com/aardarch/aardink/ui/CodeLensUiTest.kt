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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.EditorLimits
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `EditorOptions.codeLens`: when the editor asks, what it does with the answer, and a click or a tap on a lens. */
@OptIn(ExperimentalTestApi::class)
class CodeLensUiTest {

    private val on = EditorOptions(codeLens = true)

    /** Answers each request only when the test says so, and says when it was called off. */
    private open class HeldLenses : LanguageService {
        class Request(val text: String) {
            val answer = CompletableDeferred<List<CodeLens>>()
            var cancelled = false
        }

        val requests = CopyOnWriteArrayList<Request>()
        val hoveredAt = CopyOnWriteArrayList<Int>()

        override suspend fun codeLenses(document: CodeDocument): List<CodeLens> {
            val request = Request(document.text)
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

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? {
            hoveredAt += offset
            return null
        }

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""
    }

    /** Answers at once with what [lenses] says about the text, and counts the questions. */
    private class Answering(val lenses: (String) -> List<CodeLens>) : HeldLenses() {
        var asked = 0

        override suspend fun codeLenses(document: CodeDocument): List<CodeLens> {
            asked++
            return lenses(document.text)
        }
    }

    private fun ComposeUiTest.show(state: CodeEditorState, service: LanguageService?, options: () -> EditorOptions = { on }) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(state = state, languageService = service, options = options(), modifier = Modifier.size(600.dp, 400.dp))
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

    private fun ComposeUiTest.answer(service: HeldLenses, lenses: List<CodeLens>) {
        pause()
        waitUntil { service.requests.isNotEmpty() }
        service.requests.last().answer.complete(lenses)
        waitForIdle()
    }

    private val manyLines = (0 until 2_000).joinToString("\n") { "line $it" }

    // ── When it asks ─────────────────────────────────────────────────────────

    @Test
    fun `nothing is asked for with the option off`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldLenses()
        show(state, service, options = { EditorOptions() })

        pause()
        type("x")
        pause()

        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `it asks after a pause, and not before`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldLenses()
        mainClock.autoAdvance = false
        show(state, service)
        mainClock.advanceTimeBy(100)
        assertTrue(service.requests.isEmpty(), "no pause yet")

        mainClock.advanceTimeBy(400)
        mainClock.autoAdvance = true

        waitUntil { service.requests.size == 1 }
    }

    @Test
    fun `an edit calls off the question out, and asks about the new text after a pause`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldLenses()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }

        state.selection = TextRange(4)
        type("s")
        pause()

        waitUntil { service.requests.size == 2 }
        assertTrue(service.requests[0].cancelled)
        assertEquals("words", service.requests[1].text)
    }

    @Test
    fun `scrolling asks nothing of a service whose lenses all have titles`() = runComposeUiTest {
        val state = CodeEditorState(manyLines)
        val service = Answering { listOf(CodeLens(0, "top"), CodeLens(it.length - 1, "bottom")) }
        show(state, service)
        pause()
        waitUntil { state.codeLenses.size == 2 }
        val asked = service.asked

        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { scroll(4_000f) }
        waitForIdle()
        pause()
        pause()

        assertEquals(asked, service.asked)
    }

    @Test
    fun `at rest nothing more is asked`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = Answering { listOf(CodeLens(0, "lens")) }
        show(state, service)
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }

        pause()
        pause()

        assertEquals(1, service.asked)
    }

    @Test
    fun `a document past the analysis limit asks nothing, and shows nothing`() = runComposeUiTest {
        val state = CodeEditorState("words")
        val service = HeldLenses()
        show(state, service)
        answer(service, listOf(CodeLens(0, "lens")))
        assertEquals(1, state.codeLenses.size)
        val asked = service.requests.size

        val limit = EditorLimits.plainTextFallbackChars
        state.computeOnMainThread = true
        EditorLimits.plainTextFallbackChars = 1
        try {
            state.selection = TextRange(5)
            type("s")
            pause()
            assertEquals(asked, service.requests.size)
            assertTrue(state.codeLenses.isEmpty())
        } finally {
            EditorLimits.plainTextFallbackChars = limit
        }
    }

    // ── What it does with the answer ─────────────────────────────────────────

    @Test
    fun `lenses stay on their text while the next answer is out`() = runComposeUiTest {
        val state = CodeEditorState("first\nsecond")
        val service = HeldLenses()
        show(state, service)
        answer(service, listOf(CodeLens(6, "lens")))

        state.selection = TextRange(0)
        type("xx")

        assertEquals(listOf(8), state.codeLenses.map { it.offset })
    }

    @Test
    fun `an offset past the document is left out, and the rest kept`() = runComposeUiTest {
        val state = CodeEditorState("abc")
        val service = HeldLenses()
        show(state, service)

        answer(service, listOf(CodeLens(99, "lost"), CodeLens(-1, "lost"), CodeLens(1, "kept")))

        assertEquals(listOf("kept"), state.codeLenses.map { it.title })
    }

    @Test
    fun `turning the option off takes the lenses away, and on asks again`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = Answering { listOf(CodeLens(0, "lens")) }
        var options by mutableStateOf(on)
        show(state, service, options = { options })
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }

        options = EditorOptions()
        waitForIdle()
        assertTrue(state.codeLenses.isEmpty())

        options = on
        waitForIdle()
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }
        assertEquals(2, service.asked)
    }

    @Test
    fun `an answer past a service stuck behind it is shown, and the whole answer replaces it`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val behind = HeldLenses()
        val own = Answering { listOf(CodeLens(0, "own")) }
        show(state, CompositeLanguageService(own, behind))
        pause()
        waitUntil { behind.requests.size == 1 }
        assertTrue(state.codeLenses.isEmpty(), "the rest may be about to come")

        mainClock.advanceTimeBy(1_100)
        waitForIdle()
        assertEquals(listOf("own"), state.codeLenses.map { it.title })

        behind.requests.single().answer.complete(listOf(CodeLens(1, "behind")))
        waitForIdle()
        assertEquals(listOf("own", "behind"), state.codeLenses.map { it.title })
    }

    // ── Titles still to come ─────────────────────────────────────────────────

    /** A lens on every 100th line, with its title only once [titled] says so. */
    private class Unresolved(val document: CodeEditorState) : HeldLenses() {
        val titled = HashSet<Int>()
        var asked = 0

        override suspend fun codeLenses(document: CodeDocument): List<CodeLens> {
            asked++
            val shown = this.document.viewport
            titled += (0 until document.lineCount).filter { it in shown.firstVisibleLine..shown.lastVisibleLine }
            return (0 until document.lineCount step 100).map { CodeLens(document.lineStart(it), if (it in titled) "line $it" else "") }
        }
    }

    @Test
    fun `a lens with no title coming on screen is asked about after a pause, and only then`() = runComposeUiTest {
        val state = CodeEditorState(manyLines)
        val service = Unresolved(state)
        show(state, service)
        pause()
        waitUntil { state.codeLenses.size == 20 }
        waitForIdle()
        assertEquals("line 0", state.codeLenses.first().title)
        assertEquals("", state.codeLenses[5].title)
        val asked = service.asked

        state.execute(EditorCommand.ScrollTo(state.viewport.contentHeight * 500f / 2_020f))
        waitForIdle()
        pause()

        waitUntil { state.codeLenses[5].title == "line 500" }
        assertEquals(asked + 1, service.asked)
    }

    @Test
    fun `an answer that leaves a lens with no title is not asked for again and again`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = Answering { listOf(CodeLens(0, "")) }
        show(state, service)
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }
        pause()
        assertEquals(2, service.asked, "once, and once for the lens now seen on screen")

        pause()
        pause()
        pause()

        assertEquals(2, service.asked)
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    @Test
    fun `a click on a lens runs it, and the caret stays where it was`() = runComposeUiTest {
        val state = CodeEditorState("first\nsecond")
        var clicked = 0
        show(state, Answering { listOf(CodeLens(0, "lens") { clicked++ }) })
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }
        state.selection = TextRange(8)
        waitForIdle()

        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(Offset(8.dp.toPx() + 6f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()

        assertEquals(1, clicked)
        assertEquals(TextRange(8), state.selection)
    }

    @Test
    fun `a tap on a lens runs it, and the caret stays where it was`() = runComposeUiTest {
        val state = CodeEditorState("first\nsecond")
        var clicked = 0
        show(state, Answering { listOf(CodeLens(0, "lens") { clicked++ }) })
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }
        state.selection = TextRange(8)
        waitForIdle()

        onNodeWithTag(EditorTestTags.EDITOR).performTouchInput { click(Offset(8.dp.toPx() + 6f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()

        assertEquals(1, clicked)
        assertEquals(TextRange(8), state.selection)
    }

    @Test
    fun `a click below a lens is a click on the text`() = runComposeUiTest {
        val state = CodeEditorState("first\nsecond")
        var clicked = 0
        show(state, Answering { listOf(CodeLens(0, "lens") { clicked++ }) })
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }
        state.selection = TextRange(8)
        waitForIdle()

        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(Offset(8.dp.toPx() + 1f, 8.dp.toPx() + 30.dp.toPx())) }
        waitForIdle()

        assertEquals(0, clicked)
        assertEquals(TextRange(0), state.selection)
    }

    @Test
    fun `resting on a lens asks for no hover of the line under it, and on text it does`() = runComposeUiTest {
        val state = CodeEditorState("first\nsecond")
        val service = Answering { listOf(CodeLens(0, "lens")) }
        show(state, service)
        pause()
        waitUntil { state.codeLenses.isNotEmpty() }

        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 6f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(service.hoveredAt.isEmpty())

        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 6f, 8.dp.toPx() + 30.dp.toPx())) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(1, service.hoveredAt.size)
    }
}
