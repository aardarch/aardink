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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Diagnostics on screen: F8 through the problems, and a squiggle's message under the mouse. */
class DiagnosticsUiTest {

    private fun ComposeUiTest.show(state: CodeEditorState, diagnostics: List<Diagnostic>, service: LanguageService? = null) {
        setContent {
            CodeEditorLayout(
                state = state,
                diagnostics = diagnostics,
                languageService = service,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    /** Fixes any range by upper-casing it. */
    private object Fixer : LanguageService {
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions) = ""

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument, options: FormattingOptions) = document.text

        override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> = listOf(
            CodeAction(
                "Upper-case it",
                edits = listOf(TextEdit(range, document.subSequence(range.first, range.last + 1).toString().uppercase())),
            ),
        )
    }

    private fun ComposeUiTest.keys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    private val problems = listOf(
        Diagnostic(0..2, "first problem", DiagnosticSeverity.Error),
        Diagnostic(4..6, "just a hint", DiagnosticSeverity.Hint),
        Diagnostic(8..10, "second problem", DiagnosticSeverity.Warning),
    )

    @Test
    fun `F8 goes through the problems, skipping hints, and wraps`() = runComposeUiTest {
        val state = CodeEditorState("aaa bbb ccc")
        show(state, problems)
        keys { pressKey(Key.F8) }
        assertEquals(TextRange(8, 11), state.selection)
        onNodeWithText("second problem  (2 of 2)").assertExists()
        keys { pressKey(Key.F8) }
        assertEquals(TextRange(0, 3), state.selection)
        onNodeWithText("first problem  (1 of 2)").assertExists()
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F8) } }
        assertEquals(TextRange(8, 11), state.selection)
        keys { pressKey(Key.Escape) }
        onNodeWithText("second problem  (2 of 2)").assertDoesNotExist()
    }

    @Test
    fun `ctrl dot offers the quick fixes for the diagnostic at the caret`() = runComposeUiTest {
        val state = CodeEditorState("aaa bbb")
        show(state, listOf(Diagnostic(4..6, "shouting needed", DiagnosticSeverity.Warning)), Fixer)
        state.selection = TextRange(5)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Period) } }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Upper-case it").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Upper-case it").performClick()
        waitForIdle()
        assertEquals("aaa BBB", state.text)
    }

    @Test
    fun `a resting mouse over a squiggle shows its message, with no language service`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        show(state, listOf(Diagnostic(0..4, "bad word", DiagnosticSeverity.Error, source = "spell", code = "S1")))
        mainClock.autoAdvance = false
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { moveTo(Offset(8.dp.toPx() + 10f, 8.dp.toPx() + 10.dp.toPx())) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("bad word").assertExists()
        onNodeWithText("spell (S1)").assertExists()
        mainClock.autoAdvance = true
    }
}
