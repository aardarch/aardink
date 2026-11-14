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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FindEngine
import com.aardarch.aardink.core.FindReplaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Find in selection, preserve case, the search history, the replace preview and `onSearchWorkspace`. */
class FindExtrasUiTest {

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        find: FindReplaceState,
        onSearchWorkspace: ((String, FindEngine.Options) -> Unit)? = null,
    ) {
        setContent {
            CodeEditorLayout(
                state = state,
                findReplaceState = find,
                onSearchWorkspace = onSearchWorkspace,
                modifier = Modifier.size(700.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    @Test
    fun `find in selection finds only in the text selected when it went on, which follows edits`() = runComposeUiTest {
        val state = CodeEditorState("ab\nab ab\nab")
        val find = FindReplaceState()
        show(state, find)
        state.selection = TextRange(3, 8)
        find.show()
        find.inSelection = true
        find.query = "ab"
        waitUntil { find.matches.size == 2 }
        assertEquals(listOf(3..4, 6..7), find.matches)

        state.applyEdit(0, 0, "xx", TextRange(2))
        waitUntil { find.matches.firstOrNull() == 5..6 }
        assertEquals(listOf(5..6, 8..9), find.matches)

        find.inSelection = false
        waitUntil { find.matches.size == 4 }
    }

    @Test
    fun `find in selection with nothing selected searches the caret's line`() = runComposeUiTest {
        val state = CodeEditorState("ab\nab ab\nab")
        val find = FindReplaceState()
        show(state, find)
        state.selection = TextRange(4)
        find.show()
        find.inSelection = true
        find.query = "ab"
        waitUntil { find.matches.size == 2 }
        assertEquals(listOf(3..4, 6..7), find.matches)
    }

    @Test
    fun `preserve case gives the replacement the case of what it replaces`() = runComposeUiTest {
        val state = CodeEditorState("foo Foo FOO")
        val find = FindReplaceState().apply {
            replacement = "bar"
            preserveCase = true
        }
        show(state, find)
        find.show(replace = true)
        find.query = "foo"
        waitUntil { find.matches.size == 3 }
        onNodeWithText("All").performClick()
        waitForIdle()
        assertEquals("bar Bar BAR", state.document.text)
    }

    @Test
    fun `up and down in the find field go through what was searched for`() = runComposeUiTest {
        val state = CodeEditorState("one two three")
        val find = FindReplaceState()
        show(state, find)
        find.show()
        waitForIdle()
        val field = onNodeWithTag(EditorTestTags.FIND_FIELD)
        field.performTextInput("one")
        field.performKeyInput { pressKey(Key.Enter) }
        find.query = "two"
        waitForIdle()
        field.performKeyInput { pressKey(Key.Enter) }
        find.query = "thr"
        waitForIdle()

        field.performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals("two", find.query)
        field.performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals("one", find.query)
        field.performKeyInput { pressKey(Key.DirectionDown) }
        field.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals("thr", find.query, "past the newest, what was being typed")
    }

    @Test
    fun `the replace row previews the current match replaced`() = runComposeUiTest {
        val state = CodeEditorState("val count = 1\nval total = count")
        val find = FindReplaceState().apply { replacement = "size" }
        show(state, find)
        find.show(replace = true)
        find.query = "count"
        waitUntil { find.matches.size == 2 }
        waitForIdle()
        val preview = onNodeWithTag(EditorTestTags.REPLACE_PREVIEW).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        assertEquals("val countsize = 1", preview)
    }

    @Test
    fun `ctrl shift F hands the selection and the find options to the host`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        val find = FindReplaceState().apply { caseSensitive = true }
        var asked: Pair<String, FindEngine.Options>? = null
        show(state, find) { query, options -> asked = query to options }
        state.selection = TextRange(6, 11)
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        waitForIdle()
        assertEquals("world" to FindEngine.Options(caseSensitive = true), asked)
        assertEquals("hello world", state.document.text)
    }

    @Test
    fun `SearchWorkspace can be rebound, and runs through execute`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        val find = FindReplaceState().apply { query = "wor" }
        val asked = ArrayList<String>()
        setContent {
            CodeEditorLayout(
                state = state,
                findReplaceState = find,
                options = EditorOptions(
                    keyBindings = listOf(
                        EditorKeyBinding(KeyChord(Key.F, ctrlCmd = true, shift = true), null),
                        EditorKeyBinding(KeyChord(Key.F7, shift = true), EditorCommand.SearchWorkspace),
                    ),
                ),
                onSearchWorkspace = { query, _ -> asked += query },
                modifier = Modifier.size(700.dp, 400.dp),
            )
        }
        waitForIdle()
        val editor = onNodeWithTag(EditorTestTags.EDITOR)
        editor.requestFocus()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
        waitForIdle()
        assertEquals(emptyList(), asked, "the host took Ctrl+Shift+F away")
        editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F7) } }
        waitForIdle()
        assertEquals(listOf("wor"), asked)

        assertTrue(state.canExecute(EditorCommand.SearchWorkspace))
        runOnIdle { state.execute(EditorCommand.SearchWorkspace) }
        assertEquals(listOf("wor", "wor"), asked)
    }

    @Test
    fun `without onSearchWorkspace the command cannot run`() = runComposeUiTest {
        val state = CodeEditorState("hello")
        show(state, FindReplaceState())
        assertFalse(state.canExecute(EditorCommand.SearchWorkspace))
        assertFalse(runOnIdle { state.execute(EditorCommand.SearchWorkspace) })
    }

    @Test
    fun `without onSearchWorkspace the panel has no button for it`() = runComposeUiTest {
        val state = CodeEditorState("hello")
        val find = FindReplaceState()
        show(state, find)
        find.show()
        waitForIdle()
        onAllNodesWithText("Search all files").assertCountEquals(0)
    }
}
