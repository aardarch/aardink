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

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.LineMark
import com.aardarch.aardink.core.TokenStyleResolver
import com.aardarch.aardink.core.lineMarks
import com.aardarch.aardink.platform.EditorClipboardEvents
import com.aardarch.aardink.platform.EditorScrollbars
import com.aardarch.aardink.platform.PlatformInfo
import com.aardarch.aardink.platform.editorMagnifier
import com.aardarch.aardink.ui.view.EditorBody
import com.aardarch.aardink.ui.view.EditorColors
import com.aardarch.aardink.ui.view.EditorContextMenu
import com.aardarch.aardink.ui.view.EditorController
import com.aardarch.aardink.ui.view.EditorDecorations
import com.aardarch.aardink.ui.view.EditorGutterView
import com.aardarch.aardink.ui.view.EditorInputElement
import com.aardarch.aardink.ui.view.EditorMetrics
import com.aardarch.aardink.ui.view.EditorSelectionHandles
import com.aardarch.aardink.ui.view.EditorTouchMenu
import com.aardarch.aardink.ui.view.EditorViewport
import com.aardarch.aardink.ui.view.Guides
import com.aardarch.aardink.ui.view.GutterContent
import com.aardarch.aardink.ui.view.INDENT_GLYPH
import com.aardarch.aardink.ui.view.MinimapView
import com.aardarch.aardink.ui.view.OverviewRuler
import com.aardarch.aardink.ui.view.RulerColors
import com.aardarch.aardink.ui.view.StickyLine
import com.aardarch.aardink.ui.view.ViewStyle
import com.aardarch.aardink.ui.view.drawStickyLines
import com.aardarch.aardink.ui.view.overviewRulerMarks
import com.aardarch.aardink.ui.view.stickyLineAt
import com.aardarch.aardink.ui.view.stickyLines
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * The gutter, text area and scrollbars of the editor's own renderer: what the `BasicTextField`
 * and its gutter are in the text-field one. Only the lines on screen are laid out and drawn.
 *
 * [controller] handles the input: focus, keys, the input method, the pointer, the clipboard.
 *
 * @param gutter what the gutter shows, or null for no gutter.
 */
@Composable
internal fun VirtualizedEditorBody(
    state: CodeEditorState,
    controller: EditorController,
    foldState: FoldState?,
    diagnostics: List<Diagnostic>,
    findReplaceState: FindReplaceState?,
    gutter: GutterContent?,
    options: EditorOptions,
    textColor: Color,
    modifier: Modifier = Modifier,
    /** The ranges sticky scroll pins the first lines of, sorted by start line, outer ones first. */
    stickyRanges: List<FoldRange> = emptyList(),
    popups: @Composable () -> Unit = {},
    /** The colours to draw swatches under, read when the text draws. */
    colorSwatches: () -> List<ColorInfo> = { emptyList() },
    /** Pinch and Ctrl+wheel: called with how much larger to make the font; null when the editor does not zoom. */
    onZoom: ((Float) -> Unit)? = null,
) {
    val view = controller.view
    val density = LocalDensity.current
    val typography = LocalEditorTypography.current
    val theme = LocalEditorTheme.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val ligatures = options.fontLigatures
    val textStyle = remember(typography, textColor, ligatures) {
        TextStyle(
            fontFeatureSettings = if (ligatures) null else "liga 0, calt 0, dlig 0",
            fontFamily = typography.fontFamily,
            fontSize = typography.fontSize,
            lineHeight = typography.lineHeight,
            color = textColor,
            // Every line is its own paragraph: none may lose the leading a whole-document
            // paragraph trims only from its first and last line.
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
        )
    }
    val metrics = remember(density, textStyle, measurer, typography) {
        val sample = measurer.measure(CHAR_WIDTH_SAMPLE, textStyle)
        with(density) {
            EditorMetrics(
                lineHeight = typography.lineHeight.toPx(),
                charWidth = sample.getLineRight(0) / CHAR_WIDTH_SAMPLE.length,
                paddingTop = EditorDefaults.contentPaddingTop.toPx(),
                paddingBottom = 0f,
                paddingStart = EditorDefaults.contentPaddingHorizontal.toPx(),
                paddingEnd = EditorDefaults.contentPaddingHorizontal.toPx(),
            )
        }
    }
    // Colours and font styles found as Monaco's themes find them: by the type, else by the
    // longest prefix of its dotted name the theme has an entry for.
    val tokenStyles = remember(theme) { TokenStyleResolver(theme) }
    val placeholderStyle = remember(textColor) { SpanStyle(color = textColor.copy(alpha = 0.4f), fontStyle = FontStyle.Italic) }
    val softWrap = options.softWrap
    val bracketStyles = remember(theme, options.bracketPairColorization) {
        if (options.bracketPairColorization) theme.bracketPairColors.map { SpanStyle(color = it) } else null
    }
    val columnWidthEm = remember(density, metrics, typography) {
        with(density) { metrics.charWidth / typography.fontSize.toPx() }
    }
    // A monospace font draws the indentation's stand-in glyph as wide as a space; any other
    // font keeps its spaces, so its indentation does not change width.
    val joinIndentation = remember(textStyle, measurer) {
        val glyph = measurer.measure(INDENT_GLYPH.toString(), textStyle).getLineRight(0)
        val space = measurer.measure(" ", textStyle).getLineRight(0)
        abs(glyph - space) < 0.5f
    }
    val style = ViewStyle(
        measurer, textStyle, tokenStyles::spanStyle, placeholderStyle, metrics, softWrap, bracketStyles,
        tabSize = options.tabSize,
        columnWidthEm = columnWidthEm,
        joinIndentation = joinIndentation,
        scrollBeyondLastLine = options.scrollBeyondLastLine,
        cursorSurroundingLines = options.cursorSurroundingLines,
        smoothScrolling = options.smoothScrolling,
        wrappingIndent = options.wrappingIndent,
        wordWrapColumn = options.wordWrapColumn,
    )
    // A reveal with smooth scrolling on: animated here, and called off by the next.
    LaunchedEffect(view) {
        snapshotFlow { view.smoothScroll }.collectLatest { target ->
            if (target != null) view.scroll.animateScrollToY(target.y)
        }
    }
    // Faint, so that it does not read as the document's text; a hint gets a ground as well, as VS
    // Code's, to tell it from what surrounds it.
    val decorationTextStyle = remember(textColor) { SpanStyle(color = textColor.copy(alpha = 0.6f)) }
    val hintStyle = remember(textColor) { SpanStyle(color = textColor.copy(alpha = 0.6f), background = textColor.copy(alpha = 0.08f)) }
    // Fainter than either, as Monaco's `editorGhostText.foreground`, and with no ground: it is about to be text.
    val ghostStyle = remember(textColor) { SpanStyle(color = textColor.copy(alpha = 0.45f)) }
    // Whether the mouse is on text a click does something on: asked as it moves, changing rarely.
    val onClickable by remember(controller, view) {
        derivedStateOf { controller.hoverAt?.let { view.offsetAt(it).injection?.id != null } == true }
    }
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboard.current
    SideEffect {
        view.style = style
        view.inline.setStyles(hintStyle, decorationTextStyle, decorationTextStyle, ghostStyle)
        view.foldState = foldState
        controller.pointer.foldState = foldState
        controller.pointer.dragAndDrop = options.dragAndDrop && !options.readOnly
        controller.clipboard = clipboard
        controller.requestFocus = { runCatching { focusRequester.requestFocus() } }
    }

    // The carets blink while the editor has focus, and show solid again after every change.
    var caretAlpha by remember { mutableFloatStateOf(1f) }
    val blinking = options.cursorBlinking
    LaunchedEffect(controller, blinking) {
        snapshotFlow { Triple(controller.focused, state.selections, state.textVersion) }.collectLatest { (focused) ->
            caretAlpha = 1f
            if (blinking == CursorBlinking.Solid) return@collectLatest
            while (focused) {
                delay(CARET_BLINK_MS)
                if (blinking == CursorBlinking.Smooth) {
                    animate(1f, 0f, animationSpec = tween(CARET_FADE_MS)) { value, _ -> caretAlpha = value }
                    delay(CARET_BLINK_MS - CARET_FADE_MS)
                    animate(0f, 1f, animationSpec = tween(CARET_FADE_MS)) { value, _ -> caretAlpha = value }
                } else {
                    caretAlpha = 0f
                    delay(CARET_BLINK_MS)
                    caretAlpha = 1f
                }
            }
        }
    }
    // A tap in the text asks for the on-screen keyboard. On the web this also gives Compose's hidden
    // text area the page's focus back, which a touch on the canvas takes away.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(controller, keyboard) {
        var seen = controller.ime.keyboardRequests
        snapshotFlow { controller.ime.keyboardRequests }.collect {
            if (it != seen) keyboard?.show()
            seen = it
        }
    }
    EditorClipboardEvents(
        active = controller.focused,
        onCopy = { controller.copyText() },
        onCut = { controller.cutText() },
        onPaste = { controller.pasteText(it) },
    )

    val colors = EditorColors(
        selection = theme.selectionColor,
        findMatch = theme.findMatchColor,
        // The current match stands out from the others as VS Code's does: the same colour, stronger.
        currentFindMatch = theme.findMatchColor.copy(alpha = (theme.findMatchColor.alpha * 2f).coerceAtMost(1f)),
        error = theme.errorColor,
        warning = theme.warningColor,
        info = theme.infoColor,
        caret = theme.cursorColor,
        text = textColor,
        lineHighlight = theme.lineHighlight,
        bracketMatch = textColor.copy(alpha = 0.18f),
        // Faint, as VS Code's (its dark theme's is a light grey at 16 %); a dot is small, so a
        // little stronger than that.
        whitespace = theme.whitespaceColor.takeOrElse { textColor.copy(alpha = 0.25f) },
        // Monaco's editor.snippetTabstopHighlightBackground.
        snippetTabStop = Color(0x4D7C7C7C),
        background = theme.background,
        // VS Code's dark theme draws its indent guides at about 20 %, the active one at about 45 %.
        indentGuide = textColor.copy(alpha = 0.16f),
        activeIndentGuide = textColor.copy(alpha = 0.45f),
        ruler = textColor.copy(alpha = 0.2f),
        bracketGuides = theme.bracketPairColors.map { it.copy(alpha = it.alpha * 0.5f) },
    )
    // The guides around the view: worked out again when the text, its tokens or the tab size
    // change, or the view scrolls out of the lines last worked out, not on every frame.
    val indentGuides = options.indentGuides
    val bracketPairGuides = options.bracketPairGuides
    val guideTabSize = options.tabSize
    val guides by remember(state, view, indentGuides, bracketPairGuides, guideTabSize) {
        var last: Guides? = null
        var lastVersion = -1 to -1
        derivedStateOf {
            if (!indentGuides && !bracketPairGuides) return@derivedStateOf null
            val shown = view.frame.lines
            if (shown.isEmpty()) return@derivedStateOf last
            val version = state.textVersion to state.tokenVersion
            val from = shown.first().line
            val to = shown.last().line
            val document = state.document
            last?.takeIf { lastVersion == version && it.covers(from, to, document.lineCount) }?.let { return@derivedStateOf it }
            Guides.compute(document, from, to, guideTabSize, bracketPairGuides) { line, action ->
                state.brackets.forEachBracket(line, action)
            }.also {
                last = it
                lastVersion = version
            }
        }
    }
    val activeGuide by remember(state) {
        derivedStateOf {
            val shown = guides ?: return@derivedStateOf null
            val document = state.document
            shown.activeGuide(document.offsetToLineCol(state.selection.end.coerceIn(0, document.length)).first)
        }
    }
    // The pair the caret touches, worked out when the caret, the text or its tokens change, not
    // on every frame the content draws (scrolling redraws it every frame).
    val matchBrackets = rememberUpdatedState(options.matchBrackets)
    val bracketMatch by remember(state) {
        derivedStateOf {
            state.textVersion
            state.tokenVersion
            if (matchBrackets.value) state.matchingBracket()?.let { it.open to it.close } else null
        }
    }
    val highlightCaretLines = options.highlightCurrentLine
    val renderWhitespace = options.renderWhitespace
    val currentStickyRanges = rememberUpdatedState(stickyRanges)
    val sticky: () -> List<StickyLine> = { view.stickyLines(currentStickyRanges.value) }
    SideEffect { controller.pointer.stickyLineAt = { position -> view.stickyLineAt(currentStickyRanges.value, position) } }
    val lineCount = remember(state.textVersion) { state.document.lineCount }
    // The overview ruler's marks, and the minimap's: worked out when what they show changes, not
    // per frame.
    val currentDiagnostics = rememberUpdatedState(diagnostics)
    val currentFind = rememberUpdatedState(findReplaceState)
    val currentDiff = rememberUpdatedState(gutter?.diffAnnotations.orEmpty())
    val rulerColors = remember(theme) {
        val ruler = theme.overviewRulerColors
        RulerColors(
            error = theme.errorColor,
            warning = theme.warningColor,
            info = theme.infoColor,
            findMatch = ruler.findMatch.takeOrElse { theme.findMatchColor.copy(alpha = 1f) },
            selection = ruler.selection.takeOrElse { theme.cursorColor.copy(alpha = 0.6f) },
            added = ruler.added.takeOrElse { Color(0xFF4CAF50) },
            modified = ruler.modified.takeOrElse { Color(0xFF2196F3) },
        )
    }
    val showRuler = options.overviewRuler
    val rulerMarks by remember(state, rulerColors) {
        derivedStateOf {
            overviewRulerMarks(
                document = state.document,
                diagnostics = currentDiagnostics.value,
                findMatches = currentFind.value?.matches.orEmpty(),
                selections = state.selections,
                diff = currentDiff.value,
                decorations = state.shownDecorations,
                colors = rulerColors,
            )
        }
    }
    // The minimap marks the host's decorations, and diagnostics and find matches as the ruler does.
    val minimapMarks by remember(state, rulerColors) {
        derivedStateOf {
            val decorated = lineMarks(state.shownDecorations, state.document) { it.minimapColor }
            val document = state.document
            val length = document.length
            val found = currentFind.value?.matches.orEmpty().map {
                val line = document.offsetToLineCol(it.first.coerceIn(0, length)).first
                LineMark(line, line, rulerColors.findMatch)
            }
            val problems = currentDiagnostics.value
                .filter { it.severity == DiagnosticSeverity.Error || it.severity == DiagnosticSeverity.Warning }
                .sortedByDescending { it.severity }
                .map {
                    val line = document.offsetToLineCol(it.range.first.coerceIn(0, length)).first
                    LineMark(line, line, if (it.severity == DiagnosticSeverity.Error) rulerColors.error else rulerColors.warning)
                }
            decorated + found + problems
        }
    }

    EditorBody(
        view = view,
        modifier = modifier,
        gutter = {
            if (gutter != null) {
                EditorGutterView(
                    view,
                    lineCount,
                    theme.gutterBackground,
                    theme.gutterForeground,
                    severityColor = { kind ->
                        when (kind) {
                            GutterAnnotationKind.Error -> colors.error
                            GutterAnnotationKind.Warning -> colors.warning
                            GutterAnnotationKind.Info -> colors.info
                        }
                    },
                    content = gutter,
                    sticky = sticky,
                )
            }
        },
        viewport = {
            EditorViewport(
                view = view,
                pointer = controller.pointer,
                decorations = {
                    EditorDecorations(
                        selections = state.selections,
                        findMatches = findReplaceState?.matches.orEmpty(),
                        currentFindMatch = findReplaceState?.currentMatchIndex ?: -1,
                        diagnostics = diagnostics,
                        composition = controller.ime.composition,
                        highlightCaretLines = highlightCaretLines,
                        bracketMatch = bracketMatch,
                        link = controller.link.takeIf { controller.linkModifier },
                        renderWhitespace = renderWhitespace,
                        snippetTabStops = state.snippet?.let { snippet ->
                            snippet.version
                            snippet.placeholderRanges()
                        }.orEmpty(),
                        hostDecorations = state.shownDecorations,
                        colorSwatches = colorSwatches(),
                        guides = guides,
                        indentGuides = indentGuides,
                        bracketPairGuides = bracketPairGuides,
                        activeGuide = activeGuide,
                        rulers = options.rulers,
                    )
                },
                carets = {
                    if (controller.focused) {
                        val drop = controller.pointer.dropCaret
                        if (drop == null) state.selections else state.selections + TextRange(drop)
                    } else {
                        emptyList()
                    }
                },
                colors = colors,
                caretStyle = options.cursorStyle,
                caretAlpha = { caretAlpha },
                softWrap = softWrap,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(EditorTestTags.EDITOR)
                    .pointerHoverIcon(
                        if (onClickable ||
                            (controller.linkModifier && controller.link != null)
                        ) {
                            PointerIcon.Hand
                        } else {
                            PointerIcon.Text
                        },
                    )
                    .pointerInput(controller) { trackHover(controller) }
                    .then(if (onZoom != null) Modifier.pointerInput(Unit) { zoomGestures(onZoom) } else Modifier),
                inputModifier = Modifier
                    .then(EditorInputElement(controller))
                    .focusRequester(focusRequester)
                    .focusTarget()
                    .editorMagnifier { controller.magnifierCenter },
                overlay = {
                    // Over the text and the carets, as VS Code's sticky widget is.
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBehind { drawStickyLines(view, sticky(), theme.background, textColor.copy(alpha = 0.12f)) },
                    )
                    EditorSelectionHandles(controller, theme.cursorColor)
                    EditorContextMenu(controller)
                    EditorTouchMenu(controller)
                    CompositionLocalProvider(LocalDocumentState provides state, content = popups)
                },
            )
        },
        scrollbars = { EditorScrollbars(view.scroll, horizontal = !softWrap, modifier = Modifier.fillMaxSize()) },
        overviewRuler = {
            if (showRuler) {
                OverviewRuler(
                    view = view,
                    marks = { rulerMarks },
                    background = theme.overviewRulerColors.background.takeOrElse { theme.background },
                    border = theme.overviewRulerColors.border.takeOrElse { textColor.copy(alpha = 0.12f) },
                    visibleRegion = textColor.copy(alpha = 0.08f),
                )
            }
        },
        minimap = {
            if (options.showMinimap) {
                MinimapView(
                    view = view,
                    tokenColor = { type -> (tokenStyles.color(type) ?: textColor).copy(alpha = 0.7f) },
                    background = theme.background,
                    slider = textColor.copy(alpha = 0.12f),
                    tabSize = options.tabSize,
                    marks = { minimapMarks },
                )
            }
        },
    )
}

/**
 * Where a mouse rests over the text, for hover documentation: [EditorController.hoverAt] follows a
 * mouse that moves with no button down, and clears when it leaves, presses, or is a touch. Only
 * looks; the events go on to selection and scrolling untouched.
 */
private suspend fun PointerInputScope.trackHover(controller: EditorController) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (change.type == PointerType.Mouse) {
                val modifiers = event.keyboardModifiers
                controller.linkModifier = if (PlatformInfo.isMacOs) modifiers.isMetaPressed else modifiers.isCtrlPressed
            }
            controller.hoverAt = when {
                change.type != PointerType.Mouse -> null
                event.type == PointerEventType.Exit || event.type == PointerEventType.Press -> null
                event.buttons.areAnyPressed -> null
                event.type == PointerEventType.Move || event.type == PointerEventType.Enter -> change.position
                else -> controller.hoverAt
            }
        }
    }
}

/** Half a caret blink: VS Code's default, 1.06 s a cycle. */
private const val CARET_BLINK_MS = 530L

/** How long a smooth blink takes to fade out or in. */
private const val CARET_FADE_MS = 200

/** Measured to find the advance of one character of the (monospace) editor font. */
private const val CHAR_WIDTH_SAMPLE = "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * Pinch and Ctrl+wheel (Cmd+wheel on macOS) zoom: [onZoom] gets how much larger to make the font.
 * Looks at events before the text's scrolling does, and takes only those it uses: two fingers on
 * the text, or a wheel turned with the modifier held. One finger and a plain wheel scroll as before.
 */
private suspend fun PointerInputScope.zoomGestures(onZoom: (Float) -> Unit) {
    awaitPointerEventScope {
        var pinch = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Scroll) {
                val modifiers = event.keyboardModifiers
                val zoomKey = if (PlatformInfo.isMacOs) modifiers.isMetaPressed else modifiers.isCtrlPressed
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (zoomKey && delta != 0f) {
                    onZoom(if (delta < 0f) WHEEL_ZOOM else 1f / WHEEL_ZOOM)
                    event.changes.forEach { it.consume() }
                }
                continue
            }
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                pinch *= event.calculateZoom()
                // In steps, so a slow pinch does not lay the text out again on every frame.
                if (pinch > 1f + PINCH_STEP || pinch < 1f - PINCH_STEP) {
                    onZoom(pinch)
                    pinch = 1f
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else {
                pinch = 1f
            }
        }
    }
}

/** How much larger one notch of Ctrl+wheel makes the font. */
private const val WHEEL_ZOOM = 1.1f

/** How far a pinch goes before the font size follows it. */
private const val PINCH_STEP = 0.06f
