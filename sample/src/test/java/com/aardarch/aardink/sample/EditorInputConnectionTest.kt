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
@file:OptIn(ExperimentalAardinkRenderer::class)

package com.aardarch.aardink.sample

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorRenderer
import com.aardarch.aardink.ui.ExperimentalAardinkRenderer
import com.aardarch.aardink.ui.LocalEditorRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's own renderer as an Android input method sees it: the `InputConnection` the
 * focused Compose view hands out, driven the way Gboard and Samsung Keyboard drive one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorInputConnectionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun stateOf(text: String) =
        CodeEditorState(initialText = text, tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
        }

    /** Shows [state] in the editor's own renderer, focuses it, puts the caret at [caret] and returns the input connection. */
    private fun connect(state: CodeEditorState, caret: Int = state.document.length): InputConnection {
        rule.setContent {
            CompositionLocalProvider(LocalEditorRenderer provides EditorRenderer.Virtualized) {
                CodeEditorLayout(state = state, modifier = Modifier.fillMaxSize())
            }
        }
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
        rule.runOnIdle { state.selection = TextRange(caret) }
        rule.waitForIdle()
        return rule.runOnIdle {
            val view = requireNotNull(rule.activity.window.decorView.findFocus()) { "nothing has focus" }
            requireNotNull(view.onCreateInputConnection(EditorInfo())) { "the focused editor handed out no InputConnection" }
        }
    }

    private fun InputConnection.run(block: InputConnection.() -> Unit) {
        rule.runOnIdle { block() }
        rule.waitForIdle()
    }

    @Test
    fun `committed text lands once, at the caret`() {
        val state = stateOf("fun ")
        val ic = connect(state)
        ic.run { commitText("main", 1) }
        assertEquals("fun main", state.document.text)
        assertEquals(TextRange(8), state.selection)
    }

    @Test
    fun `a composing word lands once when committed, and undoes in one step`() {
        val state = stateOf("say ")
        val ic = connect(state)
        ic.run {
            setComposingText("h", 1)
            setComposingText("he", 1)
            setComposingText("hel", 1)
        }
        assertEquals("say hel", state.document.text)
        ic.run { commitText("hello", 1) }
        assertEquals("say hello", state.document.text)
        rule.runOnIdle { state.undo() }
        assertEquals("say ", state.document.text)
    }

    @Test
    fun `delete surrounding text is a backspace`() {
        val state = stateOf("abc")
        val ic = connect(state)
        ic.run { deleteSurroundingText(1, 0) }
        assertEquals("ab", state.document.text)
    }

    @Test
    fun `a batch lands as one edit, after it ends`() {
        val state = stateOf("one")
        val ic = connect(state)
        ic.run {
            beginBatchEdit()
            commitText(" two", 1)
            setSelection(0, 0)
            commitText("zero ", 1)
        }
        assertEquals("one", state.document.text)
        ic.run { endBatchEdit() }
        assertEquals("zero one two", state.document.text)
        assertEquals(TextRange(5), state.selection)
    }

    @Test
    fun `reads see bounded slices around the caret`() {
        val state = stateOf("x".repeat(10_000))
        val ic = connect(state, caret = 5_000)
        rule.runOnIdle {
            assertEquals(3, ic.getTextBeforeCursor(3, 0)?.length)
            assertEquals(3, ic.getTextAfterCursor(3, 0)?.length)
            assertNull(ic.getSelectedText(0))
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            assertTrue("a slice, not the document", extracted.text.length < 10_000)
            assertEquals(5_000, extracted.startOffset + extracted.selectionStart)
        }
    }

    @Test
    fun `a key event from the keyboard goes through the editor's keys`() {
        val state = stateOf("abc")
        val ic = connect(state)
        ic.run {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        assertEquals("ab", state.document.text)
    }

    @Test
    fun `an auto-correction replaces the word before the caret`() {
        val state = stateOf("teh ")
        val ic = connect(state)
        ic.run {
            beginBatchEdit()
            setComposingRegion(0, 3)
            commitText("the", 1)
            endBatchEdit()
        }
        assertEquals("the ", state.document.text)
    }
}
