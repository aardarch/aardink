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

/**
 * A diagnostic as a web host describes it — the shape of a Monaco marker, so hosts moving off
 * Monaco can pass theirs through unchanged, and the shape [AardinkWeb.onDiagnosticsChange]
 * reports the language's own in. Lines and columns are **1-based**, and [endColumn] is
 * **exclusive**. A range that runs past the end of its line is clamped to it.
 *
 * @property severity `"error"`, `"warning"` or `"info"`; anything else is treated as `"info"`.
 */
@Serializable
data class WebDiagnostic(val line: Int, val startColumn: Int, val endColumn: Int, val message: String, val severity: String = "error")

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
