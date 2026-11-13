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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.EarlyCodeLenses
import com.aardarch.aardink.core.EarlyInlayHints
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.LanguageService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

/** Columns [from, to) of a line's own text, drawn in [color]. */
@Immutable
internal data class ColumnColor(val from: Int, val to: Int, val color: Color)

/**
 * The lines a language service is asked for inlay hints about, each of [runs] one question, with
 * [folds] closed as they were. Lines between two runs are all hidden, and a run may hold hidden
 * lines too, where few are hidden between lines shown.
 */
@Immutable
internal data class HintWindow(val runs: List<IntRange>, val folds: FoldLayout = FoldLayout.None) {
    operator fun contains(line: Int): Boolean = runs.any { line in it }
}

/**
 * The one answer to what a line shows besides its document text, for [EditorView.injections],
 * merged from everything that has something to show: the text and colours of the host's
 * decorations, and the language service's inlay hints and code lenses. [build] is where a new source
 * adds to it.
 *
 * Each line is a lookup. The hints and decorations are worked out again after a change, in time
 * proportional to what there is to show and not to the document, and only when next asked: an edit
 * marks them stale. Lenses are about the whole document, so no edit works them all out: a line's row
 * is built when that line is asked about, from what [ShownLenses] finds on it.
 *
 * The state holds what is shown, and moves it with an edit through listeners it adds before any
 * view can add one. So when [EditorView] calls [onDocumentChange], first thing, it has all moved,
 * and the rows the view estimates next come from where it now is.
 *
 * @param onChanged told which lines' answers changed, sorted, other than by an edit moving them.
 *   Empty when only colours across several lines did, which no row count depends on.
 */
internal class InlineContent(private val state: CodeEditorState, private val onChanged: (lines: List<Int>) -> Unit) {

    private class Answer(val lines: Map<Int, LineInjections>, val wide: List<WideColor>)

    /**
     * The lenses as the last edit left them, and [order], their indices by offset. An edit moves
     * offsets but never past each other, so that order outlasts it: a line's lenses are a search,
     * and an edit costs this nothing however many there are.
     */
    private class ShownLenses(val all: List<CodeLens>, val order: IntArray) {
        constructor(all: List<CodeLens>) : this(all, all.indices.sortedBy { all[it].offset }.toIntArray())

        /** Those at offsets [start] to [end], in the order they were given. */
        fun within(start: Int, end: Int): List<CodeLens> {
            var first = 0
            var past = order.size
            while (first < past) {
                val middle = (first + past) / 2
                if (all[order[middle]].offset < start) first = middle + 1 else past = middle
            }
            past = first
            while (past < order.size && all[order[past]].offset <= end) past++
            if (past == first) return emptyList()
            return order.copyOfRange(first, past).sorted().map { all[it] }
        }

        /** What each line's row is made of: for what an answer changed, not for an edit. */
        fun titlesByLine(document: CodeDocument): Map<Int, List<String>> {
            val titles = HashMap<Int, ArrayList<String>>()
            for (lens in all) titles.getOrPut(document.offsetToLineCol(lens.offset).first) { ArrayList() } += lens.title
            return titles
        }
    }

    /** What a click on a lens is reported with: which of its line's lenses it is. By value, as the row is part of what a layout shows. */
    private data class LensSlot(val index: Int)

    /** A colour over several lines, kept whole: an edit must not cost a walk over all of them. */
    private data class WideColor(val firstLine: Int, val lastLine: Int, val start: Int, val end: Int, val color: Color)

    private val document: CodeDocument get() = state.document

    private var answer = Answer(emptyMap(), emptyList())
    private var stale = false

    private var lenses = ShownLenses(emptyList())

    /** The lines with a lens that were asked about since the last change, with all they show. */
    private var lensLines = HashMap<Int, LineInjections>()

    /** Lines with a lens worked out so far, for tests. */
    var lensLinesBuilt = 0
        private set

    private var hintStyle: SpanStyle? = null
    private var decorationStyle: SpanStyle? = null
    private var lensStyle: SpanStyle? = null
    private var ghostStyle: SpanStyle? = null

    /** Whether no line has anything to show. */
    val isEmpty: Boolean
        get() = lenses.all.isEmpty() && state.inlineCompletions.shown == null && current().let { it.lines.isEmpty() && it.wide.isEmpty() }

    /** What [line] shows besides its text: what [EditorView.injections] is set to, and always this one function. */
    val lookup: (Int) -> LineInjections? = { line -> lineAnswer(line) }

    /**
     * Columns between the tab stops a document's tabs are drawn at, or 0 where a tab is as wide as
     * the font draws it. Setting it does not say any line changed: the view measures all of them
     * anew when its style changes.
     */
    var tabColumns = 0
        set(value) {
            if (value == field) return
            field = value
            forgetLensLines()
        }

    /** How a hint's label, a decoration's text with no colour of its own, a lens's row and ghost text are drawn. */
    fun setStyles(hint: SpanStyle?, decoration: SpanStyle?, lens: SpanStyle? = null, ghost: SpanStyle? = null) {
        if (ghost != ghostStyle) {
            ghostStyle = ghost
            state.inlineCompletions.shown?.let { onChanged(listOf(it.line)) }
        }
        if (hint == hintStyle && decoration == decorationStyle && lens == lensStyle) return
        val restyled = lens != lensStyle
        hintStyle = hint
        decorationStyle = decoration
        lensStyle = lens
        refresh(restyled)
    }

    /** Whether [line] has a lens that has no title yet. */
    fun isWaiting(line: Int): Boolean = lensesOn(line).any { it.title.isBlank() }

    /** Injected text on [line] was clicked: what the lens it is part of does, if it is one. */
    fun clicked(line: Int, injection: Injection) {
        val slot = injection.id as? LensSlot ?: return
        lensesOn(line).filter { it.title.isNotBlank() }.getOrNull(slot.index)?.onClick?.invoke()
    }

    /** An edit moved what is shown, or dropped it all: it is worked out again when next asked for. */
    fun onDocumentChange() {
        if (state.hasInlineContent || answer.lines.isNotEmpty() || answer.wide.isNotEmpty()) stale = true
        forgetLensLines()
        val moved = state.codeLenses
        if (moved === lenses.all) return
        // Fewer: the edit took some with their text. Otherwise each is where it was in the list.
        lenses = if (moved.size == lenses.all.size) ShownLenses(moved, lenses.order) else ShownLenses(moved)
    }

    /**
     * What the state shows changed, other than by an edit. Compared with the last answer even if an
     * edit has left it stale: its lines may be out of step with the document, which at worst says a
     * line changed that did not, where working it out first would hide what did.
     */
    fun changed() = refresh(lensesRestyled = false)

    private fun refresh(lensesRestyled: Boolean) {
        val before = answer
        val after = build()
        answer = after
        stale = false
        forgetLensLines()
        val changed = HashSet<Int>()
        for ((line, shown) in after.lines) if (before.lines[line] != shown) changed += line
        for (line in before.lines.keys) if (line !in after.lines) changed += line
        if (lensesRestyled || state.codeLenses !== lenses.all) changed += takeLenses(lensesRestyled)
        if (changed.isNotEmpty() || before.wide != after.wide) onChanged(changed.sorted())
    }

    /** Shows the state's lenses, and says which lines that changes: all that have one, if [restyled]. */
    private fun takeLenses(restyled: Boolean): Set<Int> {
        val before = lenses.titlesByLine(document)
        lenses = ShownLenses(state.codeLenses)
        val after = lenses.titlesByLine(document)
        if (restyled) return before.keys + after.keys
        return (before.keys + after.keys).filterTo(HashSet()) { before[it] != after[it] }
    }

    private fun forgetLensLines() {
        if (lensLines.isNotEmpty()) lensLines = HashMap()
    }

    private fun current(): Answer {
        if (stale) {
            answer = build()
            stale = false
        }
        return answer
    }

    private fun lensesOn(line: Int): List<CodeLens> {
        if (lenses.all.isEmpty() || line !in 0 until document.lineCount) return emptyList()
        return lenses.within(document.lineStart(line), document.lineEnd(line))
    }

    /**
     * What [line] shows, [others] being all but its lenses. The row comes first: it is at column 0,
     * ahead of anything else at that column, and first to what a line can show, so that no piece of
     * it is dropped.
     */
    private fun withLenses(line: Int, others: LineInjections?): LineInjections? {
        lensLines[line]?.let { return it }
        val onLine = lensesOn(line)
        if (onLine.isEmpty()) return others
        lensLinesBuilt++
        val row = lensRow(line, onLine.filter { it.title.isNotBlank() })
        return LineInjections.of(row + others?.items.orEmpty(), others?.colors.orEmpty()).also { lensLines[line] = it }
    }

    /**
     * What [line] shows, [others] being all but its ghost text. That comes first among what is at its
     * column, so that it follows the caret straight away, with a hint there after it.
     */
    private fun withGhost(line: Int, others: LineInjections?): LineInjections? {
        val ghost = state.inlineCompletions.shown?.takeIf { it.line == line } ?: return others
        val lineStart = document.lineStart(line)
        val items = ghost.pieces.map { piece ->
            val column = piece.offset - lineStart
            Injection(column, ghostRows(piece.text, column), ghostStyle)
        }
        return LineInjections.of(items + others?.items.orEmpty(), others?.colors.orEmpty())
    }

    /**
     * [text] from [column] on as a layout can show it: an injected tab gets no stop of its own, so
     * each is the spaces to its stop, and the line breaks stay, for the rows they add.
     */
    private fun ghostRows(text: String, column: Int): String {
        if (text.none { it < ' ' && it != '\n' }) return text
        return buildString(text.length) {
            var at = column
            for (char in text) {
                when {
                    char == '\n' -> {
                        append(char)
                        at = -1
                    }

                    char == '\t' && tabColumns > 0 -> {
                        val width = tabColumns - at % tabColumns
                        repeat(width) { append(' ') }
                        at += width - 1
                    }

                    char == '\t' -> append(char)

                    else -> append(if (char < ' ') ' ' else char)
                }
                at++
            }
        }
    }

    private fun lineAnswer(line: Int): LineInjections? {
        val shown = current()
        val own = withGhost(line, withLenses(line, shown.lines[line]))
        if (shown.wide.isEmpty()) return own
        val lineStart = document.lineStart(line)
        val lineEnd = document.lineEnd(line)
        val colors = shown.wide
            .filter { line in it.firstLine..it.lastLine }
            .map { ColumnColor(max(it.start, lineStart) - lineStart, min(it.end, lineEnd) - lineStart, it.color) }
            .filter { it.to > it.from }
        if (colors.isEmpty()) return own
        return LineInjections.of(own?.items.orEmpty(), own?.colors.orEmpty() + colors)
    }

    private class Pending {
        val items = ArrayList<Injection>()
        val colors = ArrayList<ColumnColor>()
    }

    /** Every source's contribution, by line. The order at one column is this order. */
    private fun build(): Answer {
        val lines = HashMap<Int, Pending>()
        val wide = ArrayList<WideColor>()
        state.forEachInlineDecoration { addDecoration(it, lines, wide) }
        for (hint in state.inlayHints) inject(lines, hint.offset, hint.label, hintStyle, caretAfter = false, tooltip = hint.tooltip)
        return Answer(lines.mapValues { (_, line) -> LineInjections.of(line.items, line.colors) }, wide)
    }

    /**
     * The row above [line], with [lenses] on it side by side as VS Code has them, and starting where
     * the line's text does. The titles fill what a row may hold. None at all is an empty row, which
     * holds the place of lenses that have no title yet.
     */
    private fun lensRow(line: Int, lenses: List<CodeLens>): List<Injection> {
        val row = ArrayList<Injection>(lenses.size * 2 + 1)
        val indent = lensIndent(line)
        var room = MAX_LENS_ROW_CHARS - indent.length
        if (indent.isNotEmpty()) row += Injection.leadingRowAbove(indent, lensStyle)
        for ((index, lens) in lenses.withIndex()) {
            val separator = if (index > 0) LENS_SEPARATOR else ""
            if (room <= separator.length) break
            if (index > 0) row += Injection.leadingRowAbove(separator, lensStyle)
            val title = oneRow(lens.title).take(room - separator.length)
            row += Injection.leadingRowAbove(title, lensStyle, LensSlot(index))
            room -= separator.length + title.length
        }
        if (lenses.isEmpty()) return listOf(Injection.rowAbove("", lensStyle))
        row[row.lastIndex] = row.last().copy(text = row.last().text + "\n")
        return row
    }

    /**
     * What makes a row above [line] start where its text does: its whitespace, with each tab as the
     * spaces to its stop, as an injected tab gets no stop of its own and would be drawn about a
     * character wide. Where tabs have no stops ([tabColumns]) the line's are as narrow, and stay.
     */
    private fun lensIndent(line: Int): String {
        val end = document.lineEnd(line)
        var at = document.lineStart(line)
        val indent = StringBuilder()
        while (at < end && indent.length < MAX_LENS_INDENT) {
            when (document[at++]) {
                ' ' -> indent.append(' ')
                '\t' -> if (tabColumns > 0) repeat(tabColumns - indent.length % tabColumns) { indent.append(' ') } else indent.append('\t')
                else -> break
            }
        }
        return indent.take(MAX_LENS_INDENT).toString()
    }

    private fun addDecoration(decoration: Decoration, lines: HashMap<Int, Pending>, wide: ArrayList<WideColor>) {
        val style = decoration.style
        val range = decoration.range
        val start = range.first
        val end = if (range.isEmpty()) start else range.last + 1
        val color = style.inlineColor
        val textStyle = if (color != Color.Unspecified) SpanStyle(color = color) else decorationStyle
        // Text typed at a range's start moves it along, and at its end stays out of it, so each
        // text has the caret on the side where what is typed will land. An empty range moves along
        // as a whole, with both texts in front of the caret's place.
        style.before?.let { inject(lines, start, it, textStyle, caretAfter = false) }
        style.after?.let { inject(lines, end, it, textStyle, caretAfter = !range.isEmpty()) }
        if (color == Color.Unspecified || end <= start) return
        val (firstLine, firstColumn) = document.offsetToLineCol(start)
        val (lastLine, lastColumn) = document.offsetToLineCol(end)
        if (firstLine == lastLine) {
            lines.getOrPut(firstLine) { Pending() }.colors += ColumnColor(firstColumn, lastColumn, color)
        } else {
            wide += WideColor(firstLine, lastLine, start, end, color)
        }
    }

    /**
     * [text] at [offset], on one row. Columns come from offsets, so none is past its line's end, and
     * what is past the column a long line is cut at is dropped here, for the row estimates.
     */
    private fun inject(
        lines: HashMap<Int, Pending>,
        offset: Int,
        text: String,
        style: SpanStyle?,
        caretAfter: Boolean,
        tooltip: String? = null,
    ) {
        if (text.isEmpty()) return
        val (line, column) = document.offsetToLineCol(offset)
        if (column > EditorView.MAX_RENDERED_LINE_CHARS) return
        lines.getOrPut(line) { Pending() }.items += Injection(column, oneRow(text), style, caretAfter, tooltip = tooltip)
    }

    private fun oneRow(text: String): String = if (text.none { it < ' ' }) {
        text
    } else {
        buildString(text.length) {
            for (char in text) append(if (char < ' ') ' ' else char)
        }
    }

    companion object {
        /** Most runs in a window: each may hand the whole document to a provider, and a server takes them one by one. */
        private const val MAX_HINT_RUNS = 8

        /** How long an answer in part waits for the rest before it is shown, which takes off what the rest would have kept. */
        private const val HINT_PATIENCE_MS = 1_000L

        /** Between two lenses on a row, as VS Code has it. */
        private const val LENS_SEPARATOR = " | "

        /** Most of a row of lenses, so that one row can never take all a line may show. */
        private const val MAX_LENS_ROW_CHARS = 1_000

        /** Most of a line's indentation that a row of lenses keeps: past that it would mostly wrap, on a phone. */
        private const val MAX_LENS_INDENT = 40

        /**
         * The lines to ask about, with [visible] on screen, [rows] of them shown, [folds] closed and
         * [lineCount] lines in all: [asked] while it holds them and the folds are as they were, so
         * that scrolling within what was asked about asks nothing, or else them and a screen of
         * shown lines either side.
         *
         * A closed fold puts many lines in [visible], and what it hides is not worth asking about:
         * a fold of more than [rows] lines parts the window in two. So however much folds hide, what
         * is asked about stays a matter of [rows] and not of the document. A screen so full of long
         * folds that that makes too many runs is asked about without its margin, from the top, and
         * the lines past the last run wait for a scroll.
         */
        fun hintWindow(
            asked: HintWindow?,
            visible: IntRange,
            rows: Int,
            folds: FoldLayout = FoldLayout.None,
            lineCount: Int = Int.MAX_VALUE,
        ): HintWindow {
            if (asked != null && asked.folds === folds && visible.first in asked && visible.last in asked) return asked
            var first = visible.first
            var last = visible.last
            repeat(rows) {
                first = folds.shownBefore(first)
                last = folds.shownAfter(last)
            }
            last = last.coerceAtMost(lineCount - 1).coerceAtLeast(visible.last)
            val runs = shownRuns(first..last, rows, folds)
            return HintWindow(if (runs.size <= MAX_HINT_RUNS) runs else shownRuns(visible, rows, folds).take(MAX_HINT_RUNS), folds)
        }

        /** [lines], parted wherever [folds] hides more than [gap] lines in a row. */
        private fun shownRuns(lines: IntRange, gap: Int, folds: FoldLayout): List<IntRange> {
            val runs = ArrayList<IntRange>()
            var start = lines.first
            var line = lines.first
            while (line < lines.last) {
                val next = folds.shownAfter(line)
                if (next > lines.last) break
                if (next - line - 1 > gap) {
                    runs += start..line
                    start = next
                }
                line = next
            }
            runs += start..line
            return runs
        }

        /** Lines [lines] of [document] as the range a language service is asked about. */
        fun hintRange(document: CodeDocument, lines: IntRange): IntRange =
            document.lineStart(lines.first) until document.lineEnd(lines.last)

        /**
         * What of [answer] [window] of [document] can show: not what is outside it, on a line a fold
         * hides, or nothing to see. So what is kept, and moved with each edit, goes by what is shown.
         */
        fun usableHints(answer: List<InlayHint>, document: CodeDocument, window: HintWindow): List<InlayHint> = answer.filter {
            if (it.label.isBlank() || it.offset !in 0..document.length) return@filter false
            val line = document.offsetToLineCol(it.offset).first
            line in window && !window.folds.isHidden(line)
        }

        /**
         * [service]'s answer about [window] of [document], its runs asked at once on [dispatcher].
         *
         * What it offers early ([EarlyInlayHints]) goes to [showEarly] if the rest is still not in
         * after [HINT_PATIENCE_MS]. Not before, as that replaces the hints on show: an answer in two
         * steps on every edit would take a server's hints off the screen and put them back each time.
         */
        suspend fun askForHints(
            service: LanguageService,
            document: CodeDocument,
            window: HintWindow,
            dispatcher: CoroutineContext,
            showEarly: (List<InlayHint>) -> Unit,
        ): List<InlayHint> = coroutineScope {
            val offered = MutableStateFlow(emptyMap<IntRange, List<InlayHint>>())
            val showing = launch {
                offered.collectLatest { soFar ->
                    if (soFar.isEmpty()) return@collectLatest
                    delay(HINT_PATIENCE_MS)
                    showEarly(soFar.values.flatten().distinct())
                }
            }
            val answers = window.runs.map { lines ->
                val early = EarlyInlayHints { hints -> offered.update { it + (lines to hints) } }
                async(dispatcher + early) { service.inlayHints(document, hintRange(document, lines)) }
            }.awaitAll()
            showing.cancel()
            answers.flatten().distinct()
        }

        /**
         * [service]'s answer about [document], on [dispatcher]. What it offers early
         * ([EarlyCodeLenses]) goes to [showEarly] as with hints: only if the rest is still not in
         * after [HINT_PATIENCE_MS].
         */
        suspend fun askForLenses(
            service: LanguageService,
            document: CodeDocument,
            dispatcher: CoroutineContext,
            showEarly: (List<CodeLens>) -> Unit,
        ): List<CodeLens> = coroutineScope {
            val offered = MutableStateFlow<List<CodeLens>?>(null)
            val showing = launch {
                offered.collectLatest { soFar ->
                    if (soFar == null) return@collectLatest
                    delay(HINT_PATIENCE_MS)
                    showEarly(soFar)
                }
            }
            val answer = withContext(dispatcher + EarlyCodeLenses { offered.value = it }) { service.codeLenses(document) }
            showing.cancel()
            answer
        }
    }
}
