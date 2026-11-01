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
import com.aardarch.aardink.core.CommentSyntax

/** An edit a command wants: [changes] against the document as it is, and the selections after. */
internal class CommandEdit(val changes: List<TextChange>, val selectionsAfter: SelectionSet)

/**
 * The line-based editing commands: comment toggling, indenting, moving, copying and deleting
 * lines. Each works on every selection at once and returns a [CommandEdit], or null when there is
 * nothing to do; applying it (and recording it for undo) is the caller's job.
 */
internal object LineCommands {

    /** One step of indentation. Spaces, matching what smart indent inserts. */
    const val INDENT = "    "

    /**
     * The lines each selection covers, merged where they touch, in document order. A selection
     * that ends at the very start of a line has not really reached it and does not include it.
     */
    fun lineBlocks(document: CodeDocument, selections: SelectionSet): List<IntRange> {
        val blocks = selections.inDocumentOrder.map { range ->
            val first = document.offsetToLineCol(range.min).first
            var last = document.offsetToLineCol(range.max).first
            if (!range.collapsed && last > first && document.lineStart(last) == range.max) last--
            first..last
        }
        val merged = ArrayList<IntRange>()
        for (block in blocks) {
            val previous = merged.lastOrNull()
            if (previous != null && block.first <= previous.last + 1) {
                merged[merged.size - 1] = previous.first..maxOf(previous.last, block.last)
            } else {
                merged.add(block)
            }
        }
        return merged
    }

    /**
     * Ctrl+/. With a line-comment prefix: uncomments when every non-blank selected line is already
     * commented, else comments them all, the prefix going at the smallest indentation among them.
     * Without one, wraps (or unwraps) each block of lines in the block comment markers.
     */
    fun toggleComment(document: CodeDocument, selections: SelectionSet, syntax: CommentSyntax?): CommandEdit? {
        syntax ?: return null
        val blocks = lineBlocks(document, selections)
        val changes = when {
            syntax.line != null -> toggleLineComments(document, blocks.flatMap { it.toList() }, syntax.line)

            syntax.blockStart != null && syntax.blockEnd != null -> blocks.flatMap {
                toggleBlockComment(document, it, syntax.blockStart, syntax.blockEnd)
            }

            else -> return null
        }
        if (changes.isEmpty()) return null
        // A caret right where a prefix goes ends up after it, still before the same text.
        return CommandEdit(changes, selections.map { mapOffset(it, changes) })
    }

    private fun toggleLineComments(document: CodeDocument, lines: List<Int>, prefix: String): List<TextChange> {
        val contentLines = lines.filter { document.lineText(it).isNotBlank() }
        if (contentLines.isEmpty()) return emptyList()
        val allCommented = contentLines.all { document.lineText(it).trimStart().startsWith(prefix) }
        return if (allCommented) {
            contentLines.map { line ->
                val text = document.lineText(line)
                val at = text.indexOf(prefix)
                val removeEnd = at + prefix.length + if (text.getOrNull(at + prefix.length) == ' ') 1 else 0
                val start = document.lineStart(line)
                TextChange(start + at, start + removeEnd, "")
            }
        } else {
            val indent = contentLines.minOf { line -> document.lineText(line).takeWhile { it == ' ' || it == '\t' }.length }
            contentLines.map { line ->
                val at = document.lineStart(line) + indent
                TextChange(at, at, "$prefix ")
            }
        }
    }

    private fun toggleBlockComment(document: CodeDocument, lines: IntRange, open: String, close: String): List<TextChange> {
        val start = document.lineStart(lines.first)
        val end = document.lineEnd(lines.last)
        val text = document.subSequence(start, end).toString()
        val contentStart = start + text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }.let { it ?: return emptyList() }
        val contentEnd = start + text.indexOfLast { !it.isWhitespace() } + 1
        val content = document.subSequence(contentStart, contentEnd).toString()
        return if (content.startsWith(open) && content.endsWith(close) && content.length >= open.length + close.length) {
            val openEnd = contentStart + open.length + if (document.getOrNull(contentStart + open.length) == ' ') 1 else 0
            val closeStart =
                contentEnd - close.length -
                    if (document.getOrNull(contentEnd - close.length - 1) == ' ' && contentEnd - close.length - 1 >= openEnd) 1 else 0
            listOf(TextChange(contentStart, openEnd, ""), TextChange(closeStart, contentEnd, ""))
        } else {
            listOf(TextChange(contentStart, contentStart, "$open "), TextChange(contentEnd, contentEnd, " $close"))
        }
    }

    /**
     * Tab with a selection: indents every covered line by [unit]. With carets only it inserts
     * [unit] at each caret, as Tab does anywhere.
     */
    fun indent(document: CodeDocument, selections: SelectionSet, unit: String = INDENT): CommandEdit {
        if (selections.ranges.all { it.collapsed }) {
            val changes = selections.ranges.map { TextChange(it.start, it.start, unit) }
            return CommandEdit(changes, selections.map { mapOffset(it, changes) })
        }
        val changes = lineBlocks(document, selections).flatMap { block ->
            block.map { line -> document.lineStart(line).let { TextChange(it, it, unit) } }
        }
        // A selection starting at column 0 keeps covering the whole line, indent included.
        return CommandEdit(changes, selections.map { mapOffset(it, changes, stickToEnd = false) })
    }

    /**
     * Shift+Tab: removes up to one indentation step from every covered line: a tab, or up to as
     * many spaces as [unit] is wide (a tab [unit] counts as [INDENT]'s width).
     */
    fun outdent(document: CodeDocument, selections: SelectionSet, unit: String = INDENT): CommandEdit? {
        val spaces = if (unit == "\t") INDENT.length else unit.length
        val changes = lineBlocks(document, selections).flatMap { block ->
            block.mapNotNull { line ->
                val text = document.lineText(line)
                val width = if (text.startsWith('\t')) 1 else text.takeWhile { it == ' ' }.length.coerceAtMost(spaces)
                if (width == 0) null else document.lineStart(line).let { TextChange(it, it + width, "") }
            }
        }
        if (changes.isEmpty()) return null
        return CommandEdit(changes, selections.map { mapOffset(it, changes, stickToEnd = false) })
    }

    /**
     * Alt+Up / Alt+Down: moves each block of covered lines past the line above or below it,
     * taking the selections along. Null when a block is already at the edge.
     */
    fun moveLines(document: CodeDocument, selections: SelectionSet, up: Boolean): CommandEdit? {
        val blocks = lineBlocks(document, selections)
        if (up && blocks.first().first == 0) return null
        if (!up && blocks.last().last == document.lineCount - 1) return null
        val changes = ArrayList<TextChange>()
        var selectionsAfter = selections
        for (block in blocks) {
            // Swap the block with the neighbouring line: one change replacing both.
            val neighbour = if (up) block.first - 1 else block.last + 1
            val first = minOf(block.first, neighbour)
            val last = maxOf(block.last, neighbour)
            val spanStart = document.lineStart(first)
            val spanEnd = document.lineEnd(last)
            val blockText = document.subSequence(document.lineStart(block.first), document.lineEnd(block.last)).toString()
            val neighbourText = document.lineText(neighbour)
            val replacement = if (up) "$blockText\n$neighbourText" else "$neighbourText\n$blockText"
            changes.add(TextChange(spanStart, spanEnd, replacement))
            // Offsets inside the block move by the neighbour's length plus its newline.
            val shift = (neighbourText.length + 1) * if (up) -1 else 1
            val blockStart = document.lineStart(block.first)
            val blockEnd = document.lineEnd(block.last)
            selectionsAfter = selectionsAfter.map { if (it in blockStart..blockEnd) it + shift else it }
        }
        return CommandEdit(changes, selectionsAfter)
    }

    /**
     * Shift+Alt+Down / Up: copies each block of covered lines below itself. With [down] the
     * selections move onto the copy; otherwise they stay on the original, which is now the upper one.
     */
    fun copyLines(document: CodeDocument, selections: SelectionSet, down: Boolean): CommandEdit {
        val blocks = lineBlocks(document, selections).map { document.lineStart(it.first)..document.lineEnd(it.last) }
        val changes = blocks.map { span -> TextChange(span.last, span.last, "\n" + document.subSequence(span.first, span.last)) }
        val after = selections.map { offset ->
            val mapped = mapOffset(offset, changes, stickToEnd = false)
            val own = blocks.firstOrNull { offset in it }
            if (down && own != null) mapped + (own.last - own.first) + 1 else mapped
        }
        return CommandEdit(changes, after)
    }

    /** Ctrl+Shift+K: deletes every covered line. */
    fun deleteLines(document: CodeDocument, selections: SelectionSet): CommandEdit {
        val blocks = lineBlocks(document, selections)
        val changes = blocks.map { block ->
            val start = document.lineStart(block.first)
            val end = document.lineEnd(block.last)
            when {
                // Take the newline after the block, or, on the last line, the one before it.
                end < document.length -> TextChange(start, end + 1, "")

                start > 0 -> TextChange(start - 1, end, "")

                else -> TextChange(start, end, "")
            }
        }
        val after = selections.map { offset ->
            val line = document.offsetToLineCol(offset).first
            val block = blocks.firstOrNull { line in it }
            if (block != null) document.lineStart(block.first) else offset
        }.map { mapOffset(it, changes, stickToEnd = false) }
        return CommandEdit(changes, after.clampedTo(document.length - changes.sumOf { it.end - it.start }))
    }

    /**
     * Ctrl+J: joins each block of covered lines into one, or a caret's line with the next, as VS
     * Code does: each line break and the indentation after it become one space (none next to an
     * empty line), trailing whitespace before it goes.
     */
    fun joinLines(document: CodeDocument, selections: SelectionSet): CommandEdit? {
        val changes = ArrayList<TextChange>()
        for (block in lineBlocks(document, selections)) {
            val last = if (block.first == block.last) block.first + 1 else block.last
            if (last >= document.lineCount) continue
            for (line in block.first until last) {
                val text = document.lineText(line)
                val next = document.lineText(line + 1)
                val trimmedEnd = text.trimEnd()
                val nextContent = next.trimStart()
                val start = document.lineStart(line) + trimmedEnd.length
                val end = document.lineStart(line + 1) + (next.length - nextContent.length)
                val joint = if (trimmedEnd.isEmpty() || nextContent.isEmpty()) "" else " "
                changes += TextChange(start, end, joint)
            }
        }
        if (changes.isEmpty()) return null
        return CommandEdit(changes, selections.map { mapOffset(it, changes) })
    }

    /** Sorts the lines of each block of covered lines, ascending or [descending]; null with nothing to sort. */
    fun sortLines(document: CodeDocument, selections: SelectionSet, descending: Boolean): CommandEdit? {
        val changes = lineBlocks(document, selections).filter { it.last > it.first }.mapNotNull { block ->
            val lines = block.map { document.lineText(it) }
            val sorted = if (descending) lines.sortedDescending() else lines.sorted()
            if (sorted ==
                lines
            ) {
                null
            } else {
                TextChange(document.lineStart(block.first), document.lineEnd(block.last), sorted.joinToString("\n"))
            }
        }
        if (changes.isEmpty()) return null
        return CommandEdit(changes, selections)
    }

    /** Upper- or lower-cases each selection, or the word at each caret. */
    fun transformCase(document: CodeDocument, selections: SelectionSet, upper: Boolean): CommandEdit? {
        val changes = selections.inDocumentOrder.mapNotNull { range ->
            val target = if (range.collapsed) {
                TextNavigator.wordAt(document, range.start).takeUnless { it.isEmpty() }?.let { it.first until it.last + 1 }
            } else {
                range.min until range.max
            } ?: return@mapNotNull null
            val text = document.subSequence(target.first, target.last + 1).toString()
            val changed = if (upper) text.uppercase() else text.lowercase()
            if (changed == text) null else TextChange(target.first, target.last + 1, changed)
        }.distinctBy { it.start }
        if (changes.isEmpty()) return null
        // Where the case changes no lengths (nearly always), every caret and selection stays put.
        val sameLengths = changes.all { it.text.length == it.end - it.start }
        return CommandEdit(changes, if (sameLengths) selections else selections.map { mapOffset(it, changes, stickToEnd = false) })
    }

    /** Removes the spaces and tabs at the end of every line of [document]. */
    fun trimTrailingWhitespace(document: CodeDocument, selections: SelectionSet): CommandEdit? {
        val changes = (0 until document.lineCount).mapNotNull { line ->
            val text = document.lineText(line)
            val trimmed = text.trimEnd(' ', '\t')
            if (trimmed.length ==
                text.length
            ) {
                null
            } else {
                document.lineStart(line).let { TextChange(it + trimmed.length, it + text.length, "") }
            }
        }
        if (changes.isEmpty()) return null
        return CommandEdit(changes, selections.map { mapOffset(it, changes) })
    }

    /**
     * Ctrl+Enter / Ctrl+Shift+Enter: a new line below (or [above]) each caret's line, indented as
     * that line, with the caret on it, whatever the caret's place in its line.
     */
    fun insertLine(document: CodeDocument, selections: SelectionSet, above: Boolean, lineBreak: String = "\n"): CommandEdit {
        val lines = selections.inDocumentOrder.map { document.offsetToLineCol(it.end).first }.distinct()
        val changes = lines.map { line ->
            val text = document.lineText(line)
            val indent = text.takeWhile { it == ' ' || it == '\t' }
            if (above) {
                document.lineStart(line).let { TextChange(it, it, indent + lineBreak) }
            } else {
                document.lineEnd(line).let { TextChange(it, it, lineBreak + indent) }
            }
        }
        // Each caret goes to the end of the indentation it inserted.
        var shift = 0
        val carets = changes.map { change ->
            val caret = change.start + shift + if (above) change.text.length - lineBreak.length else change.text.length
            shift += change.text.length
            TextRange(caret)
        }
        return CommandEdit(changes, SelectionSet.of(carets))
    }

    /**
     * Ctrl+L: selects each selection's lines whole, line break included; a selection that already
     * is whole lines grows by the next line.
     */
    fun selectLines(document: CodeDocument, selections: SelectionSet): SelectionSet = SelectionSet.of(
        selections.ranges.map { range ->
            val first = document.offsetToLineCol(range.min).first
            val wholeLines = !range.collapsed && document.lineStart(first) == range.min &&
                (range.max == document.length || document.offsetToLineCol(range.max).second == 0)
            val lastLine = when {
                wholeLines && range.max < document.length -> document.offsetToLineCol(range.max).first
                wholeLines -> document.lineCount - 1
                else -> document.offsetToLineCol(range.max).first
            }
            val end = if (lastLine + 1 < document.lineCount) document.lineStart(lastLine + 1) else document.length
            TextRange(document.lineStart(first), end)
        },
    )

    /**
     * Ctrl+K Ctrl+C ([add]) or Ctrl+K Ctrl+U: comments every non-blank covered line with the line
     * comment, or uncomments those that are. Null without a line comment, or with nothing to do.
     */
    fun setLineComments(document: CodeDocument, selections: SelectionSet, syntax: CommentSyntax?, add: Boolean): CommandEdit? {
        val prefix = syntax?.line ?: return null
        val lines = lineBlocks(document, selections).flatMap { it.toList() }.filter { document.lineText(it).isNotBlank() }
        val changes = if (add) {
            if (lines.isEmpty()) return null
            val indent = lines.minOf { line -> document.lineText(line).takeWhile { it == ' ' || it == '\t' }.length }
            lines.map { line -> (document.lineStart(line) + indent).let { TextChange(it, it, "$prefix ") } }
        } else {
            lines.filter { document.lineText(it).trimStart().startsWith(prefix) }.map { line ->
                val text = document.lineText(line)
                val at = text.indexOf(prefix)
                val removeEnd = at + prefix.length + if (text.getOrNull(at + prefix.length) == ' ') 1 else 0
                val start = document.lineStart(line)
                TextChange(start + at, start + removeEnd, "")
            }
        }
        if (changes.isEmpty()) return null
        return CommandEdit(changes, selections.map { mapOffset(it, changes) })
    }

    /**
     * Shift+Alt+A: wraps each selection in the block comment markers, or unwraps one that already
     * is; at a caret, inserts an empty block comment with the caret inside.
     */
    fun toggleBlockComment(document: CodeDocument, selections: SelectionSet, syntax: CommentSyntax?): CommandEdit? {
        val open = syntax?.blockStart ?: return null
        val close = syntax.blockEnd ?: return null
        val changes = ArrayList<TextChange>()
        val carets = ArrayList<Pair<TextRange, Int>>()
        for (range in selections.inDocumentOrder) {
            if (range.collapsed) {
                changes += TextChange(range.start, range.start, "$open  $close")
                carets += range to open.length + 1
                continue
            }
            val text = document.subSequence(range.min, range.max).toString()
            if (text.startsWith(open) && text.endsWith(close) && text.length >= open.length + close.length) {
                val inner = text.substring(open.length, text.length - close.length)
                changes += TextChange(range.min, range.max, inner.removePrefix(" ").removeSuffix(" "))
            } else {
                changes += TextChange(range.min, range.max, "$open $text $close")
            }
        }
        val after = if (selections.ranges.all { it.collapsed }) {
            var shift = 0
            SelectionSet.of(
                carets.map { (range, inside) ->
                    val caret = range.start + shift + inside
                    shift += open.length + close.length + 2
                    TextRange(caret)
                },
            )
        } else {
            SelectionSet.of(
                changes.runningFold(0) { acc, c -> acc + c.text.length - (c.end - c.start) }.zip(changes).map { (shift, c) ->
                    TextRange(c.start + shift, c.start + shift + c.text.length)
                },
            )
        }
        return CommandEdit(changes, after)
    }

    /** Shift+Alt+I: a caret at the end of every line each selection covers, the selections gone. */
    fun cursorsAtLineEnds(document: CodeDocument, selections: SelectionSet): SelectionSet? {
        if (selections.ranges.all { it.collapsed }) return null
        val carets = lineBlocks(document, selections).flatMap { block -> block.map { TextRange(document.lineEnd(it)) } }
        return SelectionSet.of(carets)
    }
}
