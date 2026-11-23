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
// Modified 2026 for Aardink touch-scroll vs tap reproduction and regression tests.
@file:OptIn(ExperimentalTestApi::class)

package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.SelectionChangeReason
import com.aardarch.aardink.ui.view.SelectionHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reproduces the fast touch fling bug (interpreted as tap) and guards touch tap, double-tap,
 * long-press, and selection-handle drag behaviour.
 */
class TouchScrollPointerUiTest {

    private fun ComposeUiTest.show(state: CodeEditorState) {
        setContent {
            CodeEditorLayout(
                state = state,
                options = EditorOptions(softWrap = false),
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    @Test
    fun `fast touch fling over text scrolls without moving selection or requesting keyboard`() = runComposeUiTest {
        val text = (1..200).joinToString("\n") { "val lineNumber$it = \"value_$it\"" }
        val state = CodeEditorState(initialText = text)
        var pointerSelections = 0
        state.addSelectionListener { _, reason ->
            if (reason == SelectionChangeReason.Pointer) pointerSelections++
        }
        show(state)
        assertEquals(TextRange(0), state.selection)
        assertFalse(state.isFocused)

        onNodeWithTag(EditorTestTags.EDITOR).performTouchInput {
            swipe(
                start = Offset(200f, 350f),
                end = Offset(200f, 50f),
                durationMillis = 100L,
            )
        }
        waitForIdle()

        val firstVisible = state.visibleLines?.first ?: 0
        assertTrue(firstVisible > 0, "Expected editor to scroll down, visibleLines=${state.visibleLines}")
        assertEquals(0, pointerSelections, "Fast touch fling must not trigger a Pointer selection change")
        assertEquals(TextRange(0), state.selection, "Fast touch fling must keep caret at 0")
        assertFalse(state.isFocused, "Fast touch fling must not focus the editor or request the keyboard")
    }

    @Test
    fun `short touch tap without movement places caret and focuses editor`() = runComposeUiTest {
        val state = CodeEditorState(initialText = "first line here\nsecond line here\nthird line here")
        var pointerSelections = 0
        state.addSelectionListener { _, reason ->
            if (reason == SelectionChangeReason.Pointer) pointerSelections++
        }
        show(state)

        onNodeWithTag(EditorTestTags.EDITOR).performTouchInput {
            click(Offset(100f, 55f))
        }
        waitForIdle()

        assertTrue(pointerSelections > 0, "Short touch tap should emit Pointer selection change")
        assertTrue(state.selection.collapsed)
        assertTrue(state.selection.start > 0, "Tap on second line should move caret past 0")
        assertTrue(state.isFocused, "Short touch tap should focus editor")
    }

    @Test
    fun `touch long press selects word`() = runComposeUiTest {
        val state = CodeEditorState(initialText = "alpha beta gamma\ndelta epsilon")
        show(state)

        onNodeWithTag(EditorTestTags.EDITOR).performTouchInput {
            longClick(Offset(24f, 16f))
        }
        waitForIdle()

        assertEquals(TextRange(0, 5), state.selection, "Long press on first word should select 'alpha'")
    }

    @Test
    fun `dragging end selection handle extends selection`() = runComposeUiTest {
        val state = CodeEditorState(initialText = "alpha beta gamma delta")
        show(state)

        onNodeWithTag(EditorTestTags.EDITOR).performTouchInput {
            longClick(Offset(24f, 16f))
        }
        waitForIdle()
        val initialEnd = state.selection.max
        assertTrue(initialEnd > 0)

        onNodeWithTag(EditorTestTags.HANDLE + SelectionHandle.End.name).performTouchInput {
            swipe(
                start = center,
                end = Offset(center.x + 80f, center.y),
                durationMillis = 200L,
            )
        }
        waitForIdle()

        assertTrue(state.selection.max > initialEnd, "Dragging end handle right should extend selection")
    }
}
