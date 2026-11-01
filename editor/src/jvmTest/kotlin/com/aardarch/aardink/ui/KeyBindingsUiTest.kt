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
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import kotlin.test.Test
import kotlin.test.assertEquals

/** Keys as data: the editor's own, a host's overrides and removals, and chords. */
class KeyBindingsUiTest {

    private object Slashes : IncrementalTokenizer {
        override val commentSyntax = CommentSyntax(line = "//")

        override fun tokenizeFull(text: String): List<Token> = emptyList()

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    private fun ComposeUiTest.show(state: CodeEditorState, options: EditorOptions = EditorOptions()) {
        setContent { CodeEditorLayout(state = state, options = options, modifier = Modifier.size(600.dp, 400.dp)) }
        waitForIdle()
    }

    private fun ComposeUiTest.keys(block: KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    @Test
    fun `ctrl j joins lines and ctrl k ctrl c comments them`() = runComposeUiTest {
        val state = CodeEditorState("a\nb", tokenizer = Slashes)
        show(state)
        state.selection = TextRange(0)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.J) } }
        assertEquals("a b", state.text)
        keys {
            withKeyDown(Key.CtrlLeft) {
                pressKey(Key.K)
                pressKey(Key.C)
            }
        }
        assertEquals("// a b", state.text)
    }

    @Test
    fun `a host's binding wins over the editor's, and a null one removes it`() = runComposeUiTest {
        val state = CodeEditorState("ab\ncd", tokenizer = Slashes)
        val options = EditorOptions(
            keyBindings = listOf(
                EditorKeyBinding(KeyChord(Key.J, winCtrl = true), EditorCommand.UpperCase),
                EditorKeyBinding(KeyChord(Key.L, ctrlCmd = true), null),
            ),
        )
        show(state, options)
        state.selection = TextRange(1)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.J) } }
        assertEquals("AB\ncd", state.text)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
        assertEquals(TextRange(1), state.selection, "Ctrl+L no longer selects the line")
    }

    @Test
    fun `ctrl enter opens a line below, and undo is still ctrl z`() = runComposeUiTest {
        val state = CodeEditorState("abc")
        show(state)
        state.selection = TextRange(1)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        assertEquals("abc\n", state.text)
        assertEquals(TextRange(4), state.selection)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        assertEquals("abc", state.text)
    }
}
