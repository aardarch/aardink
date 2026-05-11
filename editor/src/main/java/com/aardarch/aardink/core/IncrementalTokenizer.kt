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

/**
 * Language-specific tokenizer that produces [Token] lists for a [CodeDocument].
 *
 * Implementations must be thread-safe: [tokenizeFull] and [tokenizeLines] may be called from a
 * background coroutine while the document is read (but not mutated) on the main thread.
 */
interface IncrementalTokenizer {

    /**
     * Tokenizes the entire [text] from scratch.
     * Called once when a document is first loaded or when [tokenizeLines] cannot produce a valid
     * incremental result (e.g. after a large paste that invalidates multi-line state).
     *
     * @return All tokens for the document, sorted by [Token.start].
     */
    fun tokenizeFull(text: String): List<Token>

    /**
     * Incrementally re-tokenizes only the lines in [dirtyRange] (0-based, inclusive).
     * The implementation may expand [dirtyRange] if multi-line constructs (e.g. block comments)
     * require it — use [canSpanLines] to decide.
     *
     * @param text The full updated document text.
     * @param dirtyRange Lines that have changed since the last tokenization pass.
     * @param previousTokens The token list from the previous pass (may be empty).
     * @return Updated tokens for the affected lines, sorted by [Token.start].
     *         The caller ([TokenCache]) merges these into the full token set.
     */
    fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token>

    /**
     * Returns true if a change on [lineIndex] can affect tokenization of subsequent lines.
     * Used by [TokenCache] to expand the dirty range before calling [tokenizeLines].
     *
     * Typical triggers: an unclosed block comment or multi-line string literal that starts on
     * [lineIndex].
     */
    fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean

    /**
     * Optional quick-input characters that this language/tokenizer wants exposed in the keyboard
     * toolbar. Returns an empty list if the language has no preference.
     *
     * Characters are shown in the order returned. Duplicates are deduplicated by the toolbar.
     */
    fun keyboardToolbarChars(): List<Char> = emptyList()
}

/** A no-op tokenizer that treats all text as [TokenType.Default]. Useful for plain-text editing. */
object PlainTextTokenizer : IncrementalTokenizer {
    override fun tokenizeFull(text: String): List<Token> = emptyList()
    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> = emptyList()
    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false
}
