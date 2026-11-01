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
@file:OptIn(ExperimentalJsExport::class, ExperimentalWasmJsInterop::class)

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

/** Replaces the whole text as one undo step, keeping the history; returns whether it changed. */
@JsExport fun aardinkReplaceValue(id: Int, text: String): Boolean = AardinkWeb.replaceValue(handle(id), text)

/**
 * Applies a JSON array of Monaco edits (`{ range, text }`) as one undo step, then places a JSON
 * array of selections, or keeps the carets for `null`. Returns "" when applied, "read-only" when
 * the editor is read-only, else what was wrong with the edits.
 */
@JsExport
fun aardinkExecuteEdits(id: Int, editsJson: String, endSelectionsJson: String): String = try {
    if (AardinkWeb.executeEditsJson(handle(id), editsJson, endSelectionsJson)) "" else "read-only"
} catch (e: IllegalArgumentException) {
    e.message ?: "not a list of edits"
}

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

/** Monaco's `setModelMarkers`: a JSON array of diagnostics as [owner]'s, shown with every other owner's and the language's own. */
@JsExport
fun aardinkSetModelMarkers(id: Int, owner: String, diagnosticsJson: String) =
    AardinkWeb.setDiagnosticsForOwnerJson(handle(id), owner, diagnosticsJson)

/** The lines on screen, a JSON array of one Monaco `IRange`. */
@JsExport fun aardinkGetVisibleRanges(id: Int): String = AardinkWeb.getVisibleRangesJson(handle(id))

/** How far the text is scrolled down, in CSS pixels. */
@JsExport fun aardinkGetScrollTop(id: Int): Double = AardinkWeb.getScrollTop(handle(id))

@JsExport fun aardinkSetScrollTop(id: Int, top: Double) = AardinkWeb.setScrollTop(handle(id), top)

/** The height of all the text, in CSS pixels. */
@JsExport fun aardinkGetScrollHeight(id: Int): Double = AardinkWeb.getScrollHeight(handle(id))

/** JSON `{ scrollTop, scrollLeft, scrollHeight }` as the text scrolls. */
@JsExport fun aardinkOnScrollChange(id: Int, callback: (String) -> Unit) = AardinkWeb.onScrollChange(handle(id), callback)

/** Measures the container again now. */
@JsExport fun aardinkLayout(id: Int) = AardinkWeb.layout(handle(id))

/** Shows a JSON array of Monaco-shaped `{ range, options }` as [owner]'s decorations, replacing that owner's. */
@JsExport
fun aardinkSetDecorations(id: Int, owner: String, decorationsJson: String) =
    AardinkWeb.setDecorationsJson(handle(id), owner, decorationsJson)

/** [owner]'s decorations' ranges now, as a JSON array of Monaco `IRange`s in the order they were set. */
@JsExport
fun aardinkGetDecorationRanges(id: Int, owner: String): String = AardinkWeb.getDecorationRangesJson(handle(id), owner)

/** The document's symbols as a JSON array of Monaco-shaped `DocumentSymbol`s, a tree. */
@JsExport fun aardinkGetDocumentSymbols(id: Int): String = AardinkWeb.getDocumentSymbolsJson(handle(id))

/** The symbols holding a 1-based position, outermost first, as the same JSON. */
@JsExport
fun aardinkGetSymbolPathAt(id: Int, line: Int, column: Int): String = AardinkWeb.getSymbolPathAtJson(handle(id), line, column)

/** Collects the language's own diagnostics again at once, without an edit; they reach [aardinkOnDiagnosticsChange]. */
@JsExport fun aardinkRevalidate(id: Int) = AardinkWeb.revalidate(handle(id))

/**
 * Adds a language highlighted by a grammar; see `AardinkWeb.registerLanguage`. [completions],
 * [hover], [diagnostics], [documentSymbols] and [documentColors] answer with JSON through a Promise, or null for nothing (pass one that
 * resolves to null for what the language does not have). Returns "" when it worked, else what was
 * wrong with the definition.
 */
@JsExport
fun aardinkRegisterLanguage(
    definitionJson: String,
    completions: (String, Int, Int) -> Promise<JsString?>,
    hover: (String, Int, Int) -> Promise<JsString?>,
    diagnostics: (String) -> Promise<JsString?>,
    documentSymbols: (String) -> Promise<JsString?>,
    documentColors: (String) -> Promise<JsString?>,
): String = try {
    AardinkWeb.registerLanguage(
        definitionJson,
        WebLanguageProviders(
            completions = { text, line, column -> completions(text, line, column).await<JsString?>()?.toString() },
            hover = { text, line, column -> hover(text, line, column).await<JsString?>()?.toString() },
            diagnostics = { text -> diagnostics(text).await<JsString?>()?.toString() },
            documentSymbols = { text -> documentSymbols(text).await<JsString?>()?.toString() },
            documentColors = { text -> documentColors(text).await<JsString?>()?.toString() },
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

/**
 * Full text, the text's version, what the change was ("edit", "undo", "redo", "flush") and a JSON
 * array of Monaco-shaped changes since the last call, after each change.
 */
@JsExport
fun aardinkOnContentChange(id: Int, callback: (String, Int, String, String) -> Unit) = AardinkWeb.onContentChange(handle(id), callback)

/** JSON `{ selections, reason, source }` whenever the selections change. */
@JsExport
fun aardinkOnSelectionChange(id: Int, callback: (String) -> Unit) = AardinkWeb.onSelectionChange(handle(id), callback)

/** True when the text gains the keyboard focus, false when it loses it. */
@JsExport fun aardinkOnFocusChange(id: Int, callback: (Boolean) -> Unit) = AardinkWeb.onFocusChange(handle(id), callback)

/** The primary caret as JSON `{ lineNumber, column }`, 1-based. */
@JsExport fun aardinkGetPosition(id: Int): String = AardinkWeb.getPositionJson(handle(id))

/** One caret at a 1-based position, without scrolling. */
@JsExport fun aardinkSetPosition(id: Int, line: Int, column: Int) = AardinkWeb.setPosition(handle(id), line, column)

/** Monaco's trigger: an action by its id, the host's or the editor's own; [payloadJson] is JSON or "null". */
@JsExport
fun aardinkTrigger(id: Int, actionId: String, payloadJson: String): Boolean = AardinkWeb.trigger(handle(id), actionId, payloadJson)

@JsExport fun aardinkCanTrigger(id: Int, actionId: String): Boolean = AardinkWeb.canTrigger(handle(id), actionId)

/** An action's label; "" for an id with none. */
@JsExport fun aardinkActionLabel(id: Int, actionId: String): String = AardinkWeb.actionLabel(handle(id), actionId).orEmpty()

/** Monaco's addAction: [keybindings] is the action's keybindings, one per line. */
@JsExport
fun aardinkAddAction(id: Int, actionId: String, label: String, keybindings: String, inContextMenu: Boolean, run: () -> Unit) =
    AardinkWeb.addAction(handle(id), actionId, label, keybindings.lines().filter { it.isNotBlank() }, inContextMenu, run)

@JsExport fun aardinkRemoveAction(id: Int, actionId: String) = AardinkWeb.removeAction(handle(id), actionId)

/** Monaco's addCommand: the command's id, or "" for a keybinding it cannot read. */
@JsExport
fun aardinkAddCommand(id: Int, keybinding: String, run: () -> Unit): String = AardinkWeb.addCommand(handle(id), keybinding, run).orEmpty()

/** Scrolls a 1-based line into view without moving the caret; in the middle with [center]. */
@JsExport fun aardinkRevealLine(id: Int, line: Int, center: Boolean) = AardinkWeb.revealLine(handle(id), line, center)

/** Scrolls a 1-based position into view without moving the caret; in the middle with [center]. */
@JsExport
fun aardinkRevealRange(id: Int, line: Int, column: Int, center: Boolean) = AardinkWeb.revealPosition(handle(id), line, column, center)

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
