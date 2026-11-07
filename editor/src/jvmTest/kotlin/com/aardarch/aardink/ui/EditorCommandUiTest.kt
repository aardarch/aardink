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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CodeEditorState.execute]: text commands on the state alone, the rest through the layout. */
class EditorCommandUiTest {

    /** Formats by upper-casing, completes with one word. */
    private object Upper : LanguageService {
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int) =
            listOf(CompletionItem("word", CompletionKind.Value, "word"))

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = document.text.uppercase()
    }

    private object Slashes : IncrementalTokenizer {
        override val commentSyntax = CommentSyntax(line = "//")

        override fun tokenizeFull(text: String): List<Token> = emptyList()

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    /** Two ranges, one inside the other: 0..3 and 1..2. */
    private val blocks = FoldingProvider { listOf(FoldRange(0, 3), FoldRange(1, 2)) }

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        service: LanguageService? = Upper,
        options: EditorOptions = EditorOptions(),
        foldState: FoldState? = null,
        onRequestGoToLine: () -> Unit = {},
    ) {
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = service,
                options = options,
                findReplaceState = FindReplaceState(),
                foldState = foldState,
                foldingProvider = if (foldState != null) blocks else com.aardarch.aardink.core.NoOpFoldingProvider,
                onRequestGoToLine = onRequestGoToLine,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    @Test
    fun `text commands work without a layout, the others need one`() {
        val state = CodeEditorState("a\nb", tokenizer = Slashes)
        assertFalse(state.canExecute(EditorCommand.Undo))
        assertTrue(state.execute(EditorCommand.ToggleComment))
        assertEquals("// a\nb", state.text)
        assertTrue(state.execute(EditorCommand.Undo))
        assertEquals("a\nb", state.text)
        state.selection = TextRange(2)
        assertTrue(state.execute(EditorCommand.InsertSnippet("<x>$0</x>")))
        assertEquals("a\n<x></x>b", state.text)
        assertEquals(TextRange(5), state.selection)
        assertFalse(state.execute(EditorCommand.Format), "no layout shows it")
        assertTrue(EditorCommand.Redo !in state.availableCommands)
    }

    @Test
    fun `the layout runs format, find, go to line and folding`() = runComposeUiTest {
        val state = CodeEditorState("one\ntwo\nthree\nfour")
        val folds = FoldState()
        var goToLine = 0
        show(state, foldState = folds, onRequestGoToLine = { goToLine++ })
        assertTrue(EditorCommand.Format in state.availableCommands)
        // The folding provider answers off the main thread; let it, before and after the edit.
        waitUntil(timeoutMillis = 5_000) { folds.foldableRanges.size == 2 }
        assertTrue(state.execute(EditorCommand.Format))
        waitUntil(timeoutMillis = 5_000) { state.text == "ONE\nTWO\nTHREE\nFOUR" }
        waitUntil(timeoutMillis = 5_000) { folds.foldableRanges == listOf(FoldRange(0, 3), FoldRange(1, 2)) }
        assertTrue(state.execute(EditorCommand.GoToLine))
        assertEquals(1, goToLine)
        assertTrue(state.execute(EditorCommand.FoldLevel(2)))
        assertTrue(folds.isFolded(1) && !folds.isFolded(0))
        assertTrue(state.execute(EditorCommand.FoldAll))
        assertTrue(folds.isFolded(0))
        assertTrue(state.execute(EditorCommand.UnfoldAll))
        assertFalse(folds.isFolded(0))
        assertTrue(state.execute(EditorCommand.Reveal(state.document.length, center = true)))
        assertTrue(state.execute(EditorCommand.TriggerSuggest))
        waitForIdle()
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
    }

    @Test
    fun `a read-only editor offers no editing commands`() = runComposeUiTest {
        val state = CodeEditorState("x", tokenizer = Slashes)
        show(state, options = EditorOptions(readOnly = true))
        assertFalse(EditorCommand.Format in state.availableCommands)
        assertFalse(EditorCommand.ToggleComment in state.availableCommands)
        assertFalse(state.execute(EditorCommand.ToggleComment))
        assertFalse(state.execute(EditorCommand.InsertSnippet("y")))
        assertTrue(EditorCommand.GoToDefinition in state.availableCommands)
        assertEquals("x", state.text)
    }

    @Test
    fun `without a language service the service's commands are unavailable`() = runComposeUiTest {
        val state = CodeEditorState("x")
        val visible = mutableStateOf(true)
        setContent {
            if (visible.value) CodeEditorLayout(state = state, modifier = Modifier.size(600.dp, 400.dp))
        }
        waitForIdle()
        assertFalse(EditorCommand.Format in state.availableCommands)
        assertTrue(EditorCommand.NextProblem in state.availableCommands)
        visible.value = false
        waitForIdle()
        assertFalse(state.canExecute(EditorCommand.NextProblem), "the layout is gone")
    }
}
