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
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.TokenType

/** Original lines [originalStart, originalEnd) became modified lines [modifiedStart, modifiedEnd). */
internal data class DiffBlock(val originalStart: Int, val originalEnd: Int, val modifiedStart: Int, val modifiedEnd: Int) {
    val originalLines: Int get() = originalEnd - originalStart
    val modifiedLines: Int get() = modifiedEnd - modifiedStart
}

/**
 * The rows a diff adds to each side's lines, for [CodeEditorState.publishDiffRows]: blank filler rows
 * side by side, and the original's deleted lines inline. A row "before line n" of a document with n
 * lines goes after its last line.
 */
internal object DiffRows {

    /**
     * Filler rows that keep two sides line for line without soft wrap: each block's shorter side gets
     * the difference, after its own lines, as Monaco puts them.
     */
    fun sideBySide(
        blocks: List<DiffBlock>,
        original: CodeDocument,
        modified: CodeDocument,
        filler: Color,
    ): Pair<Map<Int, List<Injection>>, Map<Int, List<Injection>>> {
        val left = HashMap<Int, MutableList<Injection>>()
        val right = HashMap<Int, MutableList<Injection>>()
        for (block in blocks) {
            val extra = block.originalLines - block.modifiedLines
            if (extra > 0) addFiller(right, modified, block.modifiedEnd, extra, filler)
            if (extra < 0) addFiller(left, original, block.originalEnd, -extra, filler)
        }
        return left to right
    }

    /**
     * Filler rows that keep two sides aligned by rows, as soft wrap and a split of any width need:
     * every unit (a block, or an unchanged line paired with its other side's) takes on its shorter
     * side the rows its taller side has more. [rowsOriginal] and [rowsModified] count a line's rows
     * without any filler of the diff's own.
     */
    fun alignedByRows(
        blocks: List<DiffBlock>,
        original: CodeDocument,
        modified: CodeDocument,
        filler: Color,
        rowsOriginal: (line: Int) -> Int,
        rowsModified: (line: Int) -> Int,
    ): Pair<Map<Int, List<Injection>>, Map<Int, List<Injection>>> {
        val left = HashMap<Int, MutableList<Injection>>()
        val right = HashMap<Int, MutableList<Injection>>()
        fun unit(oStart: Int, oEnd: Int, mStart: Int, mEnd: Int) {
            var rowsLeft = 0
            for (line in oStart until oEnd) rowsLeft += rowsOriginal(line)
            var rowsRight = 0
            for (line in mStart until mEnd) rowsRight += rowsModified(line)
            if (rowsLeft > rowsRight) addFiller(right, modified, mEnd, rowsLeft - rowsRight, filler)
            if (rowsRight > rowsLeft) addFiller(left, original, oEnd, rowsRight - rowsLeft, filler)
        }
        var o = 0
        var m = 0
        for (block in blocks + DiffBlock(original.lineCount, original.lineCount, modified.lineCount, modified.lineCount)) {
            while (o < block.originalStart && m < block.modifiedStart) {
                unit(o, o + 1, m, m + 1)
                o++
                m++
            }
            if (block.originalLines > 0 ||
                block.modifiedLines > 0
            ) {
                unit(block.originalStart, block.originalEnd, block.modifiedStart, block.modifiedEnd)
            }
            o = block.originalEnd
            m = block.modifiedEnd
        }
        return left to right
    }

    /**
     * The original's deleted lines as rows of the modified side, before each block's first modified
     * line, in the original's token colours over [background].
     */
    fun inline(
        blocks: List<DiffBlock>,
        original: CodeEditorState,
        modified: CodeDocument,
        tokenStyle: (TokenType) -> SpanStyle?,
        background: Color,
        tabSize: Int,
    ): Map<Int, List<Injection>> {
        val rows = HashMap<Int, MutableList<Injection>>()
        for (block in blocks) {
            if (block.originalLines == 0) continue
            val below = block.modifiedStart >= modified.lineCount
            val target = if (below) modified.lineCount - 1 else block.modifiedStart
            for (line in block.originalStart until block.originalEnd) {
                val (text, spans) = shownLine(original, line, tokenStyle, tabSize)
                rows.getOrPut(target) { ArrayList() } += if (below) {
                    Injection(
                        modified.lineEnd(target) - modified.lineStart(target),
                        "\n" + text,
                        background = background,
                        spans = spans.shifted(1),
                    )
                } else {
                    Injection(0, text + "\n", caretAfter = true, background = background, spans = spans)
                }
            }
        }
        return rows
    }

    private fun List<InjectedSpan>.shifted(by: Int) = map { it.copy(start = it.start + by, end = it.end + by) }

    private fun addFiller(into: HashMap<Int, MutableList<Injection>>, document: CodeDocument, before: Int, rows: Int, color: Color) {
        if (before >= document.lineCount) {
            val last = document.lineCount - 1
            into.getOrPut(last) { ArrayList() } +=
                Injection(document.lineEnd(last) - document.lineStart(last), "\n".repeat(rows), background = color)
        } else {
            into.getOrPut(before) { ArrayList() } += Injection(0, "\n".repeat(rows), caretAfter = true, background = color)
        }
    }

    /** [line] of [state] as one row of injected text, tabs as spaces to their stops, with its token colours. */
    private fun shownLine(
        state: CodeEditorState,
        line: Int,
        tokenStyle: (TokenType) -> SpanStyle?,
        tabSize: Int,
    ): Pair<String, List<InjectedSpan>> {
        val document = state.document
        val raw = document.subSequence(document.lineStart(line), document.lineEnd(line)).toString().take(EditorView.MAX_RENDERED_LINE_CHARS)
        // Where each raw column lands once tabs are spaces.
        val at = IntArray(raw.length + 1)
        val text = StringBuilder(raw.length)
        for ((i, char) in raw.withIndex()) {
            at[i] = text.length
            when {
                char == '\t' -> repeat(tabSize - text.length % tabSize) { text.append(' ') }
                char < ' ' -> text.append(' ')
                else -> text.append(char)
            }
        }
        at[raw.length] = text.length
        val tokens = if (state.exceedsAnalysisLimit) null else state.tokenStore.lineTokens(line)
        val spans = ArrayList<InjectedSpan>()
        if (tokens != null) {
            for (i in 0 until tokens.size) {
                val style = tokenStyle(tokens.types[i]) ?: continue
                val from = at[tokens.start(i).coerceIn(0, raw.length)]
                val to = at[tokens.end(i).coerceIn(0, raw.length)]
                if (to > from) spans += InjectedSpan(from, to, style)
            }
        }
        return text.toString() to spans
    }
}
