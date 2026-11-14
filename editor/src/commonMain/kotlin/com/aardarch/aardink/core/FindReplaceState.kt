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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Snapshot-state holder for the find/replace UI panel.
 *
 * The panel mutates these fields directly. The owning [CodeEditorState] (or a [LaunchedEffect]
 * watching this state) is responsible for re-running [FindEngine.findAll] when [query] or any
 * option changes, and for updating [matches] / [currentMatchIndex].
 */
@Stable
class FindReplaceState {

    var visible by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var useRegex by mutableStateOf(false)

    /**
     * Find in selection: matches only within the text selected when this was switched on (the
     * caret's line when nothing was), which moves with the edits made since. Each time the panel
     * opens with it on and text selected, that selection becomes the place searched.
     */
    var inSelection by mutableStateOf(false)

    /**
     * Preserve case: a replacement takes the case of the text it replaces, all capitals, all small
     * letters or a capital first letter, as VS Code's does.
     */
    var preserveCase by mutableStateOf(false)

    /** Where [inSelection] searches, moved along with each edit; the layout attaches it to the document. */
    internal val scopeTracker = TrackedRanges<IntRange>({ it }, { _, range -> range }, keepsOneCharacter = false)

    /** The place [inSelection] searches, or null for the whole document. */
    internal val scope: IntRange? get() = if (inSelection) scopeTracker.items.firstOrNull() else null

    var matches by mutableStateOf<List<IntRange>>(emptyList())
        internal set

    /** Whether the search stopped at [EditorLimits.maxFindMatches], so [matches] is not all of them. */
    internal var matchesCapped by mutableStateOf(false)

    /**
     * Whether the panel shows its replace row. Set by [show]; the panel has a toggle for it too.
     */
    var replaceMode by mutableStateOf(false)

    var currentMatchIndex by mutableIntStateOf(-1)
        internal set

    /**
     * Counts [show] calls, so the panel takes the keyboard focus each time it is asked to open,
     * also when it is open already.
     */
    internal var showRequests by mutableIntStateOf(0)
        private set

    /**
     * Opens the panel: find only, or with the replace row when [replace] (Ctrl+H). The panel takes
     * the keyboard focus: the find field, or the replace field when [replace] and there is
     * already something to find.
     */
    fun show(replace: Boolean = false) {
        visible = true
        replaceMode = replace
        showRequests++
    }

    fun hide() {
        remember()
        visible = false
        matches = emptyList()
        matchesCapped = false
        currentMatchIndex = -1
    }

    /** Advances [currentMatchIndex] to the next match, wrapping at the end. */
    fun nextMatch(): IntRange? {
        if (matches.isEmpty()) return null
        currentMatchIndex = (currentMatchIndex + 1) % matches.size
        return matches[currentMatchIndex]
    }

    /** Moves [currentMatchIndex] to the previous match, wrapping at the start. */
    fun prevMatch(): IntRange? {
        if (matches.isEmpty()) return null
        currentMatchIndex = if (currentMatchIndex <= 0) matches.size - 1 else currentMatchIndex - 1
        return matches[currentMatchIndex]
    }

    fun toOptions(): FindEngine.Options = FindEngine.Options(caseSensitive, wholeWord, useRegex)

    /** The edits that replace [targets] in [text] with [replacement], groups expanded in regex mode. */
    internal fun replaceEdits(text: CharSequence, targets: List<IntRange>): List<TextEdit> =
        FindEngine.replaceEdits(text, targets, query, replacement, toOptions(), preserveCase)

    // ── History ───────────────────────────────────────────────────────────────

    /** What was searched for, newest first. */
    internal val queryHistory = SearchHistory()

    /** What was replaced with, newest first. */
    internal val replaceHistory = SearchHistory()

    /** Keeps the query and, when [replaced], the replacement in their histories. */
    internal fun remember(replaced: Boolean = false) {
        queryHistory.add(query)
        if (replaced) replaceHistory.add(replacement)
    }
}
