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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
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
    val tokenStyles: Map<TokenType, SpanStyle>,
    val placeholderStyle: SpanStyle,
    val metrics: EditorMetrics,
    val softWrap: Boolean,
)

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
    val layout: TextLayoutResult,
    /** The fold that starts on this line and is closed, drawn as a placeholder after the text. */
    val fold: FoldRange?,
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
internal class ViewHit(val offset: Int, val line: Int, val onFoldPlaceholder: Boolean)

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

    private val layouts = LineLayoutCache<TextLayoutResult>()

    private val document get() = state.document

    private var styleState by mutableStateOf<ViewStyle?>(null)

    /** How lines are measured and styled; set from composition. Read by the layout pass. */
    var style: ViewStyle?
        get() = styleState
        set(value) {
            if (value == styleState) return
            styleState = value
            layouts.clear()
            structureDirty = true
        }

    /** The host's fold state; its closed folds hide lines. */
    var foldState: FoldState? = null

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
        structureDirty = true
    }

    fun detach() {
        document.removeChangeListener(this)
    }

    /** The folds the view is showing, for the gutter. */
    val foldLayout: FoldLayout get() = folds

    override fun onDocumentChange(change: DocumentChange) {
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
            if (style?.softWrap == true && frame.find(change.startLine) == null) {
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
        revealRequests
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
            val line = lineMap.visibleLineAtOrBefore(anchorLine.coerceIn(0, lineCount - 1))
            scrollY = contentTop(line, metrics) + (if (line == anchorLine) anchorOffset else 0f)
            restoreAnchor = false
        }
        var contentHeight = metrics.paddingTop + lineMap.totalRows * lineHeight + metrics.paddingBottom
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
                val layout = layoutFor(line)
                if (style.softWrap) {
                    val delta = lineMap.setRows(line, layout.lineCount)
                    // A line starting above the view that turned out taller or shorter: keep the
                    // rows below it where they were.
                    if (delta != 0 && top < scrollY) scrollY += delta * lineHeight
                } else {
                    widestMeasured = max(widestMeasured, layout.size.width.toFloat())
                }
                val start = document.lineStart(line)
                val length = lineLength(line)
                visible.add(VisibleLine(line, start, length, min(length, MAX_RENDERED_LINE_CHARS), top, layout, folds.startingAt(line)))
                line++
            }
            contentHeight = metrics.paddingTop + lineMap.totalRows * lineHeight + metrics.paddingBottom
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

    /** Content y of [line]'s first row. */
    fun contentTop(line: Int, metrics: EditorMetrics = style?.metrics ?: EditorMetrics.Zero): Float =
        metrics.paddingTop + lineMap.firstRowOf(line) * metrics.lineHeight

    private fun lineLength(line: Int): Int = document.lineEnd(line) - document.lineStart(line)

    private fun longestLine(): Int {
        var longest = 0
        for (line in 0 until document.lineCount) longest = max(longest, lineLength(line))
        return longest
    }

    private fun estimateRows(line: Int): Int {
        if (folds.isHidden(line)) return 0
        val style = style
        if (style == null || !style.softWrap || wrapWidth <= 0) return 1
        val placeholder = folds.startingAt(line)?.let { it.placeholder.length + 1 } ?: 0
        val chars = min(lineLength(line), MAX_RENDERED_LINE_CHARS) + placeholder
        return max(1, ceil(chars * style.metrics.charWidth / wrapWidth).toInt())
    }

    /** [line] laid out, from the cache when nothing it shows has changed. */
    fun layoutFor(line: Int): TextLayoutResult {
        val style = checkNotNull(style) { "layoutFor before the view has a style" }
        val start = document.lineStart(line)
        val shown = min(lineLength(line), MAX_RENDERED_LINE_CHARS)
        val text = document.subSequence(start, start + shown).toString()
        val tokens = if (state.exceedsAnalysisLimit) null else state.tokenStore.lineTokens(line)
        val fold = folds.startingAt(line)
        return layouts.getOrPut(LineLayoutCache.Key(text, tokens, fold?.placeholder)) {
            measureLine(style, text, tokens, fold)
        }
    }

    private fun measureLine(style: ViewStyle, text: String, tokens: TokenStore.LineTokens?, fold: FoldRange?): TextLayoutResult {
        val builder = AnnotatedString.Builder(text.length + 4)
        builder.append(text)
        if (tokens != null) {
            for (i in 0 until tokens.size) {
                val spanStyle = style.tokenStyles[tokens.types[i]] ?: continue
                val from = tokens.start(i).coerceIn(0, text.length)
                val to = tokens.end(i).coerceIn(from, text.length)
                if (to > from) builder.addStyle(spanStyle, from, to)
            }
        }
        if (fold != null) {
            builder.pushStyle(style.placeholderStyle)
            builder.append(" ")
            builder.append(fold.placeholder)
            builder.pop()
        }
        return style.measurer.measure(
            text = builder.toAnnotatedString(),
            style = style.textStyle,
            softWrap = style.softWrap,
            constraints = if (style.softWrap) Constraints(maxWidth = wrapWidth) else Constraints(),
            skipCache = true,
        )
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
        val column = layout.getOffsetForPosition(Offset(x, y))
        val shown = min(lineLength(line), MAX_RENDERED_LINE_CHARS)
        val onPlaceholder = folds.startingAt(line) != null && column > shown
        return ViewHit(document.lineStart(line) + column.coerceAtMost(shown), line, onPlaceholder)
    }

    /** The caret at [offset], in content coordinates. */
    private fun contentCaretRect(offset: Int, metrics: EditorMetrics): Rect {
        val (line, column) = document.offsetToLineCol(offset)
        val shownLine = lineMap.visibleLineAtOrBefore(line)
        val layout = layoutFor(shownLine)
        val shown = min(lineLength(shownLine), MAX_RENDERED_LINE_CHARS)
        // A caret inside a closed fold is drawn at the end of the fold's first line.
        val col = if (shownLine == line) column.coerceAtMost(shown) else shown
        val rect = layout.getCursorRect(col)
        return rect.translate(metrics.paddingStart, contentTop(shownLine, metrics))
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
     * folded lines do not. Past the first row it goes to the start of the document, past the last
     * to its end.
     */
    fun verticalMove(offset: Int, rows: Int, x: Float? = null): Int {
        if (style == null || lineMap.totalRows == 0) return offset
        val (line, column) = document.offsetToLineCol(offset)
        val shownLine = lineMap.visibleLineAtOrBefore(line)
        val layout = layoutFor(shownLine)
        val shown = min(lineLength(shownLine), MAX_RENDERED_LINE_CHARS)
        val col = if (shownLine == line) column.coerceAtMost(shown) else shown
        val rowInLine = layout.getLineForOffset(col)
        val targetX = x ?: layout.getHorizontalPosition(col, usePrimaryDirection = true)
        val targetRow = lineMap.firstRowOf(shownLine) + rowInLine + rows
        if (targetRow < 0) return 0
        if (targetRow >= lineMap.totalRows) return document.length
        val targetLine = lineMap.lineAtRow(targetRow)
        val targetLayout = layoutFor(targetLine)
        val row = (targetRow - lineMap.firstRowOf(targetLine)).coerceIn(0, targetLayout.lineCount - 1)
        val y = (targetLayout.getLineTop(row) + targetLayout.getLineBottom(row)) / 2f
        val targetShown = min(lineLength(targetLine), MAX_RENDERED_LINE_CHARS)
        val targetColumn = targetLayout.getOffsetForPosition(Offset(targetX, y)).coerceAtMost(targetShown)
        return document.lineStart(targetLine) + targetColumn
    }

    companion object {
        /**
         * Characters of one line that are laid out and drawn. A longer line (minified code, a data
         * blob) is cut there, as VS Code does, rather than laid out whole on every change.
         */
        const val MAX_RENDERED_LINE_CHARS = 10_000
    }
}
