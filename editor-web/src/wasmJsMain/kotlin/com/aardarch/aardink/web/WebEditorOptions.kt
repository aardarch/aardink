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

import kotlinx.serialization.Serializable

/**
 * Editor settings a web host can change at any time through [AardinkWeb.updateOptions] or
 * [AardinkWeb.patchOptions]. Serializable so the JavaScript side can pass them as JSON.
 *
 * @property language A [com.aardarch.aardink.languages.LanguageDefinition.id] in the registry
 *   passed to [AardinkWeb.mount]; an unknown id falls back to `plaintext`.
 * @property theme A key in the theme map passed to [AardinkWeb.mount] (by default
 *   [AardinkWeb.builtInThemes]); an unknown key falls back to `vscode-dark`.
 * @property fontSize In CSS pixels. Line height scales with it at the editor's default 20:14 ratio.
 * @property minimap A picture of the whole text at the side, with a slider over what is shown.
 * @property stickyScroll Keeps the first lines of the blocks the top of the view is inside pinned
 *   at the top.
 * @property bracketPairColorization Colours brackets by how deeply they are nested.
 * @property highlightCurrentLine Highlights the line of each caret.
 * @property tabSize The width of an indent, in spaces.
 * @property insertSpaces Tab indents with [tabSize] spaces rather than a tab character.
 * @property renderWhitespace Which spaces and tabs are drawn, as Monaco's option: `none`,
 *   `boundary`, `selection` (the default), `trailing` or `all`. Anything else counts as `selection`.
 * @property quickSuggestions Typing a letter in code opens the completion list, as Monaco's
 *   `quickSuggestions`; off by default, as in 0.6.
 * @property overviewRuler The overview ruler at the right edge; Monaco's `overviewRulerLanes`
 *   above 0. Off by default.
 * @property occurrencesHighlight The other occurrences of the symbol at the caret, highlighted.
 * @property linkedEditing Typing a tag name changes its partner, as Monaco's `linkedEditing`.
 * @property colorDecorators Colour swatches under the colours in the text, with a picker.
 * @property breadcrumbs The path of symbols at the caret, in a row above the text.
 * @property fontLigatures The font's ligatures; on by default.
 */
@Serializable
data class WebEditorOptions(
    val language: String = "plaintext",
    val theme: String = "vscode-dark",
    val fontSize: Float = 14f,
    val wordWrap: Boolean = true,
    val readOnly: Boolean = false,
    val showGutter: Boolean = true,
    val showLineNumbers: Boolean = true,
    val showFoldMarkers: Boolean = true,
    val minimap: Boolean = false,
    val stickyScroll: Boolean = false,
    val bracketPairColorization: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val renderWhitespace: String = "selection",
    val quickSuggestions: Boolean = false,
    val overviewRuler: Boolean = false,
    val occurrencesHighlight: Boolean = false,
    val linkedEditing: Boolean = false,
    val colorDecorators: Boolean = false,
    val breadcrumbs: Boolean = false,
    val fontLigatures: Boolean = true,
)

/**
 * A selection as a web host describes it: Monaco's `Selection`, 1-based. It runs from where it
 * started ([selectionStartLineNumber], [selectionStartColumn]) to where the caret is
 * ([positionLineNumber], [positionColumn]); a caret has both the same.
 */
@Serializable
data class WebSelection(
    val selectionStartLineNumber: Int,
    val selectionStartColumn: Int,
    val positionLineNumber: Int,
    val positionColumn: Int,
)

/** A position as a web host describes it: Monaco's `IPosition`, 1-based. */
@Serializable
data class WebPosition(val lineNumber: Int, val column: Int)

/**
 * A diagnostic as a web host describes it — the shape of a Monaco marker, so hosts moving off
 * Monaco can pass theirs through unchanged, and the shape [AardinkWeb.onDiagnosticsChange]
 * reports the language's own in. Lines and columns are **1-based**, and [endColumn] is
 * **exclusive**. It runs from [line] to [endLineNumber] ([line] when absent); a position past the
 * end of its line is the line's end.
 *
 * @property severity `"error"`, `"warning"`, `"info"` or `"hint"`; anything else is treated as `"info"`.
 * @property code The producer's identifier for this kind of problem, shown after [source].
 * @property codeHref Where [code] is explained (Monaco's `code.target`).
 * @property tags Monaco's `MarkerTag`s: `1` unnecessary (drawn faded), `2` deprecated (struck through).
 */
@Serializable
data class WebDiagnostic(
    val line: Int,
    val startColumn: Int,
    val endColumn: Int,
    val message: String,
    val severity: String = "error",
    val endLineNumber: Int? = null,
    val source: String? = null,
    val code: String? = null,
    val codeHref: String? = null,
    val tags: List<Int>? = null,
    val relatedInformation: List<WebRelatedInformation>? = null,
)

/**
 * Another place that bears on a [WebDiagnostic]: Monaco's `IRelatedInformation`, with its
 * `resource` as a string.
 */
@Serializable
data class WebRelatedInformation(
    val resource: String,
    val message: String,
    val startLineNumber: Int,
    val startColumn: Int,
    val endLineNumber: Int,
    val endColumn: Int,
)

/**
 * A range as a web host describes it: Monaco's `IRange`, 1-based, from [startLineNumber] and
 * [startColumn] to [endLineNumber] and [endColumn], which is **exclusive**. A position past the end
 * of its line is the line's end; one past the last line, the end of the document.
 */
@Serializable
data class WebRange(val startLineNumber: Int, val startColumn: Int, val endLineNumber: Int, val endColumn: Int)

/**
 * An edit as a web host describes it, for [AardinkWeb.executeEdits]: Monaco's
 * `IIdentifiedSingleEditOperation`, replacing [range] with [text]. `null` or `""` deletes the
 * range; an empty range inserts.
 */
@Serializable
data class WebEdit(val range: WebRange, val text: String? = null)

/**
 * A document symbol as a web host describes it: Monaco's `DocumentSymbol`. [kind] is Monaco's
 * `SymbolKind` number (0 `File`, 4 `Class`, 11 `Function`, 17 `Array`, 18 `Object`, 19 `Key`...);
 * [selectionRange], the part that names it, is [range] when absent. The shape of a
 * `provideDocumentSymbols` answer and of `getDocumentSymbols`.
 */
@Serializable
data class WebDocumentSymbol(
    val name: String,
    val kind: Int = 0,
    val range: WebRange,
    val selectionRange: WebRange? = null,
    val detail: String? = null,
    val children: List<WebDocumentSymbol>? = null,
)
