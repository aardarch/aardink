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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FindEngine
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.LineDiffKind
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SimpleDiffProvider
import com.aardarch.aardink.core.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The canonical code editor composable.
 *
 * Layout (top → bottom):
 *   - [FindReplacePanel] (slides down when visible)
 *   - [AnnotationTooltip] (shows when user taps a gutter annotation dot)
 *   - Row: [EditorGutter] | scrollable [BasicTextField]
 *   - [CompletionDropdown] strip
 *   - [KeyboardToolbarRow]
 *
 * @param diagnostics Language-service diagnostics; rendered as gutter dots and squiggle underlines.
 * @param savedText Baseline text for the diff lane (typically the last-saved version).
 * @param softWrap When true, long lines wrap to the editor width and horizontal scrolling is disabled.
 */
@Composable
fun CodeEditorLayout(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    annotatedText: AnnotatedString? = null,
    languageService: LanguageService? = null,
    findReplaceState: FindReplaceState? = null,
    foldState: FoldState? = null,
    foldingProvider: FoldingProvider = NoOpFoldingProvider,
    diagnostics: List<Diagnostic> = emptyList(),
    savedText: String = "",
    onCursorChange: (line: Int, column: Int) -> Unit = { _, _ -> },
    readOnly: Boolean = false,
    toolbarStyle: KeyboardToolbarStyle = KeyboardToolbarDefaults.style(),
    keyboardToolbarPlacement: KeyboardToolbarPlacement = KeyboardToolbarPlacement.BottomHover,
    showGutter: Boolean = true,
    showLineNumbers: Boolean = true,
    showFoldMarkers: Boolean = true,
    showDiagnosticAnnotations: Boolean = true,
    showDiffMarkers: Boolean = true,
    softWrap: Boolean = false,
) {
    val density = LocalDensity.current
    val lineHeightPx = with(density) { EditorDefaults.lineHeight.toPx() }
    val topPaddingPx = with(density) { EditorDefaults.contentPaddingTop.toPx() }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val theme = LocalEditorTheme.current
    val textColor = theme.tokenColors[TokenType.Default] ?: MaterialTheme.colorScheme.onSurface
    val gutterBackground = theme.gutterBackground
    val gutterForeground = theme.gutterForeground
    val cursorColor = theme.cursorColor

    val textVersion = state.textVersion
    val tokenVersion = state.tokenVersion
    val documentLineCount = remember(textVersion) { state.document.lineCount }

    // Auto-derive an AnnotatedString from the token cache + theme when the consumer didn't pass
    // one explicitly. Recomputed on token-cache or theme changes; clamped to the current text so
    // a stale token list (one tokenization tick behind an edit) is safe.
    val effectiveAnnotatedText: AnnotatedString? = remember(annotatedText, tokenVersion, textVersion, theme) {
        if (annotatedText != null) return@remember annotatedText
        val cachedTokens = state.tokenCache.tokens
        if (cachedTokens.isEmpty()) return@remember null
        annotateTokens(state.document.text, cachedTokens, theme)
    }

    var fieldValue by remember { mutableStateOf(TextFieldValue(state.document.text)) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Completion state
    var completionItems by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var showCompletion by remember { mutableStateOf(false) }
    var completionJob by remember { mutableStateOf<Job?>(null) }

    // Code actions, Signature help & Rename state
    var showCodeActionsMenu by remember { mutableStateOf(false) }
    var currentSignatureHelp by remember { mutableStateOf<SignatureHelp?>(null) }
    var showSignatureHelp by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetName by remember { mutableStateOf("") }
    var renameTargetRange by remember { mutableStateOf(IntRange.EMPTY) }

    // ── Rename: resolve the symbol the host asked to rename, then open the dialog ─
    // A null `prepareRename` means "this symbol cannot be renamed" — including the default
    // implementation of a service that has no rename at all. Opening the dialog anyway produced a
    // prompt whose confirmation silently did nothing, so the request just ends here instead.
    LaunchedEffect(state, languageService) {
        snapshotFlow { state.pendingRename }.collect { request ->
            if (request == null) return@collect
            state.clearRename()
            val text = state.document.text
            val range = withContext(Dispatchers.Default) {
                languageService?.prepareRename(state.document, request.offset)
            } ?: return@collect
            if (range.isEmpty()) return@collect
            renameTargetRange = range
            renameTargetName = text.substring(range.first, (range.last + 1).coerceAtMost(text.length))
            showRenameDialog = true
        }
    }

    if (languageService != null) {
        LaunchedEffect(languageService, state.selection, textVersion) {
            val offset = state.selection.start
            if (offset < 0 || offset > state.document.length) return@LaunchedEffect
            // Keep asking while the cursor sits inside the argument list, not just right after
            // '(' or ',' — otherwise the popup vanishes on the first character of an argument.
            // The service decides when the context has stopped being valid by returning null.
            if (!isInsideCallArguments(state.document.text, offset)) {
                showSignatureHelp = false
                return@LaunchedEffect
            }
            // Debounced like the find and folding passes: a language server should not field a
            // request per keystroke.
            delay(SIGNATURE_HELP_DEBOUNCE_MS)
            val sig = withContext(Dispatchers.Default) {
                languageService.signatureHelp(state.document, offset)
            }
            currentSignatureHelp = sig
            showSignatureHelp = sig != null && sig.signatures.isNotEmpty()
        }
    }

    // Annotation tooltip: shown when user taps a gutter dot
    var tooltipDiagnostic by remember { mutableStateOf<Diagnostic?>(null) }

    // Quick fixes for the tapped diagnostic — the only way the action menu is opened. Tapping a
    // gutter dot doesn't move the cursor, so actions are fetched for the diagnostic's own range,
    // lazily, rather than for every cursor position the user passes through.
    var tooltipCodeActions by remember { mutableStateOf<List<CodeAction>>(emptyList()) }
    LaunchedEffect(languageService, tooltipDiagnostic, textVersion) {
        tooltipCodeActions = emptyList()
        val tooltip = tooltipDiagnostic ?: return@LaunchedEffect
        if (languageService == null) return@LaunchedEffect
        tooltipCodeActions = withContext(Dispatchers.Default) {
            languageService.codeActions(state.document, tooltip.range)
        }
    }

    // Diff lane: compute line diffs when savedText or document changes
    var diffAnnotations by remember { mutableStateOf<Map<Int, LineDiffKind>>(emptyMap()) }
    LaunchedEffect(savedText, textVersion) {
        if (savedText.isEmpty()) {
            diffAnnotations = emptyMap()
            return@LaunchedEffect
        }
        val current = state.document.text
        if (current == savedText) {
            diffAnnotations = emptyMap()
            return@LaunchedEffect
        }
        diffAnnotations = withContext(Dispatchers.Default) {
            SimpleDiffProvider.diff(savedText.lines(), current.lines())
                .associate { it.lineIndex to it.kind }
        }
    }

    // Gutter annotations: highest-severity diagnostic per line
    val gutterAnnotations = remember(diagnostics) {
        diagnostics
            .groupBy { it.lineNumber }
            .mapValues { (_, diags) ->
                val worst = diags.maxBy { it.severity.ordinal }
                when (worst.severity) {
                    DiagnosticSeverity.Error -> GutterAnnotationKind.Error
                    DiagnosticSeverity.Warning -> GutterAnnotationKind.Warning
                    DiagnosticSeverity.Info -> GutterAnnotationKind.Info
                }
            }
    }

    val triggerChars = remember(languageService) {
        languageService?.triggerCharacters ?: emptySet()
    }

    // Keep fieldValue in sync when text changes externally (undo/redo/load from ViewModel)
    LaunchedEffect(state) {
        snapshotFlow { state.textVersion }
            .collect { _ ->
                val currentText = state.document.text
                if (fieldValue.text != currentText) {
                    fieldValue = TextFieldValue(
                        text = currentText,
                        selection = state.selection,
                    )
                    showCompletion = false
                    tooltipDiagnostic = null
                }
            }
    }

    // Report cursor position changes upward
    LaunchedEffect(fieldValue.selection) {
        val sel = fieldValue.selection
        val (line, col) = state.document.offsetToLineCol(sel.start)
        onCursorChange(line + 1, col + 1)
        state.selection = sel
    }

    // ── Find/Replace: re-run search on query/option/text changes (debounced) ──
    if (findReplaceState != null) {
        LaunchedEffect(findReplaceState, state) {
            snapshotFlow {
                listOf(
                    findReplaceState.visible,
                    findReplaceState.query,
                    findReplaceState.caseSensitive,
                    findReplaceState.wholeWord,
                    findReplaceState.useRegex,
                    state.textVersion,
                )
            }.collect { _ ->
                if (!findReplaceState.visible || findReplaceState.query.isEmpty()) {
                    findReplaceState.matches = emptyList()
                    findReplaceState.currentMatchIndex = -1
                    return@collect
                }
                delay(200)
                val text = state.document.text
                val opts = findReplaceState.toOptions()
                val matches = withContext(Dispatchers.Default) {
                    FindEngine.findAll(text, findReplaceState.query, opts)
                }
                findReplaceState.matches = matches
                findReplaceState.currentMatchIndex = if (matches.isEmpty()) -1 else 0
            }
        }
    }

    // ── Folding: recompute foldable ranges when text changes ─────────────────
    if (foldState != null) {
        LaunchedEffect(foldState, state, foldingProvider) {
            snapshotFlow { state.textVersion }.collect { _ ->
                delay(200)
                val ranges = withContext(Dispatchers.Default) {
                    foldingProvider.foldableRanges(state.document)
                }
                foldState.updateFoldableRanges(ranges)
            }
        }
    }

    // ── Pending navigation: scroll to target offset and update selection ─────
    LaunchedEffect(state) {
        snapshotFlow { state.pendingNavigation }.collect { nav ->
            if (nav == null) return@collect
            val tlr = textLayoutResult
            val targetY = if (tlr != null) {
                val transformedLength = tlr.layoutInput.text.length
                val offset = nav.targetOffset.coerceIn(0, transformedLength)
                val visualRow = tlr.getLineForOffset(offset)
                    .coerceIn(0, (tlr.lineCount - 1).coerceAtLeast(0))
                (topPaddingPx + tlr.getLineTop(visualRow) - lineHeightPx * 3).coerceAtLeast(0f).toInt()
            } else {
                val (line, _) = state.document.offsetToLineCol(nav.targetOffset)
                (topPaddingPx + line * lineHeightPx - lineHeightPx * 3).coerceAtLeast(0f).toInt()
            }
            verticalScrollState.animateScrollTo(targetY)
            if (nav.select != null) {
                fieldValue = fieldValue.copy(selection = nav.select)
                state.selection = nav.select
            } else {
                val sel = TextRange(nav.targetOffset)
                fieldValue = fieldValue.copy(selection = sel)
                state.selection = sel
            }
            state.clearNavigation()
        }
    }

    val matches = findReplaceState?.matches ?: emptyList()
    val currentMatchIndex = findReplaceState?.currentMatchIndex ?: -1
    val matchHighlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val currentMatchHighlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)

    val foldedRanges = foldState?.foldedRanges() ?: emptyList()

    val syntaxTransformation =
        remember(effectiveAnnotatedText, textColor, matches, currentMatchIndex, matchHighlight, currentMatchHighlight, foldedRanges) {
            VisualTransformation { inputText ->
                val base = if (effectiveAnnotatedText != null && effectiveAnnotatedText.text == inputText.text) {
                    effectiveAnnotatedText
                } else {
                    buildAnnotatedString {
                        append(inputText.text)
                        addStyle(SpanStyle(color = textColor), 0, inputText.text.length)
                    }
                }
                val highlighted = if (matches.isEmpty()) {
                    base
                } else {
                    buildAnnotatedString {
                        append(base)
                        matches.forEachIndexed { idx, range ->
                            val bg = if (idx == currentMatchIndex) currentMatchHighlight else matchHighlight
                            val end = (range.last + 1).coerceAtMost(inputText.text.length)
                            val start = range.first.coerceAtLeast(0).coerceAtMost(end)
                            if (start < end) addStyle(SpanStyle(background = bg), start, end)
                        }
                    }
                }
                if (foldedRanges.isEmpty()) {
                    TransformedText(highlighted, OffsetMapping.Identity)
                } else {
                    applyFolding(
                        text = highlighted,
                        foldedRanges = foldedRanges,
                        document = state.document,
                        placeholderStyle = SpanStyle(
                            color = textColor.copy(alpha = 0.4f),
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                }
            }
        }

    val foldableLines = foldState?.foldableRanges?.map { it.startLine }?.toSet() ?: emptySet()
    // foldedRanges already computed above for syntaxTransformation

    val toolbar: @Composable () -> Unit = {
        if (keyboardToolbarPlacement != KeyboardToolbarPlacement.Hidden) {
            KeyboardToolbarRow(
                quickChars = remember(state.tokenizer) { state.tokenizer.keyboardToolbarChars() },
                canUndo = state.undoManager.canUndo,
                canRedo = state.undoManager.canRedo,
                onInsertChar = { char ->
                    val sel = fieldValue.selection
                    val insertAt = sel.start
                    val newText = fieldValue.text.let {
                        it.substring(0, insertAt) + char + it.substring(insertAt)
                    }
                    val newSelection = TextRange(insertAt + 1)
                    state.applyEdit(insertAt, 0, char.toString(), newSelection)
                    fieldValue = TextFieldValue(newText, newSelection)
                },
                onMoveCursorLeft = {
                    val newPos = (fieldValue.selection.start - 1).coerceAtLeast(0)
                    val newSel = TextRange(newPos)
                    state.selection = newSel
                    fieldValue = fieldValue.copy(selection = newSel)
                    showCompletion = false
                },
                onMoveCursorRight = {
                    val newPos = (fieldValue.selection.start + 1).coerceAtMost(fieldValue.text.length)
                    val newSel = TextRange(newPos)
                    state.selection = newSel
                    fieldValue = fieldValue.copy(selection = newSel)
                    showCompletion = false
                },
                onUndo = {
                    state.undo()?.let { newText ->
                        fieldValue = TextFieldValue(newText, state.selection)
                        showCompletion = false
                    }
                },
                onRedo = {
                    state.redo()?.let { newText ->
                        fieldValue = TextFieldValue(newText, state.selection)
                        showCompletion = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                style = toolbarStyle,
                alwaysVisible = keyboardToolbarPlacement != KeyboardToolbarPlacement.BottomHover,
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .imePadding(),
    ) {
        // ── Find/Replace panel (slides down from the top) ────────────────────
        if (findReplaceState != null) {
            FindReplacePanel(
                state = findReplaceState,
                onNext = {
                    val match = findReplaceState.nextMatch() ?: return@FindReplacePanel
                    state.navigateTo(match.first, TextRange(match.first, match.last + 1))
                },
                onPrev = {
                    val match = findReplaceState.prevMatch() ?: return@FindReplacePanel
                    state.navigateTo(match.first, TextRange(match.first, match.last + 1))
                },
                onReplace = {
                    val idx = findReplaceState.currentMatchIndex
                    val match = findReplaceState.matches.getOrNull(idx) ?: return@FindReplacePanel
                    val replacement = findReplaceState.replacement
                    val newSel = TextRange(match.first + replacement.length)
                    state.applyEdit(match.first, match.last - match.first + 1, replacement, newSel)
                    fieldValue = TextFieldValue(state.document.text, newSel)
                },
                onReplaceAll = {
                    val all = findReplaceState.matches
                    if (all.isEmpty()) return@FindReplacePanel
                    val replacement = findReplaceState.replacement
                    all.sortedByDescending { it.first }.forEach { range ->
                        state.applyEdit(
                            deleteOffset = range.first,
                            deleteLength = range.last - range.first + 1,
                            insertText = replacement,
                            newSelection = TextRange(range.first + replacement.length),
                        )
                    }
                    fieldValue = TextFieldValue(state.document.text, state.selection)
                },
                onClose = { findReplaceState.hide() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Annotation tooltip (shown when gutter dot is tapped) ─────────────
        val tooltip = tooltipDiagnostic
        if (tooltip != null) {
            AnnotationTooltip(
                message = tooltip.message,
                severity = tooltip.severity,
                onDismiss = { tooltipDiagnostic = null },
                onQuickFix = if (tooltipCodeActions.isNotEmpty()) {
                    { showCodeActionsMenu = true }
                } else {
                    null
                },
            )
        }

        // ── Signature help, Code action popups & Rename Dialog ────────────────
        if (showSignatureHelp && currentSignatureHelp != null) {
            SignatureHelpPopup(
                help = currentSignatureHelp!!,
                onDismiss = { showSignatureHelp = false },
            )
        }

        if (showCodeActionsMenu && tooltipCodeActions.isNotEmpty()) {
            CodeActionMenu(
                actions = tooltipCodeActions,
                onSelectAction = { action ->
                    state.applyTextEdits(action.edits)
                    fieldValue = TextFieldValue(state.document.text, state.selection)
                    showCodeActionsMenu = false
                },
                onDismiss = { showCodeActionsMenu = false },
            )
        }

        if (showRenameDialog && renameTargetName.isNotEmpty()) {
            RenameDialog(
                currentName = renameTargetName,
                onConfirm = { newName ->
                    val target = renameTargetRange.first
                    coroutineScope.launch {
                        val edits = languageService?.rename(state.document, target, newName) ?: emptyList()
                        if (edits.isNotEmpty()) {
                            state.applyTextEdits(edits)
                            fieldValue = TextFieldValue(state.document.text, state.selection)
                        }
                    }
                    showRenameDialog = false
                },
                onDismiss = { showRenameDialog = false },
            )
        }

        if (keyboardToolbarPlacement == KeyboardToolbarPlacement.Top) {
            toolbar()
        }

        // ── Editor body: gutter + text area ──────────────────────────────────
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (showGutter) {
                // Use the document's logical line count as the source of truth and ask the
                // text layout where each line actually sits. This keeps the gutter aligned
                // with what BasicTextField rendered, regardless of trailing-newline phantom
                // lines, soft wrap, or folds. NaN entries mark logical lines that collapsed
                // onto the previous visual row (a phantom trailing line not rendered by
                // Compose) and are skipped by the gutter renderer.
                val gutterLineCount = documentLineCount
                val lineTops: FloatArray? = remember(textLayoutResult, textVersion, foldedRanges) {
                    val tlr = textLayoutResult ?: return@remember null
                    val transformedLength = tlr.layoutInput.text.length
                    val maxRow = (tlr.lineCount - 1).coerceAtLeast(0)
                    var prevRow = -1
                    FloatArray(documentLineCount) { i ->
                        val origOffset = state.document.lineStart(i)
                        val transformedOffset = originalToTransformedOffset(state.document, foldedRanges, origOffset)
                            .coerceIn(0, transformedLength)
                        val visualRow = tlr.getLineForOffset(transformedOffset).coerceIn(0, maxRow)
                        if (visualRow == prevRow) {
                            Float.NaN
                        } else {
                            prevRow = visualRow
                            topPaddingPx + tlr.getLineTop(visualRow)
                        }
                    }
                }
                val lineBottoms: FloatArray? = remember(textLayoutResult, textVersion, foldedRanges) {
                    val tlr = textLayoutResult ?: return@remember null
                    val transformedLength = tlr.layoutInput.text.length
                    val maxRow = (tlr.lineCount - 1).coerceAtLeast(0)
                    fun visualRowOf(line: Int): Int {
                        val origOffset = state.document.lineStart(line)
                        val transformedOffset = originalToTransformedOffset(state.document, foldedRanges, origOffset)
                            .coerceIn(0, transformedLength)
                        return tlr.getLineForOffset(transformedOffset).coerceIn(0, maxRow)
                    }
                    FloatArray(documentLineCount) { i ->
                        // The bottom of a logical line = top of the next non-collapsed logical
                        // line, or the last visual row's bottom for the final one.
                        val thisRow = visualRowOf(i)
                        var nextI = i + 1
                        var bottom = Float.NaN
                        while (nextI < documentLineCount) {
                            val nextRow = visualRowOf(nextI)
                            if (nextRow != thisRow) {
                                bottom = topPaddingPx + tlr.getLineTop(nextRow)
                                break
                            }
                            nextI++
                        }
                        if (bottom.isNaN()) topPaddingPx + tlr.getLineBottom(maxRow) else bottom
                    }
                }
                val lineTopProvider: ((Int) -> Float)? = lineTops?.let { tops -> { i -> tops.getOrElse(i) { Float.NaN } } }
                val lineBottomProvider: ((Int) -> Float)? = lineBottoms?.let { bottoms -> { i -> bottoms.getOrElse(i) { 0f } } }
                EditorGutter(
                    lineCount = gutterLineCount,
                    scrollState = verticalScrollState,
                    lineHeightPx = lineHeightPx,
                    topPaddingPx = topPaddingPx,
                    background = gutterBackground,
                    foreground = gutterForeground,
                    annotations = if (showDiagnosticAnnotations) gutterAnnotations else emptyMap(),
                    foldableLines = if (showFoldMarkers) foldableLines else emptySet(),
                    foldedRanges = foldedRanges,
                    onToggleFold = { line -> foldState?.toggle(line) },
                    diffAnnotations = if (showDiffMarkers) diffAnnotations else emptyMap(),
                    onAnnotationTap = { lineIndex ->
                        tooltipDiagnostic = diagnostics
                            .filter { it.lineNumber == lineIndex }
                            .maxByOrNull { it.severity.ordinal }
                    },
                    showLineNumbers = showLineNumbers,
                    lineTopProvider = lineTopProvider,
                    lineBottomProvider = lineBottomProvider,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState)
                    .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState))
                    .padding(
                        start = EditorDefaults.contentPaddingHorizontal,
                        top = EditorDefaults.contentPaddingTop,
                        end = EditorDefaults.contentPaddingHorizontal,
                    )
                    .drawBehind {
                        drawSquiggles(
                            diagnostics = diagnostics,
                            textLayoutResult = textLayoutResult,
                            errorColor = theme.errorColor,
                            warningColor = theme.warningColor,
                            infoColor = theme.infoColor,
                        )
                    },
            ) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        if (!readOnly) {
                            val oldText = fieldValue.text
                            val newText = newValue.text
                            if (oldText != newText) {
                                handleTextChange(
                                    state = state,
                                    newValue = newValue,
                                    oldText = oldText,
                                    languageService = languageService,
                                    triggerChars = triggerChars,
                                    onFieldValue = { fieldValue = it },
                                    onCompletionItems = { completionItems = it },
                                    onShowCompletion = { showCompletion = it },
                                    completionJobRef = { completionJob = it },
                                    currentCompletionJob = completionJob,
                                    coroutineScope = coroutineScope,
                                )
                            } else {
                                state.selection = newValue.selection
                                fieldValue = newValue
                            }
                        }
                    },
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = EditorDefaults.fontSize,
                        lineHeight = EditorDefaults.lineHeight,
                        color = textColor,
                    ),
                    cursorBrush = SolidColor(cursorColor),
                    visualTransformation = syntaxTransformation,
                    readOnly = readOnly,
                )
            }
        }

        // ── Completion strip ─────────────────────────────────────────────────
        CompletionDropdown(
            items = completionItems,
            visible = showCompletion,
            onAccept = { item ->
                applyCompletion(
                    state = state,
                    fieldValue = fieldValue,
                    item = item,
                    onFieldValue = { fieldValue = it },
                )
                showCompletion = false
                completionItems = emptyList()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Keyboard toolbar (when placed at bottom) ─────────────────────────
        if (keyboardToolbarPlacement == KeyboardToolbarPlacement.BottomHover ||
            keyboardToolbarPlacement == KeyboardToolbarPlacement.BottomFixed
        ) {
            toolbar()
        }
    }
}

// ── Text change handler ────────────────────────────────────────────────────────

private fun handleTextChange(
    state: CodeEditorState,
    newValue: TextFieldValue,
    oldText: String,
    languageService: LanguageService?,
    triggerChars: Set<Char>,
    onFieldValue: (TextFieldValue) -> Unit,
    onCompletionItems: (List<CompletionItem>) -> Unit,
    onShowCompletion: (Boolean) -> Unit,
    completionJobRef: (Job?) -> Unit,
    currentCompletionJob: Job?,
    coroutineScope: CoroutineScope,
) {
    val newText = newValue.text
    val delta = computeEditDelta(oldText, newText)

    state.applyEdit(delta.deleteOffset, delta.deleteLength, delta.insertText, newValue.selection)

    var finalSelection = newValue.selection

    val isSingleInsert = delta.deleteLength == 0 && delta.insertText.length == 1
    if (isSingleInsert && languageService != null) {
        val typedChar = delta.insertText[0]
        val insertedAt = delta.deleteOffset

        if (typedChar == '\n') {
            val (newLine, _) = state.document.offsetToLineCol(insertedAt + 1)
            val spaces = languageService.smartIndent(state.document, newLine)
            if (spaces > 0) {
                val indent = " ".repeat(spaces)
                val indentAt = insertedAt + 1
                finalSelection = TextRange(indentAt + spaces)
                state.applyEdit(indentAt, 0, indent, finalSelection)
            }
            onShowCompletion(false)
        }

        if (typedChar != '\n') {
            val closing = languageService.autoClose(state.document, insertedAt, typedChar)
            if (closing != null) {
                state.applyEdit(finalSelection.start, 0, closing, finalSelection)
            }
        }

        when {
            typedChar in triggerChars -> {
                currentCompletionJob?.cancel()
                completionJobRef(
                    coroutineScope.launch {
                        val items = languageService.completions(state.document, finalSelection.start)
                        onCompletionItems(items)
                        onShowCompletion(items.isNotEmpty())
                    },
                )
            }

            typedChar.isLetterOrDigit() || typedChar == '_' -> {
                currentCompletionJob?.cancel()
                completionJobRef(
                    coroutineScope.launch {
                        val items = languageService.completions(state.document, finalSelection.start)
                        onCompletionItems(items)
                        if (items.isEmpty()) onShowCompletion(false)
                    },
                )
            }

            else -> onShowCompletion(false)
        }
    } else if (!isSingleInsert) {
        onShowCompletion(false)
    }

    onFieldValue(TextFieldValue(state.document.text, finalSelection))
}

// ── Completion acceptance ──────────────────────────────────────────────────────

private fun applyCompletion(
    state: CodeEditorState,
    fieldValue: TextFieldValue,
    item: CompletionItem,
    onFieldValue: (TextFieldValue) -> Unit,
) {
    val target = completionReplaceRange(fieldValue.text, fieldValue.selection.start, item)
    val newSelection = TextRange(target.first + item.insertText.length)
    state.applyEdit(target.first, target.last - target.first + 1, item.insertText, newSelection)
    onFieldValue(TextFieldValue(state.document.text, newSelection))
}

/**
 * Characters a completion never reaches back across when guessing what it replaces.
 *
 * `.` is one of them: after `list.`, accepting a member completion must insert after the receiver,
 * not swallow it. A provider that knows better says so with [CompletionItem.replaceRange].
 */
private val COMPLETION_BOUNDARY_CHARS =
    setOf('<', '>', '{', '}', '(', ')', '[', ']', '"', '\'', '=', ',', ';', '.', ' ', '\n', '\t', '@', '|', ':')

/**
 * Half-open-as-inclusive range in [text] that accepting [item] at [cursorPos] replaces.
 *
 * A provider that knows the exact range it means (a language server's `textEdit`) wins; only when
 * [CompletionItem.replaceRange] is absent does the editor guess the token before the cursor.
 */
internal fun completionReplaceRange(text: String, cursorPos: Int, item: CompletionItem): IntRange {
    val cursor = cursorPos.coerceIn(0, text.length)
    item.replaceRange?.let { provided ->
        val start = provided.first.coerceIn(0, text.length)
        val end = (provided.last + 1).coerceIn(start, text.length)
        return start until end
    }
    var start = cursor
    while (start > 0 && text[start - 1] !in COMPLETION_BOUNDARY_CHARS) {
        start--
    }
    return start until cursor
}

// ── Signature help context ─────────────────────────────────────────────────────

/** Delay before a signature-help request, so a language server isn't asked per keystroke. */
private const val SIGNATURE_HELP_DEBOUNCE_MS = 200L

/** How far back the call scan looks — a call opened further above the cursor isn't worth chasing. */
private const val CALL_SCAN_LIMIT = 2000

/**
 * Whether [offset] sits inside an unclosed `(` argument list, ignoring parentheses in comments and
 * string or character literals.
 *
 * The scan is bounded to the last [CALL_SCAN_LIMIT] characters, so it can start inside a multi-line
 * literal and misjudge; that only costs one extra signature-help request, which the language service
 * answers with null.
 */
internal fun isInsideCallArguments(text: String, offset: Int): Boolean {
    val cursor = offset.coerceIn(0, text.length)
    var i = (cursor - CALL_SCAN_LIMIT).coerceAtLeast(0)
    var depth = 0
    while (i < cursor) {
        when {
            text.startsWith("//", i) -> {
                val nl = text.indexOf('\n', i)
                i = if (nl < 0 || nl >= cursor) cursor else nl + 1
            }

            text.startsWith("/*", i) -> {
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0 || end + 2 >= cursor) cursor else end + 2
            }

            text[i] == '"' || text[i] == '\'' -> i = endOfLiteral(text, i, cursor)

            text[i] == '(' -> {
                depth++
                i++
            }

            text[i] == ')' -> {
                depth = (depth - 1).coerceAtLeast(0)
                i++
            }

            else -> i++
        }
    }
    return depth > 0
}

/** Index just past the literal opened by the quote at [start], bounded by [limit]. */
private fun endOfLiteral(text: String, start: Int, limit: Int): Int {
    val quote = text[start]
    var i = start + 1
    while (i < limit) {
        when (text[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            '\n' -> return i + 1
            else -> i++
        }
    }
    return limit
}

// ── Edit delta computation ─────────────────────────────────────────────────────

internal data class EditDelta(val deleteOffset: Int, val deleteLength: Int, val insertText: String)

internal fun computeEditDelta(oldText: String, newText: String): EditDelta {
    if (oldText == newText) return EditDelta(0, 0, "")

    var prefixLen = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefixLen < maxPrefix && oldText[prefixLen] == newText[prefixLen]) prefixLen++

    var suffixLen = 0
    val maxSuffix = minOf(oldText.length - prefixLen, newText.length - prefixLen)
    while (suffixLen < maxSuffix &&
        oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
    ) {
        suffixLen++
    }

    return EditDelta(
        deleteOffset = prefixLen,
        deleteLength = oldText.length - prefixLen - suffixLen,
        insertText = newText.substring(prefixLen, newText.length - suffixLen),
    )
}
