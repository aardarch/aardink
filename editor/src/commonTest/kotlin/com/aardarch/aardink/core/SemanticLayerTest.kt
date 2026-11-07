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
package com.aardarch.aardink.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The semantic layer: a second [TokenStore], and how [CodeEditorState] keeps it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticLayerTest {

    private val named = NamedTokenType("function.method")

    private fun word(text: String, word: String, type: TokenType = named): Token {
        val start = text.indexOf(word)
        return Token(start, start + word.length, type)
    }

    private fun TokenStore.words(document: CodeDocument, line: Int): List<String> =
        tokensForLine(line).map { document.text.substring(it.start, it.end) }

    private suspend fun storeOf(document: CodeDocument, vararg words: String): TokenStore =
        TokenStore(document).also { store -> store.overlay(words.map { word(document.text, it) }, document.snapshot()) }

    // ── The store ────────────────────────────────────────────────────────────

    @Test
    fun `an answer lands on its lines, and one spanning lines is split`() = runTest {
        val document = CodeDocument("foo bar\nbaz\nqux")
        val store = TokenStore(document)

        val changed = store.overlay(listOf(Token(4, 7, named), Token(8, 13, named)), document.snapshot())

        assertTrue(changed)
        assertEquals(listOf("bar"), store.words(document, 0))
        assertEquals(listOf("baz"), store.words(document, 1))
        assertEquals(listOf("q"), store.words(document, 2))
    }

    @Test
    fun `tokens past the end of the document are dropped`() = runTest {
        val document = CodeDocument("foo")
        val store = TokenStore(document)

        store.overlay(listOf(Token(1, 2, named), Token(50, 60, named)), document.snapshot())

        assertEquals(listOf("o"), store.words(document, 0))
    }

    @Test
    fun `tokens out of order still land on their lines`() = runTest {
        val document = CodeDocument("one\ntwo\nthree")
        val store = TokenStore(document)
        val text = document.text

        store.overlay(listOf(word(text, "three"), word(text, "one"), word(text, "two")), document.snapshot())

        assertEquals(listOf("one"), store.words(document, 0))
        assertEquals(listOf("two"), store.words(document, 1))
        assertEquals(listOf("three"), store.words(document, 2))
    }

    @Test
    fun `an answer leaves the tokenizer's dirty lines alone`() = runTest {
        val document = CodeDocument("foo\nbar")
        val store = TokenStore(document)
        document.insert(0, "x")
        val dirty = assertNotNull(document.dirtyLines)

        store.overlay(listOf(Token(0, 3, named)), document.snapshot())

        assertEquals(dirty, document.dirtyLines)
    }

    @Test
    fun `its tokens stay on their text through edits, as the syntax store's do`() = runTest {
        val document = CodeDocument("foo bar\nbaz qux")
        val store = storeOf(document, "bar", "qux")

        document.insert(0, "12")
        assertEquals(listOf("bar"), store.words(document, 0))

        // A new line above: the line below moves down with its colours.
        document.insert(0, "top\n")
        assertEquals(emptyList(), store.words(document, 0))
        assertEquals(listOf("bar"), store.words(document, 1))
        assertEquals(listOf("qux"), store.words(document, 2))

        // The line break under the first token's line goes: both are on one line.
        document.delete(document.text.indexOf('\n', 4), 1)
        assertEquals(2, store.lineCount)
        assertEquals(listOf("bar", "qux"), store.words(document, 1))
    }

    @Test
    fun `new text in the whole document empties it`() = runTest {
        val document = CodeEditorState("foo", scope = CoroutineScope(Dispatchers.Unconfined)).apply {
            computeDispatcher = Dispatchers.Unconfined
        }
        document.applySemanticTokens(listOf(Token(0, 3, named)), document.document.snapshot())

        document.loadText("foo\nbar")

        assertEquals(2, document.semanticStore!!.lineCount)
        assertEquals(emptyList(), document.semanticStore!!.tokensForLine(0))
    }

    @Test
    fun `an answer that says the same about a line keeps that line's runs`() = runTest {
        val document = CodeDocument("foo\nbar\nbaz")
        val store = TokenStore(document)
        val text = document.text
        store.overlay(listOf(word(text, "foo"), word(text, "bar"), word(text, "baz")), document.snapshot())
        val first = store.lineTokens(0)
        val second = store.lineTokens(1)
        val third = store.lineTokens(2)

        val changed = store.overlay(
            listOf(word(text, "foo"), word(text, "bar", NamedTokenType("type.class")), word(text, "baz")),
            document.snapshot(),
        )

        assertTrue(changed)
        assertSame(first, store.lineTokens(0))
        assertNotSame(second, store.lineTokens(1))
        assertSame(third, store.lineTokens(2))
    }

    @Test
    fun `the same answer twice changes nothing`() = runTest {
        val document = CodeDocument("foo\nbar")
        val store = TokenStore(document)
        val answer = listOf(word(document.text, "foo"), word(document.text, "bar"))
        assertTrue(store.overlay(answer, document.snapshot()))

        assertFalse(store.overlay(answer, document.snapshot()))
    }

    @Test
    fun `an empty answer takes the colours away`() = runTest {
        val document = CodeDocument("foo")
        val store = storeOf(document, "foo")

        assertTrue(store.overlay(emptyList(), document.snapshot()))

        assertEquals(emptyList(), store.tokensForLine(0))
    }

    @Test
    fun `a large answer pauses, and shows nothing until it is all in`() = runTest {
        val lines = 2_000
        val document = CodeDocument(List(lines) { "word" }.joinToString("\n"))
        val store = storeOf(document, "word")
        val old = store.lineTokens(0)
        val answer = List(lines) { Token(it * 5 + 1, it * 5 + 4, named) }

        val pass = async { store.overlay(answer, document.snapshot(), CooperativePacer(Duration.ZERO)) }
        runCurrent()
        assertFalse(pass.isCompleted, "the first look at the clock pauses")
        assertSame(old, store.lineTokens(0), "lines are swapped whole, at the end")

        assertTrue(pass.await())
        assertEquals(listOf("ord"), store.words(document, lines - 1))
        // A check every 256 steps, over a step per token and a step per line.
        val checks = (answer.size + lines) / CooperativePacer.STEPS_PER_CHECK
        assertEquals(checks.toLong(), testScheduler.currentTime, "a millisecond's pause at each check")
    }

    @Test
    fun `an edit between slices ends the pass and keeps what is shown`() = runTest {
        val document = CodeDocument(List(2_000) { "word" }.joinToString("\n"))
        val store = storeOf(document, "word")
        val snapshot = document.snapshot()
        val answer = List(2_000) { Token(it * 5, it * 5 + 2, named) }

        val pass = async { store.overlay(answer, snapshot, CooperativePacer(Duration.ZERO)) }
        runCurrent()
        document.insert(0, "x")

        assertFalse(pass.await())
        assertEquals(listOf("word"), store.words(document, 0), "still the old answer, moved with the text")
        assertEquals(1L, testScheduler.currentTime, "it gives up at the first look after the edit")
    }

    @Test
    fun `an answer for text that has moved on changes nothing`() = runTest {
        val document = CodeDocument("foo")
        val store = TokenStore(document)
        val snapshot = document.snapshot()
        document.insert(0, "x")

        assertFalse(store.overlay(listOf(Token(0, 3, named)), snapshot))
        assertFalse(store.overlay(emptyList(), snapshot))

        assertEquals(emptyList(), store.tokensForLine(0))
    }

    // ── The state ────────────────────────────────────────────────────────────

    /** A token per word, and a record of each partial pass. */
    private class Tokenizer : IncrementalTokenizer {
        val partialPasses = mutableListOf<IntRange>()
        var fullPasses = 0

        private fun words(text: String) = Regex("[a-z]+").findAll(text).map {
            Token(it.range.first, it.range.last + 1, TokenType.Identifier)
        }

        override fun tokenizeFull(text: String): List<Token> {
            fullPasses++
            return words(text).toList()
        }

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
            partialPasses += dirtyRange
            return words(text).toList()
        }

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
    }

    @Test
    fun `an answer between an edit and the next pass leaves that pass its lines`() = runTest {
        val tokenizer = Tokenizer()
        val state = CodeEditorState("", tokenizer, tokenizeDebounceMs = 100, scope = backgroundScope)
        state.computeDispatcher = StandardTestDispatcher(testScheduler)
        state.loadText("one\ntwo\nthree")
        runCurrent()
        assertEquals(1, tokenizer.fullPasses)

        state.applyTextEdits(listOf(TextEdit(4..6, "xyz")))
        state.applySemanticTokens(listOf(Token(0, 3, named)), state.document.snapshot())
        assertEquals(1..1, state.document.dirtyLines)
        advanceTimeBy(150)
        runCurrent()

        assertEquals(listOf(1..1), tokenizer.partialPasses)
        assertEquals(1, tokenizer.fullPasses, "had the answer cleared the lines, this would be a full pass")
        assertNull(state.document.dirtyLines)
    }

    @Test
    fun `the layer is not the syntax tokens, and there is none until there is an answer`() = runTest {
        val state = CodeEditorState("", Tokenizer(), tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined))
        state.computeDispatcher = Dispatchers.Unconfined
        state.loadText("one two")
        assertNull(state.semanticStore)
        val syntax = state.tokensForLine(0)
        val tokenVersion = state.tokenVersion

        state.applySemanticTokens(listOf(Token(0, 3, named)), state.document.snapshot())

        assertEquals(listOf(Token(0, 3, named)), state.semanticStore!!.tokensForLine(0))
        assertEquals(syntax, state.tokensForLine(0), "a host reading tokens gets the syntax's")
        assertEquals(tokenVersion, state.tokenVersion)
    }

    @Test
    fun `the view is told only when an answer changes something`() = runTest {
        val state = CodeEditorState("one", scope = CoroutineScope(Dispatchers.Unconfined))
        state.computeDispatcher = Dispatchers.Unconfined
        val answer = listOf(Token(0, 3, named))

        state.applySemanticTokens(answer, state.document.snapshot())
        assertEquals(1, state.semanticVersion)
        state.applySemanticTokens(answer, state.document.snapshot())
        assertEquals(1, state.semanticVersion)
        state.applySemanticTokens(emptyList(), state.document.snapshot())
        assertEquals(2, state.semanticVersion)
    }

    @Test
    fun `clearing the layer stops it following the document`() = runTest {
        val state = CodeEditorState("one", scope = CoroutineScope(Dispatchers.Unconfined))
        state.computeDispatcher = Dispatchers.Unconfined
        state.applySemanticTokens(listOf(Token(0, 3, named)), state.document.snapshot())
        val store = state.semanticStore!!
        val version = state.semanticVersion

        state.clearSemanticTokens()
        state.document.insert(0, "x\n")

        assertNull(state.semanticStore)
        assertEquals(version + 1, state.semanticVersion)
        assertEquals(1, store.lineCount, "an edit no longer reaches it")

        state.clearSemanticTokens()
        assertEquals(version + 1, state.semanticVersion, "nothing was shown, so nothing changes")
    }
}
