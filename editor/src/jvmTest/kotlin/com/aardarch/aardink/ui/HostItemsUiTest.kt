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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FindReplaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The host's buttons and menu items, and the find keys. */
class HostItemsUiTest {

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        find: FindReplaceState? = null,
        toolbar: List<KeyboardToolbarItem> = emptyList(),
        touchMenu: List<EditorMenuItem> = emptyList(),
        contextMenu: List<EditorMenuItem> = emptyList(),
    ) {
        setContent {
            CodeEditorLayout(
                state = state,
                findReplaceState = find,
                keyboardToolbarPlacement = KeyboardToolbarPlacement.Top,
                keyboardToolbarItems = toolbar,
                touchMenuItems = touchMenu,
                contextMenuItems = contextMenu,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.keys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    @Test
    fun `toolbar items type characters, run commands and call the host`() = runComposeUiTest {
        val state = CodeEditorState("x")
        var clicked = 0
        show(
            state,
            toolbar = listOf(
                KeyboardToolbarItem.Character('|'),
                KeyboardToolbarItem.Action("Wrap", command = EditorCommand.InsertSnippet("<b>$0</b>")),
                KeyboardToolbarItem.Action("Ping", onClick = { clicked++ }),
            ),
        )
        state.selection = TextRange(1)
        onNodeWithText("|").performClick()
        onNodeWithText("Wrap").performClick()
        onNodeWithText("Ping").performClick()
        waitForIdle()
        assertEquals("x|<b></b>", state.text)
        assertEquals(1, clicked)
    }

    @Test
    fun `the touch menu offers the host's items`() = runComposeUiTest {
        val state = CodeEditorState("alpha beta")
        var picked = ""
        show(state, touchMenu = listOf(EditorMenuItem("Pick colour", onClick = { picked = "colour" })))
        onNodeWithTag(EditorTestTags.EDITOR).performTouchInput { longClick(Offset(8.dp.toPx() + 4f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        onNodeWithText("Pick colour").performClick()
        waitForIdle()
        assertEquals("colour", picked)
    }

    @Test
    fun `the right-click menu offers the host's items, disabled when their command cannot run`() = runComposeUiTest {
        val state = CodeEditorState("abc")
        var asked = 0
        show(
            state,
            contextMenu = listOf(
                EditorMenuItem("Ask", onClick = { asked++ }),
                EditorMenuItem("Format it", command = EditorCommand.Format),
            ),
        )
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { rightClick(Offset(20f, 20f)) }
        waitForIdle()
        onNodeWithText("Format it").assertExists()
        onNodeWithText("Ask").performClick()
        waitForIdle()
        assertEquals(1, asked)
    }

    @Test
    fun `ctrl F takes a selection on one line as its query, F3 goes through the matches, alt enter selects them all`() = runComposeUiTest {
        val state = CodeEditorState("cat dog cat dog cat")
        val find = FindReplaceState()
        show(state, find = find)
        state.selection = TextRange(0, 3)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        assertEquals("cat", find.query)
        waitUntil(timeoutMillis = 5_000) { find.matches.size == 3 }
        // The query is selected in the field: typing replaces it.
        onNodeWithTag(EditorTestTags.FIND_FIELD).performTextInput("dog")
        waitForIdle()
        assertEquals("dog", find.query)
        waitUntil(timeoutMillis = 5_000) { find.matches.size == 2 }
        keys { pressKey(Key.F3) }
        assertTrue(state.selection.length == 3 && state.text.substring(state.selection.min, state.selection.max) == "dog")
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        assertEquals(listOf(TextRange(4, 7), TextRange(12, 15)), state.selections.sortedBy { it.min })
    }
}
