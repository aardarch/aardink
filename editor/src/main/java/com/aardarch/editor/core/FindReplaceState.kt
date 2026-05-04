package com.aardarch.editor.core

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

    var matches by mutableStateOf<List<IntRange>>(emptyList())
        internal set

    var currentMatchIndex by mutableIntStateOf(-1)
        internal set

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
        matches = emptyList()
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
}
