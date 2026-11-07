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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
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
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.core.InlineCompletionProvider
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `CodeEditorLayout(inlineCompletionProvider)`: when it is asked, and what the keys do with the answer. */
@OptIn(ExperimentalTestApi::class)
class InlineCompletionUiTest {

    /** Answers each request only when the test says so, and says when it was called off. */
    private class Held : InlineCompletionProvider {
        class Request(val text: String, val offset: Int) {
            val answer = CompletableDeferred<List<InlineCompletion>>()
            var cancelled = false
        }

        val requests = CopyOnWriteArrayList<Request>()

        override suspend fun provideInlineCompletions(document: CodeDocument, cursorOffset: Int): List<InlineCompletion> {
            val request = Request(document.text, cursorOffset)
            requests += request
            return try {
                request.answer.await()
            } catch (e: CancellationException) {
                request.cancelled = true
                throw e
            }
        }
    }

    /** Always answers [answers], counting how often it is asked. */
    private class Ready(vararg val answers: String) : InlineCompletionProvider {
        @Volatile var asked = 0

        override suspend fun provideInlineCompletions(document: CodeDocument, cursorOffset: Int): List<InlineCompletion> {
            asked++
            return answers.map { InlineCompletion(it) }
        }
    }

    /** [to], under a new instance each time one is built. */
    private class Passing(to: InlineCompletionProvider) : InlineCompletionProvider by to

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        provider: InlineCompletionProvider?,
        options: () -> EditorOptions = { EditorOptions() },
        find: FindReplaceState? = null,
        service: LanguageService? = null,
    ) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(
                state = state,
                options = options(),
                findReplaceState = find,
                languageService = service,
                inlineCompletionProvider = provider,
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

    private fun ComposeUiTest.keys(block: KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    private fun ComposeUiTest.pixels(): IntArray = onNodeWithTag(EditorTestTags.EDITOR).captureToImage().toPixelMap().buffer.copyOf()

    /** Types [text] and waits out the pause, with [provider] answering at once. */
    private fun ComposeUiTest.typeAndPause(text: String) {
        type(text)
        pause()
    }

    private fun stateAtEnd(text: String) = CodeEditorState(text).apply { selection = TextRange(text.length) }

    private val CodeEditorState.ghost: String? get() = inlineCompletions.shown?.pieces?.joinToString("|") { it.text }

    // ── When it asks ─────────────────────────────────────────────────────────

    @Test
    fun `nothing is asked until something is typed`() = runComposeUiTest {
        val provider = Ready("x")
        show(stateAtEnd("word"), provider)
        pause()
        assertEquals(0, provider.asked)
    }

    @Test
    fun `it is asked once typing pauses, not for each key`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Held()
        show(state, provider)
        for (char in "abc") {
            type(char.toString())
            mainClock.advanceTimeBy(100)
            waitForIdle()
        }
        assertEquals(0, provider.requests.size, "still typing")
        pause()
        assertEquals(1, provider.requests.size)
        assertEquals("abc", provider.requests.single().text)
        assertEquals(3, provider.requests.single().offset)
    }

    @Test
    fun `an answer shows without touching the document`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Held()
        show(state, provider)
        type("a")
        val without = pixels()
        pause()
        provider.requests.single().answer.complete(listOf(InlineCompletion("bcdef")))
        waitForIdle()
        assertEquals("bcdef", state.ghost)
        assertEquals("a", state.document.text)
        assertFalse(without.contentEquals(pixels()), "it is drawn")
    }

    @Test
    fun `a keystroke calls off a request still running, and its late answer is dropped`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Held()
        show(state, provider)
        typeAndPause("a")
        val slow = provider.requests.single()
        type("b")
        assertTrue(slow.cancelled, "called off")
        slow.answer.complete(listOf(InlineCompletion("late")))
        pause()
        assertNull(state.ghost)
        assertEquals(2, provider.requests.size, "the new text is asked about")
        provider.requests.last().answer.complete(listOf(InlineCompletion("c")))
        waitForIdle()
        assertEquals("c", state.ghost)
    }

    @Test
    fun `a provider that ignores being called off costs nothing`() = runComposeUiTest {
        val state = stateAtEnd("")
        var release: CompletableDeferred<Unit>? = null
        val stubborn = InlineCompletionProvider { _, _ ->
            val mine = CompletableDeferred<Unit>().also { release = it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mine.await() }
            listOf(InlineCompletion("late"))
        }
        show(state, stubborn)
        typeAndPause("a")
        type("b")
        release?.complete(Unit)
        waitForIdle()
        assertNull(state.ghost)
    }

    @Test
    fun `moving the caret calls off a request`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Held()
        show(state, provider)
        typeAndPause("ab")
        state.selection = TextRange(0)
        waitForIdle()
        assertTrue(provider.requests.single().cancelled)
    }

    @Test
    fun `typing what it says asks nothing more`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Ready("bcd")
        show(state, provider)
        typeAndPause("a")
        assertEquals(1, provider.asked)
        typeAndPause("b")
        typeAndPause("c")
        assertEquals("d", state.ghost)
        assertEquals(1, provider.asked)
    }

    @Test
    fun `typing something else drops it and asks again after a pause`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Ready("bcd")
        show(state, provider)
        typeAndPause("a")
        type("z")
        assertNull(state.ghost)
        assertEquals(1, provider.asked, "not before the pause")
        pause()
        assertEquals(2, provider.asked)
    }

    @Test
    fun `backspace drops it and asks again after a pause`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Ready("bcd")
        show(state, provider)
        typeAndPause("xa")
        keys { pressKey(Key.Backspace) }
        assertNull(state.ghost)
        pause()
        assertEquals(2, provider.asked)
        assertEquals("bcd", state.ghost)
    }

    @Test
    fun `a provider that throws shows nothing and leaves the editor working`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, InlineCompletionProvider { _, _ -> error("no") })
        typeAndPause("a")
        assertNull(state.ghost)
        type("b")
        assertEquals("ab", state.document.text)
    }

    @Test
    fun `a new provider ends what the last one offered`() = runComposeUiTest {
        val state = stateAtEnd("")
        state.computeDispatcher = Dispatchers.Unconfined
        var provider by mutableStateOf<InlineCompletionProvider?>(Ready("one"))
        setContent {
            CodeEditorLayout(state = state, inlineCompletionProvider = provider, modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        typeAndPause("a")
        assertEquals("one", state.ghost)
        provider = null
        waitForIdle()
        assertNull(state.ghost)
        keys { pressKey(Key.Tab) }
        assertEquals("a    ", state.document.text, "Tab indents again")
    }

    @Test
    fun `a provider built in composition leaves what is on offer alone, so a host's accept button stays`() = runComposeUiTest {
        val state = stateAtEnd("")
        state.computeDispatcher = Dispatchers.Unconfined
        var canAccept = false
        setContent {
            // The accept button a phone needs: this scope runs again as ghost text comes.
            canAccept = EditorCommand.AcceptInlineCompletion in state.availableCommands
            CodeEditorLayout(state = state, inlineCompletionProvider = Ready("one"), modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        typeAndPause("a")
        assertEquals("one", state.ghost)
        assertTrue(canAccept)
    }

    @Test
    fun `a recomposition leaves a provider at work alone`() = runComposeUiTest {
        val state = stateAtEnd("")
        state.computeDispatcher = Dispatchers.Unconfined
        val held = Held()
        var caretShown by mutableStateOf(0)
        setContent {
            caretShown
            CodeEditorLayout(state = state, inlineCompletionProvider = Passing(held), modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        typeAndPause("a")
        caretShown++
        waitForIdle()
        val request = held.requests.single()
        assertFalse(request.cancelled)
        request.answer.complete(listOf(InlineCompletion("one")))
        waitForIdle()
        assertEquals("one", state.ghost)
    }

    @Test
    fun `a provider put in its place answers from the next ask`() = runComposeUiTest {
        val state = stateAtEnd("")
        state.computeDispatcher = Dispatchers.Unconfined
        var provider by mutableStateOf<InlineCompletionProvider?>(Ready("one"))
        setContent {
            CodeEditorLayout(state = state, inlineCompletionProvider = provider, modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        typeAndPause("a")
        assertEquals("one", state.ghost)
        provider = Ready("two")
        waitForIdle()
        typeAndPause("z")
        assertEquals("two", state.ghost)
    }

    @Test
    fun `an editor that leaves the screen takes it with it`() = runComposeUiTest {
        val state = stateAtEnd("")
        state.computeDispatcher = Dispatchers.Unconfined
        var shown by mutableStateOf(true)
        setContent {
            if (shown) CodeEditorLayout(state = state, inlineCompletionProvider = Ready("one"), modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        typeAndPause("a")
        assertEquals("one", state.ghost)
        shown = false
        waitForIdle()
        assertNull(state.ghost)
        assertFalse(state.inlineCompletions.provided)
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    @Test
    fun `Tab accepts it, as one undo step, with the caret at the end`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"))
        typeAndPause("a")
        keys { pressKey(Key.Tab) }
        assertEquals("abcd", state.document.text)
        assertEquals(TextRange(4), state.selection)
        state.undo()
        assertEquals("a", state.document.text)
    }

    @Test
    fun `Tab indents when nothing shows`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, InlineCompletionProvider { _, _ -> emptyList() })
        typeAndPause("a")
        keys { pressKey(Key.Tab) }
        assertEquals("a    ", state.document.text)
    }

    @Test
    fun `Tab indents with no provider at all`() = runComposeUiTest {
        val state = stateAtEnd("a")
        show(state, null)
        keys { pressKey(Key.Tab) }
        assertEquals("a    ", state.document.text)
    }

    @Test
    fun `Down leaves the line, past rows of it and the text they push down`() = runComposeUiTest {
        val state = CodeEditorState("foo()\nnext").apply { selection = TextRange(4) }
        show(state, InlineCompletionProvider { _, _ -> listOf(InlineCompletion("a,\n    b)")) })
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        assertEquals("a,\n    b", state.ghost)
        keys { pressKey(Key.DirectionDown) }
        assertEquals(1, state.document.offsetToLineCol(state.selection.end).first)
        assertNull(state.ghost)
    }

    /** The caret in `foo(|)` with `next` below it, and rows of ghost text showing. */
    private fun ComposeUiTest.showRows(): CodeEditorState {
        val state = CodeEditorState("foo()\nnext").apply { selection = TextRange(4) }
        show(state, InlineCompletionProvider { _, _ -> listOf(InlineCompletion("a,\n    b)")) })
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        assertEquals("a,\n    b", state.ghost)
        return state
    }

    private val CodeEditorState.caretLines: List<Int>
        get() = currentSelections().ranges.map { document.offsetToLineCol(it.end).first }.sorted()

    @Test
    fun `a caret added below goes to the next line, past rows of it and the text they push down`() = runComposeUiTest {
        val state = showRows()
        keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } } }
        assertEquals(listOf(0, 1), state.caretLines)
        assertNull(state.ghost)
    }

    @Test
    fun `a column selection goes down to the next line, past rows of it`() = runComposeUiTest {
        val state = showRows()
        keys {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
            }
        }
        assertEquals(listOf(0, 1), state.caretLines)
        assertNull(state.ghost)
    }

    @Test
    fun `Shift+Tab is not accepting`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"))
        typeAndPause("    a")
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        assertEquals("a", state.document.text)
    }

    @Test
    fun `Alt with a bracket goes through what is on offer`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("one", "two", "three"))
        typeAndPause("a")
        assertEquals("one", state.ghost)
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        assertEquals("two", state.ghost)
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.LeftBracket) } }
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.LeftBracket) } }
        assertEquals("three", state.ghost)
        keys { pressKey(Key.Tab) }
        assertEquals("athree", state.document.text)
    }

    @Test
    fun `Alt with a bracket with nothing showing asks now, with no pause`() = runComposeUiTest {
        val state = stateAtEnd("a")
        val provider = Ready("one")
        show(state, provider)
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        assertEquals(1, provider.asked)
        assertEquals("one", state.ghost)
    }

    @Test
    fun `Escape dismisses it and asks nothing, and only then reaches the find panel`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Ready("bcd")
        val find = FindReplaceState().apply { show() }
        show(state, provider, find = find)
        typeAndPause("a")
        keys { pressKey(Key.Escape) }
        assertNull(state.ghost)
        assertTrue(find.visible, "the first Escape was for the ghost")
        pause()
        assertEquals(1, provider.asked)
        keys { pressKey(Key.Escape) }
        assertFalse(find.visible)
    }

    @Test
    fun `dismissing from a button leaves it gone until the next edit asks`() = runComposeUiTest {
        val state = stateAtEnd("")
        val provider = Ready("bcd")
        show(state, provider)
        typeAndPause("a")
        assertTrue(EditorCommand.DismissInlineCompletion in state.availableCommands)
        assertTrue(state.execute(EditorCommand.DismissInlineCompletion))
        waitForIdle()
        assertNull(state.ghost)
        assertFalse(EditorCommand.DismissInlineCompletion in state.availableCommands)
        pause()
        assertNull(state.ghost, "it stays gone")
        assertEquals(1, provider.asked)
        typeAndPause("b")
        assertEquals(2, provider.asked, "an edit asks again")
        assertEquals("bcd", state.ghost)
    }

    /** Answers hover at any place, for what Escape closes after ghost text. */
    private class Documented : LanguageService by Words() {
        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc = HoverDoc("documented", "text")
    }

    @Test
    fun `Escape dismisses ahead of the hover, and the next one closes it`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"), service = Documented())
        typeAndPause("a")
        assertTrue(state.execute(EditorCommand.ShowHover))
        waitUntil { onAllNodes(hasText("documented")).fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(state.ghost)
        keys { pressKey(Key.Escape) }
        assertNull(state.ghost)
        onNodeWithText("documented").assertExists()
        keys { pressKey(Key.Escape) }
        onNodeWithText("documented").assertDoesNotExist()
    }

    @Test
    fun `a snippet ends it, and Escape goes to the snippet`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"))
        typeAndPause("a")
        assertTrue(state.execute(EditorCommand.InsertSnippet("<$1>$0")))
        waitForIdle()
        assertNull(state.ghost)
        keys { pressKey(Key.Escape) }
        assertNull(state.snippet)
    }

    @Test
    fun `a second caret ends it, and Escape goes to the carets`() = runComposeUiTest {
        val state = stateAtEnd("one\n")
        show(state, Ready("bcd"))
        typeAndPause("a")
        keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } } }
        assertEquals(2, state.selections.size)
        assertNull(state.ghost)
        keys { pressKey(Key.Escape) }
        assertEquals(1, state.selections.size)
    }

    @Test
    fun `Escape leaves the view where it is, with the caret out of it`() = runComposeUiTest {
        val state = stateAtEnd("line\n".repeat(200))
        show(state, Ready("bcd"))
        typeAndPause("a")
        assertTrue(state.execute(EditorCommand.ScrollTo(0f)))
        waitForIdle()
        assertEquals(0, state.viewport.firstVisibleLine)
        assertNotNull(state.ghost)
        keys { pressKey(Key.Escape) }
        assertNull(state.ghost)
        assertEquals(0, state.viewport.firstVisibleLine, "nothing scrolled to the caret")
    }

    @Test
    fun `after Escape the caret goes down from where it is, not where a column was kept`() = runComposeUiTest {
        val state = stateAtEnd("abcdef\na\nabcdef")
        state.selection = TextRange(6)
        show(state, Ready("bcd"))
        keys { pressKey(Key.DirectionDown) }
        assertEquals(TextRange(8), state.selection)
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        waitForIdle()
        assertNotNull(state.ghost)
        keys { pressKey(Key.Escape) }
        keys { pressKey(Key.DirectionDown) }
        assertEquals(TextRange(10), state.selection)
    }

    /** The editor inside a host that counts each Escape the editor leaves to it. */
    private fun ComposeUiTest.showInHost(
        state: CodeEditorState,
        provider: InlineCompletionProvider,
        options: EditorOptions,
        reached: () -> Unit,
    ) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            Box(
                Modifier.onKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) reached()
                    false
                },
            ) {
                CodeEditorLayout(
                    state = state,
                    options = options,
                    inlineCompletionProvider = provider,
                    modifier = Modifier.size(600.dp, 400.dp),
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun `Escape is kept while it dismisses, and a host gets the next one`() = runComposeUiTest {
        val state = stateAtEnd("")
        var reached = 0
        showInHost(state, Ready("bcd"), EditorOptions()) { reached++ }
        typeAndPause("a")
        keys { pressKey(Key.Escape) }
        assertNull(state.ghost)
        assertEquals(0, reached)
        keys { pressKey(Key.Escape) }
        assertEquals(1, reached)
    }

    private val noDismissingKey = listOf(EditorKeyBinding(KeyChord(Key.Escape), null))

    @Test
    fun `a host can move dismissing, and Escape keeps the rest of what it does`() = runComposeUiTest {
        val state = stateAtEnd("")
        val find = FindReplaceState().apply { show() }
        val bindings = noDismissingKey + EditorKeyBinding(KeyChord(Key.J, ctrlCmd = true), EditorCommand.DismissInlineCompletion)
        show(state, Ready("bcd"), options = { EditorOptions(keyBindings = bindings) }, find = find)
        typeAndPause("a")
        keys { pressKey(Key.Escape) }
        assertEquals("bcd", state.ghost, "Escape no longer dismisses")
        assertFalse(find.visible, "and it still closes the find panel")
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.J) } }
        assertNull(state.ghost)
        assertEquals("a", state.document.text)
    }

    @Test
    fun `a host that removes dismissing has Escape with it showing`() = runComposeUiTest {
        val state = stateAtEnd("")
        var reached = 0
        showInHost(state, Ready("bcd"), EditorOptions(keyBindings = noDismissingKey)) { reached++ }
        typeAndPause("a")
        keys { pressKey(Key.Escape) }
        assertEquals("bcd", state.ghost)
        assertEquals(1, reached, "nothing else was open, so the key is the host's")
    }

    @Test
    fun `removing dismissing leaves Escape ending a snippet and extra carets`() = runComposeUiTest {
        val state = stateAtEnd("one\ntwo")
        show(state, null, options = { EditorOptions(keyBindings = noDismissingKey) })
        keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } } }
        assertEquals(2, state.selections.size)
        keys { pressKey(Key.Escape) }
        assertEquals(1, state.selections.size)
        assertTrue(state.execute(EditorCommand.InsertSnippet("<$1>$0")))
        waitForIdle()
        assertNotNull(state.snippet)
        keys { pressKey(Key.Escape) }
        assertNull(state.snippet)
    }

    @Test
    fun `the keys are data a host can change`() = runComposeUiTest {
        val state = stateAtEnd("")
        val bindings = listOf(
            EditorKeyBinding(KeyChord(Key.Tab), null),
            EditorKeyBinding(KeyChord(Key.J, ctrlCmd = true), EditorCommand.AcceptInlineCompletion),
        )
        show(state, Ready("bcd"), options = { EditorOptions(keyBindings = bindings) })
        typeAndPause("a")
        keys { pressKey(Key.Tab) }
        assertEquals("a", state.document.text.take(1))
        assertFalse("abcd" in state.document.text, "this host took Tab away")
    }

    @Test
    fun `a host's key for accepting works, and goes on to what it did when nothing shows`() = runComposeUiTest {
        val state = stateAtEnd("")
        val bindings = listOf(EditorKeyBinding(KeyChord(Key.DirectionRight), EditorCommand.AcceptInlineCompletion))
        show(state, Ready("bcd"), options = { EditorOptions(keyBindings = bindings) })
        typeAndPause("a")
        keys { pressKey(Key.DirectionRight) }
        assertEquals("abcd", state.document.text)
        state.selection = TextRange(0)
        waitForIdle()
        keys { pressKey(Key.DirectionRight) }
        assertEquals(TextRange(1), state.selection, "the arrow moves the caret")
    }

    @Test
    fun `the default bindings hold them`() {
        val defaults = EditorKeyBindings.defaults(isMac = false)
        val mac = EditorKeyBindings.defaults(isMac = true)
        for (list in listOf(defaults, mac)) {
            assertEquals(EditorCommand.AcceptInlineCompletion, list.single { it.chord == listOf(KeyChord(Key.Tab)) }.command)
            assertEquals(
                EditorCommand.CycleInlineCompletion(forward = true),
                list.single { it.chord == listOf(KeyChord(Key.RightBracket, alt = true)) }.command,
            )
            assertEquals(
                EditorCommand.CycleInlineCompletion(forward = false),
                list.single { it.chord == listOf(KeyChord(Key.LeftBracket, alt = true)) }.command,
            )
        }
    }

    // ── A soft keyboard's way in ─────────────────────────────────────────────

    @Test
    fun `commands do what the keys do`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("one", "two"))
        typeAndPause("a")
        assertTrue(EditorCommand.AcceptInlineCompletion in state.availableCommands)
        assertTrue(state.canExecute(EditorCommand.CycleInlineCompletion(forward = true)))
        assertTrue(state.execute(EditorCommand.CycleInlineCompletion(forward = true)))
        assertEquals("two", state.ghost)
        assertTrue(state.execute(EditorCommand.AcceptInlineCompletion))
        assertEquals("atwo", state.document.text)
    }

    @Test
    fun `accepting through the command shows the caret, as the key does`() = runComposeUiTest {
        val state = stateAtEnd("")
        val lines = (1..60).joinToString("\n") { "line $it" }
        show(state, InlineCompletionProvider { document, _ -> if (document.length == 1) listOf(InlineCompletion(lines)) else emptyList() })
        typeAndPause("a")
        assertTrue(state.execute(EditorCommand.AcceptInlineCompletion))
        waitForIdle()
        val caretLine = state.document.offsetToLineCol(state.selection.end).first
        assertEquals(59, caretLine)
        assertTrue(caretLine in state.viewport.firstVisibleLine..state.viewport.lastVisibleLine, "at ${state.viewport.firstVisibleLine}")
    }

    @Test
    fun `without a provider the commands are not there`() = runComposeUiTest {
        val state = stateAtEnd("a")
        show(state, null)
        assertFalse(state.canExecute(EditorCommand.CycleInlineCompletion()))
        assertFalse(state.execute(EditorCommand.CycleInlineCompletion()))
        assertFalse(state.execute(EditorCommand.AcceptInlineCompletion))
    }

    // ── Read-only ────────────────────────────────────────────────────────────

    @Test
    fun `a read-only editor is never asked, and its commands do nothing`() = runComposeUiTest {
        val state = stateAtEnd("a")
        val provider = Ready("one")
        show(state, provider, options = { EditorOptions(readOnly = true) })
        assertFalse(state.canExecute(EditorCommand.CycleInlineCompletion()))
        assertFalse(state.execute(EditorCommand.CycleInlineCompletion()))
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        pause()
        assertEquals(0, provider.asked)
    }

    @Test
    fun `turning read-only on takes it away`() = runComposeUiTest {
        val state = stateAtEnd("")
        state.computeDispatcher = Dispatchers.Unconfined
        var readOnly by mutableStateOf(false)
        val provider = Ready("one")
        setContent {
            CodeEditorLayout(
                state = state,
                options = EditorOptions(readOnly = readOnly),
                inlineCompletionProvider = provider,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
        typeAndPause("a")
        assertEquals("one", state.ghost)
        readOnly = true
        waitForIdle()
        assertNull(state.ghost)
        keys { pressKey(Key.Tab) }
        assertEquals("a", state.document.text)
    }

    // ── Several carets, a selection ──────────────────────────────────────────

    @Test
    fun `a caret added takes it away, and typing at several asks nothing`() = runComposeUiTest {
        val state = CodeEditorState("one\ntwo").apply { selection = TextRange(3) }
        val provider = Ready("!")
        show(state, provider)
        typeAndPause("x")
        assertEquals("!", state.ghost)
        state.setSelections(listOf(TextRange(4), TextRange(8)))
        waitForIdle()
        assertNull(state.ghost)
        type("y")
        pause()
        assertEquals(1, provider.asked)
    }

    @Test
    fun `Tab with a selection indents, with nothing showing`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"))
        typeAndPause("a")
        state.selection = TextRange(0, 1)
        waitForIdle()
        keys { pressKey(Key.Tab) }
        assertEquals("    a", state.document.text)
    }

    // ── The completion list ──────────────────────────────────────────────────

    private class Words : LanguageService {
        override val triggerCharacters: Set<Char> = setOf('.')

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> =
            listOf(CompletionItem("listed", CompletionKind.Value, "listed"))

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
    }

    @Test
    fun `an open list hides it and takes Tab, and it comes back when the list goes`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("ghost"), service = Words())
        type("a")
        typeAndPause(".")
        waitForIdle()
        assertNull(state.ghost, "the list is up")
        keys { pressKey(Key.Escape) }
        assertEquals("ghost", state.ghost, "Escape closed the list, and it was still good")
        keys { pressKey(Key.Escape) }
        assertNull(state.ghost, "the next Escape is for it")
        assertEquals("a.", state.document.text)
    }

    @Test
    fun `Tab goes to the list, not the ghost text behind it`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("ghost"), service = Words())
        type("a")
        typeAndPause(".")
        keys { pressKey(Key.Tab) }
        assertEquals("a.listed", state.document.text)
    }

    @Test
    fun `Alt with a bracket does nothing to ghost text under an open list`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("one", "two"), service = Words())
        type("a")
        typeAndPause(".")
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        keys { pressKey(Key.Escape) }
        assertEquals("one", state.ghost)
    }

    // ── Never in the document ────────────────────────────────────────────────

    @Test
    fun `semantics hold the document's text with it on show`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"))
        typeAndPause("a")
        assertNotNull(state.ghost)
        val node = onNodeWithTag(EditorTestTags.EDITOR).fetchSemanticsNode()
        assertEquals("a", node.config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun `the undo stack does not know of it`() = runComposeUiTest {
        val state = stateAtEnd("")
        show(state, Ready("bcd"))
        typeAndPause("a")
        val version = state.alternativeVersionId
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.RightBracket) } }
        keys { pressKey(Key.Escape) }
        assertEquals(version, state.alternativeVersionId)
        state.undo()
        assertEquals("", state.document.text, "undo took back the typing")
        assertNotEquals(version, state.alternativeVersionId)
    }
}
