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
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's renderer in a real composition: what it lays out, and what the pointer does to
 * the selection.
 */
class EditorViewUiTest {

    private fun ComposeUiTest.showEditor(state: CodeEditorState, foldState: FoldState? = null, softWrap: Boolean = false) {
        setContent {
            CodeEditorLayout(
                state = state,
                foldState = foldState,
                options = EditorOptions(softWrap = softWrap),
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    /** Saves what is on screen, for looking at while developing: -Daardink.viewShots=<dir>. */
    private fun ComposeUiTest.shot(name: String) {
        val dir = System.getProperty("aardink.viewShots") ?: return
        val image = onRoot().captureToImage().toAwtImage()
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    @Test
    fun `a large document lays out only the lines on screen`() = runComposeUiTest {
        val text = (1..100_000).joinToString("\n") { "val line$it = $it" }
        val state = CodeEditorState(initialText = text, tokenizer = WordTokenizer)
        showEditor(state)
        shot("large")
        val lines = state.visibleLines
        assertTrue(lines != null && lines.first == 0 && lines.last in 10..40, "visible lines were $lines")
    }

    @Test
    fun `a click places the caret where it lands`() = runComposeUiTest {
        val state = CodeEditorState(initialText = "first line\nsecond line\nthird line")
        showEditor(state)
        // Line 2 (row 1), a few characters in: 8 dp padding, 20 sp rows.
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput {
            click(
                Offset(
                    8.dp.toPx() + 3 * 8.4f.dp.toPx() / 1.0f,
                    8.dp.toPx() + 30.dp.toPx(),
                ),
            )
        }
        waitForIdle()
        val caret = state.selection
        assertTrue(caret.collapsed)
        assertEquals(1, state.document.offsetToLineCol(caret.start).first)
    }

    @Test
    fun `a double click selects the word`() = runComposeUiTest {
        val state = CodeEditorState(initialText = "alpha beta gamma")
        showEditor(state)
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { doubleClick(Offset(8.dp.toPx() + 2f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        assertEquals(TextRange(0, 5), state.selection)
    }

    @Test
    fun `closed folds hide their lines`() = runComposeUiTest {
        val text = (0 until 50).joinToString("\n") { "line $it" }
        val state = CodeEditorState(initialText = text)
        val folds = FoldState().apply {
            updateFoldableRanges(listOf(FoldRange(2, 30)))
            toggle(2)
        }
        showEditor(state, folds)
        shot("folded")
        val lines = state.visibleLines!!
        // Lines 3..30 are hidden, so the screen reaches well past line 30.
        assertTrue(lines.last > 35, "visible lines were $lines")
    }

    @Test
    fun `soft wrap breaks long lines into rows`() = runComposeUiTest {
        val long = (1..60).joinToString(" ") { "word$it" }
        val state = CodeEditorState(initialText = "$long\nshort")
        showEditor(state, softWrap = true)
        shot("wrapped")
        val lines = state.visibleLines!!
        assertEquals(0..1, lines)
    }

    @Test
    fun `selections, find matches and edits draw`() = runComposeUiTest {
        val state = CodeEditorState(initialText = "fun main() {\n    val x = 1\n    println(x)\n}", tokenizer = WordTokenizer)
        showEditor(state)
        state.setSelections(listOf(TextRange(17, 22), TextRange(40, 41)))
        waitForIdle()
        shot("selections")
        state.applyEdit(0, 0, "// added\n", TextRange(9))
        waitForIdle()
        shot("edited")
        assertEquals(0..4, state.visibleLines)
    }

    /** Keywords and numbers, enough to see colours. */
    private object WordTokenizer : IncrementalTokenizer {
        private val keywords = setOf("val", "fun")

        override fun tokenizeFull(text: String): List<Token> = buildList {
            Regex("[A-Za-z_]+|[0-9]+").findAll(text).forEach { match ->
                val type = when {
                    match.value in keywords -> TokenType.Keyword
                    match.value[0].isDigit() -> TokenType.Number
                    else -> return@forEach
                }
                add(Token(match.range.first, match.range.last + 1, type))
            }
        }

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = tokenizeFull(text)

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }
}
