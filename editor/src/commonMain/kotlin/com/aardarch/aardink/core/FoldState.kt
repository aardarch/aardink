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

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * Holds the set of currently-folded ranges for a [CodeEditorState].
 *
 * - [foldableRanges] is the full set of foldable regions in the document — recomputed when text
 *   changes. The renderer reads this to draw fold triangles in the gutter.
 * - The internal map of folded `startLine` → [FoldRange] is what the renderer uses to decide which
 *   lines to hide or replace with a placeholder.
 *
 * Folded entries that no longer exist after a text edit (e.g. the user deleted the opening tag) are
 * pruned automatically by [updateFoldableRanges].
 */
@Stable
class FoldState {

    private val foldedMap = SnapshotStateMap<Int, FoldRange>()

    var foldableRanges by mutableStateOf<List<FoldRange>>(emptyList())
        private set

    val foldedCount: Int get() = foldedMap.size

    fun isFoldable(startLine: Int): Boolean = foldableRanges.any { it.startLine == startLine }

    fun isFolded(startLine: Int): Boolean = startLine in foldedMap

    fun rangeAt(startLine: Int): FoldRange? = foldableRanges.firstOrNull { it.startLine == startLine }

    /** Returns the currently-folded ranges, in start-line order. */
    fun foldedRanges(): List<FoldRange> = foldedMap.values.sortedBy { it.startLine }

    fun toggle(startLine: Int) {
        if (startLine in foldedMap) {
            foldedMap.remove(startLine)
        } else {
            rangeAt(startLine)?.let { foldedMap[startLine] = it }
        }
    }

    fun foldAll() {
        foldedMap.clear()
        foldableRanges.forEach { foldedMap[it.startLine] = it }
    }

    fun unfoldAll() {
        foldedMap.clear()
    }

    /**
     * Replaces the foldable-ranges list and prunes stale folded entries.
     * Call this from a [LaunchedEffect] watching the document's textVersion.
     */
    fun updateFoldableRanges(newRanges: List<FoldRange>) {
        foldableRanges = newRanges
        val validStartLines = newRanges.map { it.startLine }.toSet()
        foldedMap.keys.toList().forEach { startLine ->
            if (startLine !in validStartLines) foldedMap.remove(startLine)
        }
    }
}
