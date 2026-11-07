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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.TokenStore
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.core.text.DocumentChange
import com.aardarch.aardink.core.text.DocumentChangeListener
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The pixel sizes the view is built from. */
@Immutable
internal data class EditorMetrics(
    val lineHeight: Float,
    val charWidth: Float,
    val paddingTop: Float,
    val paddingBottom: Float,
    val paddingStart: Float,
    val paddingEnd: Float,
) {
    companion object {
        val Zero = EditorMetrics(1f, 1f, 0f, 0f, 0f, 0f)
    }
}

/**
 * Everything a line's layout depends on besides its text and tokens: how text is measured and
 * styled, the colours tokens get, and whether lines wrap. A different value empties the layout
 * cache and rebuilds the row map.
 */
@Immutable
internal data class ViewStyle(
    val measurer: TextMeasurer,
    val textStyle: TextStyle,
    /** What a token of a type is drawn with (colour and font style), or null for plain text. */
    val tokenStyle: (TokenType) -> SpanStyle?,
    val placeholderStyle: SpanStyle,
    val metrics: EditorMetrics,
    val softWrap: Boolean,
    /** Bracket colours by nesting depth, repeating; null leaves brackets their token colour. */
    val bracketStyles: List<SpanStyle>? = null,
    /** Columns between tab stops: a tab is as wide as the columns up to the next one. */
    val tabSize: Int = 4,
    /** A column's width in em of the text's font size, for a tab's width; 0 leaves tabs as the font draws them. */
    val columnWidthEm: Float = 0f,
    /**
     * Whether a wrapped line's indentation is laid out as invisible glyphs ([INDENT_GLYPH]) rather
     * than spaces, so the first word stays on the first row. Only for a font that draws the
     * glyph as wide as a space: a monospace one.
     */
    val joinIndentation: Boolean = false,
)

/**
 * What a wrapped line's leading spaces and tabs are measured as. Android's line breaker starts
 * a word after any white space, and a placeholder is a break opportunity, so a first word that
 * does not fit after the indentation would be moved to the next row, leaving a row of nothing
 * but indentation. A tilde is a letter to the breaker, neutral to bidi as a space is, in every
 * font, and drawn transparent; a tab is one of them with letter spacing for its other columns.
 */
internal const val INDENT_GLYPH = '~'

/** One line on screen: its layout, and where it starts in the document and in the content. */
@Immutable
internal class VisibleLine(
    val line: Int,
    /** Document offset of the line's first character. */
    val start: Int,
    /** Characters on the line, without its line break. */
    val length: Int,
    /** Characters laid out: [length], cut at [MAX_RENDERED_LINE_CHARS]. */
    val shownLength: Int,
    /** Content y of the line's first row. */
    val top: Float,
    val layout: LineLayout,
    /** The fold that starts on this line and is closed, drawn as a placeholder after the text. */
    val fold: FoldRange?,
    /** The [shownLength] characters as the document has them; the layout's text may stand in for some. */
    val text: String,
) {
    val end: Int get() = start + length
}

/**
 * What the last layout pass put on screen. The gutter, the text and the caret overlay all draw
 * from the same frame, so they cannot disagree about where a line is.
 */
@Immutable
internal class ViewFrame(
    val lines: List<VisibleLine>,
    val scrollX: Float,
    val scrollY: Float,
    val width: Int,
    val height: Int,
    val metrics: EditorMetrics,
    val lineCount: Int,
) {
    /** The visible line for document [line], or null when it is not on screen. */
    fun find(line: Int): VisibleLine? {
        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val candidate = lines[mid].line
            when {
                candidate < line -> lo = mid + 1
                candidate > line -> hi = mid - 1
                else -> return lines[mid]
            }
        }
        return null
    }

    /** Viewport x of the start of every line's text. */
    val textLeft: Float get() = metrics.paddingStart - scrollX

    companion object {
        val Empty = ViewFrame(emptyList(), 0f, 0f, 0, 0, EditorMetrics.Zero, 0)
    }
}

/** Where a point in the viewport falls in the document. */
internal class ViewHit(
    val offset: Int,
    val line: Int,
    val onFoldPlaceholder: Boolean,
    /** The injected text under the point; [offset] is then the column it is at. */
    val injection: Injection? = null,
)

/**
 * The closed folds, ready for the questions the view asks per line: is it hidden, and does a fold
 * start here. Nested folds inside a closed one, and folds that no longer fit the document, are
 * dropped, as the text-field renderer did.
 */
internal class FoldLayout private constructor(private val folds: List<FoldRange>) {

    val isEmpty: Boolean get() = folds.isEmpty()

    /** Whether [line] is inside a closed fold (its first line stays visible). */
    fun isHidden(line: Int): Boolean {
        val index = lastStartingAtOrBefore(line)
        return index >= 0 && line > folds[index].startLine && line <= folds[index].endLine
    }

    /** The closed fold whose first line is [line]. */
    fun startingAt(line: Int): FoldRange? {
        val index = lastStartingAtOrBefore(line)
        return if (index >= 0 && folds[index].startLine == line) folds[index] else null
    }

    /** The next line down from [line], itself not hidden, that is not hidden. */
    fun shownAfter(line: Int): Int = (startingAt(line)?.endLine ?: line) + 1

    /** The next line up from [line], itself not hidden, that is not hidden, or the first line. */
    fun shownBefore(line: Int): Int {
        val above = (line - 1).coerceAtLeast(0)
        return if (isHidden(above)) folds[lastStartingAtOrBefore(above)].startLine else above
    }

    private fun lastStartingAtOrBefore(line: Int): Int {
        var lo = 0
        var hi = folds.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (folds[mid].startLine <= line) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    override fun equals(other: Any?): Boolean = other is FoldLayout && other.folds == folds

    override fun hashCode(): Int = folds.hashCode()

    companion object {
        val None = FoldLayout(emptyList())

        fun of(folded: List<FoldRange>, lineCount: Int): FoldLayout {
            if (folded.isEmpty()) return None
            val kept = ArrayList<FoldRange>(folded.size)
            for (fold in folded.sortedBy { it.startLine }) {
                val end = fold.endLine.coerceAtMost(lineCount - 1)
                if (fold.startLine < 0 || end <= fold.startLine) continue
                val previous = kept.lastOrNull()
                if (previous != null && fold.startLine <= previous.endLine) continue
                kept.add(if (end == fold.endLine) fold else fold.copy(endLine = end))
            }
            return if (kept.isEmpty()) None else FoldLayout(kept)
        }
    }
}

/**
 * The editor's view of its document: which rows each line takes ([lineMap]), where the view is
 * scrolled to ([scroll]), and the laid-out lines on screen ([frame]).
 *
 * [prepare] runs in the layout pass: it lays out just the lines on screen (from a cache of recent
 * ones), corrects wrapped row counts as it goes, and keeps what is on screen in place when rows
 * above it change. Nothing here walks the whole document per frame or per keystroke; only a change
 * of folds, wrap width or style rebuilds the row map, in O(lines).
 *
 * Listens to the document while [attach]ed, to keep the row map and the folds in step with edits.
 */
@Stable
internal class EditorView(val state: CodeEditorState) : DocumentChangeListener {

    val scroll = EditorScrollController()

    internal val lineMap = VisualLineMap()

    private val layouts = LineLayoutCache<LineLayout>()

    private val document get() = state.document

    private var styleState by mutableStateOf<ViewStyle?>(null)

    /** How lines are measured and styled; set from composition. Read by the layout pass. */
    var style: ViewStyle?
        get() = styleState
        set(value) {
            if (value == styleState) return
            styleState = value
            inline.tabColumns = if (value != null && value.columnWidthEm > 0f) value.tabSize else 0
            layouts.clear()
            structureDirty = true
        }

    /** The host's fold state; its closed folds hide lines. */
    var foldState: FoldState? = null

    /** Counts changes to what [injections] answers, for what is kept by row. */
    var injectionVersion by mutableIntStateOf(0)
        private set

    /**
     * What a line shows besides its text, asked for per line and on demand, so it must answer
     * from a lookup; null when nothing is ever injected. Setting it rebuilds the row map; when
     * only some lines change, say which with [injectionsChanged].
     */
    var injections: ((Int) -> LineInjections?)? = null
        set(value) {
            if (value === field) return
            field = value
            structureDirty = true
            injectionVersion++
        }

    /** The decorations' text and colours, the inlay hints and the code lenses, which [injections] answers from once there are any. */
    val inline = InlineContent(state, ::inlineChanged)

    private val inlineSourceChanged: () -> Unit = inline::changed
    private val ghostChanged: (List<Int>) -> Unit = ::inlineChanged

    /** A click on injected text with an [Injection.id], on a line. */
    var onInjectionClick: ((line: Int, injection: Injection) -> Unit)? = inline::clicked

    private var folds = FoldLayout.None
    private var wrapWidth = 0
    private var structureDirty = true
    private var maxLineChars = 0
    private var widestMeasured = 0f
    private var contentWidth = 0f

    // What stays in place when rows above it change: a line, and how far into it the view starts.
    private var anchorLine = 0
    private var anchorOffset = 0f
    private var restoreAnchor = false

    private var revealTarget = -1
    private var revealCenter = false
    private var revealRequests by mutableIntStateOf(0)

    /** What the last layout pass put on screen. */
    var frame by mutableStateOf(ViewFrame.Empty)
        private set

    fun attach() {
        document.addChangeListener(this)
        state.inlineListeners += inlineSourceChanged
        state.ghostListeners += ghostChanged
        inline.changed()
        structureDirty = true
    }

    fun detach() {
        document.removeChangeListener(this)
        state.inlineListeners -= inlineSourceChanged
        state.ghostListeners -= ghostChanged
    }

    /**
     * What [inline] answers for [lines] changed. A view with nothing inline has no [injections], and
     * so costs what one without the feature does, until the first thing to show arrives. That leaves
     * the row map as it is, unlike setting [injections] to anything else: every other line still
     * answers nothing, and a rebuild would lose the rows measured so far for estimates.
     */
    private fun inlineChanged(lines: List<Int>) {
        if (injections == null) {
            if (inline.isEmpty) return
            val rowsStand = !structureDirty
            injections = inline.lookup
            if (rowsStand) structureDirty = false
        }
        injectionsChanged(lines)
    }

    /** The folds the view is showing, for the gutter. */
    val foldLayout: FoldLayout get() = folds

    override fun onDocumentChange(change: DocumentChange) {
        // First: the rows below are estimated from what it answers.
        inline.onDocumentChange()
        if (change.lineDelta != 0 && !change.isReset) {
            foldState?.onLinesChanged(change.startLine, change.removedLines, change.addedLines)
            folds = FoldLayout.of(foldState?.foldedRanges().orEmpty(), document.lineCount)
        }
        restoreAnchor = true
        if (change.isReset || lineMap.lineCount == 0 || structureDirty) {
            structureDirty = true
            return
        }
        // The text above the anchor changed line count: the anchor line moves with its text.
        if (change.startLine < anchorLine) {
            anchorLine = if (anchorLine <= change.startLine + change.removedLines) change.startLine else anchorLine + change.lineDelta
        }
        if (change.lineDelta == 0) {
            // A line on screen gets its measured row count in the next layout pass.
            if ((style?.softWrap == true || injections != null) && frame.find(change.startLine) == null) {
                lineMap.setRows(change.startLine, estimateRows(change.startLine))
            }
        } else {
            lineMap.splice(change.startLine, change.removedLines, change.addedLines) { estimateRows(it) }
        }
        for (line in change.startLine..(change.startLine + change.addedLines).coerceAtMost(document.lineCount - 1)) {
            maxLineChars = max(maxLineChars, lineLength(line))
        }
    }

    /**
     * What [injections] answers for lines [firstLine] to [lastLine] changed. Their rows are
     * estimated without laying them out, and what is on screen stays where it is. A line on
     * screen is left to the next layout pass, which measures it: an estimate there would move
     * the text for a frame.
     *
     * The scroll position moves with the rows here and now, from where it is now: an answer often
     * lands between a scroll and its layout pass, or just before the next tick of a fling, and
     * neither should be undone by what the last pass saw.
     */
    fun injectionsChanged(firstLine: Int = 0, lastLine: Int = document.lineCount - 1) = injectionsChanged(firstLine..lastLine)

    /** As above, for [lines] that need not be next to each other. With none, only what is on screen is laid out again. */
    fun injectionsChanged(lines: Iterable<Int>) {
        injectionVersion++
        if (structureDirty || lineMap.lineCount == 0) return
        val metrics = style?.metrics ?: return
        val keepInPlace = !restoreAnchor
        if (keepInPlace) captureAnchor(scroll.scrollY, metrics)
        // A scroll since the last pass has left its frame: what that showed will not be measured.
        val measured = if (frame.scrollY == scroll.scrollY) frame else ViewFrame.Empty
        for (line in lines) {
            if (line in 0 until lineMap.lineCount && measured.find(line) == null) lineMap.setRows(line, estimateRows(line))
        }
        if (keepInPlace) {
            scroll.contentHeight = rowsHeight(metrics)
            scroll.placeY(anchorY(metrics))
        }
    }

    /** Counts [line]'s rows from its layout now, for a move that cannot wait for the layout pass to do so. */
    fun measureRows(line: Int) {
        if (style != null && line in 0 until lineMap.lineCount) lineMap.setRows(line, layoutFor(line).lineCount)
    }

    /**
     * Scrolls [offset] into view in the next layout pass: the least distance, or with [center] to
     * the middle of the view when it is not on screen already.
     */
    fun requestReveal(offset: Int, center: Boolean = false) {
        revealTarget = offset
        revealCenter = center
        revealRequests++
    }

    /** Rows that fit in the viewport. */
    val visibleRows: Int
        get() {
            val lineHeight = style?.metrics?.lineHeight ?: return 1
            return (scroll.viewportHeight / lineHeight).toInt().coerceAtLeast(1)
        }

    /**
     * Lays out the lines on screen for a viewport of [width] × [height] and publishes them as
     * [frame]. Runs in the layout pass; everything it reads that can change (text, tokens, folds,
     * scroll position) makes the pass run again.
     */
    fun prepare(width: Int, height: Int) {
        val style = style ?: return
        val metrics = style.metrics
        val lineHeight = metrics.lineHeight
        // Reads the pass depends on.
        state.textVersion
        state.tokenVersion
        state.semanticVersion
        revealRequests
        injectionVersion
        val folded = foldState?.foldedRanges().orEmpty()
        var scrollY = scroll.scrollY
        var scrollX = scroll.scrollX
        val lineCount = document.lineCount

        if (scroll.viewportWidth != width) scroll.viewportWidth = width
        if (scroll.viewportHeight != height) scroll.viewportHeight = height

        val wrap = if (style.softWrap) {
            max(
                (width - metrics.paddingStart - metrics.paddingEnd).toInt(),
                metrics.charWidth.toInt().coerceAtLeast(1),
            )
        } else {
            0
        }
        if (wrap != wrapWidth) {
            wrapWidth = wrap
            layouts.clear()
            structureDirty = true
        }
        val newFolds = FoldLayout.of(folded, lineCount)
        if (newFolds != folds) {
            folds = newFolds
            structureDirty = true
        }
        if (structureDirty || lineMap.lineCount != lineCount) {
            if (!restoreAnchor) captureAnchor(scrollY, metrics)
            lineMap.reset(lineCount) { estimateRows(it) }
            maxLineChars = longestLine()
            structureDirty = false
            restoreAnchor = true
        }
        if (restoreAnchor) {
            scrollY = anchorY(metrics)
            restoreAnchor = false
        }
        var contentHeight = rowsHeight(metrics)
        scroll.contentHeight = contentHeight

        if (revealTarget >= 0) {
            val target = revealTarget.coerceIn(0, document.length)
            revealTarget = -1
            val rect = contentCaretRect(target, metrics)
            val maxY = (contentHeight - height).coerceAtLeast(0f)
            val visible = rect.top >= scrollY && rect.bottom <= scrollY + height
            scrollY = if (revealCenter && !visible) {
                (rect.center.y - height / 2f).coerceIn(0f, maxY)
            } else {
                val margin = lineHeight.coerceAtMost(((height - rect.height) / 2f).coerceAtLeast(0f))
                when {
                    rect.top - margin < scrollY -> (rect.top - margin).coerceIn(0f, maxY)
                    rect.bottom + margin > scrollY + height -> (rect.bottom + margin - height).coerceIn(0f, maxY)
                    else -> scrollY
                }
            }
            if (!style.softWrap && width > 0) {
                val marginX = (metrics.charWidth * 4).coerceAtMost(width / 3f)
                val left = rect.left - marginX
                val right = rect.right + marginX
                scrollX = when {
                    left < scrollX -> left.coerceAtLeast(0f)
                    right > scrollX + width -> right - width
                    else -> scrollX
                }
            }
        }
        scrollY = scrollY.coerceIn(0f, (contentHeight - height).coerceAtLeast(0f))

        val visible = ArrayList<VisibleLine>()
        if (lineCount > 0 && lineMap.totalRows > 0) {
            val firstRow = floor((scrollY - metrics.paddingTop) / lineHeight).toInt().coerceIn(0, lineMap.totalRows - 1)
            var line = lineMap.lineAtRow(firstRow)
            while (line < lineCount) {
                if (lineMap.isHidden(line)) {
                    // Straight past the hidden run: the next row belongs to the next shown line.
                    val next = lineMap.lineAtRow(lineMap.firstRowOf(line))
                    if (next <= line) break
                    line = next
                    continue
                }
                val top = contentTop(line, metrics)
                if (top > scrollY + height) break
                val text = lineText(line)
                val layout = layoutFor(line, text)
                if (style.softWrap || injections != null) {
                    val delta = lineMap.setRows(line, layout.lineCount)
                    // A line starting above the view that turned out taller or shorter: keep the
                    // rows below it where they were.
                    if (delta != 0 && top < scrollY) scrollY += delta * lineHeight
                }
                if (!style.softWrap) widestMeasured = max(widestMeasured, layout.size.width.toFloat())
                val start = document.lineStart(line)
                val length = lineLength(line)
                visible.add(
                    VisibleLine(line, start, length, min(length, MAX_RENDERED_LINE_CHARS), top, layout, folds.startingAt(line), text),
                )
                line++
            }
            contentHeight = rowsHeight(metrics)
            scroll.contentHeight = contentHeight
        }

        contentWidth = if (style.softWrap) {
            0f
        } else {
            max(
                contentWidth,
                metrics.paddingStart + max(maxLineChars * metrics.charWidth, widestMeasured) + metrics.paddingEnd + metrics.charWidth,
            )
        }
        if (scroll.contentWidth != contentWidth) scroll.contentWidth = contentWidth
        scrollX = if (style.softWrap) 0f else scrollX.coerceIn(0f, (contentWidth - width).coerceAtLeast(0f))
        if (scrollY != scroll.scrollY) scroll.placeY(scrollY)
        if (scrollX != scroll.scrollX) scroll.placeX(scrollX)
        captureAnchor(scrollY, metrics)

        if (visible.isNotEmpty()) {
            val range = visible.first().line..visible.last().line
            if (state.visibleLines != range) state.visibleLines = range
        }
        frame = ViewFrame(visible, scrollX, scrollY, width, height, metrics, lineCount)
    }

    private fun captureAnchor(scrollY: Float, metrics: EditorMetrics) {
        if (lineMap.lineCount == 0 || lineMap.totalRows == 0) {
            // Nothing laid out yet: the first line will start below the top padding.
            anchorLine = 0
            anchorOffset = scrollY - metrics.paddingTop
            return
        }
        val row = floor((scrollY - metrics.paddingTop) / metrics.lineHeight).toInt().coerceIn(0, lineMap.totalRows - 1)
        anchorLine = lineMap.lineAtRow(row)
        anchorOffset = scrollY - contentTop(anchorLine, metrics)
    }

    /** The scroll position that puts the anchor back where it was. */
    private fun anchorY(metrics: EditorMetrics): Float {
        val line = lineMap.visibleLineAtOrBefore(anchorLine.coerceIn(0, lineMap.lineCount - 1))
        return contentTop(line, metrics) + (if (line == anchorLine) anchorOffset else 0f)
    }

    /** The height of every row, with the padding around them. */
    private fun rowsHeight(metrics: EditorMetrics): Float =
        metrics.paddingTop + lineMap.totalRows * metrics.lineHeight + metrics.paddingBottom

    /** Content y of [line]'s first row. */
    fun contentTop(line: Int, metrics: EditorMetrics = style?.metrics ?: EditorMetrics.Zero): Float =
        metrics.paddingTop + lineMap.firstRowOf(line) * metrics.lineHeight

    /** Content y of the first row of [line]'s text: below the rows injected above it. */
    fun textTop(line: Int, metrics: EditorMetrics = style?.metrics ?: EditorMetrics.Zero): Float {
        val rows = if (injections == null) 0 else layoutFor(line).textRow
        return contentTop(line, metrics) + rows * metrics.lineHeight
    }

    /** The rows injected above [line]'s text, known without laying it out; a row that wraps counts once. */
    fun rowsAbove(line: Int): Int = injections?.invoke(line)?.rowsAbove ?: 0

    private fun lineLength(line: Int): Int = document.lineEnd(line) - document.lineStart(line)

    private fun longestLine(): Int {
        var longest = 0
        for (line in 0 until document.lineCount) longest = max(longest, lineLength(line))
        return longest
    }

    private fun estimateRows(line: Int): Int {
        if (folds.isHidden(line)) return 0
        val style = style
        val injected = injections?.invoke(line)
        val added = injected?.addedRows ?: 0
        if (style == null || !style.softWrap || wrapWidth <= 0) return 1 + added
        val placeholder = folds.startingAt(line)?.let { it.placeholder.length + 1 } ?: 0
        val chars = min(lineLength(line), MAX_RENDERED_LINE_CHARS) + placeholder + (injected?.inlineChars ?: 0)
        // A row above that is wider than the view takes more rows than the break that makes it.
        val wrappedAbove = ceil((injected?.charsAbove ?: 0) * style.metrics.charWidth / wrapWidth).toInt() - (injected?.rowsAbove ?: 0)
        return max(1, ceil(chars * style.metrics.charWidth / wrapWidth).toInt()) + added + max(0, wrappedAbove)
    }

    /** The characters of [line] that are laid out: its text, cut at [MAX_RENDERED_LINE_CHARS]. */
    private fun lineText(line: Int): String {
        val start = document.lineStart(line)
        val shown = min(lineLength(line), MAX_RENDERED_LINE_CHARS)
        return document.subSequence(start, start + shown).toString()
    }

    /** [line] laid out, from the cache when nothing it shows has changed. */
    fun layoutFor(line: Int): LineLayout = layoutFor(line, lineText(line))

    private fun layoutFor(line: Int, text: String): LineLayout {
        val style = checkNotNull(style) { "layoutFor before the view has a style" }
        val tokens = if (state.exceedsAnalysisLimit) null else state.tokenStore.lineTokens(line)
        // Nothing here and no layer at all are the same layout: turning the layer on costs none.
        val semantic = if (tokens == null) null else state.semanticStore?.lineTokens(line)?.takeIf { it.size > 0 }
        val fold = folds.startingAt(line)
        // Bracket colours depend on the depth the line starts at: part of what the layout shows.
        val bracketStyles = style.bracketStyles?.takeIf { tokens != null && it.isNotEmpty() }
        val depth = if (bracketStyles != null) state.brackets.depthAtLineStart(line) % bracketStyles.size else -1
        val injected = injections?.invoke(line)
        return layouts.getOrPut(LineLayoutCache.Key(text, tokens, LineDecoration(fold?.placeholder, depth, injected, semantic))) {
            measureLine(style, line, text, tokens, semantic, fold, bracketStyles, depth, injected)
        }
    }

    /** What a line's layout shows besides its text and tokens; [semantic] is by identity, as they are. */
    private data class LineDecoration(
        val placeholder: String?,
        val bracketDepth: Int,
        val injections: LineInjections?,
        val semantic: TokenStore.LineTokens?,
    )

    private fun measureLine(
        style: ViewStyle,
        line: Int,
        text: String,
        tokens: TokenStore.LineTokens?,
        semantic: TokenStore.LineTokens?,
        fold: FoldRange?,
        bracketStyles: List<SpanStyle>?,
        startDepth: Int,
        injected: LineInjections?,
    ): LineLayout {
        val builder = AnnotatedString.Builder(text.length + 4)
        val indent = if (style.softWrap && style.joinIndentation) indentationLength(text) else 0
        val shownInjections = injected?.fitted(text, indent, MAX_RENDERED_LINE_CHARS)
        val map = if (shownInjections == null || shownInjections.items.isEmpty()) {
            OffsetMap.Identity
        } else {
            OffsetMap.of(shownInjections, text.length)
        }
        var appended = 0
        shownInjections?.items?.forEach { injection ->
            appendText(builder, text, appended, injection.column, indent)
            appended = injection.column
            val injectedStyle = injection.style
            if (injectedStyle != null) builder.pushStyle(injectedStyle)
            builder.append(injection.text)
            if (injectedStyle != null) builder.pop()
        }
        appendText(builder, text, appended, text.length, indent)
        if (tokens != null) {
            for (i in 0 until tokens.size) {
                val spanStyle = style.tokenStyle(tokens.types[i]) ?: continue
                val from = tokens.start(i).coerceIn(0, text.length)
                val to = tokens.end(i).coerceIn(from, text.length)
                if (to > from) builder.addColumnStyle(map, spanStyle, from, to)
            }
        }
        if (semantic != null && tokens != null) builder.addSemanticStyles(map, style, semantic, tokens, text)
        val colors = shownInjections?.colors.orEmpty()
        val bracketColumns = if (colors.isEmpty()) null else ArrayList<Int>()
        if (bracketStyles != null) {
            // A pair shares a colour: an opening bracket takes the colour of the depth it opens,
            // its closing one the colour of the depth it closes. Only the depth modulo the
            // palette matters, so that is all the key and this count keep.
            val colours = bracketStyles.size
            var depth = startDepth
            state.brackets.forEachBracket(line) { column, open ->
                if (column >= text.length) return@forEachBracket
                bracketColumns?.add(column)
                if (open) {
                    builder.addColumnStyle(map, bracketStyles[depth], column, column + 1)
                    depth = (depth + 1) % colours
                } else {
                    depth = (depth - 1).mod(colours)
                    builder.addColumnStyle(map, bracketStyles[depth], column, column + 1)
                }
            }
        }
        if (colors.isNotEmpty()) builder.addInlineColors(map, colors, text.length, indent, spanStarts(tokens, semantic, bracketColumns))
        if (fold != null) {
            builder.pushStyle(style.placeholderStyle)
            builder.append(" ")
            builder.append(fold.placeholder)
            builder.pop()
        }
        // Last, over any token that covers the indentation: the glyphs must not be seen.
        val column = if (indent > 0) hideIndentation(builder, text, indent, style, map.charIndex(0)) else 0
        val result = style.measurer.measure(
            text = builder.toAnnotatedString(),
            style = style.textStyle,
            softWrap = style.softWrap,
            constraints = if (style.softWrap) Constraints(maxWidth = wrapWidth) else Constraints(),
            placeholders = tabStops(text, style, indent, column, map),
            skipCache = true,
        )
        return LineLayout(result, map)
    }

    /** Characters [from, to) of [text], the first [indent] of which are laid out as hidden glyphs. */
    private fun appendText(builder: AnnotatedString.Builder, text: String, from: Int, to: Int, indent: Int) {
        val glyphs = min(indent, to)
        for (i in from until glyphs) builder.append(INDENT_GLYPH)
        val start = max(from, glyphs)
        if (to > start) builder.append(text, start, to)
    }

    /**
     * What [style] gives each run of [semantic], over the spans [syntax] already added on a line
     * reading [text]. Where spans overlap, Skia's text takes the one that starts last, and only
     * between spans that start together the one added last. So a run is cut at each syntax run
     * that starts inside it: every piece starts with the syntax span under it, and comes after it.
     */
    private fun AnnotatedString.Builder.addSemanticStyles(
        map: OffsetMap,
        style: ViewStyle,
        semantic: TokenStore.LineTokens,
        syntax: TokenStore.LineTokens,
        text: String,
    ) {
        var next = 0
        for (i in 0 until semantic.size) {
            val spanStyle = style.tokenStyle(semantic.types[i]) ?: continue
            var from = semantic.start(i).coerceIn(0, text.length)
            val to = semantic.end(i).coerceIn(from, text.length)
            while (next < syntax.size && syntax.start(next) < to) {
                val cut = syntax.start(next++)
                if (cut > from) {
                    addColumnStyle(map, spanStyle, from, cut)
                    from = cut
                }
            }
            if (to > from) addColumnStyle(map, spanStyle, from, to)
        }
    }

    /** Where every span the line already has starts, in order. */
    private fun spanStarts(tokens: TokenStore.LineTokens?, semantic: TokenStore.LineTokens?, brackets: List<Int>?): List<Int> {
        val starts = ArrayList<Int>(brackets.orEmpty())
        if (tokens != null) for (i in 0 until tokens.size) starts += tokens.start(i)
        if (semantic != null) for (i in 0 until semantic.size) starts += semantic.start(i)
        starts.sort()
        return starts
    }

    /**
     * Each of [colors] over the text of a line [length] long, over every span there is: cut at each
     * of [spanStarts], for the reason [addSemanticStyles] gives. Not over the first [indent]
     * columns, whose glyphs must stay hidden.
     */
    private fun AnnotatedString.Builder.addInlineColors(
        map: OffsetMap,
        colors: List<ColumnColor>,
        length: Int,
        indent: Int,
        spanStarts: List<Int>,
    ) {
        for (color in colors) {
            val spanStyle = SpanStyle(color = color.color)
            var from = color.from.coerceIn(indent, length)
            val to = color.to.coerceIn(from, length)
            for (cut in spanStarts) {
                if (cut >= to) break
                if (cut <= from) continue
                addColumnStyle(map, spanStyle, from, cut)
                from = cut
            }
            if (to > from) addColumnStyle(map, spanStyle, from, to)
        }
    }

    /** [spanStyle] over columns [from, to), leaving injected text between them its own style. */
    private fun AnnotatedString.Builder.addColumnStyle(map: OffsetMap, spanStyle: SpanStyle, from: Int, to: Int) {
        if (map.isIdentity) addStyle(spanStyle, from, to) else map.forEachRun(from, to) { start, end -> addStyle(spanStyle, start, end) }
    }

    /** How many spaces and tabs [text] starts with. */
    private fun indentationLength(text: String): Int {
        var n = 0
        while (n < text.length && (text[n] == ' ' || text[n] == '\t')) n++
        return n
    }

    /**
     * Makes the first [indent] glyphs of [builder] invisible and as wide as the spaces and tabs
     * of [text] they stand for: a run of spaces is one transparent span, a tab one with letter
     * spacing for the columns to its stop. The glyphs start [base] characters into [builder], after
     * any rows above the line. Returns the column after the indentation.
     */
    private fun hideIndentation(builder: AnnotatedString.Builder, text: String, indent: Int, style: ViewStyle, base: Int): Int {
        var column = 0
        var i = 0
        while (i < indent) {
            if (text[i] == '\t') {
                val columns = style.tabSize - column % style.tabSize
                val spacing = ((columns - 1) * style.columnWidthEm).em
                builder.addStyle(SpanStyle(color = Color.Transparent, letterSpacing = spacing), base + i, base + i + 1)
                column += columns
                i++
            } else {
                var end = i + 1
                while (end < indent && text[end] == ' ') end++
                builder.addStyle(HIDDEN, base + i, base + end)
                column += end - i
                i = end
            }
        }
        return column
    }

    /**
     * Every tab of [text] after its first [from] characters as a blank as wide as the columns to
     * the next tab stop, as code editors draw tabs; the font alone would draw one about a
     * character wide. A placeholder covers its own character, so offsets, carets and hit-testing
     * stay as they are. [startColumn] is the column the character at [from] is in, before anything
     * injected there; what [map] injects takes up columns too, and the placeholders are at its
     * positions, not the document's.
     */
    private fun tabStops(
        text: String,
        style: ViewStyle,
        from: Int,
        startColumn: Int,
        map: OffsetMap,
    ): List<AnnotatedString.Range<Placeholder>> {
        if (style.columnWidthEm <= 0f || text.indexOf('\t', from) < 0) return emptyList()
        val stops = ArrayList<AnnotatedString.Range<Placeholder>>()
        var column = startColumn
        for (i in from until text.length) {
            column = map.visualColumnAfter(i, column)
            if (text[i] == '\t') {
                val columns = style.tabSize - column % style.tabSize
                val placeholder = Placeholder((columns * style.columnWidthEm).em, 1.em, PlaceholderVerticalAlign.TextCenter)
                val index = map.charIndex(i)
                stops += AnnotatedString.Range(placeholder, index, index + 1)
                column += columns
            } else {
                column++
            }
        }
        return stops
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    /** Where [position] (viewport coordinates) falls in the document. */
    fun offsetAt(position: Offset): ViewHit {
        val metrics = style?.metrics ?: return ViewHit(0, 0, false)
        if (document.lineCount == 0 || lineMap.totalRows == 0) return ViewHit(0, 0, false)
        val contentY = position.y + scroll.scrollY - metrics.paddingTop
        val row = floor(contentY / metrics.lineHeight).toInt().coerceIn(0, lineMap.totalRows - 1)
        val line = lineMap.lineAtRow(row)
        val layout = layoutFor(line)
        val rowInLine = (row - lineMap.firstRowOf(line)).coerceIn(0, layout.lineCount - 1)
        val x = position.x + scroll.scrollX - metrics.paddingStart
        val y = (layout.getLineTop(rowInLine) + layout.getLineBottom(rowInLine)) / 2f
        val column = layout.columnAt(Offset(x, y))
        val shown = min(lineLength(line), MAX_RENDERED_LINE_CHARS)
        // Past the text of the line, in columns: the map keeps a placeholder past it.
        val onPlaceholder = folds.startingAt(line) != null && column > shown
        return ViewHit(document.lineStart(line) + column.coerceAtMost(shown), line, onPlaceholder, layout.injectionAt(x, rowInLine))
    }

    /** The caret at [offset], in content coordinates. */
    private fun contentCaretRect(offset: Int, metrics: EditorMetrics): Rect {
        val (line, column) = document.offsetToLineCol(offset)
        val shownLine = lineMap.visibleLineAtOrBefore(line)
        val layout = layoutFor(shownLine)
        val shown = min(lineLength(shownLine), MAX_RENDERED_LINE_CHARS)
        // A caret inside a closed fold is drawn at the end of the fold's first line.
        val col = if (shownLine == line) column.coerceAtMost(shown) else shown
        val rect = layout.cursorRect(col)
        val shift = layout.rowShift(layout.rowFor(col), metrics.lineHeight)
        return rect.translate(metrics.paddingStart, contentTop(shownLine, metrics) + shift)
    }

    /** The caret at [offset], in viewport coordinates. */
    fun caretRect(offset: Int): Rect {
        val metrics = style?.metrics ?: return Rect.Zero
        return contentCaretRect(offset, metrics).translate(-scroll.scrollX, -scroll.scrollY)
    }

    /** The x of the caret at [offset], in content coordinates: what vertical movement keeps. */
    fun caretX(offset: Int): Float {
        val metrics = style?.metrics ?: return 0f
        return contentCaretRect(offset, metrics).left - metrics.paddingStart
    }

    /**
     * The offset [rows] rows above (negative) or below [offset], as close as possible to [x] (text
     * coordinates; the caret's own x when null). Rows are what is on screen: wrapped rows count,
     * folded lines do not, and a row no caret can be on is passed over. It lands on the row it
     * reaches, whatever is injected under [x]. Past the first row it goes to the start of the
     * document, past the last to its end.
     */
    fun verticalMove(offset: Int, rows: Int, x: Float? = null): Int {
        if (style == null || lineMap.totalRows == 0) return offset
        val (line, column) = document.offsetToLineCol(offset)
        val shownLine = lineMap.visibleLineAtOrBefore(line)
        val layout = layoutFor(shownLine)
        val shown = min(lineLength(shownLine), MAX_RENDERED_LINE_CHARS)
        val col = if (shownLine == line) column.coerceAtMost(shown) else shown
        val rowInLine = layout.rowFor(col)
        val targetX = x ?: layout.caretX(col)
        val step = if (rows < 0) -1 else 1
        var targetRow = lineMap.firstRowOf(shownLine) + rowInLine + rows
        while (true) {
            if (targetRow < 0) return 0
            if (targetRow >= lineMap.totalRows) return document.length
            val targetLine = lineMap.lineAtRow(targetRow)
            val targetLayout = layoutFor(targetLine)
            val row = (targetRow - lineMap.firstRowOf(targetLine)).coerceIn(0, targetLayout.lineCount - 1)
            if (!targetLayout.hasCaret(row)) {
                targetRow += step
                continue
            }
            val targetShown = min(lineLength(targetLine), MAX_RENDERED_LINE_CHARS)
            return document.lineStart(targetLine) + targetLayout.columnOnRow(targetX, row).coerceAtMost(targetShown)
        }
    }

    // ── Rows, for column selections ──────────────────────────────────────────

    /** Rows on screen in the whole document: wrapped rows count, folded lines do not. */
    val totalRows: Int get() = lineMap.totalRows

    /**
     * The rows lines [firstLine] to [lastLine] take on screen, from the first to one past the last,
     * without laying any of them out: wrapped rows as estimated until a line is laid out. A folded
     * line takes none, so a mark on one lands on the row after its fold's first line.
     */
    fun rowsOfLines(firstLine: Int, lastLine: Int): Pair<Int, Int> {
        val count = lineMap.lineCount
        if (count == 0) return 0 to 0
        val a = firstLine.coerceIn(0, count - 1)
        val b = lastLine.coerceIn(a, count - 1)
        return lineMap.firstRowOf(a) to lineMap.firstRowOf(b + 1)
    }

    /** Whether a caret or a selection can be on [row] on screen, as none can on injected text. */
    fun isCaretRow(row: Int): Boolean {
        if (injections == null || style == null || lineMap.totalRows == 0) return true
        val (_, layout, rowInLine) = rowLayout(row)
        return layout.hasCaret(rowInLine)
    }

    /** [row], or the nearest row a caret can be on, looking in the direction of [step] first. */
    fun textRowFrom(row: Int, step: Int): Int {
        if (step == 0 || isCaretRow(row)) return row
        var next = row + step
        while (next in 0 until lineMap.totalRows) {
            if (isCaretRow(next)) return next
            next += step
        }
        next = row - step
        while (next in 0 until lineMap.totalRows) {
            if (isCaretRow(next)) return next
            next -= step
        }
        return row
    }

    /** The row on screen the caret at [offset] is on. */
    fun rowOf(offset: Int): Int {
        if (style == null || lineMap.totalRows == 0) return 0
        val (line, column) = document.offsetToLineCol(offset)
        val shownLine = lineMap.visibleLineAtOrBefore(line)
        val layout = layoutFor(shownLine)
        val shown = min(lineLength(shownLine), MAX_RENDERED_LINE_CHARS)
        val col = if (shownLine == line) column.coerceAtMost(shown) else shown
        return lineMap.firstRowOf(shownLine) + layout.rowFor(col)
    }

    /** The row on screen at viewport y [y], clamped to the document's rows. */
    fun rowAt(y: Float): Int {
        val metrics = style?.metrics ?: return 0
        if (lineMap.totalRows == 0) return 0
        return floor((y + scroll.scrollY - metrics.paddingTop) / metrics.lineHeight).toInt().coerceIn(0, lineMap.totalRows - 1)
    }

    /** Viewport x [x] in text coordinates, as [caretX] and [verticalMove] take it. */
    fun textX(x: Float): Float = x + scroll.scrollX - (style?.metrics?.paddingStart ?: 0f)

    /** The offset on [row] closest to [x] (text coordinates); past the row's end, its end. */
    fun offsetOnRow(row: Int, x: Float): Int {
        if (style == null || lineMap.totalRows == 0) return 0
        val (line, layout, rowInLine) = rowLayout(row)
        val shown = min(lineLength(line), MAX_RENDERED_LINE_CHARS)
        return document.lineStart(line) + layout.columnOnRow(x, rowInLine).coerceAtMost(shown)
    }

    /** Where the text on [row] ends, in text coordinates (a closed fold's placeholder not included). */
    fun rowEndX(row: Int): Float {
        if (style == null || lineMap.totalRows == 0) return 0f
        val (line, layout, rowInLine) = rowLayout(row)
        return layout.rowEndX(rowInLine, min(lineLength(line), MAX_RENDERED_LINE_CHARS))
    }

    private data class RowLayout(val line: Int, val layout: LineLayout, val rowInLine: Int)

    private fun rowLayout(row: Int): RowLayout {
        val target = row.coerceIn(0, lineMap.totalRows - 1)
        val line = lineMap.lineAtRow(target)
        val layout = layoutFor(line)
        return RowLayout(line, layout, (target - lineMap.firstRowOf(line)).coerceIn(0, layout.lineCount - 1))
    }

    companion object {
        /**
         * Characters of one line that are laid out and drawn. A longer line (minified code, a data
         * blob) is cut there, as VS Code does, rather than laid out whole on every change.
         */
        const val MAX_RENDERED_LINE_CHARS = 10_000

        /** The style of an indentation's glyphs: there, but not drawn. */
        private val HIDDEN = SpanStyle(color = Color.Transparent)
    }
}
