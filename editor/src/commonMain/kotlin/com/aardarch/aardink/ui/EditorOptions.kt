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
import androidx.compose.ui.unit.TextUnit
import com.aardarch.aardink.core.LineEnding

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
 * @property quickSuggestions Typing a letter in code (not in a string or a comment) opens the
 *   completion list when the language service has items, as Monaco's `quickSuggestions`. Off by
 *   default, as in 0.6: the list opens on trigger characters and Ctrl+Space, and letters refresh it.
 * @property eol The line ending Enter, typing and pasting insert. Null, the default, keeps the
 *   document's own ([com.aardarch.aardink.core.CodeDocument.eol], from its first line break), so a
 *   CR LF file stays CR LF. The text already in the document is never converted.
 * @property breadcrumbs A row above the text with the path of document symbols the caret is in
 *   (`html › body › div`), from the language service's `documentSymbols`; a tap on one selects
 *   it. Off by default.
 * @property overviewRuler A narrow strip at the right edge that maps the whole document: marks
 *   where its diagnostics, find matches, selections, changed lines and the host's decorations
 *   (their `overviewRulerColor`) are; a click scrolls there. The scrollbar is drawn over it, as in
 *   VS Code. Off by default.
 * @property occurrencesHighlight While the caret rests on a symbol, its other occurrences are
 *   highlighted: those the language service's `documentHighlights` gives, or else the other
 *   occurrences of the word as a whole word. Off by default.
 * @property linkedEditing Typing in a range the language service links to others (an XML or HTML
 *   element's name, in its opening and closing tags) changes them all, as VS Code's
 *   `editor.linkedEditing`. Off by default.
 * @property colorDecorators The colours the language service finds in the text (`documentColors`:
 *   hex colours in the built-in XML, JSON, TOML and CSS) get a swatch, a bar in the colour under
 *   their text. Its picker opens from the hover, the touch menu, or
 *   [EditorCommand.ShowColorPicker][com.aardarch.aardink.core.EditorCommand.ShowColorPicker] with
 *   the caret in the colour, and writes the new colour back. Off by default.
 * @property keyBindings The host's keys for commands, before the editor's own
 *   ([EditorKeyBindings.Default]): a binding here wins over one of the editor's for the same keys,
 *   and one with a null command removes the editor's. Moving Select All Occurrences to
 *   Ctrl+Shift+D and freeing Ctrl+L, say:
 *   `listOf(EditorKeyBinding(KeyChord(Key.D, ctrlCmd = true, shift = true), EditorCommand.SelectAllOccurrences),
 *   EditorKeyBinding(KeyChord(Key.L, ctrlCmd = true), null))`. These bind the editor's commands;
 *   a host's own actions (Ctrl+S to save) belong in its own key handling.
 * @property fontSize The text's size, over [LocalEditorTypography]'s; unspecified, the default,
 *   keeps the typography's. With `CodeEditorLayout(onFontSizeChange)` the host owns it: zooming
 *   asks for a new size there.
 * @property lineHeight The height of a row of text; unspecified, the default, scales the
 *   typography's with [fontSize].
 * @property pinchToZoom Two fingers pinching the text, Ctrl+wheel (Cmd+wheel on macOS), Ctrl+=,
 *   Ctrl+- and Ctrl+0 change the font size, from 6 to 72 sp. Without `onFontSizeChange` the
 *   editor keeps the size itself. Off by default; with `onFontSizeChange` given, zooming is on
 *   whatever this says.
 * @property fontLigatures The font's ligatures (`->` drawn as one arrow, in fonts that have them).
 *   On by default, as text has always been drawn; off draws every character on its own.
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
    val eol: LineEnding? = null,
    val quickSuggestions: Boolean = false,
    val breadcrumbs: Boolean = false,
    val overviewRuler: Boolean = false,
    val occurrencesHighlight: Boolean = false,
    val linkedEditing: Boolean = false,
    val colorDecorators: Boolean = false,
    val keyBindings: List<EditorKeyBinding> = emptyList(),
    val fontSize: TextUnit = TextUnit.Unspecified,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val pinchToZoom: Boolean = false,
    val fontLigatures: Boolean = true,
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
