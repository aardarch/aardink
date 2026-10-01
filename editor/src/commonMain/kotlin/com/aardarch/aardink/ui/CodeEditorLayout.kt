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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.DiagnosticsTracker
import com.aardarch.aardink.core.EditorLimits
import com.aardarch.aardink.core.FindEngine
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.LineDiffKind
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SimpleDiffProvider
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.core.edit.MinimalEdits
import com.aardarch.aardink.core.edit.TextNavigator
import com.aardarch.aardink.platform.PlatformInfo
import com.aardarch.aardink.ui.view.EditorController
import com.aardarch.aardink.ui.view.EditorHostActions
import com.aardarch.aardink.ui.view.EditorView
import com.aardarch.aardink.ui.view.GutterContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The code editor.
 *
 * Only the lines on screen are laid out and drawn, so typing, scrolling and highlighting cost the
 * same in a 50-line file and a 100,000-line one. The editor takes its own text input (keys, input
 * methods, the clipboard) on Android, desktop and the web.
 *
 * Layout (top → bottom):
 *   - [FindReplacePanel], while [findReplaceState] is visible
 *   - [AnnotationTooltip], after a tap on a gutter dot
 *   - the gutter and the text
 *   - [CompletionDropdown] strip, on touch devices (elsewhere completions, signature help, hover
 *     documentation and code actions pop up next to the caret or the symbol)
 *   - [KeyboardToolbarRow]
 *
 * @param options How the editor looks and behaves; see [EditorOptions].
 * @param diagnostics Diagnostics to show as gutter dots and squiggles, or null for the editor to
 *   ask [languageService] for them, 500 ms after it appears, after each pause in typing and at
 *   once on [CodeEditorState.revalidate], dropping any answer for text that has changed since it
 *   was asked. Either way their ranges
 *   move along with each edit until the next list arrives.
 * @param savedText Baseline text for the diff lane (typically the last-saved version).
 * @param onDiagnosticsChange Called with each list the editor collects from [languageService] while
 *   [diagnostics] is null; not called for a list the host passes.
 * @param onNavigateToLocation Called when go to definition (F12, Ctrl/Cmd+click) or the references
 *   list leads to another file: a [Location] in this document is shown here, and one elsewhere
 *   (its range [IntRange.EMPTY], with [Location.uri], [Location.line] and [Location.column]) is
 *   the host's to open.
 * @param onRequestGoToLine Invoked on Cmd/Ctrl+G. Hosts wire it to [GoToLineDialog]; the default
 *   does nothing, so the shortcut is inert until a host opts in.
 */
@Composable
fun CodeEditorLayout(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    options: EditorOptions = EditorOptions(),
    languageService: LanguageService? = null,
    findReplaceState: FindReplaceState? = null,
    foldState: FoldState? = null,
    foldingProvider: FoldingProvider = NoOpFoldingProvider,
    diagnostics: List<Diagnostic>? = null,
    savedText: String = "",
    onCursorChange: (line: Int, column: Int) -> Unit = { _, _ -> },
    onDiagnosticsChange: (List<Diagnostic>) -> Unit = {},
    onNavigateToLocation: (Location) -> Unit = {},
    toolbarStyle: KeyboardToolbarStyle = KeyboardToolbarDefaults.style(),
    keyboardToolbarPlacement: KeyboardToolbarPlacement = KeyboardToolbarPlacement.BottomHover,
    onRequestGoToLine: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val theme = LocalEditorTheme.current
    // A theme without a default token colour falls back to the chrome's text colour, not the host's.
    val textColor = theme.tokenColors[TokenType.Default] ?: remember(theme) { EditorChromeColors(theme).foreground }

    val view = remember(state) { EditorView(state) }
    DisposableEffect(view) {
        view.attach()
        onDispose { view.detach() }
    }
    val controller = remember(view) { EditorController(state, view) }

    val textVersion = state.textVersion

    // ── Diagnostics: the host's list, or the language service's ──────────────
    // Either way the tracker moves their ranges along with each edit until the next list.
    val diagnosticsTracker = remember(state) { DiagnosticsTracker(state.document) }
    DisposableEffect(diagnosticsTracker) {
        state.document.addChangeListener(diagnosticsTracker)
        onDispose { state.document.removeChangeListener(diagnosticsTracker) }
    }
    val currentOnDiagnosticsChange = rememberUpdatedState(onDiagnosticsChange)
    val collectDiagnostics = diagnostics == null
    LaunchedEffect(diagnosticsTracker, languageService, collectDiagnostics) {
        if (!collectDiagnostics) return@LaunchedEffect
        val publish: (List<Diagnostic>) -> Unit = { list ->
            diagnosticsTracker.replace(list)
            currentOnDiagnosticsChange.value(list)
        }
        val service = languageService
        if (service == null) {
            if (diagnosticsTracker.diagnostics.isNotEmpty()) publish(emptyList())
            return@LaunchedEffect
        }
        var askedVersion: Int? = null
        snapshotFlow { state.textVersion to state.revalidations }.collectLatest { (textVersion, _) ->
            // Asked once typing pauses, as Monaco's validation is; collectLatest drops a pass
            // still running when the next edit comes. The first pass waits too, so that it runs
            // after the editor's first frames rather than among them: on the web it shares their
            // thread, and in among the first highlighting pass it could hold up a frame. Only a
            // revalidate() of text already asked about goes at once.
            val edited = textVersion != askedVersion
            askedVersion = textVersion
            if (edited) delay(DIAGNOSTICS_DEBOUNCE_MS)
            val document = state.document.snapshot()
            val list = if (state.exceedsAnalysisLimit) {
                emptyList()
            } else {
                withContext(state.computeDispatcher) { service.diagnostics(document) }
            }
            // An edit made straight through the document bumps no text version, and cancels
            // nothing: the answer is for text that no longer exists.
            if (state.document.version != document.version) return@collectLatest
            publish(list)
        }
    }
    // A list the host has just passed is shown as it is, and handed to the tracker after this
    // composition; from then on the tracker's copy, moved along with the edits, is shown.
    val shownDiagnostics = if (diagnostics != null && diagnosticsTracker.given !== diagnostics) {
        diagnostics
    } else {
        diagnosticsTracker.diagnostics
    }
    if (diagnostics != null) {
        SideEffect { if (diagnosticsTracker.given !== diagnostics) diagnosticsTracker.replace(diagnostics) }
    }

    // Completion state. With a hardware keyboard the list sits at the caret and the arrows move
    // its selection; on a touch device it is a strip above the keyboard.
    var completionItems by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var showCompletion by remember { mutableStateOf(false) }
    var completionJob by remember { mutableStateOf<Job?>(null) }
    var completionSelected by remember { mutableIntStateOf(0) }
    val completionAtCaret = !PlatformInfo.hasSoftKeyboard
    LaunchedEffect(completionItems) { completionSelected = 0 }

    // Hover documentation: after the mouse rests on a symbol, or from the touch menu's "Info".
    var hover by remember { mutableStateOf<HoverShown?>(null) }
    var hoverJob by remember { mutableStateOf<Job?>(null) }

    // The references list (Shift+F12), at the caret it was asked for.
    var references by remember { mutableStateOf<ReferencesShown?>(null) }
    var referenceSelected by remember { mutableIntStateOf(0) }

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
            // Taken here, on the main thread: the service runs on the compute dispatcher and must
            // not see the document change under it.
            val document = state.document.snapshot()
            // Same guard the rename itself has: the server answers later, and a range measured
            // against the older text would be sliced from a document that has since changed.
            val requestedVersion = state.textVersion
            val range = withContext(state.computeDispatcher) {
                languageService?.prepareRename(document, request.offset)
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
        if (!isInsideCallArguments(state.document, offset)) {
            showSignatureHelp = false
            currentSignatureHelp = null
            return@LaunchedEffect
        }
        // Debounced like the find and folding passes: a language server should not field a
        // request per keystroke.
        delay(SIGNATURE_HELP_DEBOUNCE_MS)
        val document = state.document.snapshot()
        val sig = withContext(state.computeDispatcher) {
            languageService.signatureHelp(document, offset)
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
        val document = state.document.snapshot()
        tooltipCodeActions = withContext(state.computeDispatcher) {
            languageService.codeActions(document, tooltip.range)
        }
    }

    // Diff lane: compute line diffs when savedText or document changes
    var diffAnnotations by remember { mutableStateOf<Map<Int, LineDiffKind>>(emptyMap()) }
    LaunchedEffect(savedText, textVersion) {
        if (savedText.isEmpty()) {
            diffAnnotations = emptyMap()
            return@LaunchedEffect
        }
        // Debounced like find and folding: the lane can lag typing by a moment, and a burst of
        // keystrokes costs one diff rather than one each.
        delay(DIFF_DEBOUNCE_MS)
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
    val gutterAnnotations = remember(shownDiagnostics) {
        shownDiagnostics
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
        snapshotFlow { state.textVersion }.collect {
            tooltipDiagnostic = null
            showCodeActionsMenu = false
        }
    }

    // The completion dropdown manages its own visibility while the user is typing (see
    // handleSingleCharacterInsert below); every OTHER kind of edit — undo/redo, a quick fix, a
    // rename, a key command, a host-driven loadText — closes it, because its items were addressed
    // to text that just changed under it in a way typing's own re-addressing doesn't cover.
    // A snippet's choice stop (`${1|one,two|}`) offers its choices in the same list, as Monaco's.
    // Only on reaching the stop: once one is taken, the list stays closed.
    LaunchedEffect(state) {
        var offeredAt: Pair<Any?, Int>? = null
        snapshotFlow { Triple(state.externalEditVersion, state.snippet, state.snippet?.version) }.collect { (_, snippet, version) ->
            val choices = snippet?.currentChoices
            val stop = snippet to (version ?: 0)
            if (choices != null && stop != offeredAt) {
                offeredAt = stop
                val range = snippet.currentRanges().first()
                completionJob?.cancel()
                completionItems = choices.map { CompletionItem(it, CompletionKind.Value, it, replaceRange = range.min until range.max) }
                showCompletion = true
            } else {
                showCompletion = false
            }
        }
    }

    // Report cursor position changes upward
    LaunchedEffect(state) {
        snapshotFlow { state.selection }.collect { sel ->
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
            }.collectLatest { _ ->
                // collectLatest: a newer query or edit cancels a search still running.
                if (!findReplaceState.visible || findReplaceState.query.isEmpty()) {
                    findReplaceState.matches = emptyList()
                    findReplaceState.matchesCapped = false
                    findReplaceState.currentMatchIndex = -1
                    return@collectLatest
                }
                delay(200)
                val text = state.document.text
                val opts = findReplaceState.toOptions()
                val found = withContext(state.computeDispatcher) {
                    FindEngine.findAllCooperatively(text, findReplaceState.query, opts, EditorLimits.maxFindMatches)
                }
                findReplaceState.matches = found.matches
                findReplaceState.matchesCapped = found.capped
                findReplaceState.currentMatchIndex = if (found.matches.isEmpty()) -1 else 0
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
                    val document = state.document.snapshot()
                    withContext(state.computeDispatcher) {
                        foldingProvider.foldableRanges(document)
                    }
                }
                foldState.updateFoldableRanges(ranges)
            }
        }
    }

    // ── Sticky scroll: the first lines of the ranges the top of the view is inside ─
    // The host's foldable ranges; without a fold state, ranges asked of the folding provider for
    // sticky scroll alone, on the same schedule as folding.
    var ownStickyRanges by remember { mutableStateOf<List<FoldRange>>(emptyList()) }
    if (options.stickyScroll && foldState == null) {
        LaunchedEffect(state, foldingProvider) {
            snapshotFlow { state.textVersion }.collectLatest {
                delay(200)
                ownStickyRanges = if (state.exceedsAnalysisLimit) {
                    emptyList()
                } else {
                    val document = state.document.snapshot()
                    withContext(state.computeDispatcher) { foldingProvider.foldableRanges(document) }
                }
            }
        }
    }
    val stickySource = if (options.stickyScroll) foldState?.foldableRanges ?: ownStickyRanges else emptyList()
    val stickyRanges = remember(stickySource) { stickySource.sortedWith(compareBy<FoldRange>({ it.startLine }, { -it.endLine })) }

    // ── Hover: the documentation of the symbol under a resting mouse ─────────
    val showHover: suspend (Int) -> Unit = hover@{ offset ->
        val service = languageService ?: return@hover
        val word = TextNavigator.wordAt(state.document, offset)
        if (word.isEmpty()) return@hover
        val version = state.textVersion
        val document = state.document.snapshot()
        val doc = withContext(state.computeDispatcher) { service.hoverDoc(document, offset) } ?: return@hover
        if (state.textVersion != version) return@hover
        hover = HoverShown(doc, word.first, word.last + 1)
    }
    LaunchedEffect(controller, languageService) {
        snapshotFlow { controller.hoverAt }.collectLatest { at ->
            hover = null
            if (at == null || languageService == null) return@collectLatest
            delay(HOVER_DELAY_MS)
            showHover(view.offsetAt(at).offset)
        }
    }
    // Typing or scrolling takes it down: it describes where the mouse was.
    LaunchedEffect(state, view) {
        snapshotFlow { state.textVersion to view.scroll.scrollY }.collect { hover = null }
    }

    // ── Go to definition, references, format ─────────────────────────────────
    val currentOnNavigateToLocation = rememberUpdatedState(onNavigateToLocation)
    // A place in this document is shown here; one in another file is the host's to open.
    val openLocation: (Location) -> Unit = { location ->
        if (location.isIn(state.document)) {
            val start = location.range.first
            val end = (location.range.last + 1).coerceIn(start, state.document.length)
            state.navigateTo(start, TextRange(start, end))
        } else {
            currentOnNavigateToLocation.value(location)
        }
    }
    val goToDefinition: () -> Boolean = definition@{
        val service = languageService ?: return@definition false
        val offset = state.selection.end
        coroutineScope.launch {
            val version = state.textVersion
            val document = state.document.snapshot()
            val location = withContext(state.computeDispatcher) { service.definition(document, offset) } ?: return@launch
            if (state.textVersion == version) openLocation(location)
        }
        true
    }
    val findReferences: () -> Boolean = find@{
        val service = languageService ?: return@find false
        val offset = state.selection.end
        coroutineScope.launch {
            val version = state.textVersion
            val document = state.document.snapshot()
            val found = withContext(state.computeDispatcher) { service.references(document, offset) }
            if (state.textVersion != version || found.isEmpty()) return@launch
            referenceSelected = 0
            references = ReferencesShown(offset, found.map { ReferenceItem.of(it, document) })
        }
        true
    }
    // Shift+Alt+F: the selection through formatRange, else the whole document through format,
    // whose result is reduced to the lines that change, so carets and folds elsewhere stay put.
    // Either way one undo step.
    val format: () -> Boolean = format@{
        val service = languageService ?: return@format false
        val selection = state.selection
        coroutineScope.launch {
            val version = state.textVersion
            val document = state.document.snapshot()
            val edits = withContext(state.computeDispatcher) {
                if (selection.collapsed) {
                    MinimalEdits.between(document.text, service.format(document))
                } else {
                    service.formatRange(document, selection.min until selection.max)
                }
            }
            if (state.textVersion == version && edits.isNotEmpty()) state.applyTextEdits(edits)
        }
        true
    }
    // The references list goes when the text changes under it.
    LaunchedEffect(state) {
        snapshotFlow { state.textVersion }.collect { references = null }
    }
    // Ctrl (Cmd) over a symbol with a definition: it becomes a link. Asked once per word the mouse
    // is over, not per pixel it moves.
    LaunchedEffect(controller, languageService) {
        snapshotFlow {
            val at = controller.hoverAt?.takeIf { controller.linkModifier && languageService != null }
            at?.let { view.offsetAt(it).offset }?.let { offset ->
                TextNavigator.wordAt(state.document, offset).takeUnless { it.isEmpty() }?.let { word -> offset to word }
            }
        }.distinctUntilChangedBy { it?.second }.collectLatest { found ->
            controller.link = null
            val service = languageService ?: return@collectLatest
            val (offset, word) = found ?: return@collectLatest
            val version = state.textVersion
            val document = state.document.snapshot()
            withContext(state.computeDispatcher) { service.definition(document, offset) } ?: return@collectLatest
            if (state.textVersion == version) controller.link = TextRange(word.first, word.last + 1)
        }
    }

    // ── Pending navigation: select the target and scroll it to the middle of the view ─
    LaunchedEffect(state, view) {
        snapshotFlow { state.pendingNavigation }.collect { nav ->
            if (nav == null) return@collect
            state.selection = nav.select ?: TextRange(nav.targetOffset)
            view.requestReveal(nav.targetOffset, center = true)
            state.clearNavigation()
        }
    }

    // ── Completion trigger, on every ordinary (single-character) keystroke ───
    // Needs composition-scoped state (completionItems/showCompletion/completionJob/
    // coroutineScope), so it lives here; the editor's input reaches it through the controller.
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
                    val items = service.completions(state.document.snapshot(), cursor)
                    completionItems = items
                    showCompletion = items.isNotEmpty()
                }
            }

            typedChar.isLetterOrDigit() || typedChar == '_' -> {
                completionItems = carried
                completionJob?.cancel()
                completionJob = coroutineScope.launch {
                    val items = service.completions(state.document.snapshot(), cursor)
                    completionItems = items
                    if (items.isEmpty()) showCompletion = false
                }
            }

            else -> showCompletion = false
        }
    }
    val currentHandleSingleCharacterInsert = rememberUpdatedState(handleSingleCharacterInsert)
    val onInput: (CodeEditorState.TypedInput?) -> Unit = { typed ->
        if (typed != null) {
            currentHandleSingleCharacterInsert.value(typed.offset, typed.char, typed.autoCloseLength)
        } else {
            showCompletion = false
        }
    }

    // ── What the editor's keys and input do beyond the text ──────────────────
    val requestCompletions: () -> Unit = {
        val service = currentLanguageService.value
        if (service != null) {
            val cursor = state.selection.start
            completionJob?.cancel()
            completionJob = coroutineScope.launch {
                val items = service.completions(state.document.snapshot(), cursor)
                completionItems = items
                showCompletion = items.isNotEmpty()
            }
        }
    }
    // The references list takes the arrows, Enter and Escape while it is up.
    val referencesKey: (KeyEvent) -> Boolean = key@{ event ->
        val shown = references ?: return@key false
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@key false
        when (event.key) {
            Key.DirectionDown -> referenceSelected = (referenceSelected + 1) % shown.items.size

            Key.DirectionUp -> referenceSelected = (referenceSelected - 1).mod(shown.items.size)

            Key.Enter, Key.NumPadEnter -> {
                references = null
                shown.items.getOrNull(referenceSelected)?.let { openLocation(it.location) }
            }

            Key.Escape -> references = null

            else -> return@key false
        }
        true
    }
    // The completion list takes the arrows, Enter, Tab and Escape while it is up at the caret.
    val completionKey: (KeyEvent) -> Boolean = key@{ event ->
        if (!completionAtCaret || !showCompletion || completionItems.isEmpty() || event.type != KeyEventType.KeyDown) return@key false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@key false
        when (event.key) {
            Key.DirectionDown -> completionSelected = (completionSelected + 1) % completionItems.size

            Key.DirectionUp -> completionSelected = (completionSelected - 1).mod(completionItems.size)

            Key.Enter, Key.NumPadEnter, Key.Tab -> {
                completionItems.getOrNull(completionSelected)?.let { applyCompletion(state, it) }
                showCompletion = false
                completionItems = emptyList()
            }

            Key.Escape -> showCompletion = false

            else -> return@key false
        }
        true
    }
    SideEffect {
        state.indentUnit = if (options.insertSpaces) " ".repeat(options.tabSize) else "\t"
        controller.readOnly = options.readOnly
        controller.scope = coroutineScope
        controller.languageService = { currentLanguageService.value }
        controller.actions = EditorHostActions(
            onFind = { findReplaceState?.show() },
            onReplace = { findReplaceState?.show(replace = true) },
            onGoToLine = onRequestGoToLine,
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
            onTriggerSuggest = requestCompletions,
            onInput = onInput,
            onGoToDefinition = goToDefinition,
            onFindReferences = findReferences,
            onFormat = format,
            onPopupKey = { event -> referencesKey(event) || completionKey(event) },
            onShowInfo = if (languageService != null) {
                { offset ->
                    hoverJob?.cancel()
                    hoverJob = coroutineScope.launch { showHover(offset) }
                }
            } else {
                null
            },
        )
    }

    val foldableLines = foldState?.foldableRanges?.map { it.startLine }?.toSet() ?: emptySet()

    val toolbar: @Composable () -> Unit = {
        if (keyboardToolbarPlacement != KeyboardToolbarPlacement.Hidden) {
            KeyboardToolbarRow(
                quickChars = remember(state.tokenizer) { state.tokenizer.keyboardToolbarChars() },
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onInsertChar = { char ->
                    // Through the typing rules, as a key would: a toolbar "(" gets its ")" too.
                    if (!options.readOnly) {
                        val selection = state.selection
                        onInput(state.applyInput(selection.min, selection.max, char.toString(), service = currentLanguageService.value))
                    }
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
                    // One batch: one undo step for the whole replacement.
                    state.applyTextEdits(all.map { TextEdit(it, replacement) })
                },
                onClose = {
                    findReplaceState.hide()
                    // The panel had the focus; give it back to the text rather than lose it.
                    controller.requestFocus()
                },
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

        // ── Rename Dialog ────────────────────────────────────────────────────
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
                        val document = state.document.snapshot()
                        val edits = withContext(state.computeDispatcher) {
                            languageService?.rename(document, target, newName)
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
        VirtualizedEditorBody(
            state = state,
            controller = controller,
            foldState = foldState,
            diagnostics = shownDiagnostics,
            findReplaceState = findReplaceState,
            gutter = if (options.showGutter) {
                GutterContent(
                    annotations = if (options.showDiagnosticAnnotations) gutterAnnotations else emptyMap(),
                    foldableLines = if (options.showFoldMarkers) foldableLines else emptySet(),
                    reserveFoldLane = options.showFoldMarkers && foldState != null && foldingProvider !== NoOpFoldingProvider,
                    diffAnnotations = if (options.showDiffMarkers) diffAnnotations else emptyMap(),
                    showLineNumbers = options.showLineNumbers,
                    onToggleFold = { line -> foldState?.toggle(line) },
                    onAnnotationTap = { lineIndex ->
                        tooltipDiagnostic = shownDiagnostics
                            .filter { it.lineNumber == lineIndex }
                            .maxByOrNull { it.severity.ordinal }
                    },
                )
            } else {
                null
            },
            options = options,
            textColor = textColor,
            stickyRanges = stickyRanges,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // ── Popups at the caret (or the symbol, or the diagnostic) ───────
            popups = {
                if (completionAtCaret && showCompletion && completionItems.isNotEmpty()) {
                    EditorAnchoredPopup(anchor = view.caretRect(state.selection.end), onDismiss = { showCompletion = false }) {
                        CompletionList(
                            items = completionItems,
                            selected = completionSelected,
                            onAccept = { item ->
                                applyCompletion(state, item)
                                showCompletion = false
                                completionItems = emptyList()
                            },
                        )
                    }
                }
                val signatureHelp = currentSignatureHelp
                if (showSignatureHelp && signatureHelp != null) {
                    EditorAnchoredPopup(
                        anchor = view.caretRect(state.selection.end),
                        preferAbove = true,
                        onDismiss = { showSignatureHelp = false },
                    ) { SignatureHelpCard(signatureHelp) }
                }
                hover?.let { shown ->
                    val start = view.caretRect(shown.start)
                    val end = view.caretRect(shown.end)
                    EditorAnchoredPopup(
                        anchor = Rect(start.left, start.top, maxOf(end.right, start.left + 1f), start.bottom),
                        onDismiss = { hover = null },
                    ) { HoverDocCard(shown.doc) }
                }
                references?.let { shown ->
                    EditorAnchoredPopup(anchor = view.caretRect(shown.offset), onDismiss = { references = null }) {
                        ReferencesPeekCard(
                            items = shown.items,
                            selected = referenceSelected,
                            onOpen = { item ->
                                references = null
                                openLocation(item.location)
                            },
                        )
                    }
                }
                val tooltipTarget = tooltipDiagnostic
                if (showCodeActionsMenu && tooltipCodeActions.isNotEmpty()) {
                    EditorAnchoredPopup(
                        anchor = view.caretRect(tooltipTarget?.range?.first ?: state.selection.end),
                        focusable = true,
                        onDismiss = { showCodeActionsMenu = false },
                    ) {
                        CodeActionCard(
                            actions = tooltipCodeActions,
                            onSelectAction = { action ->
                                state.applyTextEdits(action.edits)
                                showCodeActionsMenu = false
                            },
                            onDismiss = { showCodeActionsMenu = false },
                        )
                    }
                }
            },
        )

        // ── Completion strip (touch devices) ─────────────────────────────────
        if (!completionAtCaret) {
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
        }

        // ── Keyboard toolbar (when placed at bottom) ─────────────────────────
        if (keyboardToolbarPlacement == KeyboardToolbarPlacement.BottomHover ||
            keyboardToolbarPlacement == KeyboardToolbarPlacement.BottomFixed
        ) {
            toolbar()
        }
    }
}

/** Hover documentation on screen, for the symbol from [start] to [end]. */
private class HoverShown(val doc: HoverDoc, val start: Int, val end: Int)

/** The references list on screen: asked for at [offset], where it is anchored. */
private class ReferencesShown(val offset: Int, val items: List<ReferenceItem>)

/** How long the mouse rests on a symbol before its documentation shows, as in VS Code. */
private const val HOVER_DELAY_MS = 500L

// ── Completion acceptance ──────────────────────────────────────────────────────

private fun applyCompletion(state: CodeEditorState, item: CompletionItem) {
    val cursor = state.selection.start
    val target = completionReplaceRange(state.document, cursor, item)

    if (item.isSnippet) {
        state.insertSnippet(target, item.insertText, item.additionalEdits, adjustWhitespace = !item.keepWhitespace)
        return
    }

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
internal fun completionReplaceRange(text: CharSequence, cursorPos: Int, item: CompletionItem): IntRange {
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

/** Delay before the language service is asked for diagnostics, after the editor appears or an edit. */
private const val DIAGNOSTICS_DEBOUNCE_MS = 500L

/** Delay before the diff lane is recomputed after an edit. */
private const val DIFF_DEBOUNCE_MS = 300L

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
internal fun isInsideCallArguments(text: CharSequence, offset: Int): Boolean {
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
private fun endOfLiteral(text: CharSequence, start: Int, limit: Int): Int {
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
