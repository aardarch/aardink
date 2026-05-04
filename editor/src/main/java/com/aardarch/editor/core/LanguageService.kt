package com.aardarch.editor.core

/**
 * LSP-lite language service contract.
 *
 * Implementations provide completions, diagnostics, smart-indent, auto-close, hover docs, and
 * document formatting for a specific language or DSL. All `suspend` functions are safe to call
 * from a coroutine on [kotlinx.coroutines.Dispatchers.Default].
 *
 * The Aardflex XML implementation lives in the `:app` module as [AardflexXmlLanguageService].
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
     * Characters that should trigger a completion request when typed.
     * The UI calls [completions] when the character just typed is in this set.
     */
    val triggerCharacters: Set<Char>
        get() = setOf('<', '{', ':', '|', '@', '=', '"', ' ')
}
