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
// The view and its input node are internal to :editor, and :editor has no Android test target.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.aardarch.aardink.sample

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.view.EditorController
import com.aardarch.aardink.ui.view.EditorInputElement
import com.aardarch.aardink.ui.view.EditorMetrics
import com.aardarch.aardink.ui.view.EditorView
import com.aardarch.aardink.ui.view.Injection
import com.aardarch.aardink.ui.view.LineInjections
import com.aardarch.aardink.ui.view.ViewStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Android part of the inline-layout spike's step S8: the `InputConnection` a keyboard drives,
 * as `EditorInputConnectionTest` drives it, on a view with text injected into the line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class InlineInputConnectionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val hint = ": Hint"
    private val lens = "3 references"

    private lateinit var view: EditorView

    private fun stateOf(text: String) =
        CodeEditorState(initialText = text, tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
        }

    private fun connect(state: CodeEditorState, column: Int, realHint: Boolean = false): InputConnection {
        val injected = LineInjections.of(Injection.rowAbove(lens), Injection(column, hint))
        rule.setContent {
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val controller: EditorController = remember {
                val made: EditorView = EditorView(state)
                made.style = ViewStyle(
                    measurer = measurer,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
                    tokenStyle = { null },
                    placeholderStyle = SpanStyle(color = Color.Gray),
                    metrics = EditorMetrics(
                        lineHeight = 20f,
                        charWidth = 8f,
                        paddingTop = 8f,
                        paddingBottom = 0f,
                        paddingStart = 8f,
                        paddingEnd = 8f,
                    ),
                    softWrap = false,
                )
                if (realHint) {
                    state.publishInlayHints(listOf(InlayHint(column, hint)))
                } else {
                    made.injections = { line -> if (line == 0) injected else null }
                }
                made.attach()
                made.prepare(600, 400)
                view = made
                EditorController(state, made)
            }
            val focus = remember { FocusRequester() }
            controller.requestFocus = { focus.requestFocus() }
            Box(Modifier.fillMaxSize().then(EditorInputElement(controller)).focusRequester(focus).focusTarget())
        }
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
        rule.runOnIdle { state.selection = TextRange(column) }
        rule.waitForIdle()
        return rule.runOnIdle {
            val focused = requireNotNull(rule.activity.window.decorView.findFocus()) { "nothing has focus" }
            requireNotNull(focused.onCreateInputConnection(EditorInfo())) { "the focused editor handed out no InputConnection" }
        }
    }

    private fun InputConnection.run(block: InputConnection.() -> Unit) {
        rule.runOnIdle { block() }
        rule.waitForIdle()
    }

    private fun assertInjected() {
        val shown: String = view.layoutFor(0).result.layoutInput.text.text
        assertTrue("the layout does hold the injected text: $shown", hint in shown && lens in shown)
    }

    @Test
    fun `committed text lands once, in the document`() {
        val state = stateOf("val x = 1")
        val connection = connect(state, 5)
        assertInjected()
        connection.run { commitText("y", 1) }
        assertEquals("val xy = 1", state.document.text)
        assertEquals(TextRange(6), state.selection)
    }

    @Test
    fun `a composing word lands once, and undoes in one step`() {
        val state = stateOf("val x = 1")
        val connection = connect(state, 5)
        connection.run { setComposingText("n", 1) }
        connection.run { setComposingText("ni", 1) }
        assertEquals("val xni = 1", state.document.text)
        connection.run { commitText("你", 1) }
        assertEquals("val x你 = 1", state.document.text)
        rule.runOnIdle { state.undo() }
        assertEquals("val x = 1", state.document.text)
    }

    @Test
    fun `what the keyboard reads is the document's text`() {
        val state = stateOf("val x = 1")
        val connection = connect(state, 5)
        assertInjected()
        rule.runOnIdle {
            assertEquals("val x", connection.getTextBeforeCursor(100, 0).toString())
            assertEquals(" = 1", connection.getTextAfterCursor(100, 0).toString())
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0).text.toString()
            assertEquals("val x = 1", extracted)
            assertFalse(hint in extracted || lens in extracted)
        }
    }

    @Test
    fun `the view counts a lens row, and Up and Down pass over it`() {
        val state = stateOf("val x = 1\nsecond")
        connect(state, 5)
        val second = state.document.lineStart(1)
        rule.runOnIdle {
            val shown: EditorView = view
            assertEquals("a lens, its line, the next line", 3, shown.totalRows)
            assertEquals("the caret is on the text row", 1, shown.rowOf(5))
            assertFalse("no caret can be on the lens row", shown.isCaretRow(0))
            assertEquals("down reaches the next line", second, shown.verticalMove(0, 1, 0f))
            assertEquals("up passes the lens and stops at the document's start", 0, shown.verticalMove(second, -1, 0f))
            assertEquals("from the first text row, up goes to the start", 0, shown.verticalMove(5, -1))
            assertEquals("the caret's band is the text row's", 8f + 20f, shown.caretRect(5).top, 2f)
        }
    }

    @Test
    fun `around a real hint a keyboard reads and writes the document, and the hint moves with it`() {
        val state = stateOf("val x = 1")
        val connection = connect(state, 5, realHint = true)
        val shown: String = view.layoutFor(0).result.layoutInput.text.text
        assertEquals("val x: Hint = 1", shown)
        rule.runOnIdle {
            assertEquals("val x", connection.getTextBeforeCursor(100, 0).toString())
            assertEquals("val x = 1", connection.getExtractedText(ExtractedTextRequest(), 0).text.toString())
        }

        connection.run { setComposingText("n", 1) }
        connection.run { setComposingText("ni", 1) }
        connection.run { commitText("y", 1) }

        assertEquals("val xy = 1", state.document.text)
        rule.runOnIdle { assertEquals(listOf(InlayHint(6, hint)), state.inlayHints) }
        rule.runOnIdle { state.undo() }
        rule.runOnIdle { assertEquals(listOf(InlayHint(5, hint)), state.inlayHints) }
    }

    @Test
    fun `delete surrounding text takes the document's characters, not the hint`() {
        val state = stateOf("val x = 1")
        val connection = connect(state, 5)
        connection.run { deleteSurroundingText(1, 1) }
        assertEquals("val = 1", state.document.text)
    }

    /**
     * A phone's list is a strip above the keyboard that takes no keys and is not at the caret. It is
     * up for most of a word, so if it hid ghost text as a list at the caret does, none would show.
     */
    @Test
    fun `the strip above a keyboard leaves ghost text showing`() {
        val state = stateOf("a")
        val words = object : LanguageService by BuiltInLanguages.Kotlin.languageService!! {
            override suspend fun completions(document: CodeDocument, cursorOffset: Int) =
                listOf(CompletionItem("listed", CompletionKind.Value, "listed"))
        }
        rule.setContent {
            CodeEditorLayout(
                state = state,
                languageService = words,
                inlineCompletionProvider = { _, _ -> listOf(InlineCompletion("ghost")) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        rule.runOnIdle {
            state.selection = TextRange(1)
            state.execute(EditorCommand.TriggerSuggest)
        }
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodes(hasText("listed")).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertTrue(state.execute(EditorCommand.CycleInlineCompletion())) }
        rule.waitUntil(timeoutMillis = 5_000) { state.inlineCompletions.shown != null }
        rule.runOnIdle { assertTrue(state.execute(EditorCommand.AcceptInlineCompletion)) }
        assertEquals("aghost", state.document.text)
    }

    /** Ghost text, through what a layout does with an answer, on the view [connect] made. */
    private fun offer(state: CodeEditorState, text: String) {
        rule.runOnIdle {
            val tracker = state.inlineCompletions
            tracker.provided = true
            tracker.askAgain(now = true)
            tracker.answer(requireNotNull(tracker.ask), state.document.version, listOf(InlineCompletion(text)))
            view.prepare(600, 400)
        }
        rule.waitForIdle()
    }

    @Test
    fun `a keyboard reads the document past ghost text, and commits through it`() {
        val state = stateOf("val x = ")
        val connection = connect(state, 8, realHint = true)
        offer(state, "compute()")
        rule.runOnIdle {
            assertEquals("val x = ", connection.getTextBeforeCursor(100, 0).toString())
            assertEquals("", connection.getTextAfterCursor(100, 0).toString())
            assertEquals("val x = ", connection.getExtractedText(ExtractedTextRequest(), 0).text.toString())
        }
        connection.run { commitText("c", 1) }
        assertEquals("val x = c", state.document.text)
        rule.runOnIdle { assertEquals("ompute()", state.inlineCompletions.shown?.pieces?.single()?.text) }
    }

    @Test
    fun `a keyboard composing shows no ghost text, and a command accepts it when it is done`() {
        val state = stateOf("val x = ")
        val connection = connect(state, 8, realHint = true)
        offer(state, "compute()")
        connection.run { setComposingText("co", 1) }
        rule.runOnIdle {
            assertEquals(null, state.inlineCompletions.shown)
            assertEquals(null, state.inlineCompletions.ask)
        }
        connection.run { finishComposingText() }
        rule.runOnIdle { assertEquals(10, state.inlineCompletions.ask?.caret) }
        offer(state, "mpute()")
        rule.runOnIdle { assertTrue(state.execute(EditorCommand.AcceptInlineCompletion)) }
        assertEquals("val x = compute()", state.document.text)
    }
}
