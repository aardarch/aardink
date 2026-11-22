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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aardarch.aardink.ui.view.EditorView
import kotlinx.coroutines.withContext

/**
 * What a diff editor shows: the [original] text and the [modified] text, each in a
 * [CodeEditorState] of its own, and the [changes] between them.
 *
 * The state computes nothing by itself. `CodeDiffEditorLayout` works the diff out while it shows the
 * state: at once when it appears, and 300 ms after typing stops on either side, on the states'
 * [CodeEditorState.computeDispatcher] and never while a key is handled. Until then [changes] are the
 * ones of the last diff, so a state that no layout shows has none. Reading the changes is always safe:
 * they are snapshot state, and a layout that is not showing them leaves them as they were.
 *
 * Both texts can be edited at any time through their own states. [revert] and [accept] edit the text
 * of one side from the other, as one undo step on the side they change.
 *
 * @property original The text the changes are from.
 * @property modified The text the changes lead to: the side that is edited, in a diff editor with
 *   default options.
 */
@Stable
class DiffEditorState(val original: CodeEditorState, val modified: CodeEditorState) {

    /**
     * The changes from [original] to [modified] as of the last diff: line ranges on each side, and the
     * characters that changed inside them. Empty until the first diff, and when the texts are the same.
     * The text may have been edited since; the layout works the diff out again once typing pauses.
     */
    var changes: List<LineChange> by mutableStateOf(emptyList())
        private set

    /**
     * Whether the last diff stopped at its time limit (`DiffEditorOptions.maxComputationTimeMs`). The
     * changes are then still right, but a region the search did not finish is one change over all its
     * lines, where the full answer would have found less. The editor shows them as it shows any change.
     */
    var hitTimeout: Boolean by mutableStateOf(false)
        private set

    private var listeners: List<(TextDiffResult) -> Unit> = emptyList()

    /** Counts the diffs worked out, so that a layout can tell a new one from one that found the same changes. */
    internal var revision by mutableIntStateOf(0)
        private set

    /** The versions of the two documents the last diff was worked out for; null before the first. */
    private var diffVersions: Pair<Long, Long>? = null

    /** The two renderers, while a layout shows them: set by the layout, for its commands and for tests. */
    internal var originalView: EditorView? = null
    internal var modifiedView: EditorView? = null

    /** Whether [changes] were worked out for the texts as they are now. */
    internal val isCurrent: Boolean
        get() = diffVersions == (original.document.version to modified.document.version)

    /**
     * Calls [listener] with each diff once it is worked out, on the thread that asked for it (the
     * main thread, for a layout). [removeDiffListener] stops it.
     */
    fun addDiffListener(listener: (TextDiffResult) -> Unit) {
        listeners += listener
    }

    /** Stops calling [listener]. */
    fun removeDiffListener(listener: (TextDiffResult) -> Unit) {
        listeners -= listener
    }

    /**
     * The change that [line] is part of, on the modified side or, with [modifiedSide] false, the
     * original's, or null when it is in none. A change that only deletes lines has no lines on the
     * modified side: it is at the line after them (the last line, when they were the last).
     * [line] is 0-based.
     */
    fun changeAt(line: Int, modifiedSide: Boolean = true): LineChange? {
        val lineCount = (if (modifiedSide) modified else original).document.lineCount
        return changes.firstOrNull { change ->
            val range = if (modifiedSide) change.modifiedLines else change.originalLines
            if (range.isEmpty()) line == range.first.coerceAtMost(lineCount - 1) else line in range
        }
    }

    /**
     * Puts the original's lines of [change] back in the modified text, in place of its modified
     * lines, as one undo step of [modified]. Nothing happens when the modified side is read-only
     * (a layout sets that from its options), or when [change] is not one of [changes]: the text has
     * been edited, and the diff worked out again, since it was taken.
     */
    fun revert(change: LineChange) {
        if (modified.readOnly || change !in changes) return
        replaceLines(modified, change.modifiedLines, original, change.originalLines)
    }

    /**
     * Puts the modified lines of [change] into the original text, in place of its original lines, as
     * one undo step of [original]. A layout only offers it when `DiffEditorOptions.originalEditable`
     * is set, and while it shows the state, the original being read-only makes this do nothing. As
     * with [revert], a [change] that is not one of [changes] does nothing.
     */
    fun accept(change: LineChange) {
        if (original.readOnly || change !in changes) return
        replaceLines(original, change.originalLines, modified, change.modifiedLines)
    }

    /** Works the diff out now, for the texts as they are, unless one of them is edited meanwhile. */
    internal suspend fun recompute(options: TextDiffOptions) {
        val originalDocument = original.document.snapshot()
        val modifiedDocument = modified.document.snapshot()
        val result = withContext(modified.computeDispatcher) { TextDiff.compute(originalDocument, modifiedDocument, options) }
        // An edit in the meantime made this answer old; the layout asks again once typing pauses.
        if (original.document.version != originalDocument.version || modified.document.version != modifiedDocument.version) return
        diffVersions = originalDocument.version to modifiedDocument.version
        changes = result.changes
        hitTimeout = result.hitTimeout
        revision++
        for (listener in listeners) listener(result)
    }

    /**
     * Replaces [targetLines] of [target] with [sourceLines] of [source], so that the lines before and
     * after stay as they are: the edit a revert or an accept makes.
     */
    private fun replaceLines(target: CodeEditorState, targetLines: IntRange, source: CodeEditorState, sourceLines: IntRange) {
        if (!isCurrent) return
        val targetDocument = target.document
        val sourceDocument = source.document
        // The target's own line break, between the lines put in and around them.
        val eol = target.lineEnding.text
        val text = sourceLines.joinToString(eol) { line ->
            sourceDocument.subSequence(sourceDocument.lineStart(line), contentEnd(sourceDocument, line)).toString()
        }
        val lineCount = targetDocument.lineCount
        val edit = when {
            // Lines for lines: the first line's start to the last line's content, its break kept.
            !targetLines.isEmpty() && !sourceLines.isEmpty() ->
                TextEdit(targetDocument.lineStart(targetLines.first) until contentEnd(targetDocument, targetLines.last), text)

            // Lines to take away, with the break that ends them, or the whole break before them at the end.
            !targetLines.isEmpty() -> if (targetLines.last + 1 < lineCount) {
                TextEdit(targetDocument.lineStart(targetLines.first) until targetDocument.lineStart(targetLines.last + 1), "")
            } else if (targetLines.first > 0) {
                TextEdit(contentEnd(targetDocument, targetLines.first - 1) until targetDocument.lineEnd(targetLines.last), "")
            } else {
                TextEdit(0 until targetDocument.length, "")
            }

            // Lines to put in, before the line they were before, or after the last line.
            sourceLines.isEmpty() -> return

            targetLines.first < lineCount -> targetDocument.lineStart(targetLines.first).let { TextEdit(it until it, text + eol) }

            else -> targetDocument.length.let { TextEdit(it until it, eol + text) }
        }
        target.executeEdits(listOf(edit))
    }

    /** Where [line]'s text ends: before the carriage return of a CRLF break, which a line's end includes. */
    private fun contentEnd(document: CodeDocument, line: Int): Int {
        val end = document.lineEnd(line)
        return if (line + 1 < document.lineCount && end > document.lineStart(line) && document[end - 1] == '\r') end - 1 else end
    }
}

/**
 * Remembers a [DiffEditorState] for [original] and [modified]: a new one when either state is
 * another.
 */
@Composable
fun rememberDiffEditorState(original: CodeEditorState, modified: CodeEditorState): DiffEditorState =
    remember(original, modified) { DiffEditorState(original, modified) }
