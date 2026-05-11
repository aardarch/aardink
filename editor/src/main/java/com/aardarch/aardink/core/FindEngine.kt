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
 * Stateless search engine producing match ranges for the find/replace UI.
 *
 * Pure-function so it can run on [kotlinx.coroutines.Dispatchers.Default]; the caller is
 * responsible for debouncing rapid query changes (200 ms is typical).
 */
object FindEngine {

    data class Options(val caseSensitive: Boolean = false, val wholeWord: Boolean = false, val useRegex: Boolean = false)

    /**
     * Returns all match ranges (inclusive-end-exclusive — Kotlin [IntRange] uses inclusive ends, so
     * `range.last` is the index of the last matched character). Empty list when [query] is empty
     * or the regex fails to compile.
     */
    fun findAll(text: String, query: String, options: Options = Options()): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val pattern = buildPattern(query, options) ?: return emptyList()
        return pattern.findAll(text).map { it.range }.toList()
    }

    private fun buildPattern(query: String, options: Options): Regex? {
        val raw = if (options.useRegex) query else Regex.escape(query)
        val withWordBoundary = if (options.wholeWord) """\b$raw\b""" else raw
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return try {
            Regex(withWordBoundary, flags)
        } catch (_: Exception) {
            null
        }
    }
}
