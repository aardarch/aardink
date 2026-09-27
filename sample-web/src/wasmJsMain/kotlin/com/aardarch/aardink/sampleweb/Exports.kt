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
@file:OptIn(ExperimentalJsExport::class)

package com.aardarch.aardink.sampleweb

// The running copy of the @JsExport template (editor-web/src/wasmJsTest/.../ExportsTemplate.kt)
// that the npm package's index.js calls. Keep the two in step: a change to one belongs in both.

import com.aardarch.aardink.web.AardinkEditorHandle
import com.aardarch.aardink.web.AardinkWeb
import com.aardarch.aardink.web.WebLanguageProviders
import kotlinx.coroutines.await
import kotlin.js.Promise

private val handles = mutableMapOf<Int, AardinkEditorHandle>()
private var nextHandleId = 1

private fun handle(id: Int): AardinkEditorHandle =
    handles[id] ?: error("No Aardink editor with id $id; it was never created or is already disposed")

/**
 * Where the bundled JetBrains Mono is served from, if not `./composeResources/...` relative to
 * the page. Call before the first [aardinkCreate]. The npm package's index.js does this for you.
 */
@JsExport fun aardinkSetBundledFontUrl(url: String) = AardinkWeb.setResourceUrl(AardinkWeb.BUNDLED_FONT_PATH, url)

/** Starts fetching the bundled font, so the first editor shows in it at once. */
@JsExport fun aardinkPreloadFont() = AardinkWeb.preloadFont()

@JsExport
fun aardinkCreate(containerId: String, initialText: String, optionsJson: String): Int {
    val handle = AardinkWeb.mount(
        containerId = containerId,
        initialText = initialText,
        options = AardinkWeb.parseOptions(optionsJson),
    )
    val id = nextHandleId++
    handles[id] = handle
    return id
}

@JsExport fun aardinkGetValue(id: Int): String = AardinkWeb.getValue(handle(id))

@JsExport fun aardinkSetValue(id: Int, text: String) = AardinkWeb.setValue(handle(id), text)

/** [patchJson] holds only the options to change, like Monaco's `updateOptions`. */
@JsExport fun aardinkUpdateOptions(id: Int, patchJson: String) = AardinkWeb.patchOptions(handle(id), patchJson)

@JsExport fun aardinkOnChange(id: Int, callback: (String) -> Unit) = AardinkWeb.onChange(handle(id), callback)

@JsExport fun aardinkOnCursorChange(id: Int, callback: (Int, Int) -> Unit) = AardinkWeb.onCursorChange(handle(id), callback)

/**
 * A JSON array of `{line, startColumn, endColumn, message, severity}`, 1-based, end-exclusive; or
 * `null` to show the language's own diagnostics again.
 */
@JsExport fun aardinkSetDiagnostics(id: Int, diagnosticsJson: String) = AardinkWeb.setDiagnosticsJson(handle(id), diagnosticsJson)

/** Called with the language's own diagnostics, as a JSON array in the same shape, each time they change. */
@JsExport
fun aardinkOnDiagnosticsChange(id: Int, callback: (String) -> Unit) = AardinkWeb.onDiagnosticsChange(handle(id), callback)

/**
 * Adds a language highlighted by a grammar; see `AardinkWeb.registerLanguage`. [completions],
 * [hover] and [diagnostics] answer with JSON through a Promise, or null for nothing (pass one that
 * resolves to null for what the language does not have). Returns "" when it worked, else what was
 * wrong with the definition.
 */
@JsExport
fun aardinkRegisterLanguage(
    definitionJson: String,
    completions: (String, Int, Int) -> Promise<JsString?>,
    hover: (String, Int, Int) -> Promise<JsString?>,
    diagnostics: (String) -> Promise<JsString?>,
): String = try {
    AardinkWeb.registerLanguage(
        definitionJson,
        WebLanguageProviders(
            completions = { text, line, column -> completions(text, line, column).await<JsString?>()?.toString() },
            hover = { text, line, column -> hover(text, line, column).await<JsString?>()?.toString() },
            diagnostics = { text -> diagnostics(text).await<JsString?>()?.toString() },
        ),
    )
    ""
} catch (e: IllegalArgumentException) {
    e.message ?: "not a language definition"
}

/** Adds a theme from VS Code theme JSON; see `AardinkWeb.registerTheme`. Returns "" when it worked, else what was wrong. */
@JsExport
fun aardinkRegisterTheme(name: String, themeJson: String): String = try {
    AardinkWeb.registerTheme(name, themeJson)
    ""
} catch (e: IllegalArgumentException) {
    e.message ?: "not a theme"
}

/** Full text, the text's version and what the change was ("edit", "undo", "redo", "flush"), after each change. */
@JsExport
fun aardinkOnContentChange(id: Int, callback: (String, Int, String) -> Unit) = AardinkWeb.onContentChange(handle(id), callback)

/** The text the diff lane compares with; "" turns it off. */
@JsExport fun aardinkSetBaseline(id: Int, text: String) = AardinkWeb.setBaseline(handle(id), text)

/** Formats the document; [done] gets whether anything changed. */
@JsExport fun aardinkFormat(id: Int, done: (Boolean) -> Unit) = AardinkWeb.format(handle(id), done)

@JsExport fun aardinkFocus(id: Int) = AardinkWeb.focus(handle(id))

/** A JSON array of Monaco-shaped selections, the primary one first. */
@JsExport fun aardinkGetSelections(id: Int): String = AardinkWeb.getSelectionsJson(handle(id))

@JsExport fun aardinkSetSelections(id: Int, selectionsJson: String) = AardinkWeb.setSelectionsJson(handle(id), selectionsJson)

@JsExport fun aardinkCanUndo(id: Int): Boolean = AardinkWeb.canUndo(handle(id))

@JsExport fun aardinkCanRedo(id: Int): Boolean = AardinkWeb.canRedo(handle(id))

@JsExport fun aardinkPushUndoStop(id: Int) = AardinkWeb.pushUndoStop(handle(id))

@JsExport fun aardinkGetAlternativeVersionId(id: Int): Double = AardinkWeb.getAlternativeVersionId(handle(id)).toDouble()

/** For debugging a grammar: Monaco's tokenize shape, as JSON. */
@JsExport fun aardinkTokenize(languageId: String, text: String): String = AardinkWeb.tokenize(languageId, text)

@JsExport fun aardinkRevealPosition(id: Int, line: Int, column: Int) = AardinkWeb.navigateTo(handle(id), line, column)

@JsExport fun aardinkShowFind(id: Int) = AardinkWeb.showFind(handle(id))

@JsExport fun aardinkUndo(id: Int): Boolean = AardinkWeb.undo(handle(id))

@JsExport fun aardinkRedo(id: Int): Boolean = AardinkWeb.redo(handle(id))

/** Safe to call twice; the second call is a no-op. */
@JsExport
fun aardinkDispose(id: Int) {
    handles.remove(id)?.let(AardinkWeb::dispose)
}

@JsExport fun aardinkVersion(): String = BuildInfo.VERSION
