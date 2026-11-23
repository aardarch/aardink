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
 * The gutter's diff lane after [TextDiff]. The lines of a change that only inserts are
 * [LineDiffKind.Added]. Of one that replaces, as many as it replaced are [LineDiffKind.Modified] and
 * the rest [LineDiffKind.Added]. One that only deletes marks the line below it [LineDiffKind.Deleted].
 * Whitespace counts: the lane is about what saving would change.
 */
object SimpleDiffProvider : DiffProvider {

    private val options = TextDiffOptions(ignoreTrimWhitespace = false, computeCharChanges = false)

    override fun diff(baseLines: List<String>, currentLines: List<String>): List<LineDiff> {
        val result = TextDiff.compute(baseLines.joinToString("\n"), currentLines.joinToString("\n"), options)
        val marks = ArrayList<LineDiff>()
        for (change in result.changes) {
            when {
                change.modifiedLines.isEmpty() -> marks += LineDiff(change.modifiedLines.first, LineDiffKind.Deleted)

                change.originalLines.isEmpty() -> change.modifiedLines.mapTo(marks) { LineDiff(it, LineDiffKind.Added) }

                else -> change.modifiedLines.mapTo(marks) { line ->
                    val kind = if (line - change.modifiedLines.first <
                        change.originalLines.count()
                    ) {
                        LineDiffKind.Modified
                    } else {
                        LineDiffKind.Added
                    }
                    LineDiff(line, kind)
                }
            }
        }
        return marks
    }
}
