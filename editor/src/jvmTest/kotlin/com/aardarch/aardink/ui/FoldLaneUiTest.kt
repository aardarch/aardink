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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.NoOpFoldingProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gutter's fold lane is there from the first frame when the language can fold, so the text
 * area keeps its width (and wrapped lines their rows) when the first ranges arrive.
 */
class FoldLaneUiTest {

    private val calls = AtomicInteger()
    private val braces = FoldingProvider {
        calls.incrementAndGet()
        listOf(FoldRange(0, 2))
    }

    private fun ComposeUiTest.gutterWidth(): Int = onNodeWithTag(EditorTestTags.GUTTER).fetchSemanticsNode().size.width

    @Test
    fun `the gutter keeps its width when the fold ranges arrive`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CodeEditorLayout(
                state = CodeEditorState("fun a() {\n    b()\n}\n"),
                modifier = Modifier.size(400.dp, 200.dp),
                foldState = FoldState(),
                foldingProvider = braces,
            )
        }
        mainClock.advanceTimeByFrame()
        val before = gutterWidth()
        assertEquals(0, calls.get(), "measured before the ranges were asked for")

        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(calls.get() > 0)
        assertEquals(before, gutterWidth())
        mainClock.autoAdvance = true
    }

    @Test
    fun `without a folding provider there is no fold lane`() = runComposeUiTest {
        var provider by mutableStateOf(NoOpFoldingProvider)
        setContent {
            CodeEditorLayout(
                state = CodeEditorState("fun a() {\n    b()\n}\n"),
                modifier = Modifier.size(400.dp, 200.dp),
                foldState = FoldState(),
                foldingProvider = provider,
            )
        }
        waitForIdle()
        val plain = gutterWidth()
        provider = braces
        waitForIdle()
        val withFolding = gutterWidth()
        assertTrue(withFolding > plain, "fold lane: $withFolding vs $plain")
    }
}
