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
package com.aardarch.aardink.web

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.EditorTypography
import com.aardarch.aardink.ui.GoToLineDialog
import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import com.aardarch.aardink.ui.LocalEditorTheme
import com.aardarch.aardink.ui.LocalEditorTypography
import com.aardarch.aardink.ui.RenderWhitespace
import com.aardarch.aardink.web.res.Res
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.configureWebResources
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * One editor mounted by [AardinkWeb.mount]. Opaque to hosts: every operation goes through
 * [AardinkWeb], which keeps the JavaScript-facing surface a flat list of functions.
 */
class AardinkEditorHandle internal constructor(
    internal val registry: LanguageRegistry,
    internal val themes: Map<String, EditorTheme>,
    internal val scope: CoroutineScope,
    initialState: CodeEditorState,
    initialLanguage: LanguageDefinition,
    initialOptions: WebEditorOptions,
) {
    internal val findReplaceState = FindReplaceState()
    internal val foldState = FoldState()

    // Replaced, not mutated, when the language changes: a state's tokenizer is fixed at
    // construction.
    internal val state: MutableState<CodeEditorState> = mutableStateOf(initialState)
    internal val language: MutableState<LanguageDefinition> = mutableStateOf(initialLanguage)
    internal val options: MutableState<WebEditorOptions> = mutableStateOf(initialOptions)

    /** The host's diagnostics, or null while the language's own are shown. */
    internal val diagnostics: MutableState<List<Diagnostic>?> = mutableStateOf(null)
    internal val disposed: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The [CodeEditorState.textVersion] last reported to [onChange]. Set when a state is created,
     * not when the composition first observes it: an edit made between [AardinkWeb.mount] and
     * the first frame must still be reported.
     */
    internal var reportedTextVersion: Int = initialState.textVersion

    internal var onChange: ((String) -> Unit)? = null
    internal var onContentChange: ((String, Int, String) -> Unit)? = null
    internal var onCursorChange: ((Int, Int) -> Unit)? = null
    internal var onDiagnosticsChange: ((String) -> Unit)? = null

    /** What the diff lane compares the text with; "" for no diff lane. */
    internal val baseline: MutableState<String> = mutableStateOf("")

    /** Counts [AardinkWeb.focus] calls, for the composition to act on, and how many it has. */
    internal val focusRequests = mutableIntStateOf(0)
    internal var focusesDone = 0

    /** The element the viewport lives in, inside the host's container, for [AardinkWeb.dispose] to take out. */
    internal var viewportElement: Element? = null

    /** Stops [followSize] for this editor's viewport; called by [AardinkWeb.dispose]. */
    internal var stopFollowingSize: JsAny? = null
}

/**
 * Mounts the Aardink editor into a web page and drives it from outside Compose.
 *
 * Every function here is plain Kotlin taking and returning simple values, so a product's own
 * wasmJs module can wrap each one in an `@JsExport` function (see
 * `docs/WEB_INTEGRATION.md`); `:sample-web` holds the reference copy of those wrappers.
 * Call everything from the browser's main thread.
 */
object AardinkWeb {

    /** Keys for [WebEditorOptions.theme]; pass a map of your own to [mount] to add or replace themes. */
    val builtInThemes: Map<String, EditorTheme> = mapOf(
        "vscode-dark" to EditorThemes.VsCodeDark,
        "vscode-light" to EditorThemes.VsCodeLight,
        "material-dark" to EditorThemes.MaterialDark,
        "material-light" to EditorThemes.MaterialLight,
        "midnight-ocean" to EditorThemes.MidnightOcean,
        "solarized-dark" to EditorThemes.SolarizedDark,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // ── What pages register: every editor mounted with the defaults sees it ─────

    /** The built-in languages and every one [registerLanguage] added. */
    internal val registry: LanguageRegistry = LanguageRegistry.withBuiltIns()

    /** The built-in themes and every one [registerTheme] added. */
    internal val registeredThemes: MutableMap<String, EditorTheme> = builtInThemes.toMutableMap()

    /** Counts registrations, so editors on screen pick up a theme or language registered again. */
    internal val registrations = mutableIntStateOf(0)

    /**
     * Adds a language highlighted by a grammar, for [mount] and [updateOptions] to use by its id.
     * [definitionJson] is `{ "id", "grammar", "extends"?, "displayName"?, "extensions"?,
     * "triggerCharacters"?, "inheritCompletions"? }`: `grammar` is a [DeclarativeGrammar] (a subset
     * of Monaco's Monarch, with `comments` as in Monaco's language configuration), and `extends`
     * names a language whose language service and folding this one takes (`"xml"`), besides what
     * [providers] answer. `inheritCompletions: false` leaves out that language's completions,
     * keeping the rest of its service (see [WebLanguageProviders]). `triggerCharacters`
     * (one-character strings, as Monaco's completion provider has them) open the completion list
     * when typed: besides those of the language it extends, or in place of them with
     * `inheritCompletions: false`; without them, the extended language's apply. Registering an id
     * again replaces it. Returns the id; throws [IllegalArgumentException] for a definition it
     * cannot use, saying where, such as a grammar with a lookbehind.
     */
    fun registerLanguage(definitionJson: String, providers: WebLanguageProviders = WebLanguageProviders()): String {
        val definition = languageFrom(definitionJson, providers, registry)
        registry.register(definition)
        registrations.intValue++
        return definition.id
    }

    /**
     * Adds (or replaces) a theme, for [WebEditorOptions.theme] to name: VS Code theme JSON, whose
     * `tokenColors` scopes also colour grammars' token names by dotted prefix (`tag.aardflex`, then
     * `tag`). What it does not set comes from `base` (a theme key) if given, else `vscode-light`
     * for a `"type": "light"` theme and `vscode-dark` otherwise. Throws [IllegalArgumentException]
     * for JSON it cannot read.
     */
    fun registerTheme(name: String, themeJson: String) {
        registeredThemes[name] = themeFrom(themeJson, registeredThemes)
        registrations.intValue++
    }

    /**
     * Renders an editor into the element with id [containerId], which must already be in the
     * document; the editor fills it. [registry] and [themes] let a product supply its own
     * [LanguageDefinition]s and [EditorTheme]s — this is how a grammar stays in the product that
     * owns it rather than in Aardink.
     *
     * The editor follows the container's size, also when only the container changes (a splitter
     * dragged, a container mounted into while hidden and shown later): each such change sends the
     * window a `resize` event, which is what Compose measures on.
     *
     * Returns immediately; the first frame renders on the next animation frame.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun mount(
        containerId: String,
        initialText: String,
        options: WebEditorOptions = WebEditorOptions(),
        registry: LanguageRegistry = this.registry,
        themes: Map<String, EditorTheme> = registeredThemes,
    ): AardinkEditorHandle {
        val container = requireNotNull(document.getElementById(containerId)) {
            "No element with id '$containerId' in the document"
        }
        // The editor's tokenization runs here rather than in CodeEditorState's default scope,
        // which is never cancelled: dispose() has to be able to stop it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val language = resolveLanguage(registry, options.language)
        val handle = AardinkEditorHandle(
            registry = registry,
            themes = themes,
            scope = scope,
            initialState = CodeEditorState(initialText, tokenizer = language.tokenizer, scope = scope),
            initialLanguage = language,
            initialOptions = options,
        )
        // An element of the editor's own, filling the container, so dispose() can take out all
        // Compose put in it (sometimes only after this returns) and nothing of the host's.
        val viewport = (document.createElement("div") as HTMLElement).apply {
            style.width = "100%"
            style.height = "100%"
            setAttribute("data-aardink-editor", "")
        }
        container.appendChild(viewport)
        ComposeViewport(viewport) {
            if (!handle.disposed.value) EditorContent(handle)
        }
        handle.viewportElement = viewport
        handle.stopFollowingSize = followSize(viewport)
        return handle
    }

    /** The editor's current text. */
    fun getValue(handle: AardinkEditorHandle): String = handle.state.value.document.text

    /**
     * Replaces the whole text and clears undo history, like Monaco's `setValue`. Fires `onChange`.
     * For a replacement the user can undo, use [replaceValue].
     */
    fun setValue(handle: AardinkEditorHandle, text: String) {
        handle.state.value.loadText(text)
    }

    /**
     * Replaces the whole text with [text] as one undo step, keeping the history, unlike [setValue]:
     * for a rewrite the user should be able to undo, such as an assistant's. Only what differs
     * changes, so carets, folds and diagnostics in unchanged text stay where they are. Reported to
     * [onContentChange] as an `"edit"` with a new version; [undo] brings the old text back, and
     * [getAlternativeVersionId] to its value then. Works in a read-only editor, as [setValue] does.
     * Returns whether the text changed.
     */
    fun replaceValue(handle: AardinkEditorHandle, text: String): Boolean = handle.state.value.replaceText(text)

    /**
     * Applies [edits] as one undo step, keeping the history, as Monaco's `executeEdits`: each is
     * reduced to the characters it really changes, so carets, folds and diagnostics elsewhere stay
     * put (see [CodeEditorState.executeEdits]). Afterwards the selections are [endSelections] when
     * given (positions in the text after the edits, as Monaco's `endCursorState`), else carried
     * through the edits. Reported to [onContentChange] as an `"edit"`.
     *
     * Returns false, changing nothing, in a read-only editor (as Monaco's does); true otherwise,
     * also when the edits left the text as it was.
     *
     * @throws IllegalArgumentException if two edits overlap.
     */
    fun executeEdits(handle: AardinkEditorHandle, edits: List<WebEdit>, endSelections: List<WebSelection>? = null): Boolean {
        if (handle.options.value.readOnly) return false
        val state = handle.state.value
        val document = state.document
        val textEdits = edits.map { edit ->
            val range = edit.range
            val a = offsetOf(document, range.startLineNumber, range.startColumn)
            val b = offsetOf(document, range.endLineNumber, range.endColumn)
            TextEdit(minOf(a, b) until maxOf(a, b), edit.text.orEmpty())
        }
        // As in Monaco, the end selections are positions in the text after the edits. Edits that
        // overlap have no such text; executeEdits below refuses them.
        val selections = endSelections?.takeIf { it.isNotEmpty() }?.let { wanted ->
            textAfter(document, textEdits)?.let(::CodeDocument)?.let { after ->
                wanted.map {
                    TextRange(
                        offsetOf(after, it.selectionStartLineNumber, it.selectionStartColumn),
                        offsetOf(after, it.positionLineNumber, it.positionColumn),
                    )
                }
            }
        }
        state.executeEdits(textEdits, selections)
        return true
    }

    /**
     * The text of [document] after [edits], applied as [CodeEditorState.executeEdits] applies them
     * (inserts at one offset in their order, before a replacement starting there); null when two
     * of them overlap.
     */
    private fun textAfter(document: CodeDocument, edits: List<TextEdit>): String? {
        val changes = edits.map { edit ->
            val start = edit.range.first.coerceIn(0, document.length)
            Triple(start, (edit.range.last + 1).coerceIn(start, document.length), edit.newText)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val text = StringBuilder()
        var at = 0
        for ((start, end, newText) in changes) {
            if (start < at) return null
            text.appendRange(document, at, start).append(newText)
            at = end
        }
        return text.appendRange(document, at, document.length).toString()
    }

    /**
     * [executeEdits] from a JSON array of [WebEdit] and a JSON array of [WebSelection] or `null`.
     *
     * @throws IllegalArgumentException for JSON that is not that, or edits that overlap.
     */
    fun executeEditsJson(handle: AardinkEditorHandle, editsJson: String, endSelectionsJson: String = "null"): Boolean = executeEdits(
        handle,
        json.decodeFromString(editsJsonSerializer, editsJson),
        json.decodeFromString(selectionsJsonSerializer.nullable, endSelectionsJson),
    )

    /**
     * The offset of a 1-based [line] and [column] in [document], as Monaco validates a position: a
     * line before the first is the start of the document, one after the last its end, and a column
     * past the end of its line that line's end.
     */
    private fun offsetOf(document: CodeDocument, line: Int, column: Int): Int = when {
        line < 1 -> 0
        line > document.lineCount -> document.length
        else -> document.lineColToOffset(line - 1, (column - 1).coerceAtLeast(0))
    }

    /** The options currently in effect. */
    fun currentOptions(handle: AardinkEditorHandle): WebEditorOptions = handle.options.value

    /**
     * Replaces every option. Changing [WebEditorOptions.language] keeps the text but starts a
     * fresh undo history, because the editor state is rebuilt around the new tokenizer.
     */
    fun updateOptions(handle: AardinkEditorHandle, options: WebEditorOptions) {
        if (options.language != handle.options.value.language) {
            val language = resolveLanguage(handle.registry, options.language)
            if (language.id != handle.language.value.id) {
                // The same state with a new tokenizer: text, selection and undo history survive.
                handle.state.value.tokenizer = language.tokenizer
                handle.language.value = language
                handle.foldState.unfoldAll()
            }
        }
        handle.options.value = options
    }

    /**
     * Applies only the options present in [patchJson] (a JSON object of [WebEditorOptions]
     * fields) on top of the current ones — the shape of Monaco's `updateOptions(partial)`.
     * Unknown keys are ignored.
     */
    fun patchOptions(handle: AardinkEditorHandle, patchJson: String) {
        val current = json.encodeToJsonElement(handle.options.value).jsonObject
        val patch = json.parseToJsonElement(patchJson).jsonObject
        updateOptions(handle, json.decodeFromJsonElement(WebEditorOptions.serializer(), JsonObject(current + patch)))
    }

    /** Parses a JSON object of [WebEditorOptions] fields; missing fields take their defaults. */
    fun parseOptions(optionsJson: String): WebEditorOptions = json.decodeFromString(WebEditorOptions.serializer(), optionsJson)

    /**
     * Registers the single content listener, replacing any previous one; `null` removes it.
     * Called with the full text after every change, typed or programmatic, once per frame at most.
     */
    fun onChange(handle: AardinkEditorHandle, callback: ((String) -> Unit)?) {
        handle.onChange = callback
    }

    /**
     * Registers the single detailed content listener, replacing any previous one; `null` removes it.
     * Called with the full text after every change, as [onChange] is, with the text's version
     * (Monaco's `versionId`: it goes up with every change) and what the change was: `"edit"`,
     * `"undo"`, `"redo"` or `"flush"` (the whole text replaced, as by [setValue]).
     */
    fun onContentChange(handle: AardinkEditorHandle, callback: ((text: String, versionId: Int, kind: String) -> Unit)?) {
        handle.onContentChange = callback
    }

    /** Registers the single caret listener (1-based line and column), replacing any previous one. */
    fun onCursorChange(handle: AardinkEditorHandle, callback: ((line: Int, column: Int) -> Unit)?) {
        handle.onCursorChange = callback
    }

    /**
     * Shows the host's [diagnostics] as squiggles and gutter markers in place of the language's
     * own; `null` goes back to the language's own. Either way they move along with the text as
     * it is edited, until the next list.
     */
    fun setDiagnostics(handle: AardinkEditorHandle, diagnostics: List<WebDiagnostic>?) {
        handle.diagnostics.value = diagnostics?.let { toDiagnostics(handle.state.value.document, it) }
    }

    /** [diagnostics] addressed to [document]; ones on lines it does not have are dropped. */
    internal fun toDiagnostics(document: CodeDocument, diagnostics: List<WebDiagnostic>): List<Diagnostic> = diagnostics.mapNotNull { web ->
        if (web.line < 1 || web.line > document.lineCount) return@mapNotNull null
        val line = web.line - 1
        val start = document.lineColToOffset(line, web.startColumn - 1)
        val endExclusive = document.lineColToOffset(line, web.endColumn - 1)
        Diagnostic(
            // Diagnostic.range is inclusive of its last character; a zero-width marker still
            // gets one character so it stays visible.
            range = start..maxOf(start, endExclusive - 1),
            lineNumber = line,
            message = web.message,
            severity = when (web.severity.lowercase()) {
                "error" -> DiagnosticSeverity.Error
                "warning" -> DiagnosticSeverity.Warning
                else -> DiagnosticSeverity.Info
            },
        )
    }

    /** A JSON array of [WebDiagnostic]; empty when it is not one. */
    internal fun parseDiagnostics(diagnosticsJson: String): List<WebDiagnostic> =
        runCatching { json.decodeFromString(diagnosticsJsonSerializer, diagnosticsJson) }.getOrDefault(emptyList())

    /** Parses a JSON array of [WebDiagnostic], or `null`, and shows it; see [setDiagnostics]. */
    fun setDiagnosticsJson(handle: AardinkEditorHandle, diagnosticsJson: String) {
        setDiagnostics(handle, json.decodeFromString(diagnosticsJsonSerializer.nullable, diagnosticsJson))
    }

    /**
     * Registers the single diagnostics listener, replacing any previous one; `null` removes it.
     * Called with a JSON array of [WebDiagnostic] each time the language's own diagnostics are
     * collected: 500 ms after the editor appears, and after each pause in typing. Not called while
     * the host's list from [setDiagnostics] is shown.
     */
    fun onDiagnosticsChange(handle: AardinkEditorHandle, callback: ((String) -> Unit)?) {
        handle.onDiagnosticsChange = callback
    }

    /**
     * Collects the language's own diagnostics again at once, without waiting for an edit: for when
     * what a registered language's diagnostics provider depends on outside the text has changed
     * (another file, a setting). They reach [onDiagnosticsChange] as usual. Does nothing while the
     * host's list from [setDiagnostics] is shown.
     */
    fun revalidate(handle: AardinkEditorHandle) {
        handle.state.value.revalidate()
    }

    /**
     * [diagnostics] in the host's shape. A marker covers one line, so a range running on past the
     * end of its first line ends there.
     */
    internal fun toWebDiagnostics(document: CodeDocument, diagnostics: List<Diagnostic>): List<WebDiagnostic> =
        diagnostics.map { diagnostic ->
            val (line, startColumn) = document.offsetToLineCol(diagnostic.range.first)
            val (endLine, endColumn) = document.offsetToLineCol(diagnostic.range.last + 1)
            WebDiagnostic(
                line = line + 1,
                startColumn = startColumn + 1,
                endColumn = if (endLine == line) endColumn + 1 else document.lineEnd(line) - document.lineStart(line) + 1,
                message = diagnostic.message,
                severity = diagnostic.severity.name.lowercase(),
            )
        }

    internal fun reportDiagnostics(handle: AardinkEditorHandle, diagnostics: List<Diagnostic>) {
        val callback = handle.onDiagnosticsChange ?: return
        callback(json.encodeToString(diagnosticsJsonSerializer, toWebDiagnostics(handle.state.value.document, diagnostics)))
    }

    /** Scrolls to and places the caret at a 1-based [line] and [column], clamped to the document. */
    fun navigateTo(handle: AardinkEditorHandle, line: Int, column: Int) {
        val state = handle.state.value
        val document = state.document
        val offset = document.lineColToOffset((line - 1).coerceIn(0, document.lineCount - 1), maxOf(0, column - 1))
        state.navigateTo(offset, TextRange(offset))
    }

    /**
     * Opens the find/replace panel and moves the keyboard focus to its find field, also from
     * outside the editor; Escape there closes it and gives the focus back to the text.
     */
    fun showFind(handle: AardinkEditorHandle) {
        handle.findReplaceState.show()
    }

    /** The text the diff lane compares with (typically what was last saved); "" turns the lane off. */
    fun setBaseline(handle: AardinkEditorHandle, text: String) {
        handle.baseline.value = text
    }

    /**
     * Formats the document with its language's formatter, as one undo step that changes only what
     * differs, so carets elsewhere stay where they are; then calls [then] with whether anything
     * changed. Nothing changes in a read-only editor, for a language with no formatter, or when
     * the text changed while the formatter worked.
     */
    fun format(handle: AardinkEditorHandle, then: (Boolean) -> Unit = {}) {
        val state = handle.state.value
        val service = handle.language.value.languageService
        if (service == null || handle.options.value.readOnly) return then(false)
        handle.scope.launch {
            val version = state.textVersion
            val text = state.document.text
            val formatted = withContext(state.computeDispatcher) { service.format(CodeDocument(text)) }
            val edit = changeBetween(text, formatted)
            if (state.textVersion != version || edit == null) return@launch then(false)
            state.applyTextEdits(listOf(edit))
            then(true)
        }
    }

    /** The one edit that turns [old] into [new]: what lies between the parts they share at either end. */
    internal fun changeBetween(old: String, new: String): TextEdit? {
        if (old == new) return null
        val limit = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return TextEdit(prefix until old.length - suffix, new.substring(prefix, new.length - suffix))
    }

    /** Moves the keyboard focus to the editor. */
    fun focus(handle: AardinkEditorHandle) {
        handle.focusRequests.intValue++
    }

    /** Every selection, the primary one first, as Monaco's `getSelections`. */
    fun getSelections(handle: AardinkEditorHandle): List<WebSelection> {
        val state = handle.state.value
        val document = state.document
        return state.selections.map { range ->
            val (anchorLine, anchorColumn) = document.offsetToLineCol(range.start)
            val (line, column) = document.offsetToLineCol(range.end)
            WebSelection(anchorLine + 1, anchorColumn + 1, line + 1, column + 1)
        }
    }

    /** Replaces the selections, the first becoming the primary one, clamped to the document; none is ignored. */
    fun setSelections(handle: AardinkEditorHandle, selections: List<WebSelection>) {
        if (selections.isEmpty()) return
        val state = handle.state.value
        val document = state.document
        fun offset(line: Int, column: Int) =
            document.lineColToOffset((line - 1).coerceIn(0, document.lineCount - 1), (column - 1).coerceAtLeast(0))
        state.setSelections(
            selections.map {
                TextRange(offset(it.selectionStartLineNumber, it.selectionStartColumn), offset(it.positionLineNumber, it.positionColumn))
            },
        )
    }

    /** [getSelections] as a JSON array. */
    fun getSelectionsJson(handle: AardinkEditorHandle): String = json.encodeToString(selectionsJsonSerializer, getSelections(handle))

    /** [setSelections] from a JSON array. */
    fun setSelectionsJson(handle: AardinkEditorHandle, selectionsJson: String) {
        setSelections(handle, json.decodeFromString(selectionsJsonSerializer, selectionsJson))
    }

    /** Whether there is an edit to undo. */
    fun canUndo(handle: AardinkEditorHandle): Boolean = handle.state.value.canUndo

    /** Whether there is an undone edit to redo. */
    fun canRedo(handle: AardinkEditorHandle): Boolean = handle.state.value.canRedo

    /** Ends the current undo step, so the next edit starts a new one, as Monaco's `pushUndoStop`. */
    fun pushUndoStop(handle: AardinkEditorHandle) {
        handle.state.value.pushUndoStop()
    }

    /**
     * A number that comes back when undo returns the text to an earlier state, as Monaco's
     * `getAlternativeVersionId`: keep it when saving, and the text is unsaved while it differs.
     */
    fun getAlternativeVersionId(handle: AardinkEditorHandle): Long = handle.state.value.alternativeVersionId

    /**
     * For debugging a grammar: the tokens [languageId]'s tokenizer gives [text], as JSON in the shape
     * of Monaco's `tokenize`: an array per line of `{ offset, type }`, where `type` is the token's
     * name (`""` for text no token covers): a grammar's own names (`comment.doc.toy`), and
     * Monaco's standard names for the built-in languages' tokens (`keyword`, `keyword.flow`,
     * `delimiter`, `string.escape`, ...).
     */
    fun tokenize(languageId: String, text: String): String {
        val language = resolveLanguage(registry, languageId)
        val tokens = language.tokenizer.tokenizeFull(text).filter { it.end > it.start }
        val lines = JsonArrayBuilderLines(text)
        for (token in tokens) lines.add(token.start, token.end, nameOf(token.type))
        return lines.build()
    }

    /** A token type's Monaco name: its scope; a host's own type without one by its class name. */
    private fun nameOf(type: TokenType): String = when {
        type == TokenType.Default -> ""
        type.scope.isNotEmpty() -> type.scope
        else -> type.toString().replaceFirstChar { it.lowercase() }
    }

    /** Undoes the last edit; false when there was nothing to undo. */
    fun undo(handle: AardinkEditorHandle): Boolean = handle.state.value.undo() != null

    /** Redoes the last undone edit; false when there was nothing to redo. */
    fun redo(handle: AardinkEditorHandle): Boolean = handle.state.value.redo() != null

    /**
     * Stops the editor: removes its composition, cancels its background work and drops the
     * listeners. Safe to call more than once. The container element itself is left in place for
     * the host to remove or reuse.
     */
    fun dispose(handle: AardinkEditorHandle) {
        if (handle.disposed.value) return
        handle.disposed.value = true
        // Out of the page: from Compose Multiplatform 1.13 that tears the viewport down entirely
        // (W-1); on 1.12 it takes its canvas and input elements away, and the editor stops.
        handle.stopFollowingSize?.let(::callStop)
        handle.stopFollowingSize = null
        handle.viewportElement?.remove()
        handle.viewportElement = null
        handle.onChange = null
        handle.onContentChange = null
        handle.onCursorChange = null
        handle.onDiagnosticsChange = null
        handle.scope.cancel()
    }

    /**
     * Starts fetching the bundled JetBrains Mono, so the first editor mounted shows in it straight
     * away (W-9). Safe to call more than once. The npm package's `preloadAardink()` calls it.
     */
    fun preloadFont() {
        bundledFontLoad()
    }

    /**
     * The path under which the bundled JetBrains Mono is requested, for [setResourceUrl].
     */
    const val BUNDLED_FONT_PATH: String =
        "composeResources/com.aardarch.aardink.web.res/font/jetbrains_mono_regular.ttf"

    /**
     * Fetches the resource at [path] (e.g. [BUNDLED_FONT_PATH]) from [url] instead of from
     * `./path` relative to the *page*, which is where Compose looks by default.
     *
     * A bundler that packages Aardink as a dependency puts its files somewhere else — Vite, for
     * one, emits them as hashed assets — so the page-relative default misses them. Call this
     * before [mount]. Other paths, including your own app's resources, keep the default.
     */
    @OptIn(ExperimentalResourceApi::class)
    fun setResourceUrl(path: String, url: String) {
        resourceUrls[path] = url
        if (!resourceMappingInstalled) {
            resourceMappingInstalled = true
            configureWebResources {
                resourcePathMapping { requested -> resourceUrls[requested] ?: "./$requested" }
            }
        }
    }

    private val resourceUrls = mutableMapOf<String, String>()
    private var resourceMappingInstalled = false

    /** True once [dispose] has run. */
    fun isDisposed(handle: AardinkEditorHandle): Boolean = handle.disposed.value

    private val diagnosticsJsonSerializer = kotlinx.serialization.builtins.ListSerializer(WebDiagnostic.serializer())
    private val selectionsJsonSerializer = kotlinx.serialization.builtins.ListSerializer(WebSelection.serializer())
    private val editsJsonSerializer = kotlinx.serialization.builtins.ListSerializer(WebEdit.serializer())

    private fun resolveLanguage(registry: LanguageRegistry, id: String): LanguageDefinition =
        registry.byId(id) ?: registry.byId("plaintext") ?: registry.all.first()
}

@Composable
private fun EditorContent(handle: AardinkEditorHandle) {
    val state = handle.state.value
    val language = handle.language.value
    val options = handle.options.value
    var showGoToLine by remember { mutableStateOf(false) }

    // Keyed on the state: a language change swaps it, and the new one's versions start over.
    LaunchedEffect(state) {
        snapshotFlow { state.textVersion }.collect { version ->
            if (version != handle.reportedTextVersion) {
                handle.reportedTextVersion = version
                val text = state.document.text
                handle.onChange?.invoke(text)
                handle.onContentChange?.invoke(text, version, state.lastChangeKind.name.lowercase())
            }
        }
    }

    // Nothing until the font is there (or has had its time): no flash of another font.
    val fontFamily = rememberBundledMonoFont()
    val typography = EditorTypography(
        fontFamily = fontFamily ?: FontFamily.Monospace,
        fontSize = options.fontSize.sp,
        lineHeight = (options.fontSize * 20f / 14f).sp,
    )

    // Registering a theme or language again changes what this editor shows.
    val registrations = AardinkWeb.registrations.intValue
    val theme = remember(options.theme, registrations) { handle.themes[options.theme] ?: EditorThemes.VsCodeDark }

    // A focus() made before the editor is on screen (it waits for its font) is kept until it is.
    val focusRequester = remember { FocusRequester() }
    val focusRequests = handle.focusRequests.intValue
    val shown = fontFamily != null
    LaunchedEffect(focusRequests, shown) {
        if (!shown || focusRequests <= handle.focusesDone) return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { focusRequester.requestFocus() }.isSuccess) handle.focusesDone = focusRequests
    }

    if (fontFamily != null) {
        CompositionLocalProvider(
            LocalEditorTheme provides theme,
            LocalEditorTypography provides typography,
        ) {
            CodeEditorLayout(
                state = state,
                modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                languageService = language.languageService,
                findReplaceState = handle.findReplaceState,
                foldState = handle.foldState,
                foldingProvider = language.foldingProvider,
                diagnostics = handle.diagnostics.value,
                savedText = handle.baseline.value,
                onCursorChange = { line, column -> handle.onCursorChange?.invoke(line, column) },
                onDiagnosticsChange = { diagnostics -> AardinkWeb.reportDiagnostics(handle, diagnostics) },
                keyboardToolbarPlacement = KeyboardToolbarPlacement.platformDefault,
                options = EditorOptions(
                    readOnly = options.readOnly,
                    softWrap = options.wordWrap,
                    showGutter = options.showGutter,
                    showLineNumbers = options.showLineNumbers,
                    showFoldMarkers = options.showFoldMarkers,
                    showMinimap = options.minimap,
                    stickyScroll = options.stickyScroll,
                    bracketPairColorization = options.bracketPairColorization,
                    highlightCurrentLine = options.highlightCurrentLine,
                    tabSize = options.tabSize.coerceIn(1, 16),
                    insertSpaces = options.insertSpaces,
                    renderWhitespace = renderWhitespaceOf(options.renderWhitespace),
                ),
                onRequestGoToLine = { showGoToLine = true },
            )
        }
    }

    if (showGoToLine) {
        // Under the editor's theme, so the dialog's colours follow it.
        CompositionLocalProvider(LocalEditorTheme provides theme) {
            GoToLineDialog(
                totalLines = state.document.lineCount,
                onConfirm = { line ->
                    showGoToLine = false
                    state.navigateTo(state.document.lineStart(line - 1))
                },
                onDismiss = { showGoToLine = false },
            )
        }
    }
}

/**
 * Compose sizes its canvas from its element on the window's `resize` event only, so an editor
 * whose container changes size on its own (a splitter dragged, a pane shown that was mounted while
 * `display: none`) would keep its old size, and clicks would land in the wrong place. This watches
 * [element] and, when its size has changed, sends the window a `resize`, at most once a frame.
 * Returns the function that stops watching.
 */
private fun followSize(element: Element): JsAny = js(
    """{
    let last = element.clientWidth + 'x' + element.clientHeight;
    let frame = 0;
    const observer = new ResizeObserver(() => {
        const size = element.clientWidth + 'x' + element.clientHeight;
        if (size === last) return;
        last = size;
        cancelAnimationFrame(frame);
        frame = requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
    });
    observer.observe(element);
    return () => {
        cancelAnimationFrame(frame);
        observer.disconnect();
    };
}""",
)

/** Calls the function [followSize] returned. */
private fun callStop(stop: JsAny): Unit = js("stop()")

/** JetBrains Mono's loading, started once and shared by every editor on the page. */
private var bundledFont: Deferred<FontFamily?>? = null

/**
 * The bundled JetBrains Mono, loading; null when it cannot be had.
 *
 * Not `org.jetbrains.compose.resources.Font(Res.font...)`: that throws inside composition when
 * the resource cannot be fetched -- a host whose bundler does not serve `composeResources/`,
 * or Karma -- and takes the whole editor down with it. A missing font should cost the typeface,
 * not the editor.
 */
private fun bundledFontLoad(): Deferred<FontFamily?> = bundledFont ?: fontScope.async {
    try {
        FontFamily(Font("JetBrainsMono-Regular", Res.readBytes("font/jetbrains_mono_regular.ttf")))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Aardink: could not load the bundled JetBrains Mono (${e.message}); using the default monospace font.")
        null
    }
}.also { bundledFont = it }

private val fontScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * The editor's font: the bundled JetBrains Mono, waited for up to [FONT_WAIT_MS] so the editor does
 * not appear in one font and then jump to another (W-9); the platform's monospace font if it takes
 * longer, swapped for JetBrains Mono when it comes. Null while waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted
@Composable
private fun rememberBundledMonoFont(): FontFamily? {
    val load = remember { bundledFontLoad() }
    val ready = if (load.isCompleted) load.getCompleted() ?: FontFamily.Monospace else null
    var family by remember { mutableStateOf(ready) }
    LaunchedEffect(load) {
        if (family != null && load.isCompleted) return@LaunchedEffect
        family = withTimeoutOrNull(FONT_WAIT_MS) { load.await() } ?: FontFamily.Monospace
        load.await()?.let { family = it }
    }
    return family
}

/** How long a new editor waits for its font before showing in the fallback. */
private const val FONT_WAIT_MS = 500L

/** Builds [AardinkWeb.tokenize]'s JSON: tokens by line, with the gaps between them as `""`. */
private class JsonArrayBuilderLines(private val text: String) {
    private val lineStarts: IntArray = run {
        val starts = ArrayList<Int>().apply { add(0) }
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        starts.toIntArray()
    }
    private val lines = Array(lineStarts.size) { mutableListOf<Pair<Int, String>>() }
    private val covered = IntArray(lineStarts.size)

    private fun lineEnd(line: Int) = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length

    fun add(start: Int, end: Int, type: String) {
        var line = lineStarts.indexOfLast { it <= start }
        var from = start
        while (line in lines.indices && from < end) {
            val column = from - lineStarts[line]
            if (column > covered[line]) lines[line] += covered[line] to ""
            lines[line] += column to type
            covered[line] = minOf(end, lineEnd(line)) - lineStarts[line]
            line++
            from = if (line < lineStarts.size) lineStarts[line] else end
        }
    }

    fun build(): String = buildJsonArray {
        for (line in lines.indices) {
            addJsonArray {
                val tokens = lines[line]
                for ((offset, type) in tokens) {
                    addJsonObject {
                        put("offset", offset)
                        put("type", type)
                    }
                }
                if (tokens.isEmpty() || covered[line] < lineEnd(line) - lineStarts[line]) {
                    addJsonObject {
                        put("offset", covered[line])
                        put("type", "")
                    }
                }
            }
        }
    }.toString()
}

/** Monaco's `renderWhitespace` value as the editor's; an unknown one is Monaco's default. */
internal fun renderWhitespaceOf(value: String): RenderWhitespace = when (value) {
    "none" -> RenderWhitespace.None
    "boundary" -> RenderWhitespace.Boundary
    "trailing" -> RenderWhitespace.Trailing
    "all" -> RenderWhitespace.All
    else -> RenderWhitespace.Selection
}
