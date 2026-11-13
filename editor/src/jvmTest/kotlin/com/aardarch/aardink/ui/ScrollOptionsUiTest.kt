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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.EditorCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `scrollBeyondLastLine`, `cursorSurroundingLines` and `smoothScrolling`. */
class ScrollOptionsUiTest {

    private val lines = (1..200).joinToString("\n") { "line $it" }

    private fun ComposeUiTest.show(state: CodeEditorState, options: EditorOptions) {
        setContent { CodeEditorLayout(state = state, options = options, modifier = Modifier.size(400.dp, 300.dp)) }
        waitForIdle()
    }

    private fun CodeEditorState.revealLine(line: Int) = execute(EditorCommand.Reveal(document.lineStart(line), center = false))

    @Test
    fun `scrolling beyond the last line adds a view's height but a line`() = runComposeUiTest {
        val plain = CodeEditorState("a\nb\nc")
        var options by mutableStateOf(EditorOptions())
        setContent { CodeEditorLayout(state = plain, options = options, modifier = Modifier.size(400.dp, 300.dp)) }
        waitForIdle()
        val before = plain.viewport.contentHeight
        options = EditorOptions(scrollBeyondLastLine = true)
        waitForIdle()
        val after = plain.viewport.contentHeight
        assertTrue(after > before + 200f, "from $before to $after")
        assertTrue(after < before + 300f, "less a line than the view's 300 dp: $after")
    }

    @Test
    fun `a wrap column wraps before the edge, and the edge still wraps first`() = runComposeUiTest {
        val state = CodeEditorState("x".repeat(30))
        var options by mutableStateOf(EditorOptions(softWrap = true))
        setContent { CodeEditorLayout(state = state, options = options, modifier = Modifier.size(400.dp, 300.dp)) }
        waitForIdle()
        val oneRow = state.viewport.contentHeight
        options = EditorOptions(softWrap = true, wordWrapColumn = 10)
        waitForIdle()
        val threeRows = state.viewport.contentHeight
        options = EditorOptions(softWrap = true, wordWrapColumn = 1_000)
        waitForIdle()
        assertEquals(oneRow, state.viewport.contentHeight, "a column past the edge wraps at the edge")
        assertTrue(threeRows > oneRow * 2, "30 characters at 10 a row: $oneRow, then $threeRows")
    }

    @Test
    fun `surrounding lines keep that many between the caret and the edge`() = runComposeUiTest {
        val state = CodeEditorState(lines)
        show(state, EditorOptions(cursorSurroundingLines = 5))
        state.revealLine(100)
        waitForIdle()
        assertEquals(105, state.viewport.lastVisibleLine, "five lines below line 100 are in view")
    }

    @Test
    fun `without surrounding lines one is kept, as before`() = runComposeUiTest {
        val state = CodeEditorState(lines)
        show(state, EditorOptions())
        state.revealLine(100)
        waitForIdle()
        assertEquals(101, state.viewport.lastVisibleLine)
    }

    @Test
    fun `a smooth reveal moves over several frames and ends where a jump would`() = runComposeUiTest {
        val state = CodeEditorState(lines)
        show(state, EditorOptions(smoothScrolling = true))
        mainClock.autoAdvance = false
        state.revealLine(100)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val early = state.viewport.lastVisibleLine
        assertTrue(early < 101, "still on its way after two frames: $early")
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertEquals(101, state.viewport.lastVisibleLine)
        mainClock.autoAdvance = true
    }
}
