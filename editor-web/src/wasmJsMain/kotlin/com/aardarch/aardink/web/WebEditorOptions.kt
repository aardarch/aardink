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
)

/**
 * A diagnostic as a web host describes it — the shape of a Monaco marker, so hosts moving off
 * Monaco can pass theirs through unchanged. Lines and columns are **1-based**, and
 * [endColumn] is **exclusive**. A range that runs past the end of its line is clamped to it.
 *
 * @property severity `"error"`, `"warning"` or `"info"`; anything else is treated as `"info"`.
 */
@Serializable
data class WebDiagnostic(val line: Int, val startColumn: Int, val endColumn: Int, val message: String, val severity: String = "error")
