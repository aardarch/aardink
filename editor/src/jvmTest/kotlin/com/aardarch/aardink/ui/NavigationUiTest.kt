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
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.edit.TextNavigator
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/** Go to definition, the references list and format, through a language service that knows a few words. */
class NavigationUiTest {

    /**
     * `use` is defined at `def` in the same text, `ext` in another file; references are every
     * occurrence of the word; formatting spaces out `=`; formatting a range uppercases it.
     */
    private object Words : LanguageService {
        const val OTHER_FILE = "file:///lib/Other.kt"

        override suspend fun definition(document: CodeDocument, offset: Int): Location? {
            val text = document.text
            return when (word(document, offset)) {
                "use" -> text.indexOf("def").let { Location("file:///this.kt", it until it + 3) }
                "ext" -> Location(OTHER_FILE, IntRange.EMPTY, line = 4, column = 2)
                else -> null
            }
        }

        override suspend fun references(document: CodeDocument, offset: Int): List<Location> {
            val word = word(document, offset)
            if (word.isEmpty()) return emptyList()
            return Regex("\\b$word\\b").findAll(document.text).map { Location("file:///this.kt", it.range) }.toList()
        }

        override suspend fun format(document: CodeDocument): String = document.text.replace(Regex("\\s*=\\s*"), " = ")

        override suspend fun formatRange(document: CodeDocument, range: IntRange): List<TextEdit> =
            listOf(TextEdit(range, document.text.substring(range.first, range.last + 1).uppercase()))

        private fun word(document: CodeDocument, offset: Int): String {
            val range = TextNavigator.wordAt(document, offset)
            return if (range.isEmpty()) "" else document.text.substring(range.first, range.last + 1)
        }

        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = 0
    }

    private fun ComposeUiTest.show(state: CodeEditorState, opened: MutableList<Location> = mutableListOf()) {
        state.computeDispatcher = Dispatchers.Unconfined
        setContent {
            CodeEditorLayout(
                state = state,
                languageService = Words,
                onNavigateToLocation = { opened += it },
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.keys(block: KeyInjectionScope.() -> Unit) {
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    @Test
    fun `F12 selects the definition when it is in this document`() = runComposeUiTest {
        val state = CodeEditorState("val def = 1\nprint(use)")
        show(state)
        state.selection = TextRange(state.document.text.indexOf("use") + 1)
        keys { pressKey(Key.F12) }
        assertEquals(TextRange(4, 7), state.selection)
    }

    @Test
    fun `F12 hands a definition in another file to the host`() = runComposeUiTest {
        val state = CodeEditorState("print(ext)")
        val opened = mutableListOf<Location>()
        show(state, opened)
        state.selection = TextRange(7)
        keys { pressKey(Key.F12) }
        assertEquals(listOf(Location(Words.OTHER_FILE, IntRange.EMPTY, line = 4, column = 2)), opened)
        assertEquals(TextRange(7), state.selection, "the caret stays")
    }

    @Test
    fun `ctrl click goes to the definition`() = runComposeUiTest {
        val state = CodeEditorState("print(use)\nval def = 1")
        show(state)
        onNodeWithTag(EditorTestTags.EDITOR).requestFocus()
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { click(Offset(8.dp.toPx() + 7.3f * 8.4f, 8.dp.toPx() + 10.dp.toPx())) }
        onNodeWithTag(EditorTestTags.EDITOR).performKeyInput { keyUp(Key.CtrlLeft) }
        waitForIdle()
        val def = state.document.text.indexOf("def")
        assertEquals(TextRange(def, def + 3), state.selection)
    }

    @Test
    fun `shift F12 lists the references, and the arrows and enter open one`() = runComposeUiTest {
        val state = CodeEditorState("val a = 1\nprint(a)\nprint(a + a)")
        show(state)
        state.selection = TextRange(4)
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F12) } }
        onNodeWithTag(EditorTestTags.REFERENCES).assertExists()
        onNodeWithText("4 references").assertExists()
        keys { pressKey(Key.DirectionDown) }
        keys { pressKey(Key.Enter) }
        onNodeWithTag(EditorTestTags.REFERENCES).assertDoesNotExist()
        val second = state.document.text.indexOf("(a)") + 1
        assertEquals(TextRange(second, second + 1), state.selection)
    }

    @Test
    fun `escape closes the references list, and a click on a row opens it`() = runComposeUiTest {
        val state = CodeEditorState("val a = 1\nprint(a)")
        show(state)
        state.selection = TextRange(4)
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F12) } }
        keys { pressKey(Key.Escape) }
        onNodeWithTag(EditorTestTags.REFERENCES).assertDoesNotExist()
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.F12) } }
        onNodeWithText("print(a)").performClick()
        waitForIdle()
        assertEquals(TextRange(16, 17), state.selection)
    }

    @Test
    fun `shift alt F formats with the smallest edits, as one undo step`() = runComposeUiTest {
        val state = CodeEditorState("val x=1\nval keep = 2\nval y  =3")
        show(state)
        // A caret on the untouched middle line stays on its text.
        val keep = state.document.text.indexOf("keep")
        state.selection = TextRange(keep)
        keys { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.F) } } }
        assertEquals("val x = 1\nval keep = 2\nval y = 3", state.document.text)
        assertEquals(TextRange(state.document.text.indexOf("keep")), state.selection)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        assertEquals("val x=1\nval keep = 2\nval y  =3", state.document.text)
    }

    @Test
    fun `with a selection, shift alt F formats just the selection`() = runComposeUiTest {
        val state = CodeEditorState("abc def ghi")
        show(state)
        state.selection = TextRange(4, 7)
        keys { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.F) } } }
        assertEquals("abc DEF ghi", state.document.text)
    }

    @Test
    fun `the right-click menu goes to the definition`() = runComposeUiTest {
        val state = CodeEditorState("print(use)\nval def = 1")
        show(state)
        onNodeWithTag(EditorTestTags.EDITOR).performMouseInput { rightClick(Offset(8.dp.toPx() + 7.3f * 8.4f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        onNodeWithText("Find References").assertExists()
        onNodeWithText("Format Document").assertExists()
        onNodeWithText("Go to Definition").performClick()
        waitForIdle()
        val def = state.document.text.indexOf("def")
        assertEquals(TextRange(def, def + 3), state.selection)
    }
}
