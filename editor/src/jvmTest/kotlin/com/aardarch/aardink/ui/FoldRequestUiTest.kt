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
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/** Ranges that have to be waited for, as a language server's have: what a test hands over, when it does. */
private class WaitedForFolding : FoldingProvider {
    val asked = AtomicInteger()
    val answered = AtomicInteger()
    private val answers = Channel<() -> List<FoldRange>>(Channel.UNLIMITED)

    fun answer(block: () -> List<FoldRange>) {
        answers.trySend(block)
    }

    override fun foldableRanges(document: CodeDocument): List<FoldRange> = error("a request is the way to ask")

    override suspend fun requestFoldableRanges(document: CodeDocument): List<FoldRange> {
        asked.incrementAndGet()
        val answer = answers.receive()
        answered.incrementAndGet()
        return answer()
    }
}

class FoldRequestUiTest {

    private val provider = WaitedForFolding()

    private fun ComposeUiTest.show(state: CodeEditorState, foldState: FoldState?) {
        setContent {
            CodeEditorLayout(
                state = state,
                options = EditorOptions(stickyScroll = true),
                foldState = foldState,
                foldingProvider = provider,
                modifier = Modifier.size(400.dp, 200.dp),
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.waitForAsked(times: Int) = waitUntil(timeoutMillis = 5_000) { provider.asked.get() == times }

    private fun CodeEditorState.typeAtEnd() = applyTextEdits(listOf(TextEdit(document.length until document.length, "x")))

    /** A state with [firstRange] folded, whose first request has been answered. */
    private fun ComposeUiTest.folded(state: CodeEditorState, folds: FoldState, firstRange: FoldRange) {
        show(state, folds)
        waitForAsked(1)
        provider.answer { listOf(firstRange) }
        waitUntil(timeoutMillis = 5_000) { folds.foldableRanges.isNotEmpty() }
        runOnIdle { assertTrue(folds.foldAt(firstRange.startLine)) }
    }

    /** Waits out the last answer, and then a later one, which only arrives once that has been dealt with. */
    private fun ComposeUiTest.answerAgainAfterAnEdit(state: CodeEditorState, folds: FoldState, times: Int, range: FoldRange) {
        waitUntil(timeoutMillis = 5_000) { provider.answered.get() == times }
        waitForIdle()
        runOnIdle { state.typeAtEnd() }
        waitForAsked(times + 1)
        provider.answer { listOf(range) }
        waitUntil(timeoutMillis = 5_000) { folds.foldableRanges == listOf(range) }
    }

    @Test
    fun `a server that never answers does not hold up the next request`() = runComposeUiTest {
        val state = CodeEditorState("a\nb\nc")
        val folds = FoldState()
        show(state, folds)
        waitForAsked(1)

        runOnIdle { state.typeAtEnd() }
        waitForAsked(2)
        provider.answer { listOf(FoldRange(0, 2)) }

        waitUntil(timeoutMillis = 5_000) { folds.foldableRanges == listOf(FoldRange(0, 2)) }
    }

    @Test
    fun `an answer for text that has since changed does not unfold anything`() = runComposeUiTest {
        val state = CodeEditorState("a\nb\nc\nd")
        val folds = FoldState()
        folded(state, folds, FoldRange(0, 3))

        runOnIdle { state.typeAtEnd() }
        waitForAsked(2)
        // Straight on the document, which tells nobody: nothing cancels the request in flight.
        runOnIdle { state.document.insert(0, "x") }
        provider.answer { emptyList() }

        answerAgainAfterAnEdit(state, folds, times = 2, range = FoldRange(0, 3))
        assertTrue(folds.isFolded(0))
    }

    @Test
    fun `a provider without an answer leaves the folds as they are`() = runComposeUiTest {
        val state = CodeEditorState("a\nb\nc\nd")
        val folds = FoldState()
        folded(state, folds, FoldRange(0, 3))

        runOnIdle { state.typeAtEnd() }
        waitForAsked(2)
        provider.answer { error("no answer this time") }

        answerAgainAfterAnEdit(state, folds, times = 2, range = FoldRange(0, 3))
        assertTrue(folds.isFolded(0))
    }

    @Test
    fun `an empty answer for the text as it is unfolds what is gone`() = runComposeUiTest {
        val state = CodeEditorState("a\nb\nc\nd")
        val folds = FoldState()
        folded(state, folds, FoldRange(0, 3))

        runOnIdle { state.typeAtEnd() }
        waitForAsked(2)
        provider.answer { emptyList() }

        waitUntil(timeoutMillis = 5_000) { !folds.isFolded(0) }
        assertTrue(folds.foldableRanges.isEmpty())
    }

    @Test
    fun `sticky scroll without a fold state asks the same way`() = runComposeUiTest {
        show(CodeEditorState("a\nb\nc"), foldState = null)

        waitForAsked(1)
    }
}
