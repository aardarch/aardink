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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.DiffEditorState
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.ui.view.EditorView
import com.aardarch.aardink.ui.view.MoreDeletedRows
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The diff layout on the desktop: what it shows side by side and inline, and how it moves through the changes. */
class CodeDiffEditorLayoutUiTest {

    private fun diffOf(original: String, modified: String) = DiffEditorState(
        CodeEditorState(original).also { it.computeDispatcher = Dispatchers.Unconfined },
        CodeEditorState(modified).also { it.computeDispatcher = Dispatchers.Unconfined },
    )

    private fun ComposeUiTest.show(
        state: DiffEditorState,
        width: Dp = 1000.dp,
        height: Dp = 1200.dp,
        options: DiffEditorOptions = DiffEditorOptions(),
        editorOptions: EditorOptions = EditorOptions(),
    ) {
        setContent { CodeDiffEditorLayout(state, Modifier.size(width, height), options, editorOptions) }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { state.original.document.text == state.modified.document.text || state.changes.isNotEmpty() }
        waitForIdle()
        waitForIdle()
    }

    private val numbered = (0 until 40).map { "line $it" }
    private val original = numbered.joinToString("\n")

    /** Lines 5 and 6 deleted, line 20 replaced by two lines, and a line inserted after line 30. */
    private val modified = numbered.flatMapIndexed { i, line ->
        when (i) {
            5, 6 -> emptyList()
            20 -> listOf("new a", "new b")
            30 -> listOf(line, "inserted")
            else -> listOf(line)
        }
    }.joinToString("\n")

    /** The unchanged lines, as pairs of original and modified line numbers. */
    private fun unchangedPairs(state: DiffEditorState): List<Pair<Int, Int>> {
        val pairs = ArrayList<Pair<Int, Int>>()
        var o = 0
        var m = 0
        val originalLines = state.original.document.lineCount
        for (change in state.changes + listOf(null)) {
            val end = change?.originalLines?.first ?: originalLines
            while (o < end) pairs += o++ to m++
            if (change != null) {
                o = change.originalLines.last + 1
                m = change.modifiedLines.last + 1
            }
        }
        return pairs
    }

    private fun lineOf(state: CodeEditorState): Int = state.document.offsetToLineCol(state.selection.end).first

    // ── Side by side ─────────────────────────────────────────────────────────

    @Test
    fun `side by side shows both texts, with every unchanged line at one height`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val left = assertNotNull(state.originalView)
        val right = assertNotNull(state.modifiedView)
        assertEquals(3, state.changes.size)
        val pairs = unchangedPairs(state)
        var compared = 0
        for ((o, m) in pairs) {
            if (left.frame.find(o) == null || right.frame.find(m) == null) continue
            assertEquals(left.textTop(o), right.textTop(m), "original $o, modified $m")
            compared++
        }
        assertTrue(compared > 25, "compared $compared lines")
        assertEquals(left.totalRows, right.totalRows)
        // The deleted lines are filler rows on the modified side, and the added ones on the original's.
        assertTrue(state.modified.diffRows.isNotEmpty())
        assertTrue(state.original.diffRows.isNotEmpty())
        // Neither document got any of it.
        assertEquals(original, state.original.text)
        assertEquals(modified, state.modified.text)
    }

    @Test
    fun `the original is read-only unless it is made editable`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        assertTrue(state.original.readOnly)
        assertTrue(!state.modified.readOnly)
    }

    @Test
    fun `the original can be edited with originalEditable`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, options = DiffEditorOptions(originalEditable = true))
        assertTrue(!state.original.readOnly)
        val change = state.changes.first()
        runOnIdle { state.accept(change) }
        waitForIdle()
        assertTrue(state.original.text.lines().none { it == "line 5" })
    }

    @Test
    fun `scrolling either side scrolls the other`() = runComposeUiTest {
        val long = (0 until 200).map { "line $it " + "x".repeat(300) }
        val state = diffOf(long.joinToString("\n"), long.filterIndexed { i, _ -> i != 3 }.joinToString("\n"))
        show(state, height = 300.dp)
        val left = assertNotNull(state.originalView)
        val right = assertNotNull(state.modifiedView)
        runOnIdle { left.scroll.scrollToY(600f) }
        waitForIdle()
        assertEquals(600f, left.scroll.scrollY)
        assertEquals(600f, right.scroll.scrollY)
        runOnIdle { right.scroll.scrollToY(1000f) }
        waitForIdle()
        assertEquals(1000f, left.scroll.scrollY)
        assertEquals(1000f, right.scroll.scrollY)
        runOnIdle { left.scroll.scrollToX(150f) }
        waitForIdle()
        assertEquals(150f, left.scroll.scrollX)
        assertEquals(150f, right.scroll.scrollX)
        runOnIdle { right.scroll.scrollToX(40f) }
        waitForIdle()
        assertEquals(40f, left.scroll.scrollX)
        assertEquals(40f, right.scroll.scrollX)
    }

    @Test
    fun `under soft wrap, panes of different widths still line up what is on screen`() = runComposeUiTest {
        // The test scene is 768 high whatever size is asked for, so the long lines are short enough that more than
        // ten lines fit on screen with any platform's font (Linux's wraps more), and long enough to wrap in the
        // narrow pane more than in the wide one.
        val lines = (0 until 40).map { if (it % 3 == 0) "line $it " + "word ".repeat(15) else "line $it" }
        val state = diffOf(lines.joinToString("\n"), lines.filterIndexed { i, _ -> i != 7 }.joinToString("\n"))
        show(state, options = DiffEditorOptions(splitRatio = 0.3f), editorOptions = EditorOptions(softWrap = true))
        repeat(3) {
            mainClock.advanceTimeBy(200)
            waitForIdle()
        }
        val left = assertNotNull(state.originalView)
        val right = assertNotNull(state.modifiedView)
        var compared = 0
        var wrapsMoreOnTheLeft = 0
        for ((o, m) in unchangedPairs(state)) {
            val leftLine = left.frame.find(o) ?: continue
            val rightLine = right.frame.find(m) ?: continue
            assertEquals(left.textTop(o), right.textTop(m), "original $o, modified $m")
            if (leftLine.layout.textRows > rightLine.layout.textRows) wrapsMoreOnTheLeft++
            compared++
        }
        assertTrue(compared > 10, "compared $compared lines")
        assertTrue(wrapsMoreOnTheLeft > 0, "no line on screen wraps more in the narrow pane")
        assertEquals(left.totalRows, right.totalRows)
    }

    @Test
    fun `the diff marks the lines and the characters that changed`() = runComposeUiTest {
        val state = diffOf("a\nhello world\nc", "a\nhello there\nc\nd")
        show(state)
        val inserted = state.modified.decorations(DIFF_OWNER)
        val removed = state.original.decorations(DIFF_OWNER)
        assertTrue(inserted.any { it.style.wholeLine && it.style.overviewRulerColor != androidx.compose.ui.graphics.Color.Unspecified })
        assertTrue(inserted.any { !it.style.wholeLine && it.style.background != androidx.compose.ui.graphics.Color.Unspecified })
        assertTrue(removed.any { it.style.wholeLine })
        assertTrue(removed.any { !it.style.wholeLine })
        // The host's own decorations are not touched.
        runOnIdle {
            state.modified.setDecorations(
                "host",
                listOf(com.aardarch.aardink.core.Decoration(0..1, com.aardarch.aardink.core.DecorationStyle())),
            )
        }
        waitForIdle()
        assertEquals(1, state.modified.decorations("host").size)
    }

    @Test
    fun `the split starts at splitRatio and the divider can be dragged`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, options = DiffEditorOptions(splitRatio = 0.3f))
        fun widths() = onAllNodesWithTag(EditorTestTags.EDITOR).fetchSemanticsNodes().map { it.size.width }
        val (left, right) = widths()
        assertEquals(0.3f, left / (left + right).toFloat(), 0.03f)
        onNodeWithTag(EditorTestTags.DIFF_DIVIDER).performTouchInput { swipeRight(startX = centerX, endX = centerX + 200f) }
        waitForIdle()
        val (draggedLeft, draggedRight) = widths()
        assertTrue(draggedLeft > left + 100, "left $left to $draggedLeft")
        assertTrue(draggedRight < right - 100)
    }

    @Test
    fun `a divider that cannot be dragged stays`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, options = DiffEditorOptions(enableSplitViewResizing = false))
        val before = onAllNodesWithTag(EditorTestTags.EDITOR).fetchSemanticsNodes().map { it.size.width }
        onNodeWithTag(EditorTestTags.DIFF_DIVIDER).performTouchInput { swipeRight(startX = centerX, endX = centerX + 200f) }
        waitForIdle()
        assertEquals(before, onAllNodesWithTag(EditorTestTags.EDITOR).fetchSemanticsNodes().map { it.size.width })
    }

    // ── Inline ───────────────────────────────────────────────────────────────

    @Test
    fun `below the breakpoint the view is inline, with the deleted lines as rows`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, width = 600.dp)
        assertNull(state.originalView)
        val view = assertNotNull(state.modifiedView)
        val rows = state.modified.diffRows.values.flatten()
        assertTrue(rows.any { "line 5" in it.text })
        assertTrue(rows.any { "line 6" in it.text })
        assertTrue(rows.any { "line 20" in it.text })
        // Two deleted lines and the one replaced, as rows above the lines after them.
        assertEquals(state.modified.document.lineCount + 3, view.totalRows)
        assertEquals(modified, state.modified.text)
    }

    @Test
    fun `renderSideBySide off is inline whatever the width`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state, options = DiffEditorOptions(renderSideBySide = false))
        assertNull(state.originalView)
        assertNotNull(state.modifiedView)
    }

    @Test
    fun `past a thousand deleted lines a row shows the rest on a click`() = runComposeUiTest {
        val deleted = (0 until 1500).joinToString("\n") { "deleted $it" }
        val state = diffOf("$deleted\nkeep", "keep")
        show(state, width = 600.dp)
        val view = assertNotNull(state.modifiedView)
        val rows = state.modified.diffRows.getValue(0)
        assertEquals(1001, rows.size)
        val more = rows.last()
        assertTrue("500 more deleted lines" in more.text, more.text)
        assertEquals(MoreDeletedRows(0), more.id)
        runOnIdle { view.onInjectionClick!!(0, more) }
        waitForIdle()
        waitForIdle()
        val all = state.modified.diffRows.getValue(0)
        assertEquals(1500, all.size)
        assertTrue(all.none { it.id != null })
    }

    // ── Moving through the changes ───────────────────────────────────────────

    @Test
    fun `F7 and Shift+F7 move the caret through the changes and wrap around`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val changes = state.changes
        val firsts = changes.map { it.modifiedLines.first }
        assertEquals(3, firsts.size)
        runOnIdle { state.modified.selection = TextRange(0) }
        val visited = ArrayList<Int>()
        repeat(4) {
            runOnIdle { assertTrue(state.modified.execute(EditorCommand.NextChange)) }
            visited += lineOf(state.modified)
        }
        assertEquals(listOf(firsts[0], firsts[1], firsts[2], firsts[0]), visited)
        val back = ArrayList<Int>()
        repeat(3) {
            runOnIdle { assertTrue(state.modified.execute(EditorCommand.PreviousChange)) }
            back += lineOf(state.modified)
        }
        assertEquals(listOf(firsts[2], firsts[1], firsts[0]), back)
        // The first change's line is shown.
        waitForIdle()
        val view = assertNotNull(state.modifiedView)
        assertNotNull(view.frame.find(firsts[0]))
    }

    @Test
    fun `the commands are available while the layout shows the states, and not after`() = runComposeUiTest {
        val state = diffOf(original, modified)
        var shown by mutableStateOf(true)
        setContent {
            if (shown) CodeDiffEditorLayout(state, Modifier.size(1000.dp, 400.dp))
        }
        waitForIdle()
        assertTrue(EditorCommand.NextChange in state.modified.availableCommands)
        assertTrue(EditorCommand.RevertChange in state.original.availableCommands)
        shown = false
        waitForIdle()
        assertNull(state.modified.diffCommands)
        assertNull(state.original.diffCommands)
        assertTrue(state.modified.diffRows.isEmpty())
        assertTrue(state.original.diffRows.isEmpty())
    }

    @Test
    fun `RevertChange reverts the change at the caret, as one undo step`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        val second = state.changes[1]
        runOnIdle { state.modified.selection = TextRange(state.modified.document.lineStart(second.modifiedLines.first)) }
        runOnIdle { assertTrue(state.modified.execute(EditorCommand.RevertChange)) }
        waitForIdle()
        val expected = modified.lines().toMutableList().also {
            it.removeAt(18)
            it.removeAt(18)
            it.add(18, "line 20")
        }.joinToString("\n")
        assertEquals(expected, state.modified.text)
        runOnIdle { state.modified.undo() }
        assertEquals(modified, state.modified.text)
    }

    @Test
    fun `RevertChange off a change does nothing`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        runOnIdle { state.modified.selection = TextRange(state.modified.document.lineStart(0)) }
        runOnIdle { assertTrue(!state.modified.execute(EditorCommand.RevertChange)) }
        assertEquals(modified, state.modified.text)
    }

    @Test
    fun `the gutter's arrow reverts a change`() = runComposeUiTest {
        val state = diffOf("a\nb\nc", "a\nB\nc")
        show(state)
        val change = state.changes.single()
        assertNotNull(change)
        // The arrow's action is what the lane runs for a tap on the change's first line.
        runOnIdle { state.changeAt(1)?.let(state::revert) }
        assertEquals("a\nb\nc", state.modified.text)
    }

    // ── Hidden unchanged regions ─────────────────────────────────────────────

    @Test
    fun `hideUnchangedRegions folds both sides at once, and opening one opens the other`() = runComposeUiTest {
        val lines = (0 until 60).map { "line $it" }
        val state = diffOf(lines.joinToString("\n"), lines.mapIndexed { i, s -> if (i == 30) "changed" else s }.joinToString("\n"))
        show(state, options = DiffEditorOptions(hideUnchangedRegions = true, unchangedContextLines = 3))
        val left = assertNotNull(state.originalView)
        val right = assertNotNull(state.modifiedView)
        val leftFolds = assertNotNull(left.foldState)
        val rightFolds = assertNotNull(right.foldState)
        assertEquals(listOf(2, 33), leftFolds.foldedRanges().map { it.startLine })
        assertEquals(leftFolds.foldedRanges(), rightFolds.foldedRanges())
        assertEquals("24 unchanged lines", leftFolds.foldedRanges().first().placeholder)
        // 3 lines of context either side of the change, and 3 at each end.
        assertEquals(6 + 7, left.totalRows)
        assertEquals(left.totalRows, right.totalRows)
        runOnIdle { leftFolds.toggle(2) }
        waitForIdle()
        waitForIdle()
        assertTrue(!rightFolds.isFolded(2))
        assertTrue(rightFolds.isFolded(33))
        assertEquals(left.totalRows, right.totalRows)
        runOnIdle { rightFolds.toggle(2) }
        waitForIdle()
        waitForIdle()
        assertTrue(leftFolds.isFolded(2))
    }

    @Test
    fun `without hideUnchangedRegions nothing is folded`() = runComposeUiTest {
        val state = diffOf(original, modified)
        show(state)
        assertNull(state.originalView?.foldState)
    }

    // ── When it runs ─────────────────────────────────────────────────────────

    @Test
    fun `the diff is worked out once typing stops, not on every key`() = runComposeUiTest {
        val state = diffOf("a\nb\nc", "a\nb\nc")
        var diffs = 0
        state.addDiffListener { diffs++ }
        mainClock.autoAdvance = false
        setContent { CodeDiffEditorLayout(state, Modifier.size(1000.dp, 400.dp)) }
        mainClock.advanceTimeBy(100)
        waitForIdle()
        assertEquals(1, diffs, "the first diff comes at once")
        repeat(6) { i ->
            runOnIdle { state.modified.applyTextEdits(listOf(TextEdit(0 until 0, "x$i "))) }
            mainClock.advanceTimeBy(100)
        }
        assertEquals(1, diffs, "no diff while keys keep coming")
        mainClock.advanceTimeBy(400)
        waitForIdle()
        assertEquals(2, diffs)
        assertEquals(1, state.changes.size)
    }
}
