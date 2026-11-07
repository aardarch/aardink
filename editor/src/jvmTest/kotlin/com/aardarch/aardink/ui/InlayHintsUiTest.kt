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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.EditorLimits
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `EditorOptions.inlayHints`: when the editor asks, about what, and what it does with the answer. */
@OptIn(ExperimentalTestApi::class)
class InlayHintsUiTest {

    private val on = EditorOptions(inlayHints = true)

    /** Answers each request only when the test says so, and says when it was called off. */
    private class HeldHints : LanguageService {
        class Request(val text: String, val range: IntRange) {
            val answer = CompletableDeferred<List<InlayHint>>()
            var cancelled = false
        }

        val requests = CopyOnWriteArrayList<Request>()

        override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> {
            val request = Request(document.text, range)
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

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        service: LanguageService?,
        find: FindReplaceState? = null,
        options: () -> EditorOptions = { on },
    ) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = service,
                findReplaceState = find,
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

    private fun ComposeUiTest.wheel(rows: Float) {
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { scroll(rows) }
        waitForIdle()
    }

    private fun ComposeUiTest.pixels(): IntArray = onNodeWithTag(EditorTestTags.EDITOR).captureToImage().toPixelMap().buffer.copyOf()

    /** The first answer, for a held request, so that the editor is at rest with [hints] on show. */
    private fun ComposeUiTest.answer(service: HeldHints, hints: List<InlayHint>) {
        pause()
        waitUntil { service.requests.isNotEmpty() }
        service.requests.last().answer.complete(hints)
        waitForIdle()
    }

    private val manyLines = (0 until 2_000).joinToString("\n") { "line $it" }

    // ── When it asks ─────────────────────────────────────────────────────────

    @Test
    fun `nothing is asked for with the option off`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldHints()
        show(state, service, options = { EditorOptions() })

        pause()
        type("x")
        pause()

        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `it asks after a pause, and not before`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldHints()
        mainClock.autoAdvance = false
        show(state, service)
        mainClock.advanceTimeBy(100)
        assertTrue(service.requests.isEmpty(), "no pause yet")

        mainClock.advanceTimeBy(400)
        mainClock.autoAdvance = true

        waitUntil { service.requests.size == 1 }
    }

    @Test
    fun `it asks about whole lines around the view, not the document`() = runComposeUiTest {
        val state = CodeEditorState(manyLines)
        val service = HeldHints()
        show(state, service)

        pause()
        waitUntil { service.requests.size == 1 }

        val document = state.document
        val range = service.requests.single().range
        val shown = state.visibleLines!!
        assertEquals(0, range.first, "from the top: there is nothing above")
        val lastLine = document.offsetToLineCol(range.last + 1).first
        assertEquals(document.lineEnd(lastLine), range.last + 1, "to the end of a line")
        assertTrue(lastLine > shown.last, "with a margin below the view: $lastLine past ${shown.last}")
        assertTrue(lastLine < shown.last + 2 * shown.count(), "and not much of one: $lastLine")
    }

    @Test
    fun `a burst of typing is one question, about the text it left`() = runComposeUiTest {
        val state = CodeEditorState("")
        val service = HeldHints()
        show(state, service)
        answer(service, emptyList())
        val asked = service.requests.size

        mainClock.autoAdvance = false
        for (char in "abc") {
            onNodeWithTag(EditorTestTags.EDITOR).performTextInput(char.toString())
            mainClock.advanceTimeBy(100)
        }
        assertEquals(asked, service.requests.size, "still typing")
        mainClock.advanceTimeBy(400)
        mainClock.autoAdvance = true
        waitUntil { service.requests.size == asked + 1 }

        assertEquals("abc", service.requests.last().text)
    }

    @Test
    fun `scrolling within what was asked about asks nothing`() = runComposeUiTest {
        val state = CodeEditorState(manyLines)
        val service = HeldHints()
        show(state, service)
        answer(service, emptyList())
        val top = state.visibleLines!!.first

        wheel(3f)
        pause()
        wheel(-3f)
        pause()
        assertEquals(top, state.visibleLines!!.first, "it came back")
        wheel(3f)
        pause()

        assertTrue(state.visibleLines!!.first > top, "it scrolled")
        assertEquals(1, service.requests.size)
    }

    @Test
    fun `a long scroll asks once it stops, not on the way`() = runComposeUiTest {
        val state = CodeEditorState(manyLines)
        val service = HeldHints()
        show(state, service)
        answer(service, emptyList())

        mainClock.autoAdvance = false
        repeat(40) {
            onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { scroll(10f) }
            mainClock.advanceTimeBy(16)
        }
        assertEquals(1, service.requests.size, "forty frames of scrolling asked nothing")
        assertTrue(state.visibleLines!!.first > 100, "it went past what was asked about: ${state.visibleLines}")

        mainClock.autoAdvance = true
        waitForIdle()
        pause()
        waitUntil { service.requests.size == 2 }

        val shown = state.visibleLines!!
        val document = state.document
        val range = service.requests.last().range
        assertTrue(range.first <= document.lineStart(shown.first), "$range covers the view, $shown")
        assertTrue(range.last + 1 >= document.lineEnd(shown.last), "$range covers the view, $shown")
        pause()
        assertEquals(2, service.requests.size, "and that was all")
    }

    @Test
    fun `a stale question is called off, and its answer never shown`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldHints()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }

        state.selection = TextRange(4)
        type("s")
        pause()
        waitUntil { service.requests.size == 2 }

        assertTrue(service.requests[0].cancelled)
        service.requests[0].answer.complete(listOf(InlayHint(0, "stale")))
        service.requests[1].answer.complete(listOf(InlayHint(5, "fresh")))
        waitUntil { state.inlayHints.isNotEmpty() }
        assertEquals(listOf(InlayHint(5, "fresh")), state.inlayHints)
    }

    @Test
    fun `an answer for text that has moved on in that same moment is not shown`() = runComposeUiTest {
        val state = CodeEditorState("word")
        val service = HeldHints()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }

        // Before the flow that cancels the question has run.
        runOnUiThread {
            state.applyEdit(0, 0, "x", TextRange(1))
            service.requests[0].answer.complete(listOf(InlayHint(0, "stale")))
        }
        waitForIdle()

        assertTrue(state.inlayHints.isEmpty())
    }

    @Test
    fun `a document past the analysis limit asks nothing and drops what it had`() = runComposeUiTest {
        val state = CodeEditorState("short")
        val service = HeldHints()
        show(state, service)
        answer(service, listOf(InlayHint(1, "h")))
        assertEquals(1, state.inlayHints.size)
        val asked = service.requests.size

        val limit = EditorLimits.plainTextFallbackChars
        state.computeOnMainThread = true
        EditorLimits.plainTextFallbackChars = 1
        try {
            state.selection = TextRange(5)
            type("s")
            pause()
            assertEquals(asked, service.requests.size)
            assertTrue(state.inlayHints.isEmpty())
        } finally {
            EditorLimits.plainTextFallbackChars = limit
        }
    }

    // ── What it does with an answer ──────────────────────────────────────────

    @Test
    fun `an answer repaints the text, with nothing else happening`() = runComposeUiTest {
        val state = CodeEditorState("val x = 1")
        val service = HeldHints()
        show(state, service)
        pause()
        waitUntil { service.requests.size == 1 }
        val before = pixels()
        assertContentEquals(before, pixels(), "nothing else is moving")

        service.requests[0].answer.complete(listOf(InlayHint(5, ": Int")))
        waitUntil { state.inlayHints.isNotEmpty() }
        waitForIdle()

        assertFalse(before.contentEquals(pixels()))
    }

    @Test
    fun `the answer stands until the next, moving with what is typed`() = runComposeUiTest {
        val state = CodeEditorState("val x = 1")
        val service = HeldHints()
        show(state, service)
        answer(service, listOf(InlayHint(5, ": Int")))
        assertEquals(1, service.requests.size)

        state.selection = TextRange(0)
        type("ab")

        assertEquals(listOf(InlayHint(7, ": Int")), state.inlayHints, "before any new answer")
        pause()
        waitUntil { service.requests.size == 2 }
        service.requests[1].answer.complete(listOf(InlayHint(7, ": Long")))
        waitUntil { state.inlayHints == listOf(InlayHint(7, ": Long")) }
    }

    @Test
    fun `what cannot show is left out of an answer`() = runComposeUiTest {
        val state = CodeEditorState(manyLines)
        val service = HeldHints()
        show(state, service)

        val beyond = state.document.lineStart(1_900)
        answer(
            service,
            listOf(
                InlayHint(-1, "before the document"),
                InlayHint(state.document.length + 1, "after it"),
                InlayHint(beyond, "outside what was asked"),
                InlayHint(1, " "),
                InlayHint(2, ""),
                InlayHint(3, "kept"),
            ),
        )

        assertEquals(listOf(InlayHint(3, "kept")), state.inlayHints)
    }

    @Test
    fun `turning the option off takes the hints away, and on asks again`() = runComposeUiTest {
        val state = CodeEditorState("val x = 1")
        val service = HeldHints()
        var options by mutableStateOf(on)
        show(state, service, options = { options })
        answer(service, listOf(InlayHint(5, ": Int")))
        assertEquals(1, state.inlayHints.size)

        options = EditorOptions()
        waitForIdle()
        assertTrue(state.inlayHints.isEmpty())

        options = on
        pause()
        waitUntil { service.requests.size == 2 }
    }

    @Test
    fun `another service's hints do not outlive it`() = runComposeUiTest {
        val state = CodeEditorState("val x = 1")
        val first = HeldHints()
        val second = HeldHints()
        var service by mutableStateOf(first)
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(state = state, languageService = service, options = on, modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        answer(first, listOf(InlayHint(5, ": Int")))

        service = second
        waitForIdle()

        assertTrue(state.inlayHints.isEmpty())
        pause()
        waitUntil { second.requests.size == 1 }
    }

    @Test
    fun `an answer leaves the caret and the scroll where they were`() = runComposeUiTest {
        val state = CodeEditorState((0 until 200).joinToString("\n") { "line $it with some more words on it, to wrap" })
        val service = HeldHints()
        show(state, service, options = { on.copy(softWrap = true) })
        pause()
        waitUntil { service.requests.isNotEmpty() }
        val caret = state.document.lineStart(60)
        state.navigateTo(caret)
        waitForIdle()
        pause()
        val request = service.requests.last()
        val top = state.viewport.firstVisibleLine
        val scrollTop = state.viewport.scrollTop
        assertTrue(top > 20, "the view is well down the document: $top")
        val document = state.document

        // Long enough to add rows on every line it asked about, above the view too.
        request.answer.complete((0 until top).map { InlayHint(document.lineStart(it) + 4, ": ${"h".repeat(120)}") })
        waitUntil { state.inlayHints.isNotEmpty() }
        waitForIdle()

        assertEquals(TextRange(caret), state.selection)
        assertEquals(top, state.viewport.firstVisibleLine, "the same line is at the top")
        assertTrue(state.viewport.scrollTop > scrollTop, "with the rows above it, which it moved past: ${state.viewport.scrollTop}")
    }

    // ── Around real hints ────────────────────────────────────────────────────

    @Test
    fun `the arrow keys step through the columns, not the hint`() = runComposeUiTest {
        val state = CodeEditorState("val x = 1")
        val service = HeldHints()
        show(state, service)
        answer(service, listOf(InlayHint(5, ": Int")))
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(Offset(4f, 4f)) }
        state.selection = TextRange(4)
        waitForIdle()

        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(TextRange(5), state.selection)
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(TextRange(6), state.selection, "one key past the hint, which is no stop")
    }

    @Test
    fun `find sees the document, not the hints`() = runComposeUiTest {
        val state = CodeEditorState("ab cd")
        val find = FindReplaceState()
        val service = HeldHints()
        show(state, service, find = find)
        // Between the characters a query spans, and with text of its own to be found.
        answer(service, listOf(InlayHint(1, "zzz")))

        find.visible = true
        find.query = "ab"
        waitUntil { find.matches.isNotEmpty() }
        assertEquals(listOf(0..1), find.matches.toList())

        find.query = "zzz"
        pause()
        waitUntil { find.matches.isEmpty() }
    }

    @Test
    fun `text typed at a hint goes in the document, once`() = runComposeUiTest {
        val state = CodeEditorState("val x = 1")
        val service = HeldHints()
        show(state, service)
        answer(service, listOf(InlayHint(5, ": Int")))

        state.selection = TextRange(5)
        type("y")

        assertEquals("val xy = 1", state.document.text)
        assertEquals(listOf(InlayHint(6, ": Int")), state.inlayHints)
    }
}
