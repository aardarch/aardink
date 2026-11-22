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
package com.aardarch.aardink.ui.view

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.diff.LineChange

/**
 * Keeps the two sides of a side-by-side diff aligned with filler rows, and keeps them aligned as
 * soft wrap measures lines.
 *
 * The diff is a list of units: a block (a change, with its lines on each side) and each unchanged line
 * paired with its other side's. Every unit takes on its shorter side the rows its taller side has
 * more, after its last line, as Monaco puts them ([DiffRows]).
 *
 * Without soft wrap a line is one row on both sides, so only the blocks need filler and [rebuild] is
 * one pass over them ([DiffRows.sideBySide]). With it, an unchanged line wraps differently in panes of
 * different widths, and [rebuild] works out every unit once, from the rows the two views count (the
 * measured rows for a line on screen, the same estimate on both sides for any other). A line that is
 * then laid out may turn out to have other rows than estimated, and that is what [settle] is for: it
 * looks at the units of the lines on screen only, and changes the filler of those whose rows differ
 * from what was counted, patching their lines and no others. It costs the lines on screen and a
 * binary search each, and does nothing once they have settled, so a frame that scrolls costs one
 * pass at most, and the next one nothing.
 *
 * What is published here is moved with edits by the state, so [settle] does nothing between an edit
 * and the next [rebuild], which the layout runs when the diff has been worked out again.
 */
internal class DiffAlignment(
    private val original: EditorView,
    private val modified: EditorView,
    private val filler: Color,
) {
    private var blocks: List<LineChange> = emptyList()
    private var wrapped = false

    // Rows of filler each unit gave each side, by the unit's first line on each side.
    private val originalGave = HashMap<Long, Int>()
    private val modifiedGave = HashMap<Long, Int>()

    // Rows of filler before a line (or, for the line count, after the last line), summed over units.
    private val originalAt = HashMap<Int, Int>()
    private val modifiedAt = HashMap<Int, Int>()

    /** Publishes the filler for [blocks] on both sides, from nothing. [wrap]: soft wrap is on, so rows are counted. */
    fun rebuild(blocks: List<LineChange>, wrap: Boolean) {
        this.blocks = blocks
        wrapped = wrap
        originalGave.clear()
        modifiedGave.clear()
        originalAt.clear()
        modifiedAt.clear()
        val originalDocument = original.state.document
        val modifiedDocument = modified.state.document
        if (!wrap) {
            val (left, right) = DiffRows.sideBySide(blocks, originalDocument, modifiedDocument, filler)
            original.state.publishDiffRows(left)
            modified.state.publishDiffRows(right)
            return
        }
        var o = 0
        var m = 0
        val end = LineChange(originalDocument.lineCount, originalDocument.lineCount, modifiedDocument.lineCount, modifiedDocument.lineCount, null)
        for (block in blocks + end) {
            while (o < block.originalStart && m < block.modifiedStart) {
                unit(o, o + 1, m, m + 1, null)
                o++
                m++
            }
            if (block.originalSize > 0 || block.modifiedSize > 0) {
                unit(block.originalStart, block.originalEnd, block.modifiedStart, block.modifiedEnd, null)
            }
            o = block.originalEnd
            m = block.modifiedEnd
        }
        original.state.publishDiffRows(injections(original, originalAt))
        modified.state.publishDiffRows(injections(modified, modifiedAt))
    }

    /** Removes the filler from both sides. */
    fun clear() {
        blocks = emptyList()
        originalGave.clear()
        modifiedGave.clear()
        originalAt.clear()
        modifiedAt.clear()
        original.state.publishDiffRows(emptyMap())
        modified.state.publishDiffRows(emptyMap())
    }

    /**
     * Works out again the units of the lines now on screen, under soft wrap, and patches the filler
     * of those whose rows differ from what was counted. Returns whether anything changed, so that the
     * views are laid out once more.
     */
    fun settle(): Boolean {
        if (!wrapped || blocks.isEmpty() && originalGave.isEmpty()) return false
        val seen = HashSet<Long>()
        val touchedOriginal = HashSet<Int>()
        val touchedModified = HashSet<Int>()
        val touched = Touched(touchedOriginal, touchedModified)
        for (line in original.frame.lines) {
            val unit = locate(line.line, onOriginal = true)
            if (seen.add(key(unit[0], unit[2]))) unit(unit[0], unit[1], unit[2], unit[3], touched)
        }
        for (line in modified.frame.lines) {
            val unit = locate(line.line, onOriginal = false)
            if (seen.add(key(unit[0], unit[2]))) unit(unit[0], unit[1], unit[2], unit[3], touched)
        }
        if (touchedOriginal.isEmpty() && touchedModified.isEmpty()) return false
        original.state.patchDiffRows(patchFor(original, originalAt, touchedOriginal))
        modified.state.patchDiffRows(patchFor(modified, modifiedAt, touchedModified))
        return true
    }

    private class Touched(val original: MutableSet<Int>, val modified: MutableSet<Int>)

    /**
     * Counts the rows a unit takes on each side and gives its shorter side the difference. With
     * [touched], only a unit whose rows changed is recorded, and the lines whose filler moved with it.
     */
    private fun unit(originalStart: Int, originalEnd: Int, modifiedStart: Int, modifiedEnd: Int, touched: Touched?) {
        val left = rowsOf(original, originalStart, originalEnd)
        val right = rowsOf(modified, modifiedStart, modifiedEnd)
        val key = key(originalStart, modifiedStart)
        give(originalGave, originalAt, key, originalEnd, (right - left).coerceAtLeast(0), touched?.original)
        give(modifiedGave, modifiedAt, key, modifiedEnd, (left - right).coerceAtLeast(0), touched?.modified)
    }

    private fun give(
        gave: HashMap<Long, Int>,
        at: HashMap<Int, Int>,
        key: Long,
        target: Int,
        rows: Int,
        touched: MutableSet<Int>?,
    ) {
        val before = gave[key] ?: 0
        if (rows == before) return
        if (rows == 0) gave.remove(key) else gave[key] = rows
        val total = (at[target] ?: 0) + rows - before
        if (total <= 0) at.remove(target) else at[target] = total
        touched?.add(target)
    }

    /**
     * The rows lines [from] to [to] of [view] take without a diff's filler: those on screen as laid
     * out, the others as the row map counts them, less the filler it counted in.
     */
    private fun rowsOf(view: EditorView, from: Int, to: Int): Int {
        if (to <= from) return 0
        val map = view.lineMap
        val lineCount = view.state.document.lineCount
        if (map.lineCount != lineCount) return to - from
        if (to - from <= MAX_LINES_SUMMED) {
            var rows = 0
            for (line in from until to) rows += ownRows(view, line)
            return rows
        }
        // A long run: the row map's total for it, less the filler on its first line, and on its last
        // when that is the document's. Lines inside it have none.
        var rows = map.firstRowOf(to) - map.firstRowOf(from) - fillerRows(view, from)
        if (to == lineCount && to - 1 != from) rows -= fillerRows(view, to - 1)
        return rows.coerceAtLeast(0)
    }

    private fun ownRows(view: EditorView, line: Int): Int {
        val map = view.lineMap
        if (map.isHidden(line)) return 0
        view.frame.find(line)?.let { return it.layout.textRows }
        return (map.rowsOf(line) - fillerRows(view, line)).coerceAtLeast(0)
    }

    /** The rows of filler the state publishes on [line]. */
    private fun fillerRows(view: EditorView, line: Int): Int {
        val rows = view.state.diffRows[line] ?: return 0
        var count = 0
        for (row in rows) for (char in row.text) if (char == '\n') count++
        return count
    }

    /** The unit [line] is in: its first and last line plus one on the original, then on the modified side. */
    private fun locate(line: Int, onOriginal: Boolean): IntArray {
        var low = 0
        var high = blocks.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val end = if (onOriginal) blocks[mid].originalEnd else blocks[mid].modifiedEnd
            if (end <= line) low = mid + 1 else high = mid
        }
        blocks.getOrNull(low)?.let { block ->
            val start = if (onOriginal) block.originalStart else block.modifiedStart
            if (start <= line) return intArrayOf(block.originalStart, block.originalEnd, block.modifiedStart, block.modifiedEnd)
        }
        val before = blocks.getOrNull(low - 1)
        return if (onOriginal) {
            val other = if (before == null) line else line - before.originalEnd + before.modifiedEnd
            intArrayOf(line, line + 1, other, other + 1)
        } else {
            val other = if (before == null) line else line - before.modifiedEnd + before.originalEnd
            intArrayOf(other, other + 1, line, line + 1)
        }
    }

    /**
     * What [view]'s [lines] show, from the rows of filler [at] each target line has: null for a line
     * with none. A line past the last is the last line, whose end shows what is after it.
     */
    private fun patchFor(view: EditorView, at: Map<Int, Int>, lines: Collection<Int>): Map<Int, List<Injection>?> {
        val document = view.state.document
        val last = document.lineCount - 1
        val result = HashMap<Int, List<Injection>?>()
        for (target in lines) {
            val line = target.coerceAtMost(last)
            if (line in result) continue
            val shown = ArrayList<Injection>(2)
            at[line]?.let { shown += DiffRows.filler(document, line, it, filler) }
            if (line == last) at[document.lineCount]?.let { shown += DiffRows.filler(document, document.lineCount, it, filler) }
            result[line] = shown.ifEmpty { null }
        }
        return result
    }

    private fun injections(view: EditorView, at: Map<Int, Int>): Map<Int, List<Injection>> =
        patchFor(view, at, at.keys).mapNotNull { (line, rows) -> rows?.let { line to it } }.toMap()

    private fun key(originalStart: Int, modifiedStart: Int): Long = (originalStart.toLong() shl 32) or modifiedStart.toLong()

    private companion object {
        /** Longest run of lines whose rows are added one by one: a longer one is counted from the row map's total. */
        const val MAX_LINES_SUMMED = 256
    }
}
