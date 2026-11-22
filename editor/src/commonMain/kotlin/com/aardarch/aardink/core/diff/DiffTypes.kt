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
package com.aardarch.aardink.core.diff

/**
 * Original lines [originalStart, originalEnd) became modified lines [modifiedStart, modifiedEnd),
 * all 0-based. An empty range on one side is a pure insertion or deletion, placed before that line
 * index. [charChanges] is null when it was not computed: the change was too large, or one side is empty.
 */
internal data class LineChange(
    val originalStart: Int,
    val originalEnd: Int,
    val modifiedStart: Int,
    val modifiedEnd: Int,
    val charChanges: List<CharChange>?,
)

/** Original characters [originalStart, originalEnd) became modified [modifiedStart, modifiedEnd), as offsets into the whole texts. */
internal data class CharChange(val originalStart: Int, val originalEnd: Int, val modifiedStart: Int, val modifiedEnd: Int)

/**
 * The [changes], sorted by position. [identical] is true when the two texts have the same lines
 * (ignoring a carriage return before a line break). [hitTimeout] is true when the time limit
 * stopped the search: the changes are then valid but may cover lines that are equal.
 */
internal data class DiffResult(val changes: List<LineChange>, val identical: Boolean, val hitTimeout: Boolean)

/**
 * What [DiffComputer] does. With [ignoreTrimWhitespace], lines that differ only in the whitespace
 * around them are equal. [maxComputationTimeMs] bounds the search (0 or less for no limit).
 * Character changes are computed when [computeCharChanges] is set and each side of a change has at
 * most [maxCharChangeChars] characters.
 */
internal data class DiffOptions(
    val ignoreTrimWhitespace: Boolean = true,
    val maxComputationTimeMs: Long = 5_000,
    val computeCharChanges: Boolean = true,
    val maxCharChangeChars: Int = 10_000,
)
