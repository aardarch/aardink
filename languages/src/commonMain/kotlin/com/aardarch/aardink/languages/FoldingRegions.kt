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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldKind
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider

/**
 * A language's folding region markers, as VS Code's `folding.markers` in a language
 * configuration: a line matching [start] opens a region and the next line matching [end] closes
 * it, nested. Each is matched against the whole line, so anchor it to the comment syntax.
 */
data class FoldingRegionMarkers(val start: Regex, val end: Regex) {
    companion object {
        /** `// #region` and `// #endregion`, VS Code's markers for the C-like languages. */
        val LineComment = FoldingRegionMarkers(Regex("""^\s*//\s*#?region\b"""), Regex("""^\s*//\s*#?endregion\b"""))

        /** `<!-- #region -->` and `<!-- #endregion -->`, for XML, HTML and Markdown. */
        val XmlComment = FoldingRegionMarkers(Regex("""^\s*<!--\s*#?region\b"""), Regex("""^\s*<!--\s*#?endregion\b"""))

        /** `/* #region */` and `/* #endregion */`, for CSS. */
        val BlockComment = FoldingRegionMarkers(Regex("""^\s*/\*\s*#?region\b"""), Regex("""^\s*/\*\s*#?endregion\b"""))

        /** `# region` and `# endregion`, for TOML and the other `#`-comment languages. */
        val HashComment = FoldingRegionMarkers(Regex("""^\s*#\s*region\b"""), Regex("""^\s*#\s*endregion\b"""))
    }
}

/**
 * This provider's ranges and the regions between [markers], each region a [FoldKind.Region] range
 * from its start marker's line to its end marker's.
 */
fun FoldingProvider.withRegionMarkers(markers: FoldingRegionMarkers): FoldingProvider {
    val base = this
    return FoldingProvider { document -> (base.foldableRanges(document) + regionRanges(document, markers)).sortedBy { it.startLine } }
}

/** The regions between [markers] in [document]. */
internal fun regionRanges(document: CodeDocument, markers: FoldingRegionMarkers): List<FoldRange> {
    val ranges = ArrayList<FoldRange>()
    val open = ArrayList<Int>()
    for (line in 0 until document.lineCount) {
        val text = document.lineText(line)
        when {
            markers.end.containsMatchIn(text) -> {
                val start = open.removeLastOrNull() ?: continue
                if (line > start) ranges += FoldRange(start, line, kind = FoldKind.Region)
            }

            markers.start.containsMatchIn(text) -> open += line
        }
    }
    return ranges
}

/**
 * Folds by indentation, as VS Code does for a language with no folding of its own: a line folds
 * the lines after it that are indented further, blank lines among them included, up to the last
 * of those. [tabSize] is how many columns a tab counts as.
 */
class IndentationFoldingProvider(private val tabSize: Int = 4) : FoldingProvider {

    override fun foldableRanges(document: CodeDocument): List<FoldRange> {
        val lineCount = document.lineCount
        val indents = IntArray(lineCount) { indentOf(document.lineText(it)) }
        val ranges = ArrayList<FoldRange>()
        // Lines still open, by indentation: each folds until a line at or below its indentation.
        val open = ArrayList<Int>()
        var lastNonBlank = -1
        for (line in 0 until lineCount) {
            val indent = indents[line]
            if (indent < 0) continue
            while (open.isNotEmpty() && indents[open.last()] >= indent) {
                val start = open.removeAt(open.size - 1)
                if (lastNonBlank > start) ranges += FoldRange(start, lastNonBlank)
            }
            open += line
            lastNonBlank = line
        }
        while (open.isNotEmpty()) {
            val start = open.removeAt(open.size - 1)
            if (lastNonBlank > start) ranges += FoldRange(start, lastNonBlank)
        }
        return ranges.sortedBy { it.startLine }
    }

    /** Columns of leading whitespace, or -1 for a blank line. */
    private fun indentOf(text: String): Int {
        var columns = 0
        for (c in text) {
            when (c) {
                ' ' -> columns++
                '\t' -> columns += tabSize - columns % tabSize
                else -> return columns
            }
        }
        return -1
    }
}
