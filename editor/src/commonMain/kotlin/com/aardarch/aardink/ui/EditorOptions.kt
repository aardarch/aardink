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

import androidx.compose.runtime.Immutable

/**
 * How [CodeEditorLayout] looks and behaves. One object instead of a parameter per switch, so a new
 * switch does not change the composable's signature; change one with `copy`.
 *
 * The defaults follow VS Code's, except where a phone screen argues otherwise (noted per option).
 *
 * @property readOnly The text can be selected, searched and copied, but not changed.
 * @property softWrap Long lines wrap at the editor's width instead of scrolling sideways.
 * @property showGutter The column left of the text with line numbers, fold triangles, diagnostic
 *   dots and diff bars. The four switches after it pick its lanes.
 * @property showLineNumbers Line numbers in the gutter.
 * @property showFoldMarkers Fold triangles in the gutter.
 * @property showDiagnosticAnnotations A dot per line with a diagnostic, in the gutter.
 * @property showDiffMarkers A bar per line changed since the diff baseline, in the gutter.
 * @property highlightCurrentLine The caret's line gets `EditorTheme.lineHighlight` behind it while
 *   nothing is selected.
 * @property matchBrackets A box around the bracket that matches the one at the caret.
 * @property bracketPairColorization Brackets coloured by nesting depth, from
 *   `EditorTheme.bracketPairColors`.
 * @property stickyScroll The first lines of the blocks the top line is inside stay pinned at the
 *   top while scrolling. Off by default: on a phone the pinned lines take room the code needs.
 * @property showMinimap A zoomed-out map of the whole document at the right edge. Off by default.
 * @property tabSize Columns a tab and an indentation step are wide.
 * @property insertSpaces Tab and indentation insert spaces ([tabSize] of them) rather than a tab.
 * @property renderWhitespace Which spaces and tabs are drawn, as faint dots and arrows in
 *   `EditorTheme.whitespaceColor`. VS Code's default: those inside a selection.
 */
@Immutable
data class EditorOptions(
    val readOnly: Boolean = false,
    val softWrap: Boolean = false,
    val showGutter: Boolean = true,
    val showLineNumbers: Boolean = true,
    val showFoldMarkers: Boolean = true,
    val showDiagnosticAnnotations: Boolean = true,
    val showDiffMarkers: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val matchBrackets: Boolean = true,
    val bracketPairColorization: Boolean = true,
    val stickyScroll: Boolean = false,
    val showMinimap: Boolean = false,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val renderWhitespace: RenderWhitespace = RenderWhitespace.Selection,
) {
    init {
        require(tabSize in 1..16) { "tabSize must be 1..16, was $tabSize" }
    }
}

/** Which whitespace [EditorOptions.renderWhitespace] draws; the values of VS Code's setting. */
enum class RenderWhitespace {
    /** None. */
    None,

    /** All but a single space between two words. */
    Boundary,

    /** Only inside a selection. */
    Selection,

    /** Only after a line's last non-whitespace character, or all of a blank line's. */
    Trailing,

    /** Every space and tab. */
    All,
}
