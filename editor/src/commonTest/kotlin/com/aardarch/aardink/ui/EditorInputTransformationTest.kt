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
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aardarch.aardink.ui

import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the user-edit path PR 1 introduced and left untested.
 *
 * `InputTransformation` only ever runs for user-originated edits, and its job is to mirror
 * whatever the field did into [CodeDocument] and [com.aardarch.aardink.core.EditorUndoManager]
 * so the two never drift. These tests drive `transformInput` through
 * [androidx.compose.foundation.text.input.TextFieldState.edit], which produces a buffer whose
 * `changes` describe the edit exactly as a real keystroke would.
 *
 * The distinction the class turns on — a *single* one-character insert triggers the language
 * hooks, anything else does not — is the behaviour PR 1's commit message admits to changing, so
 * it is pinned from both sides here.
 */
class EditorInputTransformationTest {

    private class Recorded(var singleCharInserts: MutableList<Triple<Int, Char, Int>> = mutableListOf(), var otherChanges: Int = 0)

    /** Auto-closes `(` and indents two spaces after a newline, so both hooks are observable. */
    private class TestLanguageService(private val autoCloseFor: Map<Char, String> = mapOf('(' to ")"), private val indentSpaces: Int = 0) :
        LanguageService {
        override val supportsRename: Boolean = false
        override val triggerCharacters: Set<Char> = emptySet()

        override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

        override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

        override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

        override suspend fun format(document: CodeDocument): String = document.text

        override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = autoCloseFor[charTyped]

        override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = indentSpaces
    }

    private fun stateFor(text: String): CodeEditorState = CodeEditorState(
        initialText = text,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
    }

    private fun runInput(
        state: CodeEditorState,
        languageService: LanguageService? = null,
        edit: androidx.compose.foundation.text.input.TextFieldBuffer.() -> Unit,
    ): Recorded {
        val recorded = Recorded()
        val transformation = EditorInputTransformation(
            state = state,
            languageService = { languageService },
            onSingleCharacterInsert = { at, ch, autoClose ->
                recorded.singleCharInserts += Triple(at, ch, autoClose)
            },
            onOtherChange = { recorded.otherChanges++ },
        )
        state.textFieldState.edit {
            edit()
            with(transformation) { transformInput() }
        }
        return recorded
    }

    @Test
    fun `a typed character reaches the document`() {
        val state = stateFor("ab")
        runInput(state) { insert(2, "c") }
        assertEquals("abc", state.document.text)
    }

    @Test
    fun `a typed character is recorded as undoable`() {
        val state = stateFor("ab")
        runInput(state) { insert(2, "c") }

        assertEquals("abc", state.document.text)
        assertTrue(state.undoManager.canUndo, "a keystroke must be undoable")
    }

    @Test
    fun `a deletion reaches the document and the undo history`() {
        val state = stateFor("abc")
        runInput(state) { delete(1, 3) }

        assertEquals("a", state.document.text)
        assertTrue(state.undoManager.canUndo)
    }

    @Test
    fun `a single character insert reports the typed character`() {
        val state = stateFor("ab")
        val recorded = runInput(state) { insert(2, "c") }

        assertEquals(1, recorded.singleCharInserts.size)
        val (at, ch, autoClose) = recorded.singleCharInserts.single()
        assertEquals(2, at)
        assertEquals('c', ch)
        assertEquals(0, autoClose)
        assertEquals(0, recorded.otherChanges, "a keystroke is not an 'other' change")
    }

    @Test
    fun `a multi character paste is not treated as a keystroke`() {
        // This is the coalescing distinction: only a single one-character insert runs the
        // language hooks. A paste must go down the other branch, or pasting "(" would
        // auto-close and pasting text ending in a newline would smart-indent.
        val state = stateFor("ab")
        val recorded = runInput(state) { insert(2, "cdef") }

        assertEquals("abcdef", state.document.text)
        assertTrue(recorded.singleCharInserts.isEmpty(), "a paste is not a keystroke")
        assertEquals(1, recorded.otherChanges)
    }

    @Test
    fun `a replacement is not treated as a keystroke even when one character long`() {
        // Selecting a character and typing over it deletes and inserts in one change. It
        // inserts one character, but it is not a bare keystroke, so the hooks must stay off.
        val state = stateFor("abc")
        val recorded = runInput(state) { replace(1, 2, "X") }

        assertEquals("aXc", state.document.text)
        assertTrue(recorded.singleCharInserts.isEmpty())
        assertEquals(1, recorded.otherChanges)
    }

    @Test
    fun `a pure deletion is not treated as a keystroke`() {
        val state = stateFor("abc")
        val recorded = runInput(state) { delete(2, 3) }

        assertTrue(recorded.singleCharInserts.isEmpty())
        assertEquals(1, recorded.otherChanges)
    }

    @Test
    fun `auto close inserts the closing character into the document`() {
        val state = stateFor("f")
        val recorded = runInput(state, TestLanguageService()) { insert(1, "(") }

        assertEquals("f()", state.document.text)
        assertEquals(1, recorded.singleCharInserts.single().third, "autoCloseLength is reported")
    }

    @Test
    fun `auto close does not fire without a language service`() {
        val state = stateFor("f")
        runInput(state, languageService = null) { insert(1, "(") }

        assertEquals("f(", state.document.text)
    }

    @Test
    fun `smart indent inserts indentation after a newline`() {
        val state = stateFor("{")
        runInput(state, TestLanguageService(autoCloseFor = emptyMap(), indentSpaces = 2)) {
            insert(1, "\n")
        }

        assertEquals("{\n  ", state.document.text)
    }

    @Test
    fun `the document stays in step with the field`() {
        // The whole point of the class: whatever the buffer ends up holding, the document
        // must hold the same thing.
        val state = stateFor("hello")
        runInput(state) { insert(5, " there") }

        assertEquals(state.textFieldState.text.toString(), state.document.text)
    }

    @Test
    fun `each edit bumps the text version`() {
        val state = stateFor("a")
        val before = state.textVersion
        runInput(state) { insert(1, "b") }

        assertTrue(state.textVersion > before, "tokenization is scheduled off textVersion")
    }
}
