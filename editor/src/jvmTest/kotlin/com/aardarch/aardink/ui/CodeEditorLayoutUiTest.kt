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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renders the real [CodeEditorLayout] and drives it through the test input APIs: typing, the
 * key bindings (movement, selection, deletion, the line commands, the shortcuts), menus and touch.
 */
class CodeEditorLayoutUiTest {

    private fun ComposeUiTest.show(
        state: CodeEditorState,
        findReplaceState: FindReplaceState? = null,
        onRequestGoToLine: () -> Unit = {},
        readOnly: Boolean = false,
        languageService: LanguageService? = null,
    ) {
        setContent {
            CodeEditorLayout(
                state = state,
                findReplaceState = findReplaceState,
                onRequestGoToLine = onRequestGoToLine,
                options = EditorOptions(readOnly = readOnly),
                languageService = languageService,
                modifier = Modifier.size(600.dp, 400.dp),
            )
        }
        waitForIdle()
    }

    private fun ComposeUiTest.editor(): SemanticsNodeInteraction = onNodeWithTag(EditorTestTags.EDITOR)

    private fun ComposeUiTest.keys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        editor().requestFocus()
        editor().performKeyInput(block)
        waitForIdle()
    }

    @Test
    fun `typing updates the document`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state)
        editor().performTextInput("abc")
        waitForIdle()
        assertEquals("abc", state.document.text)
        assertEquals(TextRange(3), state.selection)
    }

    @Test
    fun `a click focuses the editor`() = runComposeUiTest {
        val state = CodeEditorState("text")
        show(state)
        editor().performMouseInput { click() }
        waitForIdle()
        editor().assertIsFocused()
    }

    @Test
    fun `ctrl Z undoes and ctrl Y redoes`() = runComposeUiTest {
        val state = CodeEditorState("start")
        show(state)
        state.selection = TextRange(5)
        editor().performTextInput("X")
        waitForIdle()
        assertEquals("startX", state.document.text)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        assertEquals("start", state.document.text)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
        assertEquals("startX", state.document.text)
    }

    @Test
    fun `ctrl F opens the find panel and escape closes it`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        val find = FindReplaceState()
        show(state, findReplaceState = find)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        assertTrue(find.visible)
        keys { pressKey(Key.Escape) }
        assertFalse(find.visible)
    }

    @Test
    fun `ctrl F puts the keyboard focus in the find field`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        val find = FindReplaceState()
        show(state, findReplaceState = find)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }

        onNodeWithTag(EditorTestTags.FIND_FIELD).assertIsFocused()
        onNodeWithTag(EditorTestTags.FIND_FIELD).performTextInput("world")
        waitForIdle()

        assertEquals("world", find.query)
        assertEquals("hello world", state.document.text, "the typing went to the find field, not the text")
    }

    @Test
    fun `the host's show takes the focus without the editor having had it`() = runComposeUiTest {
        val find = FindReplaceState()
        show(CodeEditorState("text"), findReplaceState = find)

        find.show()
        waitForIdle()

        onNodeWithTag(EditorTestTags.FIND_FIELD).assertIsFocused()
    }

    @Test
    fun `show again while open takes the focus back from the text`() = runComposeUiTest {
        val find = FindReplaceState()
        show(CodeEditorState("text"), findReplaceState = find)
        find.show()
        waitForIdle()
        editor().requestFocus()
        waitForIdle()

        find.show()
        waitForIdle()

        onNodeWithTag(EditorTestTags.FIND_FIELD).assertIsFocused()
    }

    @Test
    fun `ctrl H focuses the replace field when there is something to find`() = runComposeUiTest {
        val find = FindReplaceState().apply { query = "o" }
        show(CodeEditorState("foo"), findReplaceState = find)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.H) } }

        onNodeWithTag(EditorTestTags.REPLACE_FIELD).assertIsFocused()
    }

    @Test
    fun `escape in the find field closes the panel and gives the focus back to the text`() = runComposeUiTest {
        val find = FindReplaceState()
        show(CodeEditorState("text"), findReplaceState = find)
        find.show()
        waitForIdle()

        onNodeWithTag(EditorTestTags.FIND_FIELD).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertFalse(find.visible)
        editor().assertIsFocused()
    }

    @Test
    fun `enter in the find field selects the next match and shift enter the previous one`() = runComposeUiTest {
        val state = CodeEditorState("ab ab ab")
        val find = FindReplaceState()
        show(state, findReplaceState = find)
        find.show()
        waitForIdle()
        onNodeWithTag(EditorTestTags.FIND_FIELD).performTextInput("ab")
        waitUntil { find.matches.size == 3 }

        onNodeWithTag(EditorTestTags.FIND_FIELD).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, find.currentMatchIndex)

        onNodeWithTag(EditorTestTags.FIND_FIELD).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals(0, find.currentMatchIndex)
    }

    @Test
    fun `ctrl G asks the host to go to a line`() = runComposeUiTest {
        var asked = false
        show(CodeEditorState("a\nb"), onRequestGoToLine = { asked = true })
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.G) } }
        assertTrue(asked)
    }

    @Test
    fun `tab indents the selected lines and shift tab outdents them`() = runComposeUiTest {
        val state = CodeEditorState("one\ntwo")
        show(state)
        state.selection = TextRange(0, 7)
        keys { pressKey(Key.Tab) }
        assertEquals("    one\n    two", state.document.text)
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        assertEquals("one\ntwo", state.document.text)
    }

    @Test
    fun `outdent leaves a line with no indentation alone`() = runComposeUiTest {
        val state = CodeEditorState("one")
        show(state)
        state.selection = TextRange(0, 3)
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        assertEquals("one", state.document.text)
    }

    @Test
    fun `ctrl slash toggles a line comment`() = runComposeUiTest {
        val state = CodeEditorState("val a = 1").apply { tokenizer = SlashComments }
        show(state)
        state.selection = TextRange(0)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Slash) } }
        assertEquals("// val a = 1", state.document.text)
    }

    @Test
    fun `alt down moves the line and shift alt up copies it`() = runComposeUiTest {
        val state = CodeEditorState("one\ntwo")
        show(state)
        state.selection = TextRange(1)
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
        assertEquals("two\none", state.document.text)
        keys { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } } }
        assertEquals("two\none\none", state.document.text)
    }

    @Test
    fun `arrows move the caret and shift selects`() = runComposeUiTest {
        val state = CodeEditorState("abc\ndef")
        show(state)
        state.selection = TextRange(1)
        keys { pressKey(Key.DirectionRight) }
        assertEquals(TextRange(2), state.selection)
        keys { pressKey(Key.DirectionDown) }
        assertEquals(TextRange(6), state.selection)
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.MoveHome) } }
        assertEquals(TextRange(6, 4), state.selection)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        assertEquals(TextRange(7), state.selection)
    }

    @Test
    fun `backspace, delete and enter edit`() = runComposeUiTest {
        val state = CodeEditorState("abcd")
        show(state)
        state.selection = TextRange(2)
        keys { pressKey(Key.Backspace) }
        assertEquals("acd", state.document.text)
        keys { pressKey(Key.Delete) }
        assertEquals("ad", state.document.text)
        keys { pressKey(Key.Enter) }
        assertEquals("a\nd", state.document.text)
        assertEquals(TextRange(2), state.selection)
    }

    @Test
    fun `ctrl D selects the word, then its next occurrence`() = runComposeUiTest {
        val state = CodeEditorState("val x = x")
        show(state)
        state.selection = TextRange(4)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.D) } }
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.D) } }
        assertEquals(listOf(TextRange(8, 9), TextRange(4, 5)), state.selections)
        keys { pressKey(Key.Escape) }
        assertEquals(1, state.selections.size)
    }

    @Test
    fun `ctrl shift alt arrows make a column selection that typing replaces on every row`() = runComposeUiTest {
        val state = CodeEditorState("abcd\nabcd\nabcd")
        show(state)
        state.selection = TextRange(1)
        keys {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) {
                    withKeyDown(Key.AltLeft) {
                        pressKey(Key.DirectionDown)
                        pressKey(Key.DirectionDown)
                        pressKey(Key.DirectionRight)
                        pressKey(Key.DirectionRight)
                    }
                }
            }
        }
        assertEquals(3, state.selections.size)
        editor().performTextInput("X")
        waitForIdle()
        assertEquals("aXd\naXd\naXd", state.document.text)
    }

    @Test
    fun `carets added with ctrl alt down type together and undo together`() = runComposeUiTest {
        val state = CodeEditorState("a\nb\nc")
        show(state)
        state.selection = TextRange(1)
        keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } } }
        keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } } }
        editor().performTextInput("!")
        waitForIdle()
        assertEquals("a!\nb!\nc!", state.document.text)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        assertEquals("a\nb\nc", state.document.text)
        assertEquals(3, state.selections.size, "undo brings every caret back")
        keys { pressKey(Key.Escape) }
        assertEquals(listOf(TextRange(1)), state.selections)
    }

    @Test
    fun `read-only keeps the text but still moves the caret`() = runComposeUiTest {
        val state = CodeEditorState("abc")
        show(state, readOnly = true)
        state.selection = TextRange(1)
        keys { pressKey(Key.Backspace) }
        keys { pressKey(Key.DirectionRight) }
        assertEquals("abc", state.document.text)
        assertEquals(TextRange(2), state.selection)
    }

    @Test
    fun `a right click opens the context menu`() = runComposeUiTest {
        val state = CodeEditorState("one two")
        show(state)
        editor().performMouseInput { rightClick(Offset(40f, 20f)) }
        waitForIdle()
        onNodeWithText("Select All").performClick()
        waitForIdle()
        assertEquals(TextRange(0, 7), state.selection)
    }

    @Test
    fun `a long press selects the word and shows both handles`() = runComposeUiTest {
        val state = CodeEditorState("alpha beta")
        show(state)
        editor().performTouchInput { longClick(Offset(8.dp.toPx() + 4f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        assertEquals(TextRange(0, 5), state.selection)
        onNodeWithTag(EditorTestTags.HANDLE + "Start").assertExists()
        onNodeWithTag(EditorTestTags.HANDLE + "End").assertExists()
        // Dragging the end handle to the right extends the selection.
        onNodeWithTag(EditorTestTags.HANDLE + "End").performTouchInput { swipeRight(startX = centerX, endX = centerX + 200f) }
        waitForIdle()
        assertEquals(0, state.selection.min)
        assertTrue(state.selection.max > 5, "extended to ${state.selection}")
    }

    @Test
    fun `a tap places the caret and shows its handle`() = runComposeUiTest {
        val state = CodeEditorState("alpha beta")
        show(state)
        editor().performTouchInput { click(Offset(8.dp.toPx() + 2f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        assertEquals(TextRange(0), state.selection)
        onNodeWithTag(EditorTestTags.HANDLE + "Insertion").assertExists()
    }

    /** Offers three completions anywhere, and documentation for any word. */
    private object Words : LanguageService {
        override val supportsRename: Boolean = false
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> =
            listOf("alpha", "beta", "gamma").map { CompletionItem(label = it, kind = CompletionKind.Property, insertText = it) }

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc =
            HoverDoc(title = "Docs for a word", content = "What it does.")

        override suspend fun format(document: CodeDocument): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

        override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = 0
    }

    @Test
    fun `ctrl space opens completions at the caret, the arrows pick one and enter takes it`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state, languageService = Words)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
        keys { pressKey(Key.DirectionDown) }
        keys { pressKey(Key.Enter) }
        assertEquals("beta", state.document.text)
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
    }

    /** Offers one snippet at the start of the text: a call with two arguments, the second a choice. */
    private object Snippets : LanguageService by Words {
        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = if (cursorOffset >
            0
        ) {
            emptyList()
        } else {
            listOf(
                CompletionItem(
                    label = "call",
                    kind = CompletionKind.Snippet,
                    insertText = "call(\${1:first}, \${2|yes,no|})\$0",
                    isSnippet = true,
                ),
            )
        }
    }

    @Test
    fun `a snippet completion is filled in with Tab, its choices offered in the list`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state, languageService = Snippets)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        keys { pressKey(Key.Enter) }
        assertEquals("call(first, yes)", state.document.text)
        assertEquals(TextRange(5, 10), state.selection)
        editor().performTextInput("x")
        keys { pressKey(Key.Tab) }
        assertEquals(TextRange(8, 11), state.selection)
        // The choice stop shows its options: the second one replaces the first.
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertExists()
        keys { pressKey(Key.DirectionDown) }
        keys { pressKey(Key.Enter) }
        assertEquals("call(x, no)", state.document.text)
        keys { pressKey(Key.Tab) }
        assertEquals(TextRange(11), state.selection)
        assertEquals(null, state.snippet)
    }

    @Test
    fun `escape leaves a snippet, and Tab indents again`() = runComposeUiTest {
        val state = CodeEditorState("")
        show(state, languageService = Snippets)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        keys { pressKey(Key.Enter) }
        keys { pressKey(Key.Escape) }
        assertEquals(null, state.snippet)
        // "first" is still selected: Tab indents its line, as it does anywhere else.
        keys { pressKey(Key.Tab) }
        assertEquals("    call(first, yes)", state.document.text)
    }

    @Test
    fun `escape closes the completion list without typing`() = runComposeUiTest {
        val state = CodeEditorState("x")
        show(state, languageService = Words)
        keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Spacebar) } }
        keys { pressKey(Key.Escape) }
        onNodeWithTag(EditorTestTags.COMPLETION_LIST).assertDoesNotExist()
        assertEquals("x", state.document.text)
    }

    @Test
    fun `a resting mouse shows the symbol's documentation`() = runComposeUiTest {
        val state = CodeEditorState("hello world")
        show(state, languageService = Words)
        mainClock.autoAdvance = false
        editor().performMouseInput { moveTo(Offset(8.dp.toPx() + 10f, 8.dp.toPx() + 10.dp.toPx())) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
        mainClock.autoAdvance = true
    }

    @Test
    fun `the touch menu offers info on the selected symbol`() = runComposeUiTest {
        val state = CodeEditorState("alpha beta")
        show(state, languageService = Words)
        editor().performTouchInput { longClick(Offset(8.dp.toPx() + 4f, 8.dp.toPx() + 10.dp.toPx())) }
        waitForIdle()
        onNodeWithTag(EditorTestTags.TOUCH_MENU).assertExists()
        onNodeWithText("Info").performClick()
        waitForIdle()
        onNodeWithText("Docs for a word").assertExists()
    }

    private object SlashComments : IncrementalTokenizer {
        override fun tokenizeFull(text: String): List<Token> = emptyList()

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

        override val commentSyntax: CommentSyntax = CommentSyntax(line = "//")
    }
}
