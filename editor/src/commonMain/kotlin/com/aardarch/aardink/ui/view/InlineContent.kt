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
import com.aardarch.aardink.core.Decoration
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
 * decorations, and the language service's inlay hints. [build] is where a new source adds to it.
 *
 * Each line is a lookup. The lines are worked out again after a change, in time proportional to
 * what there is to show and not to the document, and only when next asked: an edit marks them stale.
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

    /** A colour over several lines, kept whole: an edit must not cost a walk over all of them. */
    private data class WideColor(val firstLine: Int, val lastLine: Int, val start: Int, val end: Int, val color: Color)

    private val document: CodeDocument get() = state.document

    private var answer = Answer(emptyMap(), emptyList())
    private var stale = false

    private var hintStyle: SpanStyle? = null
    private var decorationStyle: SpanStyle? = null

    /** Whether no line has anything to show. */
    val isEmpty: Boolean
        get() = current().let { it.lines.isEmpty() && it.wide.isEmpty() }

    /** What [line] shows besides its text: what [EditorView.injections] is set to, and always this one function. */
    val lookup: (Int) -> LineInjections? = { line -> lineAnswer(line) }

    /** How a hint's label, and a decoration's text with no colour of its own, are drawn. */
    fun setStyles(hint: SpanStyle?, decoration: SpanStyle?) {
        if (hint == hintStyle && decoration == decorationStyle) return
        hintStyle = hint
        decorationStyle = decoration
        changed()
    }

    /** An edit moved what is shown, or dropped it all: it is worked out again when next asked for. */
    fun onDocumentChange() {
        if (state.hasInlineContent || answer.lines.isNotEmpty() || answer.wide.isNotEmpty()) stale = true
    }

    /**
     * What the state shows changed, other than by an edit. Compared with the last answer even if an
     * edit has left it stale: its lines may be out of step with the document, which at worst says a
     * line changed that did not, where working it out first would hide what did.
     */
    fun changed() {
        val before = answer
        val after = build()
        answer = after
        stale = false
        val changed = ArrayList<Int>()
        for ((line, shown) in after.lines) if (before.lines[line] != shown) changed += line
        for (line in before.lines.keys) if (line !in after.lines) changed += line
        if (changed.isNotEmpty() || before.wide != after.wide) onChanged(changed.sorted())
    }

    private fun current(): Answer {
        if (stale) {
            answer = build()
            stale = false
        }
        return answer
    }

    private fun lineAnswer(line: Int): LineInjections? {
        val shown = current()
        val own = shown.lines[line]
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
        for (hint in state.inlayHints) inject(lines, hint.offset, hint.label, hintStyle, caretAfter = false)
        return Answer(lines.mapValues { (_, line) -> LineInjections.of(line.items, line.colors) }, wide)
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
    private fun inject(lines: HashMap<Int, Pending>, offset: Int, text: String, style: SpanStyle?, caretAfter: Boolean) {
        if (text.isEmpty()) return
        val (line, column) = document.offsetToLineCol(offset)
        if (column > EditorView.MAX_RENDERED_LINE_CHARS) return
        lines.getOrPut(line) { Pending() }.items += Injection(column, oneRow(text), style, caretAfter)
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
    }
}
