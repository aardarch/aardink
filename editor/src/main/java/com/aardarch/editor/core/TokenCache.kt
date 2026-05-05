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
package com.aardarch.editor.core

/**
 * Stores per-line token lists and manages incremental invalidation + merge.
 *
 * The cache maps 0-based line numbers to the tokens whose [Token.start] falls on that line.
 * All offsets stored in tokens are document-absolute (not line-relative), so the rendering layer
 * can use them directly without translation.
 */
class TokenCache {

    // line index → tokens on that line, sorted by Token.start
    private val cache = HashMap<Int, List<Token>>()

    // Full sorted flat list — rebuilt lazily after a merge
    private var flatTokens: List<Token> = emptyList()
    private var flatDirty = true

    // ── Read ─────────────────────────────────────────────────────────────────

    /** All cached tokens in document order. Rebuilt lazily after each [merge] call. */
    val tokens: List<Token>
        get() {
            if (flatDirty) {
                flatTokens = cache.entries
                    .sortedBy { it.key }
                    .flatMap { it.value }
                flatDirty = false
            }
            return flatTokens
        }

    /** Tokens for a specific [line] (0-based), or an empty list if the line has no tokens. */
    fun tokensForLine(line: Int): List<Token> = cache[line] ?: emptyList()

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Replaces the entire cache with [allTokens] (result of [IncrementalTokenizer.tokenizeFull]).
     * [document] is used to bucket tokens by line.
     */
    fun reset(document: CodeDocument, allTokens: List<Token>) {
        cache.clear()
        bucketAndStore(document, allTokens)
        flatDirty = true
        document.dirtyLines = null
    }

    /**
     * Merges [updatedTokens] (result of [IncrementalTokenizer.tokenizeLines]) into the cache for
     * the affected [dirtyRange]. Lines outside [dirtyRange] retain their cached tokens.
     *
     * Lines that were deleted (when the document shrinks) are pruned automatically by [pruneLines].
     */
    fun merge(document: CodeDocument, dirtyRange: IntRange, updatedTokens: List<Token>) {
        // Remove stale entries for the dirty range
        for (line in dirtyRange) {
            cache.remove(line)
        }
        bucketAndStore(document, updatedTokens)
        flatDirty = true
        document.dirtyLines = null
    }

    /**
     * Removes cache entries for lines beyond [lastValidLine].
     * Called after lines are deleted from the document to keep the cache consistent.
     */
    fun pruneLines(lastValidLine: Int) {
        val toRemove = cache.keys.filter { it > lastValidLine }
        toRemove.forEach { cache.remove(it) }
        if (toRemove.isNotEmpty()) flatDirty = true
    }

    /** Clears all cached data. */
    fun clear() {
        cache.clear()
        flatTokens = emptyList()
        flatDirty = false
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun bucketAndStore(document: CodeDocument, tokens: List<Token>) {
        for (token in tokens) {
            val (line, _) = document.offsetToLineCol(token.start)
            cache.getOrPut(line) { mutableListOf() }.let { list ->
                (list as MutableList).add(token)
            }
        }
        // Sort each line's tokens by start offset
        for ((line, list) in cache) {
            if (list is MutableList && list.size > 1) {
                list.sortBy { it.start }
            }
            // Freeze to read-only list for thread safety
            cache[line] = (list as MutableList<Token>).toList()
        }
    }
}
