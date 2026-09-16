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
@file:OptIn(ExperimentalTestApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aardarch.aardink.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FindReplaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renders the real [CodeEditorLayout] and drives it through the test input APIs.
 *
 * These run on the JVM target only. The scenarios here are the ones unit tests genuinely
 * cannot reach: they need a composition, a focused text field and real key events. The
 * keyboard shortcuts in particular could not be covered any other way — they are installed as
 * a modifier on the field and only fire for actual key input.
 */
class CodeEditorLayoutUiTest {

    private fun stateFor(text: String) = CodeEditorState(initialText = text)

    @Test
    fun `typing updates the document`() = runComposeUiTest {
        val state = stateFor("")
        setContent { CodeEditorLayout(state = state) }

        onNodeWithTag(EditorTestTags.TEXT_FIELD).performTextInput("abc")
        waitForIdle()

        assertEquals("abc", state.document.text)
        assertEquals(state.document.text, state.textFieldState.text.toString())
    }

    @Test
    fun `ctrl Z undoes through EditorUndoManager`() = runComposeUiTest {
        // The point of intercepting at preview level: BasicTextField's built-in Ctrl+Z would
        // undo on its own private stack and leave CodeDocument behind.
        val state = stateFor("start")
        setContent { CodeEditorLayout(state = state) }

        onNodeWithTag(EditorTestTags.TEXT_FIELD).performTextInput("X")
        waitForIdle()
        assertTrue(state.document.text.contains("X"), "precondition: the edit landed")

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) }
        }
        waitForIdle()

        assertEquals("start", state.document.text)
        assertEquals(state.document.text, state.textFieldState.text.toString())
    }

    @Test
    fun `ctrl Y redoes`() = runComposeUiTest {
        val state = stateFor("start")
        setContent { CodeEditorLayout(state = state) }

        onNodeWithTag(EditorTestTags.TEXT_FIELD).performTextInput("X")
        waitForIdle()
        val afterEdit = state.document.text

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) }
        }
        waitForIdle()
        assertEquals("start", state.document.text)

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) }
        }
        waitForIdle()

        assertEquals(afterEdit, state.document.text)
    }

    @Test
    fun `ctrl F opens the find panel`() = runComposeUiTest {
        val state = stateFor("hello world")
        val findReplaceState = FindReplaceState()
        setContent { CodeEditorLayout(state = state, findReplaceState = findReplaceState) }

        assertFalse(findReplaceState.visible, "precondition: find starts hidden")

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.F) }
        }
        waitForIdle()

        assertTrue(findReplaceState.visible)
    }

    @Test
    fun `escape dismisses the find panel`() = runComposeUiTest {
        val state = stateFor("hello world")
        val findReplaceState = FindReplaceState()
        setContent { CodeEditorLayout(state = state, findReplaceState = findReplaceState) }

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.F) }
        }
        waitForIdle()
        assertTrue(findReplaceState.visible)

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertFalse(findReplaceState.visible)
    }

    @Test
    fun `ctrl G asks the host to go to a line`() = runComposeUiTest {
        val state = stateFor("a\nb\nc")
        var requested = false
        setContent {
            CodeEditorLayout(state = state, onRequestGoToLine = { requested = true })
        }

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.G) }
        }
        waitForIdle()

        assertTrue(requested, "Ctrl+G should reach the host callback")
    }

    @Test
    fun `tab indents the selected lines`() = runComposeUiTest {
        val state = stateFor("one\ntwo\nthree")
        setContent { CodeEditorLayout(state = state) }
        waitForIdle()

        // Select the first two lines.
        state.selection = TextRange(0, 7)
        waitForIdle()

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()

        assertEquals("    one\n    two\nthree", state.document.text)
    }

    @Test
    fun `shift tab outdents the selected lines`() = runComposeUiTest {
        val state = stateFor("    one\n    two\nthree")
        setContent { CodeEditorLayout(state = state) }
        waitForIdle()

        state.selection = TextRange(0, 15)
        waitForIdle()

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        waitForIdle()

        assertEquals("one\ntwo\nthree", state.document.text)
    }

    @Test
    fun `outdent leaves a line with no indentation alone`() = runComposeUiTest {
        val state = stateFor("one")
        setContent { CodeEditorLayout(state = state) }
        waitForIdle()

        state.selection = TextRange(0, 3)
        waitForIdle()

        onNodeWithTag(EditorTestTags.TEXT_FIELD).requestFocus()
        onNodeWithTag(EditorTestTags.TEXT_FIELD).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        waitForIdle()

        assertEquals("one", state.document.text)
    }
}
