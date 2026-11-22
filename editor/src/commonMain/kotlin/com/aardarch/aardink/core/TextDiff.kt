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

import com.aardarch.aardink.core.diff.DiffComputer
import com.aardarch.aardink.core.diff.DiffOptions

/**
 * The lines and characters that differ between two texts, as a diff editor shows them.
 *
 * Lines are compared by Myers's algorithm, which finds the fewest lines to delete and insert, and
 * then the characters inside each change. It runs in time proportional to the texts and the number
 * of changes, and in memory proportional to the texts. Call it off the main thread for large texts:
 * it does not switch threads itself.
 */
object TextDiff {

    /**
     * The changes that turn [original] into [modified]. A line break is `\n`, and a carriage
     * return before one is not part of the line.
     */
    fun compute(original: CharSequence, modified: CharSequence, options: TextDiffOptions = TextDiffOptions()): TextDiffResult {
        val result = DiffComputer.compute(
            original,
            modified,
            DiffOptions(
                ignoreTrimWhitespace = options.ignoreTrimWhitespace,
                maxComputationTimeMs = options.maxComputationTimeMs,
                computeCharChanges = options.computeCharChanges,
            ),
        )
        return TextDiffResult(
            changes = result.changes.map { change ->
                LineChange(
                    originalLines = change.originalStart until change.originalEnd,
                    modifiedLines = change.modifiedStart until change.modifiedEnd,
                    charChanges = change.charChanges?.map {
                        CharChange(it.originalStart until it.originalEnd, it.modifiedStart until it.modifiedEnd)
                    },
                )
            },
            identical = result.identical,
            hitTimeout = result.hitTimeout,
        )
    }
}

/**
 * How [TextDiff] compares.
 *
 * @param ignoreTrimWhitespace Whether whitespace at the start and end of a line is left out of the
 *   comparison, as Monaco's diff editor does by default.
 * @param maxComputationTimeMs How long the search may take, in milliseconds; past it, what is not yet
 *   resolved is one change per region and [TextDiffResult.hitTimeout] is true. 0 or less is no limit.
 * @param computeCharChanges Whether to find the characters that differ inside each change.
 */
data class TextDiffOptions(
    val ignoreTrimWhitespace: Boolean = true,
    val maxComputationTimeMs: Long = 5_000,
    val computeCharChanges: Boolean = true,
)

/**
 * What [TextDiff.compute] found.
 *
 * @property changes In order, none touching another.
 * @property identical Whether the texts have the same lines exactly. With
 *   [TextDiffOptions.ignoreTrimWhitespace], texts that differ only in such whitespace have no
 *   [changes] and are not identical.
 * @property hitTimeout Whether the time limit stopped the search: [changes] are still right, but may
 *   hold lines that are equal.
 */
data class TextDiffResult(val changes: List<LineChange>, val identical: Boolean, val hitTimeout: Boolean)

/**
 * The original's lines [originalLines] became the modified text's lines [modifiedLines]: 0-based
 * line indices, each range `until` its end. An empty range on one side is an insertion or a deletion
 * there, before that line index.
 *
 * @property charChanges The characters that differ inside the change, or null when they were not
 *   worked out: one side is empty, a side is longer than 10,000 characters, or they were not asked for.
 */
data class LineChange(val originalLines: IntRange, val modifiedLines: IntRange, val charChanges: List<CharChange>?)

/** The original's characters [original] became the modified text's [modified]: offsets into each whole text. */
data class CharChange(val original: IntRange, val modified: IntRange)
