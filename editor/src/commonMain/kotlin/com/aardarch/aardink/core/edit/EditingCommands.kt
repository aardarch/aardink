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
package com.aardarch.aardink.core.edit

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument

/**
 * What Backspace, Delete, cut, copy and paste do, over every selection at once. Pure functions of
 * the document and the selections, like [LineCommands]; the caller applies the result and records
 * it for undo.
 */
internal object EditingCommands {

    /**
     * Backspace, or Ctrl+Backspace with [word]: deletes each selection, and before each caret the
     * character (never half a surrogate pair) or the word.
     */
    fun deleteLeft(document: CharSequence, selections: SelectionSet, word: Boolean): CommandEdit? = deletion(
        selections,
        selections.ranges.map { range ->
            when {
                !range.collapsed -> TextChange(range.min, range.max, "")
                word -> TextChange(TextNavigator.wordLeft(document, range.end), range.end, "")
                else -> TextChange(TextNavigator.charLeft(document, range.end), range.end, "")
            }
        },
    )

    /** Delete, or Ctrl+Delete with [word]: deletes each selection, and after each caret the character or word. */
    fun deleteRight(document: CharSequence, selections: SelectionSet, word: Boolean): CommandEdit? = deletion(
        selections,
        selections.ranges.map { range ->
            when {
                !range.collapsed -> TextChange(range.min, range.max, "")
                word -> TextChange(range.end, TextNavigator.wordRight(document, range.end), "")
                else -> TextChange(range.end, TextNavigator.charRight(document, range.end), "")
            }
        },
    )

    private fun deletion(selections: SelectionSet, changes: List<TextChange>): CommandEdit? {
        val merged = mergeOverlapping(changes.filterNot { it.isNoOp })
        if (merged.isEmpty()) return null
        return CommandEdit(merged, selections.map { mapOffset(it, merged) })
    }

    /** Deletions sorted, with the ones that overlap or touch joined: two carets can reach the same text. */
    private fun mergeOverlapping(changes: List<TextChange>): List<TextChange> {
        val merged = ArrayList<TextChange>(changes.size)
        for (change in changes.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && change.start <= last.end) {
                merged[merged.size - 1] = TextChange(last.start, maxOf(last.end, change.end), "")
            } else {
                merged.add(change)
            }
        }
        return merged
    }

    /**
     * What copying puts on the clipboard. With only carets, the lines they are on, whole
     * ([wholeLines]): pasting that text then inserts it above the caret's line, as in VS Code.
     */
    class Copied(val text: String, val wholeLines: Boolean)

    /** Ctrl+C: the text of the selections in document order, one per line, or the carets' lines. */
    fun copy(document: CodeDocument, selections: SelectionSet): Copied {
        val ranges = selections.inDocumentOrder
        if (ranges.all { it.collapsed }) {
            val lines = ranges.map { document.offsetToLineCol(it.end).first }.distinct()
            return Copied(lines.joinToString("") { document.lineText(it) + "\n" }, wholeLines = true)
        }
        return Copied(ranges.filterNot { it.collapsed }.joinToString("\n") { document.subSequence(it.min, it.max) }, wholeLines = false)
    }

    /** Ctrl+X: what to put on the clipboard, and the edit that removes it (the whole lines for carets). */
    fun cut(document: CodeDocument, selections: SelectionSet): Pair<Copied, CommandEdit>? {
        val copied = copy(document, selections)
        val edit = if (copied.wholeLines) {
            LineCommands.deleteLines(document, selections)
        } else {
            deletion(selections, selections.ranges.filterNot { it.collapsed }.map { TextChange(it.min, it.max, "") })
        } ?: return null
        return copied to edit
    }

    /**
     * Ctrl+V. The text replaces every selection, and each caret ends up after it. With several
     * selections and exactly as many lines of text, each selection gets its own line. Text copied
     * as [wholeLines] goes in above each caret's line instead, the carets staying where they were.
     */
    fun paste(document: CodeDocument, selections: SelectionSet, text: String, wholeLines: Boolean): CommandEdit? {
        val normalized = TypingRules.normalizeLineEndings(text)
        if (normalized.isEmpty()) return null
        val ordered = selections.inDocumentOrder
        if (wholeLines && ordered.all { it.collapsed }) {
            val lineStarts = ordered.map { document.lineStart(document.offsetToLineCol(it.end).first) }.distinct()
            val changes = lineStarts.map { TextChange(it, it, normalized) }
            return CommandEdit(changes, selections.map { mapOffset(it, changes) })
        }
        val lines = normalized.removeSuffix("\n").split('\n')
        val spread = ordered.size > 1 && lines.size == ordered.size
        val changes = ordered.mapIndexed { i, range -> TextChange(range.min, range.max, if (spread) lines[i] else normalized) }
        return CommandEdit(changes, SelectionSet.of(selections.ranges.map { TextRange(mapOffset(it.max, changes)) }))
    }

    /**
     * Every selection with its caret moved to [target]; with [extend], the anchors stay put, so
     * the selections grow or shrink (Shift+arrow). The primary stays primary.
     */
    fun move(selections: SelectionSet, extend: Boolean, target: (TextRange) -> Int): SelectionSet =
        SelectionSet.of(selections.ranges.map { range -> target(range).let { if (extend) TextRange(range.start, it) else TextRange(it) } })
}
