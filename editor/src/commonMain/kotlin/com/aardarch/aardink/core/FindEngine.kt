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

import kotlinx.coroutines.yield

/**
 * Stateless search engine producing match ranges for the find/replace UI.
 *
 * Pure-function so it can run on [com.aardarch.aardink.platform.EditorDispatchers.compute];
 * the caller is responsible for debouncing rapid query changes (200 ms is typical).
 *
 * Note that on wasmJs that dispatcher is the single event loop, so a search over a very
 * large document blocks painting; see [com.aardarch.aardink.platform.EditorDispatchers.computeIsMainThread].
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

    /** What [findAllCooperatively] found, and whether it stopped at its limit. */
    internal class Result(val matches: List<IntRange>, val capped: Boolean)

    /**
     * [findAll] for the editor's find panel: stops after [limit] matches, and yields every
     * [YIELD_EVERY_CHARS] characters of progress, which on the single-threaded web host keeps the
     * page responsive through a large document and lets a newer query cancel this one.
     */
    internal suspend fun findAllCooperatively(text: String, query: String, options: Options, limit: Int): Result {
        if (query.isEmpty() || text.isEmpty()) return Result(emptyList(), capped = false)
        val pattern = buildPattern(query, options) ?: return Result(emptyList(), capped = false)
        val matches = ArrayList<IntRange>()
        var nextYield = YIELD_EVERY_CHARS
        var match = pattern.find(text)
        while (match != null) {
            if (matches.size >= limit) return Result(matches, capped = true)
            matches.add(match.range)
            if (match.range.last >= nextYield) {
                yield()
                nextYield = match.range.last + YIELD_EVERY_CHARS
            }
            match = match.next()
        }
        return Result(matches, capped = false)
    }

    private const val YIELD_EVERY_CHARS = 64 * 1024

    /**
     * The edits that replace [matches] of [query] in [text] with [replacement].
     *
     * Without [Options.useRegex] every match becomes [replacement] as it is. With it, the
     * replacement is read as VS Code reads it: `$0` or `$&` is the whole match, `$1` to `$99` a
     * group (two digits when the pattern has that many groups, else one; a group that took no part
     * in the match is empty), `$$` a dollar sign, and `\n`, `\t` and `\\` a line break, a tab and
     * a backslash. Anything else is literal. The groups come from matching [query] again at each
     * match's start, so a match that no longer matches there (the text changed since the search)
     * is left out rather than replacing the wrong text.
     */
    internal fun replaceEdits(
        text: CharSequence,
        matches: List<IntRange>,
        query: String,
        replacement: String,
        options: Options,
    ): List<TextEdit> {
        if (!options.useRegex) return matches.map { TextEdit(it, replacement) }
        val pattern = buildPattern(query, options) ?: return emptyList()
        return matches.mapNotNull { range ->
            if (range.first !in 0..text.length) return@mapNotNull null
            val match = pattern.matchAt(text, range.first) ?: return@mapNotNull null
            if (match.range != range) return@mapNotNull null
            TextEdit(range, expandReplacement(replacement, match))
        }
    }

    private fun expandReplacement(replacement: String, match: MatchResult): String {
        val groupCount = match.groups.size - 1
        val out = StringBuilder(replacement.length)
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            val next = replacement.getOrNull(i + 1)
            when {
                c == '$' && next == '$' -> {
                    out.append('$')
                    i += 2
                }

                c == '$' && next == '&' -> {
                    out.append(match.value)
                    i += 2
                }

                c == '$' && next != null && next.isAsciiDigit() -> {
                    val one = next - '0'
                    val second = replacement.getOrNull(i + 2)
                    val two = if (second != null && second.isAsciiDigit()) one * 10 + (second - '0') else -1
                    val group = if (two in 1..groupCount) two else one
                    if (group > groupCount) {
                        out.append(c).append(next)
                    } else {
                        out.append(match.groups[group]?.value.orEmpty())
                    }
                    i += if (group == two) 3 else 2
                }

                c == '\\' && next == 'n' -> {
                    out.append('\n')
                    i += 2
                }

                c == '\\' && next == 't' -> {
                    out.append('\t')
                    i += 2
                }

                c == '\\' && next == '\\' -> {
                    out.append('\\')
                    i += 2
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun buildPattern(query: String, options: Options): Regex? {
        val raw = if (options.useRegex) query else Regex.escape(query)
        val withWordBoundary = if (options.wholeWord) """\b$raw\b""" else raw
        // IGNORE_CASE is not identical across targets: on JVM it is Unicode-aware, while on
        // wasmJs it becomes the JS `i` flag, whose case folding differs for some non-ASCII
        // characters. ASCII searches -- the overwhelming majority in code -- behave the same
        // everywhere.
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return try {
            Regex(withWordBoundary, flags)
        } catch (_: Exception) {
            null
        }
    }
}
