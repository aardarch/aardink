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
package com.aardarch.aardink.languages.internal.folding

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider

/**
 * Folds Markdown heading sections. A heading folds everything until the next heading at the same
 * or higher level (or end-of-document).
 *
 * Also folds fenced code blocks (`` ``` … ``` ``).
 */
object MarkdownFoldingProvider : FoldingProvider {

    private val headingRegex = Regex("^(#{1,6})\\s")

    override fun foldableRanges(document: CodeDocument): List<FoldRange> {
        val text = document.text
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        val ranges = mutableListOf<FoldRange>()

        // Headings
        data class Heading(val line: Int, val level: Int)
        val headings = mutableListOf<Heading>()
        var insideFence = false
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) {
                insideFence = !insideFence
                continue
            }
            if (insideFence) continue
            val m = headingRegex.find(line) ?: continue
            headings.add(Heading(i, m.groupValues[1].length))
        }
        for ((idx, heading) in headings.withIndex()) {
            val nextSameOrHigher = headings.subList(idx + 1, headings.size)
                .firstOrNull { it.level <= heading.level }
            val end = (nextSameOrHigher?.line ?: lines.size) - 1
            if (end > heading.line) {
                ranges.add(FoldRange(heading.line, end))
            }
        }

        // Fenced code blocks
        var fenceStart = -1
        for ((i, line) in lines.withIndex()) {
            if (!line.trimStart().startsWith("```")) continue
            if (fenceStart < 0) {
                fenceStart = i
            } else {
                if (i - fenceStart >= 1) ranges.add(FoldRange(fenceStart, i))
                fenceStart = -1
            }
        }

        return ranges.sortedBy { it.startLine }
    }
}
