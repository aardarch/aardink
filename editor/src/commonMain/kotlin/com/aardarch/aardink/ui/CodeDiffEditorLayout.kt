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
package com.aardarch.aardink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Decoration
import com.aardarch.aardink.core.DecorationStyle
import com.aardarch.aardink.core.DiffEditorState
import com.aardarch.aardink.core.EditorCommand
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.LineChange
import com.aardarch.aardink.core.LineDiffKind
import com.aardarch.aardink.core.TextDiffOptions
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.ui.view.DiffAlignment
import com.aardarch.aardink.ui.view.DiffRows
import com.aardarch.aardink.ui.view.EditorView
import com.aardarch.aardink.ui.view.MoreDeletedRows
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.aardarch.aardink.core.diff.LineChange as Block

/**
 * Shows [state]'s two texts and what differs between them, as Monaco's diff editor does: side by side,
 * the original on the left and the modified text, which is the one edited, on the right, or inline,
 * with the original's deleted lines as rows above the lines that replaced them.
 *
 * **Side by side.** Blank rows keep the two editors aligned: an unchanged line is at one height on both
 * sides, with or without soft wrap and with the divider anywhere. They are placed from the rows each
 * editor counts, for the lines on screen, so with soft wrap the alignment settles within a layout pass or
 * two after a scroll. Scrolling either editor, vertically or horizontally, puts the other at the same
 * place; when the two have unequal widths of text, the other scrolls as far as it can. The divider
 * can be dragged ([DiffEditorOptions.enableSplitViewResizing]). The original is read-only unless
 * [DiffEditorOptions.originalEditable].
 *
 * **Inline.** One editor on the modified text. The original's deleted lines are rows above the line
 * after them, in the original's token colours, which the caret and the arrow keys pass over and which
 * are not in the document. A block of more than 1,000 deleted lines shows the first 1,000, then a row
 * that says how many lines more were deleted and shows them all when it is clicked. The view is
 * inline when [DiffEditorOptions.renderSideBySide] is off, and when the layout is narrower than
 * [DiffEditorOptions.renderSideBySideInlineBreakpoint].
 *
 * **What it marks.** Inserted lines on the modified side and removed lines on the original's have a
 * background across the line, and the characters that changed inside them a stronger one, from the
 * theme's `diffEditor.*` colours. Each change has a mark in the overview ruler of both editors,
 * and the gutter's lane marks the added, modified and deleted lines (without it, see
 * [DiffEditorOptions.renderIndicators]), with an arrow on a change's first line that reverts it on
 * the modified side, and accepts it on the original's when that is editable.
 *
 * **Moving through the changes.** F7 moves the caret to the next change and Shift+F7 to the previous,
 * after the last the first, and shows it in the middle of the editor. The commands
 * ([EditorCommand.NextChange], [EditorCommand.PreviousChange], [EditorCommand.RevertChange]) are
 * available on both editors while this layout shows them, and a host's buttons can run them through
 * either `CodeEditorState.execute`. [EditorCommand.RevertChange] reverts the change at the caret.
 * Revert and accept are also in the editors' context menus.
 *
 * **Timing.** The diff is worked out as the layout appears, then 300 ms after typing stops on
 * either side, on the states' compute dispatcher and never while a key is handled. Between an edit
 * and the next diff, the filler and deleted rows move with the lines they were on. A diff that
 * runs out of time ([DiffEditorOptions.maxComputationTimeMs]) shows its changes like any other;
 * [DiffEditorState.hitTimeout] says so.
 *
 * Each side's own options come from [editorOptions]: the original is read-only unless
 * [DiffEditorOptions.originalEditable], and both show an overview ruler, whatever [editorOptions] says,
 * for the marks of the changes. A host's decorations on either state are left as they are: the diff
 * draws its own under the owner name `aardink.diff`.
 *
 * @param state The two texts and their changes.
 * @param options How the difference is shown and worked out.
 * @param editorOptions How each of the two editors looks and behaves.
 * @param languageService What the modified editor asks for completions, diagnostics, hover and the rest.
 * @param originalLanguageService The same for the original editor; the one for the modified editor by default.
 */
@Composable
fun CodeDiffEditorLayout(
    state: DiffEditorState,
    modifier: Modifier = Modifier,
    options: DiffEditorOptions = DiffEditorOptions(),
    editorOptions: EditorOptions = EditorOptions(),
    languageService: LanguageService? = null,
    originalLanguageService: LanguageService? = languageService,
) {
    val strings = currentEditorStrings(editorOptions.languageTag)
    val theme = LocalEditorTheme.current
    val colors = remember(theme) { DiffColors.of(theme) }
    val tokenStyles = remember(theme) { TokenStyleResolver(theme) }
    val dividerColor = remember(theme) { EditorChromeColors(theme).surface(3) }
    val textOptions = TextDiffOptions(options.ignoreTrimWhitespace, options.maxComputationTimeMs)

    // ── The diff: once on show, then once typing stops on either side ────────
    LaunchedEffect(state, textOptions) {
        var first = true
        snapshotFlow { state.original.textVersion to state.modified.textVersion }.collectLatest {
            if (!first) delay(DIFF_DEBOUNCE_MS)
            first = false
            state.recompute(textOptions)
        }
    }

    val views = remember(state) { DiffViews(state) }
    // What each side's editor is told about the diff, until the next one.
    val revision = state.revision

    DisposableEffect(state) {
        onDispose {
            state.original.clearDecorations(DIFF_OWNER)
            state.modified.clearDecorations(DIFF_OWNER)
            state.original.publishDiffRows(emptyMap())
            state.modified.publishDiffRows(emptyMap())
        }
    }

    // ── What it marks ────────────────────────────────────────────────────────
    LaunchedEffect(state, revision, colors) {
        val changes = state.changes
        state.original.setDecorations(DIFF_OWNER, DiffMarks.original(changes, state.original, colors))
        state.modified.setDecorations(DIFF_OWNER, DiffMarks.modified(changes, state.modified, colors))
    }

    // ── The commands, on both editors ────────────────────────────────────────
    DisposableEffect(state) {
        state.original.diffCommands = { command -> runDiffCommand(state, command, modifiedSide = false) }
        state.modified.diffCommands = { command -> runDiffCommand(state, command, modifiedSide = true) }
        onDispose {
            state.original.diffCommands = null
            state.modified.diffCommands = null
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val sideBySide = options.renderSideBySide && maxWidth >= options.renderSideBySideInlineBreakpoint
        val totalWidth = constraints.maxWidth.toFloat()
        var ratio by remember(options.splitRatio) { mutableFloatStateOf(options.splitRatio.coerceIn(MIN_SPLIT, 1f - MIN_SPLIT)) }
        val softWrap = editorOptions.softWrap

        // ── Unchanged regions folded on both sides at once ───────────────────
        val originalFolds = remember(state) { FoldState() }
        val modifiedFolds = remember(state) { FoldState() }
        val hide = options.hideUnchangedRegions
        val context = options.unchangedContextLines.coerceAtLeast(1)
        if (hide) {
            LaunchedEffect(state, revision, context, strings) {
                keepRegionsFolded(state, originalFolds, modifiedFolds, context, strings::diffUnchangedLines)
            }
        } else {
            DisposableEffect(state) {
                originalFolds.unfoldAll()
                modifiedFolds.unfoldAll()
                onDispose { }
            }
        }

        // ── Side by side: alignment, and one scroll position ─────────────────
        val originalView = views.original
        val modifiedView = views.modified
        if (sideBySide && originalView != null && modifiedView != null) {
            val alignment = remember(originalView, modifiedView, colors.filler) { DiffAlignment(originalView, modifiedView, colors.filler) }
            DisposableEffect(alignment) { onDispose { alignment.clear() } }
            LaunchedEffect(alignment, revision, softWrap) { alignment.rebuild(state.changes.toBlocks(), softWrap) }
            LaunchedEffect(alignment, softWrap) {
                snapshotFlow { originalView.frame to modifiedView.frame }.collectLatest {
                    if (!state.isCurrent) return@collectLatest
                    alignment.settle()
                    // A pane that changed width (a resize, the divider) counts its lines in other rows: all of
                    // them are worked out again, once it has stopped changing.
                    if (alignment.needsRebuild()) {
                        delay(WIDTH_SETTLE_MS)
                        if (state.isCurrent) alignment.rebuild(state.changes.toBlocks(), wrap = true)
                    }
                }
            }
            LaunchedEffect(originalView, modifiedView) { shareScroll(originalView, modifiedView) }
        }

        // ── Inline: the original's deleted lines as rows ─────────────────────
        var expanded by remember(state) { mutableStateOf(emptySet<Int>()) }
        views.expand = { expanded += it }
        if (!sideBySide) {
            LaunchedEffect(state, revision, expanded, colors.removedLine, tokenStyles, editorOptions.tabSize) {
                var first = true
                snapshotFlow { state.original.tokenVersion }.collectLatest {
                    // The original's tokens come from its own pass, and may still be on their way.
                    if (!first) delay(INLINE_TOKENS_DEBOUNCE_MS)
                    first = false
                    state.modified.publishDiffRows(
                        DiffRows.inline(
                            state.changes.toBlocks(),
                            state.original,
                            state.modified.document,
                            tokenStyles::spanStyle,
                            colors.removedLine,
                            editorOptions.tabSize,
                            expanded = expanded,
                            removedText = colors.removedText,
                        ),
                    )
                }
            }
            DisposableEffect(state) { onDispose { state.modified.publishDiffRows(emptyMap()) } }
        }

        val originalEditable = options.originalEditable && !editorOptions.readOnly
        val originalOptions = editorOptions.copy(
            readOnly = !originalEditable,
            showDiffMarkers = options.renderIndicators,
            overviewRuler = true,
        )
        val modifiedOptions = editorOptions.copy(showDiffMarkers = options.renderIndicators, overviewRuler = true)

        val modifiedSide = remember(state, revision, colors, editorOptions.readOnly, views) {
            val changes = state.changes
            val lineCount = state.modified.document.lineCount
            DiffSide(
                onView = views.onModifiedView,
                lane = DiffMarks.modifiedLane(changes),
                colors = colors,
                actionLines = if (editorOptions.readOnly) {
                    emptySet()
                } else {
                    changes.map {
                        it.modifiedLines.first.coerceIn(0, lineCount - 1)
                    }.toSet()
                },
                onAction = { line -> state.changeAt(line, modifiedSide = true)?.let(state::revert) },
            )
        }
        val originalSide = remember(state, revision, colors, originalEditable, views) {
            val changes = state.changes
            val lineCount = state.original.document.lineCount
            DiffSide(
                onView = views.onOriginalView,
                lane = DiffMarks.originalLane(changes),
                // The original's lane marks lines removed from it, in the colour of deleted lines.
                colors = colors.copy(gutterModified = colors.gutterDeleted),
                actionLines = if (originalEditable) {
                    changes.map {
                        it.originalLines.first.coerceIn(0, lineCount - 1)
                    }.toSet()
                } else {
                    emptySet()
                },
                onAction = { line -> state.changeAt(line, modifiedSide = false)?.let(state::accept) },
            )
        }
        val originalMenu = remember(state, originalEditable, strings) {
            buildList {
                if (originalEditable) {
                    add(EditorMenuItem(strings.diffAcceptChange) { acceptAtCaret(state) })
                }
                add(EditorMenuItem(strings.diffNextChange, EditorCommand.NextChange))
                add(EditorMenuItem(strings.diffPreviousChange, EditorCommand.PreviousChange))
            }
        }
        val modifiedMenu = remember(state, editorOptions.readOnly, strings) {
            buildList {
                if (!editorOptions.readOnly) add(EditorMenuItem(strings.diffRevertChange, EditorCommand.RevertChange))
                add(EditorMenuItem(strings.diffNextChange, EditorCommand.NextChange))
                add(EditorMenuItem(strings.diffPreviousChange, EditorCommand.PreviousChange))
            }
        }
        val keepFolds = remember { KeepFolds }

        @Composable
        fun OriginalEditor(modifier: Modifier) {
            CompositionLocalProvider(LocalDiffSide provides originalSide) {
                CodeEditorLayout(
                    state = state.original,
                    modifier = modifier,
                    options = originalOptions,
                    languageService = originalLanguageService,
                    foldState = if (hide) originalFolds else null,
                    foldingProvider = keepFolds,
                    contextMenuItems = originalMenu,
                )
            }
        }

        @Composable
        fun ModifiedEditor(modifier: Modifier) {
            CompositionLocalProvider(LocalDiffSide provides modifiedSide) {
                CodeEditorLayout(
                    state = state.modified,
                    modifier = modifier,
                    options = modifiedOptions,
                    languageService = languageService,
                    foldState = if (hide) modifiedFolds else null,
                    foldingProvider = keepFolds,
                    contextMenuItems = modifiedMenu,
                )
            }
        }

        if (sideBySide) {
            val dividerWidth = if (options.enableSplitViewResizing) DIVIDER_WIDTH else 1.dp
            Row(Modifier.fillMaxSize()) {
                OriginalEditor(Modifier.weight(ratio).fillMaxHeight())
                val currentWidth = rememberUpdatedState(totalWidth)
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(dividerWidth)
                        .fillMaxHeight()
                        .background(dividerColor)
                        .testTag(EditorTestTags.DIFF_DIVIDER)
                        .pointerInput(options.enableSplitViewResizing) {
                            if (!options.enableSplitViewResizing) return@pointerInput
                            detectHorizontalDragGestures { _, amount ->
                                if (currentWidth.value > 0f) {
                                    ratio = (ratio + amount / currentWidth.value).coerceIn(MIN_SPLIT, 1f - MIN_SPLIT)
                                }
                            }
                        },
                )
                ModifiedEditor(Modifier.weight(1f - ratio).fillMaxHeight())
            }
        } else {
            ModifiedEditor(Modifier.fillMaxSize())
        }
    }
}

/**
 * The two editors' renderers, as they are composed: handed over by [DiffSide.onView], and put where
 * [DiffEditorState] and the layout's effects can reach them.
 */
private class DiffViews(private val state: DiffEditorState) {
    var original by mutableStateOf<EditorView?>(null)
        private set
    var modified by mutableStateOf<EditorView?>(null)
        private set

    /** Shows a deleted block's rows in full, by its first original line. */
    var expand: (Int) -> Unit = {}

    val onOriginalView: (EditorView?) -> Unit = { view ->
        original = view
        state.originalView = view
    }

    val onModifiedView: (EditorView?) -> Unit = { view ->
        modified = view
        state.modifiedView = view
        // A click on the row that stands for the rest of a deleted block; any other goes where it did.
        view?.onInjectionClick = { line, injection ->
            val id = injection.id
            if (id is MoreDeletedRows) expand(id.firstLine) else view.inline.clicked(line, injection)
        }
    }
}

/** Keeps the fold ranges the diff layout puts in its [FoldState]s: the folding provider never answers. */
private val KeepFolds = FoldingProvider { error("The diff editor sets its own fold ranges") }

/** The owner of the decorations the diff draws. */
internal const val DIFF_OWNER = "aardink.diff"

/** How long typing must pause before the diff is worked out again, as the gutter's lane waits. */
private const val DIFF_DEBOUNCE_MS = 300L

/** How long a pane must keep its width before the filler of the whole diff is worked out for it. */
private const val WIDTH_SETTLE_MS = 100L

/** How long the inline view waits for the original's tokens to stop changing before it shows them. */
private const val INLINE_TOKENS_DEBOUNCE_MS = 50L

/** The least share of the width either side keeps. */
private const val MIN_SPLIT = 0.1f

private val DIVIDER_WIDTH = 5.dp

private fun List<LineChange>.toBlocks(): List<Block> = map {
    Block(
        it.originalLines.first,
        it.originalLines.last + 1,
        it.modifiedLines.first,
        it.modifiedLines.last + 1,
        it.charChanges?.map { c ->
            com.aardarch.aardink.core.diff.CharChange(
                c.original.first,
                c.original.last + 1,
                c.modified.first,
                c.modified.last + 1,
            )
        },
    )
}

// ── The commands ─────────────────────────────────────────────────────────────

/** Runs [command] for the editor on [modifiedSide]: its caret decides where it starts. */
private fun runDiffCommand(state: DiffEditorState, command: EditorCommand, modifiedSide: Boolean): Boolean {
    val side = if (modifiedSide) state.modified else state.original
    val document = side.document
    val changes = state.changes
    if (changes.isEmpty()) return false
    val caretLine = document.offsetToLineCol(side.selection.end.coerceIn(0, document.length)).first
    when (command) {
        EditorCommand.NextChange, EditorCommand.PreviousChange -> {
            val firsts = changes.map { change ->
                (if (modifiedSide) change.modifiedLines else change.originalLines).first.coerceIn(0, document.lineCount - 1)
            }
            val target = if (command == EditorCommand.NextChange) {
                firsts.firstOrNull { it > caretLine } ?: firsts.first()
            } else {
                firsts.lastOrNull { it < caretLine } ?: firsts.last()
            }
            val offset = document.lineStart(target)
            side.selection = TextRange(offset)
            side.execute(EditorCommand.Reveal(offset, center = true))
        }

        EditorCommand.RevertChange -> {
            val change = state.changeAt(caretLine, modifiedSide) ?: return false
            state.revert(change)
        }

        else -> return false
    }
    return true
}

private fun acceptAtCaret(state: DiffEditorState) {
    val original = state.original
    val line = original.document.offsetToLineCol(original.selection.end.coerceIn(0, original.document.length)).first
    state.changeAt(line, modifiedSide = false)?.let(state::accept)
}

// ── One scroll position ──────────────────────────────────────────────────────

/**
 * Puts each editor where the other was scrolled to, on both axes, for as long as it runs. Both
 * have the same rows once aligned, so one position serves both. The side that moved is followed
 * (the modified side when both did), and what the other side clamps to is not copied back, so that
 * a side with less to scroll does not hold the other back. Costs a comparison of four numbers per frame
 * in which a scroll position changes.
 */
private suspend fun shareScroll(original: EditorView, modified: EditorView) {
    var lastOriginalY = original.scroll.scrollY
    var lastOriginalX = original.scroll.scrollX
    var lastModifiedY = modified.scroll.scrollY
    var lastModifiedX = modified.scroll.scrollX
    snapshotFlow {
        floatArrayOf(original.scroll.scrollY, original.scroll.scrollX, modified.scroll.scrollY, modified.scroll.scrollX)
    }.collect { at ->
        val originalYMoved = at[0] != lastOriginalY
        val modifiedYMoved = at[2] != lastModifiedY
        if (originalYMoved || modifiedYMoved) {
            if (modifiedYMoved) original.scroll.scrollToY(at[2]) else modified.scroll.scrollToY(at[0])
            lastOriginalY = original.scroll.scrollY
            lastModifiedY = modified.scroll.scrollY
        }
        val originalXMoved = at[1] != lastOriginalX
        val modifiedXMoved = at[3] != lastModifiedX
        if (originalXMoved || modifiedXMoved) {
            if (modifiedXMoved) original.scroll.scrollToX(at[3]) else modified.scroll.scrollToX(at[1])
            lastOriginalX = original.scroll.scrollX
            lastModifiedX = modified.scroll.scrollX
        }
    }
}

// ── Unchanged regions ────────────────────────────────────────────────────────

/** A run of unchanged lines long enough to fold: the fold's first (visible) line and last line, on each side. */
private class UnchangedRegion(val originalStart: Int, val originalEnd: Int, val modifiedStart: Int, val modifiedEnd: Int) {
    val hidden: Int get() = originalEnd - originalStart
}

/**
 * The runs of unchanged lines longer than twice [context], each as a fold that leaves [context] lines
 * either side: its first line is the last line of context before it, which stays visible with the
 * placeholder after it. Both sides have the same lines in a run, so the folds have the same height.
 */
private fun unchangedRegions(changes: List<LineChange>, originalLines: Int, context: Int): List<UnchangedRegion> {
    val regions = ArrayList<UnchangedRegion>()
    var o = 0
    var m = 0
    fun run(originalEnd: Int) {
        val length = originalEnd - o
        if (length > 2 * context) {
            regions += UnchangedRegion(o + context - 1, originalEnd - context - 1, m + context - 1, m + originalEnd - o - context - 1)
        }
    }
    for (change in changes) {
        run(change.originalLines.first)
        o = change.originalLines.last + 1
        m = change.modifiedLines.last + 1
    }
    run(originalLines)
    return regions
}

/**
 * Folds the unchanged regions of the last diff on both sides, and keeps the two sides' folds the
 * same: opening or closing one on a side does it on the other. A region the user had open stays
 * open when the diff is worked out again; a new one starts folded. Runs until cancelled.
 */
private suspend fun keepRegionsFolded(
    state: DiffEditorState,
    originalFolds: FoldState,
    modifiedFolds: FoldState,
    context: Int,
    placeholder: (Int) -> String,
) {
    val regions = unchangedRegions(state.changes, state.original.document.lineCount, context)
    val wasOpen = regions.map { region ->
        val known = originalFolds.isFoldable(region.originalStart) && modifiedFolds.isFoldable(region.modifiedStart)
        known && (!originalFolds.isFolded(region.originalStart) || !modifiedFolds.isFolded(region.modifiedStart))
    }
    originalFolds.updateFoldableRanges(regions.map { FoldRange(it.originalStart, it.originalEnd, placeholder(it.hidden)) })
    modifiedFolds.updateFoldableRanges(regions.map { FoldRange(it.modifiedStart, it.modifiedEnd, placeholder(it.hidden)) })
    for ((i, region) in regions.withIndex()) {
        setFolded(originalFolds, region.originalStart, !wasOpen[i])
        setFolded(modifiedFolds, region.modifiedStart, !wasOpen[i])
    }
    val last = BooleanArray(regions.size) { !wasOpen[it] }
    snapshotFlow { regions.map { originalFolds.isFolded(it.originalStart) to modifiedFolds.isFolded(it.modifiedStart) } }.collect { now ->
        // After an edit the folds have moved with their lines and the regions have not, until the next diff.
        if (!state.isCurrent) return@collect
        for ((i, pair) in now.withIndex()) {
            val (a, b) = pair
            if (a == b) {
                last[i] = a
                continue
            }
            // The side that changed is followed.
            val folded = if (a != last[i]) a else b
            setFolded(originalFolds, regions[i].originalStart, folded)
            setFolded(modifiedFolds, regions[i].modifiedStart, folded)
            last[i] = folded
        }
    }
}

private fun setFolded(folds: FoldState, startLine: Int, folded: Boolean) {
    if (folds.isFolded(startLine) != folded) folds.toggle(startLine)
}

// ── What the editors mark ────────────────────────────────────────────────────

/** The diff's decorations, and the gutter lane's marks, for each side. */
private object DiffMarks {

    fun modified(changes: List<LineChange>, state: CodeEditorState, colors: DiffColors): List<Decoration> {
        val document = state.document
        val marks = ArrayList<Decoration>()
        for (change in changes) {
            val lines = change.modifiedLines
            if (lines.isEmpty()) {
                // Lines were deleted here: a mark in the ruler where they were.
                val at = document.lineStart(lines.first.coerceIn(0, document.lineCount - 1))
                marks += Decoration(at until at, DecorationStyle(overviewRulerColor = colors.gutterDeleted))
                continue
            }
            val ruler = if (change.originalLines.isEmpty()) colors.gutterAdded else colors.gutterModified
            marks += wholeLines(document.lineStart(lines.first), document.lineEnd(lines.last), colors.insertedLine, ruler)
            change.charChanges?.forEach { chars ->
                if (!chars.modified.isEmpty()) marks += Decoration(chars.modified, DecorationStyle(background = colors.insertedText))
            }
        }
        return marks
    }

    fun original(changes: List<LineChange>, state: CodeEditorState, colors: DiffColors): List<Decoration> {
        val document = state.document
        val marks = ArrayList<Decoration>()
        for (change in changes) {
            val lines = change.originalLines
            if (lines.isEmpty()) {
                val at = document.lineStart(lines.first.coerceIn(0, document.lineCount - 1))
                marks += Decoration(at until at, DecorationStyle(overviewRulerColor = colors.gutterAdded))
                continue
            }
            marks += wholeLines(document.lineStart(lines.first), document.lineEnd(lines.last), colors.removedLine, colors.gutterDeleted)
            change.charChanges?.forEach { chars ->
                if (!chars.original.isEmpty()) marks += Decoration(chars.original, DecorationStyle(background = colors.removedText))
            }
        }
        return marks
    }

    private fun wholeLines(start: Int, end: Int, background: Color, ruler: Color) = Decoration(
        start until end.coerceAtLeast(start),
        DecorationStyle(background = background, wholeLine = true, overviewRulerColor = ruler),
    )

    fun modifiedLane(changes: List<LineChange>): DiffLane {
        if (changes.isEmpty()) return DiffLane.None
        val marks = HashMap<Int, LineDiffKind>()
        val deleted = HashSet<Int>()
        for (change in changes) {
            if (change.modifiedLines.isEmpty()) {
                deleted += change.modifiedLines.first
            } else {
                val kind = if (change.originalLines.isEmpty()) LineDiffKind.Added else LineDiffKind.Modified
                for (line in change.modifiedLines) marks[line] = kind
            }
        }
        return DiffLane(marks, deleted)
    }

    /** The lines taken out of the original, as modified lines: the lane draws them in the deleted colour. */
    fun originalLane(changes: List<LineChange>): DiffLane {
        if (changes.isEmpty()) return DiffLane.None
        val marks = HashMap<Int, LineDiffKind>()
        for (change in changes) for (line in change.originalLines) marks[line] = LineDiffKind.Modified
        return DiffLane(marks, emptySet())
    }
}
