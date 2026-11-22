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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.DiffEditorState
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The diff layout's pointer input: the gutter's arrow, a mouse drag of the divider and the context menus. */
class DiffEditorPointerUiTest {

    private fun diffOf(original: String, modified: String) = DiffEditorState(
        CodeEditorState(original).also { it.computeDispatcher = Dispatchers.Unconfined },
        CodeEditorState(modified).also { it.computeDispatcher = Dispatchers.Unconfined },
    )

    private fun ComposeUiTest.show(
        state: DiffEditorState,
        width: Dp = 1000.dp,
        height: Dp = 600.dp,
        options: DiffEditorOptions = DiffEditorOptions(),
    ) {
        setContent { CodeDiffEditorLayout(state, Modifier.size(width, height), options) }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { state.changes.isNotEmpty() }
        waitForIdle()
        waitForIdle()
    }

    private val numbered = (0 until 30).map { "line $it" }
    private val original = numbered.joinToString("\n")

    /** Line 5 replaced, and line 20 replaced: two changes, well apart. */
    private val modified = numbered.mapIndexed { i, s ->
        when (i) {
            5 -> "five changed"
            20 -> "twenty changed"
            else -> s
        }
    }.joinToString("\n")

    private fun ComposeUiTest.editors() = onAllNodesWithTag(EditorTestTags.EDITOR)

    private fun lineOf(state: CodeEditorState): Int = state.document.offsetToLineCol(state.selection.end).first

    /** A point in the middle of [line]'s row, in the pane of [view]'s editor, [x] from its left edge. */
    private fun rowCenter(view: com.aardarch.aardink.ui.view.EditorView, line: Int, x: Float) =
        Offset(x, view.textTop(line) - view.scroll.scrollY + view.frame.metrics.lineHeight / 2f)

    @Test
    fun `tapping the gutter's arrow on a change's first line reverts that change`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val view = assertNotNull(state.modifiedView)
        val second = state.changes[1].modifiedLines.first
        val gutter = onAllNodesWithTag(EditorTestTags.GUTTER)[1]
        // The arrow sits in the diff lane, past its 4 dp bar.
        gutter.performTouchInput { click(rowCenter(view, second, density * 10f)) }
        waitForIdle()
        assertEquals(modified.replace("twenty changed", "line 20"), state.modified.text)
        // The first change is still there, and the diff is worked out again.
        waitUntil(timeoutMillis = 5_000) { state.changes.size == 1 }
        assertEquals(5, state.changes.single().modifiedLines.first)
    }

    @Test
    fun `tapping the diff lane on a line that is not a change's first does nothing`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val view = assertNotNull(state.modifiedView)
        onAllNodesWithTag(EditorTestTags.GUTTER)[1].performTouchInput { click(rowCenter(view, 12, density * 10f)) }
        waitForIdle()
        assertEquals(modified, state.modified.text)
    }

    @Test
    fun `the divider can be dragged with a mouse`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, options = DiffEditorOptions(splitRatio = 0.3f))
        fun widths() = editors().fetchSemanticsNodes().map { it.size.width }
        val (left, right) = widths()
        onNodeWithTag(EditorTestTags.DIFF_DIVIDER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(100f, 0f))
            moveBy(Offset(100f, 0f))
            release()
        }
        waitForIdle()
        val (draggedLeft, draggedRight) = widths()
        assertTrue(draggedLeft > left + 100, "left $left to $draggedLeft")
        assertTrue(draggedRight < right - 100, "right $right to $draggedRight")
        // And back, to the left.
        onNodeWithTag(EditorTestTags.DIFF_DIVIDER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(-150f, 0f))
            release()
        }
        waitForIdle()
        assertTrue(widths()[0] < draggedLeft - 100)
    }

    @Test
    fun `the modified side's menu reverts the change at the caret`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val view = assertNotNull(state.modifiedView)
        val first = state.changes[0].modifiedLines.first
        runOnIdle { state.modified.selection = TextRange(state.modified.document.lineStart(first)) }
        editors()[1].performMouseInput { rightClick(rowCenter(view, first, 200f)) }
        waitForIdle()
        onNodeWithText("Next change").assertExists()
        onNodeWithText("Previous change").assertExists()
        onNodeWithText("Revert change").performClick()
        waitForIdle()
        assertEquals(modified.replace("five changed", "line 5"), state.modified.text)
    }

    @Test
    fun `the menu's Next change and Previous change move the caret`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val view = assertNotNull(state.modifiedView)
        val firsts = state.changes.map { it.modifiedLines.first }
        runOnIdle { state.modified.selection = TextRange(0) }
        editors()[1].performMouseInput { rightClick(rowCenter(view, 0, 200f)) }
        waitForIdle()
        onNodeWithText("Next change").performClick()
        waitForIdle()
        assertEquals(firsts[0], lineOf(state.modified))
        editors()[1].performMouseInput { rightClick(rowCenter(view, firsts[0], 200f)) }
        waitForIdle()
        onNodeWithText("Next change").performClick()
        waitForIdle()
        assertEquals(firsts[1], lineOf(state.modified))
        editors()[1].performMouseInput { rightClick(rowCenter(view, firsts[1], 200f)) }
        waitForIdle()
        onNodeWithText("Previous change").performClick()
        waitForIdle()
        assertEquals(firsts[0], lineOf(state.modified))
        assertEquals(modified, state.modified.text)
    }

    @Test
    fun `a read-only original's menu has no Accept change, an editable one's accepts the change`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val left = assertNotNull(state.originalView)
        val change = state.changes[0]
        val line = change.originalLines.first
        runOnIdle { state.original.selection = TextRange(state.original.document.lineStart(line)) }
        editors()[0].performMouseInput { rightClick(rowCenter(left, line, 200f)) }
        waitForIdle()
        onNodeWithText("Next change").assertExists()
        assertEquals(0, onAllNodesWithText("Accept change").fetchSemanticsNodes().size)
    }

    @Test
    fun `an editable original's menu accepts the change at its caret`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, options = DiffEditorOptions(originalEditable = true))
        val left = assertNotNull(state.originalView)
        val line = state.changes[0].originalLines.first
        runOnIdle { state.original.selection = TextRange(state.original.document.lineStart(line)) }
        editors()[0].performMouseInput { rightClick(rowCenter(left, line, 200f)) }
        waitForIdle()
        onNodeWithText("Accept change").performClick()
        waitForIdle()
        assertEquals(original.replace("line 5", "five changed"), state.original.text)
        assertEquals(modified, state.modified.text)
    }
}
