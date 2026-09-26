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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.EditorTypography
import com.aardarch.aardink.ui.GoToLineDialog
import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import com.aardarch.aardink.ui.LocalEditorTheme
import com.aardarch.aardink.ui.LocalEditorTypography
import com.aardarch.aardink.web.res.Res
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.configureWebResources

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
    internal val diagnostics: MutableState<List<Diagnostic>> = mutableStateOf(emptyList())
    internal val disposed: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The [CodeEditorState.textVersion] last reported to [onChange]. Set when a state is created,
     * not when the composition first observes it: an edit made between [AardinkWeb.mount] and
     * the first frame must still be reported.
     */
    internal var reportedTextVersion: Int = initialState.textVersion

    internal var onChange: ((String) -> Unit)? = null
    internal var onCursorChange: ((Int, Int) -> Unit)? = null
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

    /**
     * Renders an editor into the element with id [containerId], which must already be in the
     * document; the editor fills it. [registry] and [themes] let a product supply its own
     * [LanguageDefinition]s and [EditorTheme]s — this is how a grammar stays in the product that
     * owns it rather than in Aardink.
     *
     * Returns immediately; the first frame renders on the next animation frame.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun mount(
        containerId: String,
        initialText: String,
        options: WebEditorOptions = WebEditorOptions(),
        registry: LanguageRegistry = LanguageRegistry.withBuiltIns(),
        themes: Map<String, EditorTheme> = builtInThemes,
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
        ComposeViewport(container) {
            if (!handle.disposed.value) EditorContent(handle)
        }
        return handle
    }

    /** The editor's current text. */
    fun getValue(handle: AardinkEditorHandle): String = handle.state.value.document.text

    /** Replaces the whole text and clears undo history, like Monaco's `setValue`. Fires `onChange`. */
    fun setValue(handle: AardinkEditorHandle, text: String) {
        handle.state.value.loadText(text)
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
                val text = handle.state.value.document.text
                val state = CodeEditorState(text, tokenizer = language.tokenizer, scope = handle.scope)
                handle.reportedTextVersion = state.textVersion
                handle.language.value = language
                handle.state.value = state
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

    /** Registers the single caret listener (1-based line and column), replacing any previous one. */
    fun onCursorChange(handle: AardinkEditorHandle, callback: ((line: Int, column: Int) -> Unit)?) {
        handle.onCursorChange = callback
    }

    /** Replaces the diagnostics shown as squiggles and gutter markers. */
    fun setDiagnostics(handle: AardinkEditorHandle, diagnostics: List<WebDiagnostic>) {
        val document = handle.state.value.document
        handle.diagnostics.value = diagnostics.mapNotNull { web ->
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
    }

    /** Parses a JSON array of [WebDiagnostic] and shows it; see [setDiagnostics]. */
    fun setDiagnosticsJson(handle: AardinkEditorHandle, diagnosticsJson: String) {
        setDiagnostics(handle, json.decodeFromString(diagnosticsJsonSerializer, diagnosticsJson))
    }

    /** Scrolls to and places the caret at a 1-based [line] and [column], clamped to the document. */
    fun navigateTo(handle: AardinkEditorHandle, line: Int, column: Int) {
        val state = handle.state.value
        val document = state.document
        val offset = document.lineColToOffset((line - 1).coerceIn(0, document.lineCount - 1), maxOf(0, column - 1))
        state.navigateTo(offset, TextRange(offset))
    }

    /** Opens the find/replace panel. */
    fun showFind(handle: AardinkEditorHandle) {
        handle.findReplaceState.show()
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
        handle.onChange = null
        handle.onCursorChange = null
        handle.scope.cancel()
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
                handle.onChange?.invoke(state.document.text)
            }
        }
    }

    val typography = EditorTypography(
        fontFamily = rememberBundledMonoFont(),
        fontSize = options.fontSize.sp,
        lineHeight = (options.fontSize * 20f / 14f).sp,
    )

    CompositionLocalProvider(
        LocalEditorTheme provides (handle.themes[options.theme] ?: EditorThemes.VsCodeDark),
        LocalEditorTypography provides typography,
    ) {
        CodeEditorLayout(
            state = state,
            modifier = Modifier.fillMaxSize(),
            languageService = language.languageService,
            findReplaceState = handle.findReplaceState,
            foldState = handle.foldState,
            foldingProvider = language.foldingProvider,
            diagnostics = handle.diagnostics.value,
            onCursorChange = { line, column -> handle.onCursorChange?.invoke(line, column) },
            readOnly = options.readOnly,
            keyboardToolbarPlacement = KeyboardToolbarPlacement.platformDefault,
            showGutter = options.showGutter,
            showLineNumbers = options.showLineNumbers,
            showFoldMarkers = options.showFoldMarkers,
            softWrap = options.wordWrap,
            onRequestGoToLine = { showGoToLine = true },
        )
    }

    if (showGoToLine) {
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

/** JetBrains Mono, once loaded; shared by every editor on the page so it is fetched once. */
private var bundledMonoFont: FontFamily? = null

/**
 * The bundled JetBrains Mono, or the platform monospace family until (and unless) it loads.
 *
 * Not `org.jetbrains.compose.resources.Font(Res.font...)`: that throws inside composition when
 * the resource cannot be fetched -- a host whose bundler does not serve `composeResources/`,
 * or Karma -- and takes the whole editor down with it. A missing font should cost the typeface,
 * not the editor.
 */
@Composable
private fun rememberBundledMonoFont(): FontFamily {
    var family by remember { mutableStateOf(bundledMonoFont ?: FontFamily.Monospace) }
    LaunchedEffect(Unit) {
        if (bundledMonoFont != null) return@LaunchedEffect
        try {
            val bytes = Res.readBytes("font/jetbrains_mono_regular.ttf")
            val loaded = FontFamily(Font("JetBrainsMono-Regular", bytes))
            bundledMonoFont = loaded
            family = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Aardink: could not load the bundled JetBrains Mono (${e.message}); using the default monospace font.")
        }
    }
    return family
}
