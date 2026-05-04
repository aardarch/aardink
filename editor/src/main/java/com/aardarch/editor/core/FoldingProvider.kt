package com.aardarch.editor.core

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
