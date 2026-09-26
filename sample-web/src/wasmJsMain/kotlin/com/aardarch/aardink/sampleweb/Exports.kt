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

import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.web.AardinkEditorHandle
import com.aardarch.aardink.web.AardinkWeb

private val handles = mutableMapOf<Int, AardinkEditorHandle>()
private var nextHandleId = 1

private fun handle(id: Int): AardinkEditorHandle =
    handles[id] ?: error("No Aardink editor with id $id; it was never created or is already disposed")

/**
 * Where the bundled JetBrains Mono is served from, if not `./composeResources/...` relative to
 * the page. Call before the first [aardinkCreate]. The npm package's index.js does this for you.
 */
@JsExport fun aardinkSetBundledFontUrl(url: String) = AardinkWeb.setResourceUrl(AardinkWeb.BUNDLED_FONT_PATH, url)

@JsExport
fun aardinkCreate(containerId: String, initialText: String, optionsJson: String): Int {
    val handle = AardinkWeb.mount(
        containerId = containerId,
        initialText = initialText,
        options = AardinkWeb.parseOptions(optionsJson),
        registry = LanguageRegistry.withBuiltIns(),
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

@JsExport fun aardinkVersion(): String = BuildInfo.VERSION
