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
package com.aardarch.aardink.languages.internal.markdown

/**
 * Markdown's formatting: each GitHub table's columns lined up, every cell padded to its column's
 * widest, as its delimiter row aligns it (`:--` left, `--:` right, `:-:` centred), with a pipe at
 * each end and one space inside each. Everything else, tables in fenced code included, is left as
 * it is. A row with fewer cells than the header gets empty ones; one with more keeps them.
 */
internal object MarkdownTables {

    private val DELIMITER_CELL = Regex("""^\s*:?-+:?\s*$""")

    private enum class Align { None, Left, Right, Center }

    fun format(text: String): String {
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(eol)
        val out = ArrayList<String>(lines.size)
        var fence: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fenceMark = fenceOf(line)
            if (fence != null) {
                if (fenceMark != null && fenceMark.first() == fence.first() && fenceMark.length >= fence.length &&
                    line.trim() == fenceMark
                ) {
                    fence = null
                }
                out += line
                i++
                continue
            }
            if (fenceMark != null) {
                fence = fenceMark
                out += line
                i++
                continue
            }
            val table = tableAt(lines, i)
            if (table == null) {
                out += line
                i++
                continue
            }
            out += formatted(lines.subList(i, table), lines[i].takeWhile { it == ' ' || it == '\t' })
            i = table
        }
        return out.joinToString(eol)
    }

    /** The run of backticks or tildes that opens or closes a fence on [line], or null. */
    private fun fenceOf(line: String): String? {
        val indent = line.takeWhile { it == ' ' }.length
        if (indent > 3) return null
        val first = line.getOrNull(indent) ?: return null
        if (first != '`' && first != '~') return null
        val run = line.substring(indent).takeWhile { it == first }
        return if (run.length >= 3) run else null
    }

    /** The end (exclusive) of the table whose header is line [start], or null when none starts there. */
    private fun tableAt(lines: List<String>, start: Int): Int? {
        if (start + 1 >= lines.size) return null
        // Four spaces in, it is indented code.
        if (lines[start].takeWhile { it == ' ' }.length > 3 || lines[start].startsWith('\t')) return null
        val header = cells(lines[start])?.takeIf { it.isNotEmpty() } ?: return null
        val delimiter = cells(lines[start + 1]) ?: return null
        if (delimiter.size != header.size || !delimiter.all { DELIMITER_CELL.matches(it) }) return null
        var end = start + 2
        while (end < lines.size && lines[end].isNotBlank() && cells(lines[end]) != null && fenceOf(lines[end]) == null) end++
        return end
    }

    /** The cells of a table row, trimmed, or null when [line] has no unescaped pipe. */
    private fun cells(line: String): List<String>? {
        val trimmed = line.trim()
        val pipes = ArrayList<Int>()
        var i = 0
        while (i < trimmed.length) {
            when (trimmed[i]) {
                '\\' -> i++
                '|' -> pipes += i
            }
            i++
        }
        if (pipes.isEmpty()) return null
        val segments = ArrayList<String>()
        var from = 0
        for (pipe in pipes) {
            segments += trimmed.substring(from, pipe)
            from = pipe + 1
        }
        segments += trimmed.substring(from)
        // The pipes at either end only frame the row.
        if (pipes.first() == 0) segments.removeAt(0)
        if (pipes.last() == trimmed.length - 1 && segments.isNotEmpty()) segments.removeAt(segments.size - 1)
        return segments.map { it.trim() }
    }

    private fun formatted(rows: List<String>, indent: String): List<String> {
        val header = cells(rows[0])!!
        val aligns = cells(rows[1])!!.map {
            val cell = it.trim()
            when {
                cell.startsWith(':') && cell.endsWith(':') && cell.length > 1 -> Align.Center
                cell.startsWith(':') -> Align.Left
                cell.endsWith(':') -> Align.Right
                else -> Align.None
            }
        }
        val body = rows.drop(2).map { row ->
            cells(row)!!.let {
                if (it.size <
                    header.size
                ) {
                    it + List(header.size - it.size) { "" }
                } else {
                    it
                }
            }
        }
        val columns = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0)
        val widths = IntArray(columns) { column ->
            maxOf(3, (listOf(header) + body).maxOf { it.getOrNull(column)?.length ?: 0 })
        }

        fun row(cells: List<String>) = cells.indices.joinToString(" | ", "$indent| ", " |") { column ->
            pad(cells[column], widths[column], aligns.getOrElse(column) { Align.None })
        }

        val delimiter = header.indices.joinToString(" | ", "$indent| ", " |") { column ->
            val width = widths[column]
            when (aligns[column]) {
                Align.None -> "-".repeat(width)
                Align.Left -> ":" + "-".repeat(width - 1)
                Align.Right -> "-".repeat(width - 1) + ":"
                Align.Center -> ":" + "-".repeat(width - 2) + ":"
            }
        }
        return listOf(row(header), delimiter) + body.map { row(it) }
    }

    private fun pad(cell: String, width: Int, align: Align): String {
        val space = width - cell.length
        if (space <= 0) return cell
        return when (align) {
            Align.Right -> " ".repeat(space) + cell
            Align.Center -> " ".repeat(space / 2) + cell + " ".repeat(space - space / 2)
            Align.None, Align.Left -> cell + " ".repeat(space)
        }
    }
}
