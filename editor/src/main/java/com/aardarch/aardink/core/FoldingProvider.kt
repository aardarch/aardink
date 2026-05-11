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
 * Produces a list of foldable line ranges for a [CodeDocument].
 *
 * Implementations are language-specific: an XML provider stacks open/close tags, a brace-language
 * provider stacks `{`/`}`, etc. Implementations should be pure-function and run on
 * [kotlinx.coroutines.Dispatchers.Default].
 */
fun interface FoldingProvider {
    fun foldableRanges(document: CodeDocument): List<FoldRange>
}

/**
 * A single foldable region.
 *
 * @param startLine 0-based line index where the fold starts (the line shown when collapsed).
 * @param endLine 0-based line index of the last line included in the fold.
 * @param placeholder Text shown in place of the collapsed lines.
 */
data class FoldRange(val startLine: Int, val endLine: Int, val placeholder: String = "…") {
    val lineCount: Int get() = endLine - startLine + 1
}

/** Fallback used when no language-specific provider is configured. */
val NoOpFoldingProvider = FoldingProvider { emptyList() }
