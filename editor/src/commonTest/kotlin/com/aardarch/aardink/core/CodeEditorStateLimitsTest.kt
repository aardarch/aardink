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

package com.aardarch.aardink.core

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How [CodeEditorState] routes tokenization through [EditorLimits]. The wasmJs paths are driven
 * on every target by flipping [CodeEditorState.computeOnMainThread].
 */
class CodeEditorStateLimitsTest {

    /** Records which entry point each tokenization pass used. */
    private class RecordingTokenizer : IncrementalTokenizer {
        val calls = mutableListOf<String>()

        // One token per line start, so the cache has something to hold.
        private fun tokens(text: String): List<Token> {
            val out = mutableListOf(Token(0, 1, TokenType.Keyword))
            text.forEachIndexed { i, c -> if (c == '\n' && i + 1 < text.length) out += Token(i + 1, i + 2, TokenType.Keyword) }
            return out
        }

        override fun tokenizeFull(text: String): List<Token> {
            calls += "full"
            return tokens(text)
        }

        override suspend fun tokenizeFullCooperative(text: String): List<Token> {
            calls += "cooperative"
            return tokens(text)
        }

        override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
            calls += "lines"
            return tokens(text).filter { it.start in lineStartOffsets(text, dirtyRange) }
        }

        override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

        private fun lineStartOffsets(text: String, lines: IntRange): Set<Int> {
            val starts = mutableListOf(0)
            text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
            return lines.mapNotNull { starts.getOrNull(it) }.toSet()
        }
    }

    private fun testState(text: String, tokenizer: IncrementalTokenizer, mainThread: Boolean): CodeEditorState = CodeEditorState(
        initialText = "",
        tokenizer = tokenizer,
        tokenizeDebounceMs = 0,
        scope = CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        computeDispatcher = Dispatchers.Unconfined
        computeOnMainThread = mainThread
        // Loaded after the flag is set: the constructor's first pass would otherwise run
        // with the platform default before a test could choose the path.
        loadText(text)
    }

    /** [EditorLimits] is process-wide; restore it so no other test sees these values. */
    private fun withLimits(cooperativeAt: Int, fallbackAt: Int, block: () -> Unit) {
        val savedCooperative = EditorLimits.cooperativeTokenizeThresholdChars
        val savedFallback = EditorLimits.plainTextFallbackChars
        EditorLimits.cooperativeTokenizeThresholdChars = cooperativeAt
        EditorLimits.plainTextFallbackChars = fallbackAt
        try {
            block()
        } finally {
            EditorLimits.cooperativeTokenizeThresholdChars = savedCooperative
            EditorLimits.plainTextFallbackChars = savedFallback
        }
    }

    private val smallText = "a\nb\nc"
    private val mediumText = "line one\nline two\nline three\nline four" // 38 chars
    private val largeText = mediumText.repeat(3)

    @Test
    fun `small document on a main-thread host keeps the incremental path`() = withLimits(cooperativeAt = 10, fallbackAt = 100) {
        val tokenizer = RecordingTokenizer()
        val state = testState(smallText, tokenizer, mainThread = true)
        tokenizer.calls.clear()

        state.applyEdit(0, 0, "x", TextRange(1))

        assertEquals(listOf("lines"), tokenizer.calls)
    }

    @Test
    fun `large document on a main-thread host always takes the cooperative pass`() = withLimits(cooperativeAt = 10, fallbackAt = 100) {
        val tokenizer = RecordingTokenizer()
        val state = testState(mediumText, tokenizer, mainThread = true)
        assertEquals(listOf("cooperative"), tokenizer.calls)

        // An edit that dirties one line still goes through the chunked full pass.
        tokenizer.calls.clear()
        state.applyEdit(0, 0, "x", TextRange(1))

        assertEquals(listOf("cooperative"), tokenizer.calls)
        assertEquals(4, state.tokenCache.tokens.size)
    }

    @Test
    fun `large document on a background-compute host is unaffected by the limits`() = withLimits(cooperativeAt = 10, fallbackAt = 100) {
        val tokenizer = RecordingTokenizer()
        val state = testState(largeText, tokenizer, mainThread = false)
        assertEquals(listOf("full"), tokenizer.calls)
        assertFalse(state.exceedsAnalysisLimit)

        tokenizer.calls.clear()
        state.applyEdit(0, 0, "x", TextRange(1))

        assertEquals(listOf("lines"), tokenizer.calls)
    }

    @Test
    fun `document over the fallback limit is left untokenized`() = withLimits(cooperativeAt = 10, fallbackAt = 100) {
        val tokenizer = RecordingTokenizer()
        val state = testState(largeText, tokenizer, mainThread = true)

        assertTrue(state.exceedsAnalysisLimit)
        assertEquals(emptyList(), tokenizer.calls)
        assertTrue(state.tokenCache.tokens.isEmpty())
    }

    @Test
    fun `highlighting returns once the document shrinks under the fallback limit`() = withLimits(cooperativeAt = 10, fallbackAt = 100) {
        val tokenizer = RecordingTokenizer()
        val state = testState(mediumText, tokenizer, mainThread = true)
        assertEquals(4, state.tokenCache.tokens.size)

        state.applyEdit(state.document.length, 0, largeText, TextRange(state.document.length + largeText.length))
        assertTrue(state.exceedsAnalysisLimit)
        assertTrue(state.tokenCache.tokens.isEmpty(), "stale tokens must not outlive the fallback")

        val versionBefore = state.tokenVersion
        tokenizer.calls.clear()
        state.loadText(smallText)

        assertFalse(state.exceedsAnalysisLimit)
        assertEquals(listOf("full"), tokenizer.calls)
        assertEquals(3, state.tokenCache.tokens.size)
        assertTrue(state.tokenVersion > versionBefore)
    }
}
