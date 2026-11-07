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
package com.aardarch.aardink.core

import androidx.compose.ui.graphics.Color

/**
 * LSP language service contract.
 *
 * Implementations provide completions, diagnostics, smart-indent, auto-close, hover docs,
 * document formatting, code actions/quick fixes, definition lookup, signature help, and rename
 * refactoring for a specific language or DSL. All `suspend` functions are safe to call from a
 * coroutine on [com.aardarch.aardink.platform.EditorDispatchers.compute].
 *
 * External library consumers implement this interface for their own DSLs.
 */
interface LanguageService {

    /**
     * Returns completion items for the current cursor position.
     *
     * Called after trigger characters (`<`, `{`, `:`, `|`, `@`, `=`, `"`, ` `) are typed.
     * Results are filtered in the UI as the user continues typing.
     *
     * @param document The current document state.
     * @param cursorOffset Document-absolute offset of the cursor.
     */
    suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem>

    /**
     * [item] with what it left out of the list for being slow to work out, its
     * [documentation][CompletionItem.documentation] say, for the item the user is looking at. [item] is one this
     * service returned from [completions]. The default is [item] as it is.
     *
     * Asked for when the detail pane shows [item], and when [item] is accepted without
     * [additionalEdits][CompletionItem.additionalEdits]. Those the answer adds are about the text as
     * it is at the call: the editor applies them after inserting the item, as an undo step of their
     * own, unless the text has moved on by then. On acceptance this runs on the UI thread up to its
     * first suspension, so it must not block.
     */
    suspend fun resolveCompletion(item: CompletionItem): CompletionItem = item

    /**
     * Validates the entire document and returns diagnostics with precise character ranges.
     *
     * Called after each edit on a debounced schedule (typically 500ms after the last keystroke
     * to avoid redundant validation during fast typing).
     */
    suspend fun diagnostics(document: CodeDocument): List<Diagnostic>

    /**
     * Returns the text a newly inserted line starts with, or "" for none: its indentation, and
     * after that anything else the language continues a line with (the ` * ` of a block comment).
     * The caret goes after it.
     *
     * Called after the user presses Enter. [lineIndex] is the 0-based index of the NEW line
     * (the one just created). The previous line is `lineIndex - 1`. Build the indentation with
     * [options] (for example `options.indentAfter(previousLine, levels = 1)`), so it is tabs or
     * spaces as the editor is set up.
     */
    fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions = FormattingOptions()): String

    /**
     * Returns auto-close text to insert immediately after [charTyped], or null if no auto-close
     * is appropriate.
     *
     * E.g. typing `{` returns `}`, typing `<` after space inside a tag returns nothing, but
     * typing `</` when there is an unclosed tag returns the tag name + `>`.
     *
     * @param offset Document-absolute offset of the typed character (points at [charTyped]).
     */
    fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String?

    /**
     * What closes a selection when [typed] is typed over it, or null for [typed] to replace the
     * selection as usual: with `(` answering `)`, typing `(` over `foo` gives `(foo)`, with `foo`
     * still selected. Every selection is wrapped, in one undo step, unless one of them is a caret
     * or only blanks, or a quote is typed over a quote. Asked with a selection only, and not while
     * an input method is composing. The default is null.
     */
    fun surroundingClose(typed: Char): String? = null

    /**
     * Returns hover documentation for the token under [offset], or null if no doc is available.
     */
    suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc?

    /**
     * Formats the entire document, indenting as [options] says, and returns the formatted string.
     * Returns the original text unchanged if formatting fails.
     */
    suspend fun format(document: CodeDocument, options: FormattingOptions = FormattingOptions()): String

    /**
     * Returns available code actions / quick fixes for [range] in [document].
     */
    suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> = emptyList()

    /**
     * Returns the target location where the symbol at [offset] is defined.
     */
    suspend fun definition(document: CodeDocument, offset: Int): Location? = null

    /**
     * Returns all references to the symbol at [offset].
     */
    suspend fun references(document: CodeDocument, offset: Int): List<Location> = emptyList()

    /**
     * Returns signature help for a function call at [offset].
     */
    suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? = null

    /**
     * Prepares for a symbol rename at [offset]. Returns the valid identifier range or null if
     * the symbol cannot be renamed.
     */
    suspend fun prepareRename(document: CodeDocument, offset: Int): IntRange? = null

    /**
     * Whether this service can actually rename a symbol.
     *
     * Both [prepareRename] and [rename] default to "no", so a service that does not override them
     * declines every rename. A host offering a rename command has no other way to tell that in
     * advance, and would otherwise present an action that silently does nothing; check this before
     * showing the affordance.
     */
    val supportsRename: Boolean
        get() = false

    /**
     * Performs a symbol rename at [offset] to [newName]. Returns text edits to apply.
     */
    suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> = emptyList()

    /**
     * Formats only the specified [range] of [document], indenting as [options] says.
     */
    suspend fun formatRange(document: CodeDocument, range: IntRange, options: FormattingOptions = FormattingOptions()): List<TextEdit> =
        emptyList()

    /**
     * Whether [completions] must be asked again on every letter typed while the list is open, as
     * LSP's and Monaco's `isIncomplete`. True, the default and 0.6's behaviour: the service is asked
     * on each keystroke and filters for what was typed itself; the editor only orders the items by
     * [CompletionItem.sortPriority]. False: the service is asked once per word, and the editor
     * filters that list by [CompletionItem.filterText] as the word grows. The editor reads this
     * after each answer, so it may change from one answer to the next.
     */
    val completionsAreIncomplete: Boolean
        get() = true

    /**
     * The structure of [document] as a tree of [DocumentSymbol]s in document order: its elements,
     * its properties, its classes and functions. The editor asks after a pause in typing, as it asks
     * for [diagnostics], and uses them for Go to Symbol (Ctrl+Shift+O), the breadcrumbs row and
     * sticky scroll; [CodeEditorState.symbols] and [CodeEditorState.symbolPathAt] hand them to the
     * host. The default is none.
     */
    suspend fun documentSymbols(document: CodeDocument): List<DocumentSymbol> = emptyList()

    /**
     * The occurrences of the symbol at [offset], for the editor to highlight while the caret rests
     * there ([com.aardarch.aardink.ui.EditorOptions.occurrencesHighlight]). The default is none:
     * the editor then highlights the other occurrences of the word at the caret itself.
     */
    suspend fun documentHighlights(document: CodeDocument, offset: Int): List<DocumentHighlight> = emptyList()

    /**
     * For each of [offsets], the ranges the selection grows through from there (Shift+Alt+Right),
     * innermost first through [SelectionRange.parent]: the same number as [offsets], in order. The
     * default is none: the editor grows the selection by word, string, brackets and line itself.
     */
    suspend fun selectionRanges(document: CodeDocument, offsets: List<Int>): List<SelectionRange> = emptyList()

    /**
     * The ranges linked to the one at [offset], which always hold the same text (an element's name
     * in its opening and closing tags), or null for none. With
     * [com.aardarch.aardink.ui.EditorOptions.linkedEditing] the editor makes an edit inside one in
     * all of them. The built-in XML and HTML services have them.
     */
    suspend fun linkedEditingRanges(document: CodeDocument, offset: Int): LinkedEditingRanges? = null

    /**
     * The links in [document] besides plain URLs, which the editor finds itself: references the
     * service can resolve, an `<include component="…">` say. Ctrl+click (Cmd+click) opens one
     * through [com.aardarch.aardink.ui.CodeEditorLayout]'s `onOpenLink`. The default is none.
     */
    suspend fun documentLinks(document: CodeDocument): List<DocumentLink> = emptyList()

    /**
     * The colours written in [document], for the editor to draw as swatches
     * ([com.aardarch.aardink.ui.EditorOptions.colorDecorators]). The built-in XML, JSON, TOML and
     * CSS services find hex colours. The default is none.
     */
    suspend fun documentColors(document: CodeDocument): List<ColorInfo> = emptyList()

    /**
     * Ways to write [color] in place of [info], best first, for the colour picker to apply: the
     * editor applies the first. The default is none: the editor then writes a CSS hex colour,
     * `#rrggbb`, or `#rrggbbaa` when it is not opaque.
     */
    suspend fun colorPresentations(document: CodeDocument, info: ColorInfo, color: Color): List<ColorPresentation> = emptyList()

    /**
     * What is known about the tokens of [document] beyond its syntax (that a name is a parameter, a
     * type, a constant), for the editor to colour over its syntax tokens
     * ([com.aardarch.aardink.ui.EditorOptions.semanticHighlighting]). Each token's type is looked up
     * in the theme as a syntax token's is; where the theme has no colour for it, or no token covers
     * a character, the syntax colour stays. The editor asks after a pause in typing, and moves
     * the tokens with the text in between. The default is none.
     */
    suspend fun semanticTokens(document: CodeDocument): List<Token> = emptyList()

    /**
     * Short labels the editor shows among the characters of [range] of [document], such as a
     * parameter's name or an inferred type, with
     * [EditorOptions.inlayHints][com.aardarch.aardink.ui.EditorOptions] on. [range] runs from the
     * start of a line to the end of that or a later one, first to last (inclusive), and holds the
     * lines around the view: the editor asks again after a pause in typing, in scrolling past what it
     * asked about, or after a fold opens or closes, and calls off a question it no longer needs
     * answered. A long closed fold on screen makes that a few calls at once, one for the lines on
     * either side of it, so that the lines it hides are not asked about.
     */
    suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> = emptyList()

    /**
     * What the editor shows in a row above lines of [document], such as a reference count or a way
     * to run a test, with [EditorOptions.codeLens][com.aardarch.aardink.ui.EditorOptions] on. Lenses
     * on one line share its row, each clicked on its own.
     *
     * The answer is about the whole document. The editor asks after a pause in typing, calls off a
     * question it no longer needs answered, and moves what it has with each edit until the next
     * answer. A service for which a lens's title costs a lot can leave it blank
     * ([CodeLens.title]), and the editor asks again when such a lens comes on screen.
     */
    suspend fun codeLenses(document: CodeDocument): List<CodeLens> = emptyList()

    /**
     * Characters that should trigger a completion request when typed.
     * The UI calls [completions] when the character just typed is in this set.
     */
    val triggerCharacters: Set<Char>
        get() = setOf('<', '{', ':', '|', '@', '=', '"', ' ')
}
