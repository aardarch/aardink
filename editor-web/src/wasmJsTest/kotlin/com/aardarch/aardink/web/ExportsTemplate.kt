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

package com.aardarch.aardink.web

// ─────────────────────────────────────────────────────────────────────────────────────────────
// THE @JsExport TEMPLATE (docs/KMP_MIGRATION_PLAN.md §6.3).
//
// Copy this file into the wasmJs module that builds your executable (the one declaring
// binaries.executable()), change the package, and fill in the three marked places. It lives in
// :editor-web's test sources so that every build proves it still compiles against AardinkWeb,
// and ExportsTemplateTest exercises it; :sample-web holds the running copy.
//
// Handles cross the JS boundary as integer ids, not JsReference: simpler for the JS side, and
// no reliance on JsReference semantics. Every export is prefixed `aardink` so it cannot collide
// with your module's own exports.
// ─────────────────────────────────────────────────────────────────────────────────────────────

import com.aardarch.aardink.languages.LanguageRegistry

private val handles = mutableMapOf<Int, AardinkEditorHandle>()
private var nextHandleId = 1

private fun handle(id: Int): AardinkEditorHandle =
    handles[id] ?: error("No Aardink editor with id $id; it was never created or is already disposed")

@JsExport
fun aardinkCreate(containerId: String, initialText: String, optionsJson: String): Int {
    val handle = AardinkWeb.mount(
        containerId = containerId,
        initialText = initialText,
        options = AardinkWeb.parseOptions(optionsJson),
        // (1) Your languages: e.g. MyLanguages.register(LanguageRegistry.withBuiltIns()).
        registry = LanguageRegistry.withBuiltIns(),
        // (2) Your themes: e.g. AardinkWeb.builtInThemes + ("my-dark" to MyDarkTheme).
        themes = AardinkWeb.builtInThemes,
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

/** A JSON array of `{line, startColumn, endColumn, message, severity}`, 1-based, end-exclusive. */
@JsExport fun aardinkSetDiagnostics(id: Int, diagnosticsJson: String) = AardinkWeb.setDiagnosticsJson(handle(id), diagnosticsJson)

@JsExport fun aardinkRevealPosition(id: Int, line: Int, column: Int) = AardinkWeb.navigateTo(handle(id), line, column)

@JsExport fun aardinkShowFind(id: Int) = AardinkWeb.showFind(handle(id))

@JsExport fun aardinkUndo(id: Int): Boolean = AardinkWeb.undo(handle(id))

@JsExport fun aardinkRedo(id: Int): Boolean = AardinkWeb.redo(handle(id))

/** Safe to call twice; the second call is a no-op. */
@JsExport
fun aardinkDispose(id: Int) {
    handles.remove(id)?.let(AardinkWeb::dispose)
}

// (3) Your published version, e.g. generated from Gradle.
@JsExport fun aardinkVersion(): String = "0.0.0-template"
