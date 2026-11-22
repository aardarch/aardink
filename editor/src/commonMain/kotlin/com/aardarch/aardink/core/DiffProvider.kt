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
 * What the gutter's diff lane marks: the lines of [currentLines] that differ from [baseLines]. The
 * editor calls it on its compute dispatcher, 300 ms after typing stops.
 */
fun interface DiffProvider {
    fun diff(baseLines: List<String>, currentLines: List<String>): List<LineDiff>
}

/**
 * Line [lineIndex] of the current text is [kind]. For [LineDiffKind.Deleted], lines were deleted
 * just above it, and a [lineIndex] equal to the current line count means after the last line.
 */
data class LineDiff(val lineIndex: Int, val kind: LineDiffKind)

enum class LineDiffKind {
    /** The line is new. */
    Added,

    /** The line replaces lines of the base. */
    Modified,

    /** Lines of the base were deleted just above the line. */
    Deleted,
}
