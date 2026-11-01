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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Folding from the keyboard: Ctrl+Shift+[ and ], and the Ctrl+K chords. */
class FoldKeysUiTest {

    /** 0..5 holds 1..2 and 3..4. */
    private val blocks = FoldingProvider { listOf(FoldRange(0, 5), FoldRange(1, 2), FoldRange(3, 4)) }

    private fun ComposeUiTest.show(state: CodeEditorState, folds: FoldState) {
        setContent {
            CodeEditorLayout(state = state, foldState = folds, foldingProvider = blocks, modifier = Modifier.size(600.dp, 400.dp))
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private fun ComposeUiTest.keys(block: KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    private val text = (0..6).joinToString("\n") { "line $it" }

    @Test
    fun `ctrl k then ctrl 0 folds everything and ctrl k ctrl j unfolds it`() = runComposeUiTest {
        val state = CodeEditorState(text)
        val folds = FoldState()
        show(state, folds)
        assertEquals(3, folds.foldableRanges.size)
        keys {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.K)
                pressKey(Key.Zero)
            }
        }
        assertEquals(3, folds.foldedCount)
        assertEquals(text, state.text, "the chord typed nothing")
        keys {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.K)
                pressKey(Key.J)
            }
        }
        assertEquals(0, folds.foldedCount)
    }

    @Test
    fun `ctrl k ctrl 2 folds the second level only`() = runComposeUiTest {
        val folds = FoldState()
        show(CodeEditorState(text), folds)
        keys {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.K)
                pressKey(Key.Two)
            }
        }
        assertEquals(listOf(1, 3), folds.foldedRanges().map { it.startLine })
    }

    @Test
    fun `a chord that means nothing is swallowed, and the next key is ordinary`() = runComposeUiTest {
        val state = CodeEditorState(text)
        val folds = FoldState()
        show(state, folds)
        state.selection = TextRange(0)
        keys {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
            pressKey(Key.Q)
        }
        assertEquals(text, state.text)
        assertEquals(0, folds.foldedCount)
    }

    @Test
    fun `ctrl shift brackets fold and unfold the innermost range at the caret`() = runComposeUiTest {
        val state = CodeEditorState(text)
        val folds = FoldState()
        show(state, folds)
        state.selection = TextRange(state.document.lineStart(3) + 2)
        keys {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.LeftBracket) } }
        }
        assertEquals(listOf(3), folds.foldedRanges().map { it.startLine })
        keys {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.RightBracket) } }
        }
        assertFalse(folds.isFolded(3))
        assertTrue(folds.foldAt(3) && folds.foldAt(3), "the outer range folds after the inner one")
        assertEquals(listOf(0, 3), folds.foldedRanges().map { it.startLine })
    }
}
