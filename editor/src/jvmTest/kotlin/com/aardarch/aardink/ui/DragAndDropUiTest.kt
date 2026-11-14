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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/** `EditorOptions.dragAndDrop`: a selection dragged with the mouse. */
class DragAndDropUiTest {

    private fun ComposeUiTest.show(state: CodeEditorState, options: EditorOptions) {
        setContent { CodeEditorLayout(state = state, options = options, modifier = Modifier.size(600.dp, 300.dp)) }
        waitForIdle()
    }

    /** A point on the first line, [x] pixels into the text. */
    private fun ComposeUiTest.onFirstLine(x: Float): Offset = with(density) { Offset(8.dp.toPx() + x, 8.dp.toPx() + 10.dp.toPx()) }

    private fun ComposeUiTest.drag(from: Offset, to: Offset) {
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput {
            moveTo(from)
            press()
            moveTo(Offset((from.x + to.x) / 2f, from.y))
            moveTo(to)
            release()
        }
        waitForIdle()
    }

    @Test
    fun `a dragged selection moves to where it is let go, selected, in one undo step`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        show(state, EditorOptions(dragAndDrop = true))
        state.selection = TextRange(0, 5)

        drag(from = onFirstLine(20f), to = onFirstLine(500f))

        assertEquals(" worldhello", state.document.text)
        assertEquals(TextRange(6, 11), state.selection)
        state.undo()
        assertEquals("hello world", state.document.text)
    }

    @Test
    fun `a press in a selection that does not move places the caret`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        show(state, EditorOptions(dragAndDrop = true))
        state.selection = TextRange(0, 5)

        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(onFirstLine(20f)) }
        waitForIdle()

        assertEquals("hello world", state.document.text)
        assertEquals(true, state.selection.collapsed)
    }

    @Test
    fun `without the option a drag in a selection selects, as before`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        show(state, EditorOptions())
        state.selection = TextRange(0, 5)

        drag(from = onFirstLine(20f), to = onFirstLine(500f))

        assertEquals("hello world", state.document.text)
        assertEquals(11, state.selection.end)
    }
}
