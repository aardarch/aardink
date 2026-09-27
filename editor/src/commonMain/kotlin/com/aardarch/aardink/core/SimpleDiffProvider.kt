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

object SimpleDiffProvider : DiffProvider {
    override fun diff(baseLines: List<String>, currentLines: List<String>): List<LineDiff> {
        // Lines the two texts share at the start and at the end are unchanged; only the span
        // between them is compared, line by line. Comparing every line by position instead marked
        // each line below an inserted one as modified.
        val limit = minOf(baseLines.size, currentLines.size)
        var prefix = 0
        while (prefix < limit && baseLines[prefix] == currentLines[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && baseLines[baseLines.size - 1 - suffix] == currentLines[currentLines.size - 1 - suffix]) {
            suffix++
        }
        val baseMiddle = baseLines.size - prefix - suffix
        val result = mutableListOf<LineDiff>()
        for (i in 0 until currentLines.size - prefix - suffix) {
            val line = prefix + i
            when {
                i >= baseMiddle -> result.add(LineDiff(line, LineDiffKind.Added))
                currentLines[line] != baseLines[prefix + i] -> result.add(LineDiff(line, LineDiffKind.Modified))
            }
        }
        return result
    }
}
