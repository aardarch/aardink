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
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.platform.EditorScrollbars
import kotlinx.coroutines.CoroutineScope
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
    /**
     * Invoked when the user presses Cmd/Ctrl+G. Hosts wire this to [GoToLineDialog]; the
     * default does nothing, so the shortcut is inert until a host opts in.
     */
    onRequestGoToLine: () -> Unit = {},
) {
    val density = LocalDensity.current
    val typography = LocalEditorTypography.current
    val lineHeightPx = with(density) { typography.lineHeight.toPx() }
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

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Completion state
    var completionItems by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var showCompletion by remember { mutableStateOf(false) }

    // Hardware-keyboard shortcuts. Unreachable without a physical keyboard, so this changes
    // nothing for touch input on Android.
    val shortcutActions = remember(state, findReplaceState, onRequestGoToLine) {
        EditorShortcutActions(
            onUndo = { state.undo() },
            onRedo = { state.redo() },
            onFind = { findReplaceState?.show() },
            onReplace = { findReplaceState?.show() },
            onGoToLine = onRequestGoToLine,
            onIndent = { state.indentSelection() },
            onOutdent = { state.outdentSelection() },
            onEscape = {
                // Consume Escape only when it actually dismissed something, so a host's own
                // dialog still sees the key when the editor had nothing open.
                when {
                    showCompletion -> {
                        showCompletion = false
                        true
                    }

                    findReplaceState?.visible == true -> {
                        findReplaceState.hide()
                        true
                    }

                    else -> false
                }
            },
        )
    }
    var completionJob by remember { mutableStateOf<Job?>(null) }

    // Code actions, Signature help & Rename state
    var showCodeActionsMenu by remember { mutableStateOf(false) }
    var currentSignatureHelp by remember { mutableStateOf<SignatureHelp?>(null) }
    var showSignatureHelp by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetName by remember { mutableStateOf("") }
    var renameTargetRange by remember { mutableStateOf(IntRange.EMPTY) }
    var renameTargetVersion by remember { mutableIntStateOf(0) }

    // ── Rename: resolve the symbol the host asked to rename, then open the dialog ─
    // A null `prepareRename` means "this symbol cannot be renamed" — including the default
    // implementation of a service that has no rename at all. Opening the dialog anyway produced a
    // prompt whose confirmation silently did nothing, so the request just ends here instead.
    LaunchedEffect(state, languageService) {
        snapshotFlow { state.pendingRename }.collect { request ->
            if (request == null) return@collect
            state.clearRename()
            val text = state.document.text
            // Same guard the rename itself has: the server answers later, and a range measured
            // against the older text would be sliced from a document that has since changed.
            val requestedVersion = state.textVersion
            val range = withContext(state.computeDispatcher) {
                languageService?.prepareRename(state.document, request.offset)
            } ?: return@collect
            if (range.isEmpty() || state.textVersion != requestedVersion) return@collect
            if (range.first < 0 || range.last >= text.length) return@collect
            renameTargetRange = range
            renameTargetName = text.substring(range.first, range.last + 1)
            renameTargetVersion = requestedVersion
            showRenameDialog = true
        }
    }

    // Unconditional, unlike the find and fold effects whose visible state lives in their nullable
    // holders: the popup's state is remembered here, so the effect must still run when the host
    // detaches the service in order to take the popup down with it.
    LaunchedEffect(languageService, state.selection, textVersion) {
        if (languageService == null) {
            showSignatureHelp = false
            currentSignatureHelp = null
            return@LaunchedEffect
        }
        val offset = state.selection.start
        if (offset < 0 || offset > state.document.length) return@LaunchedEffect
        // Keep asking while the cursor sits inside the argument list, not just right after
        // '(' or ',' — otherwise the popup vanishes on the first character of an argument.
        // The service decides when the context has stopped being valid by returning null.
        if (!isInsideCallArguments(state.document.text, offset)) {
            showSignatureHelp = false
            currentSignatureHelp = null
            return@LaunchedEffect
        }
        // Debounced like the find and folding passes: a language server should not field a
        // request per keystroke.
        delay(SIGNATURE_HELP_DEBOUNCE_MS)
        val sig = withContext(state.computeDispatcher) {
            languageService.signatureHelp(state.document, offset)
        }
        currentSignatureHelp = sig
        showSignatureHelp = sig != null && sig.signatures.isNotEmpty()
    }

    // Annotation tooltip: shown when user taps a gutter dot
    var tooltipDiagnostic by remember { mutableStateOf<Diagnostic?>(null) }

    // Quick fixes for the tapped diagnostic — the only way the action menu is opened. Tapping a
    // gutter dot doesn't move the cursor, so actions are fetched for the diagnostic's own range,
    // lazily, rather than for every cursor position the user passes through.
    // Not keyed on the text version: the tooltip is dismissed by any edit (see the sync effect
    // below), because its diagnostic's range describes text that no longer exists.
    var tooltipCodeActions by remember { mutableStateOf<List<CodeAction>>(emptyList()) }
    LaunchedEffect(languageService, tooltipDiagnostic) {
        tooltipCodeActions = emptyList()
        val tooltip = tooltipDiagnostic ?: return@LaunchedEffect
        if (languageService == null) return@LaunchedEffect
        tooltipCodeActions = withContext(state.computeDispatcher) {
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
        diffAnnotations = withContext(state.computeDispatcher) {
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

    // Any edit — typed, applied from a quick fix, or made by the host — leaves the tapped
    // diagnostic's range and the code-action menu pointing at text that has moved.
    LaunchedEffect(state) {
        snapshotFlow { textVersion }.collect {
            tooltipDiagnostic = null
            showCodeActionsMenu = false
        }
    }

    // The completion dropdown manages its own visibility while the user is typing (see
    // handleSingleCharacterInsert below); every OTHER kind of edit — undo/redo, a quick fix, a
    // rename, a toolbar quick-insert, a host-driven loadText — closes it, because its items were
    // addressed to text that just changed under it in a way typing's own re-addressing doesn't
    // cover.
    LaunchedEffect(state) {
        snapshotFlow { state.externalEditVersion }.collect {
            showCompletion = false
        }
    }

    // Report cursor position changes upward
    LaunchedEffect(state) {
        snapshotFlow { state.textFieldState.selection }.collect { sel ->
            val (line, col) = state.document.offsetToLineCol(sel.start)
            onCursorChange(line + 1, col + 1)
        }
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
                val matches = withContext(state.computeDispatcher) {
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
                val ranges = if (state.exceedsAnalysisLimit) {
                    emptyList()
                } else {
                    withContext(state.computeDispatcher) {
                        foldingProvider.foldableRanges(state.document)
                    }
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
            state.selection = nav.select ?: TextRange(nav.targetOffset)
            state.clearNavigation()
        }
    }

    val matches = findReplaceState?.matches ?: emptyList()
    val currentMatchIndex = findReplaceState?.currentMatchIndex ?: -1
    val matchHighlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val currentMatchHighlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)

    val foldedRanges = foldState?.foldedRanges() ?: emptyList()

    // ── Completion trigger, on every ordinary (single-character) keystroke ───
    // Needs composition-scoped state (completionItems/showCompletion/completionJob/
    // coroutineScope) EditorInputTransformation itself doesn't have, so it lives here and is
    // invoked from a stable callback the transformation holds onto across recompositions.
    val currentLanguageService = rememberUpdatedState(languageService)
    val currentTriggerChars = rememberUpdatedState(triggerChars)

    val handleSingleCharacterInsert: (Int, Char, Int) -> Unit = handler@{ insertedAt, typedChar, autoCloseLength ->
        val service = currentLanguageService.value
        if (service == null) {
            showCompletion = false
            return@handler
        }
        val cursor = state.selection.start
        // The list stays up while a fresh request is in flight, so an item accepted in between
        // comes from a list addressed to the text before this keystroke. Re-address the ranges a
        // provider gave: the typed character joins the token being completed, and any auto-closed
        // character inserted right after the cursor must not be swallowed by it.
        var carried = completionItems.map { it.shiftedForInsert(insertedAt, 1, absorbing = true) }
        if (autoCloseLength > 0) {
            carried = carried.map { it.shiftedForInsert(insertedAt + 1, autoCloseLength, absorbing = false) }
        }
        when {
            typedChar in currentTriggerChars.value -> {
                completionItems = carried
                completionJob?.cancel()
                completionJob = coroutineScope.launch {
                    val items = service.completions(state.document, cursor)
                    completionItems = items
                    showCompletion = items.isNotEmpty()
                }
            }

            typedChar.isLetterOrDigit() || typedChar == '_' -> {
                completionItems = carried
                completionJob?.cancel()
                completionJob = coroutineScope.launch {
                    val items = service.completions(state.document, cursor)
                    completionItems = items
                    if (items.isEmpty()) showCompletion = false
                }
            }

            else -> showCompletion = false
        }
    }
    val currentHandleSingleCharacterInsert = rememberUpdatedState(handleSingleCharacterInsert)

    val inputTransformation = remember(state) {
        EditorInputTransformation(
            state = state,
            languageService = { currentLanguageService.value },
            onSingleCharacterInsert = { insertedAt, typedChar, autoCloseLength ->
                currentHandleSingleCharacterInsert.value(insertedAt, typedChar, autoCloseLength)
            },
            onOtherChange = { showCompletion = false },
        )
    }

    val outputTransformation = remember(
        effectiveAnnotatedText,
        textColor,
        matches,
        currentMatchIndex,
        matchHighlight,
        currentMatchHighlight,
        foldedRanges,
    ) {
        EditorOutputTransformation(
            syntaxColoredText = effectiveAnnotatedText,
            textColor = textColor,
            matches = matches,
            currentMatchIndex = currentMatchIndex,
            matchHighlight = matchHighlight,
            currentMatchHighlight = currentMatchHighlight,
            foldedRanges = foldedRanges,
            document = state.document,
            placeholderStyle = SpanStyle(
                color = textColor.copy(alpha = 0.4f),
                fontStyle = FontStyle.Italic,
            ),
        )
    }

    val foldableLines = foldState?.foldableRanges?.map { it.startLine }?.toSet() ?: emptySet()

    val toolbar: @Composable () -> Unit = {
        if (keyboardToolbarPlacement != KeyboardToolbarPlacement.Hidden) {
            KeyboardToolbarRow(
                quickChars = remember(state.tokenizer) { state.tokenizer.keyboardToolbarChars() },
                canUndo = state.undoManager.canUndo,
                canRedo = state.undoManager.canRedo,
                onInsertChar = { char ->
                    val insertAt = state.selection.start
                    state.applyEdit(insertAt, 0, char.toString(), TextRange(insertAt + 1))
                },
                onMoveCursorLeft = {
                    val newPos = (state.selection.start - 1).coerceAtLeast(0)
                    state.selection = TextRange(newPos)
                    showCompletion = false
                },
                onMoveCursorRight = {
                    val newPos = (state.selection.start + 1).coerceAtMost(state.document.length)
                    state.selection = TextRange(newPos)
                    showCompletion = false
                },
                onUndo = { state.undo() },
                onRedo = { state.redo() },
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
                    // The target offset was measured when the dialog opened, and the server's
                    // edits are offsets into the text as it is when we ask. The host may have
                    // replaced the document while the dialog was up, and the user can type while
                    // the server works; in either case the offsets point at whatever happens to
                    // sit there now, so the rename is dropped rather than applied to the wrong
                    // characters.
                    val requestedVersion = renameTargetVersion
                    showRenameDialog = false
                    if (state.textVersion != requestedVersion) return@RenameDialog
                    coroutineScope.launch {
                        val edits = withContext(state.computeDispatcher) {
                            languageService?.rename(state.document, target, newName)
                        } ?: emptyList()
                        if (edits.isNotEmpty() && state.textVersion == requestedVersion) {
                            state.applyTextEdits(edits)
                        }
                    }
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

            // Outer box exists so the scrollbars can overlay the scrolling content rather
            // than take layout space from it. On Android EditorScrollbars draws nothing, so
            // this costs one empty Box and changes no pixels.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                        state = state.textFieldState,
                        inputTransformation = inputTransformation,
                        outputTransformation = outputTransformation,
                        onTextLayout = { getResult -> textLayoutResult = getResult() },
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(EditorTestTags.TEXT_FIELD)
                            .editorKeyboardShortcuts(shortcutActions),
                        textStyle = TextStyle(
                            fontFamily = typography.fontFamily,
                            fontSize = typography.fontSize,
                            lineHeight = typography.lineHeight,
                            color = textColor,
                        ),
                        cursorBrush = SolidColor(cursorColor),
                        readOnly = readOnly,
                    )
                }

                EditorScrollbars(
                    vertical = verticalScrollState,
                    horizontal = if (softWrap) null else horizontalScrollState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Completion strip ─────────────────────────────────────────────────
        CompletionDropdown(
            items = completionItems,
            visible = showCompletion,
            onAccept = { item ->
                applyCompletion(state, item)
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

// ── Completion acceptance ──────────────────────────────────────────────────────

private fun applyCompletion(state: CodeEditorState, item: CompletionItem) {
    val cursor = state.selection.start
    val target = completionReplaceRange(state.document.text, cursor, item)

    if (item.additionalEdits.isEmpty()) {
        val newSelection = TextRange(target.first + item.insertText.length)
        state.applyEdit(target.first, target.last - target.first + 1, item.insertText, newSelection)
        return
    }

    // An auto-import completion inserts the symbol and its import together, so the two go in as one
    // batch: applyTextEdits orders them and records a single undo step, and the caret is placed
    // afterwards accounting for any edit that shifted text ahead of it.
    state.applyTextEdits(item.additionalEdits + TextEdit(target, item.insertText))
    val shiftBefore = item.additionalEdits
        .filter { it.range.first <= target.first }
        .sumOf { it.newText.length - (it.range.last - it.range.first + 1) }
    val caret = (target.first + shiftBefore + item.insertText.length).coerceIn(0, state.document.length)
    state.selection = TextRange(caret)
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

/**
 * [CompletionItem] re-addressed after [length] characters were inserted at [at], for a list that
 * outlives the text it was computed for. A provider's [CompletionItem.replaceRange] and
 * [CompletionItem.additionalEdits] are offsets into that older text; applying them verbatim after
 * a keystroke replaces the wrong span. Ranges after the insertion move along; a range the
 * insertion lands inside grows to keep covering the same text; and when [absorbing] a range ending
 * exactly at the insertion grows too, so the character just typed becomes part of the token the
 * completion replaces rather than a leftover after it.
 */
internal fun CompletionItem.shiftedForInsert(at: Int, length: Int, absorbing: Boolean): CompletionItem {
    val range = replaceRange?.let { shiftRangeForInsert(it, at, length, absorbing) }
    val extra = additionalEdits.map { TextEdit(shiftRangeForInsert(it.range, at, length, absorbing = false), it.newText) }
    return if (range == replaceRange && extra == additionalEdits) this else copy(replaceRange = range, additionalEdits = extra)
}

private fun shiftRangeForInsert(range: IntRange, at: Int, length: Int, absorbing: Boolean): IntRange = when {
    at < range.first -> range.first + length..range.last + length
    at <= range.last || (absorbing && at == range.last + 1) -> range.first..range.last + length
    else -> range
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
