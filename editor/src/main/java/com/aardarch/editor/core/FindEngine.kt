package com.aardarch.editor.core

/**
 * Stateless search engine producing match ranges for the find/replace UI.
 *
 * Pure-function so it can run on [kotlinx.coroutines.Dispatchers.Default]; the caller is
 * responsible for debouncing rapid query changes (200 ms is typical).
 */
object FindEngine {

    data class Options(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val useRegex: Boolean = false,
    )

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
