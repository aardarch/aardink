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

/**
 * LSP language service contract.
 *
 * Implementations provide completions, diagnostics, smart-indent, auto-close, hover docs,
 * document formatting, code actions/quick fixes, definition lookup, signature help, and rename
 * refactoring for a specific language or DSL. All `suspend` functions are safe to call from a
 * coroutine on [kotlinx.coroutines.Dispatchers.Default].
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
     * Validates the entire document and returns diagnostics with precise character ranges.
     *
     * Called after each edit on a debounced schedule (typically 500ms after the last keystroke
     * to avoid redundant validation during fast typing).
     */
    suspend fun diagnostics(document: CodeDocument): List<Diagnostic>

    /**
     * Returns the indent level (number of spaces) for a newly inserted line.
     *
     * Called after the user presses Enter. [lineIndex] is the 0-based index of the NEW line
     * (the one just created). The previous line is `lineIndex - 1`.
     */
    fun smartIndent(document: CodeDocument, lineIndex: Int): Int

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
     * Returns hover documentation for the token under [offset], or null if no doc is available.
     */
    suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc?

    /**
     * Formats the entire document and returns the formatted string.
     * Returns the original text unchanged if formatting fails.
     */
    suspend fun format(document: CodeDocument): String

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
     * Formats only the specified [range] of [document].
     */
    suspend fun formatRange(document: CodeDocument, range: IntRange): List<TextEdit> = emptyList()

    /**
     * Characters that should trigger a completion request when typed.
     * The UI calls [completions] when the character just typed is in this set.
     */
    val triggerCharacters: Set<Char>
        get() = setOf('<', '{', ':', '|', '@', '=', '"', ' ')
}
