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
 * One language service made of two: [primary], a host's own for its dialect, answers first and
 * [fallback], typically a built-in language's, fills in what it does not. This is how a language
 * extends another (the web's `registerLanguage({ extends: 'xml' })` works this way too):
 *
 * - **Completions:** the primary's, then the fallback's, less any fallback item with the same kind
 *   and label as one of the primary's. Only the fallback's when the primary has none. Without
 *   [inheritCompletions], only the primary's.
 * - **Diagnostics, code actions and references:** both, the primary's first.
 * - **Hover, definition, signature help, auto-close:** the primary's, or else the fallback's.
 * - **Enter indentation:** the primary's, or the fallback's when the primary gives none (`""`).
 * - **Formatting:** the primary's when it changes anything, else the fallback's.
 * - **Rename:** by whichever of the two [LanguageService.supportsRename], the primary first.
 * - **Document symbols, highlights and selection ranges:** the primary's, or the fallback's when
 *   the primary has none. **Linked editing ranges:** likewise.
 * - **Document links:** both, without two for the same range.
 * - **Trigger characters:** both sets, or only the primary's without [inheritCompletions].
 *
 * Open, so a subclass can change one rule and keep the rest.
 */
open class CompositeLanguageService(val primary: LanguageService, val fallback: LanguageService, val inheritCompletions: Boolean = true) :
    LanguageService {

    override val triggerCharacters: Set<Char>
        get() = if (inheritCompletions) primary.triggerCharacters + fallback.triggerCharacters else primary.triggerCharacters

    override val supportsRename: Boolean
        get() = primary.supportsRename || fallback.supportsRename

    override val completionsAreIncomplete: Boolean
        get() = primary.completionsAreIncomplete || (inheritCompletions && fallback.completionsAreIncomplete)

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val own = primary.completions(document, cursorOffset)
        if (!inheritCompletions) return own
        val inherited = fallback.completions(document, cursorOffset)
        val all = merged(own, inherited)
        if (primary.completionsAreIncomplete == fallback.completionsAreIncomplete) return all
        // The list as a whole is asked for again per keystroke, so the editor will not filter it:
        // the complete side's items, which rely on that filtering, are filtered here instead.
        val complete = (if (primary.completionsAreIncomplete) inherited else own).toHashSet()
        return all.filter {
            it !in complete ||
                CompletionFilter.matches(CompletionFilter.typedFor(document, cursorOffset, it), it.filterText)
        }
    }

    /** [own] then [inherited], less the inherited items [own] already offers by kind and label. */
    protected fun merged(own: List<CompletionItem>, inherited: List<CompletionItem>): List<CompletionItem> {
        if (own.isEmpty()) return inherited
        val offered = own.mapTo(HashSet()) { it.kind to it.label }
        return own + inherited.filterNot { (it.kind to it.label) in offered }
    }

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> =
        primary.diagnostics(document) + fallback.diagnostics(document)

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String =
        primary.smartIndent(document, lineIndex, options).ifEmpty { fallback.smartIndent(document, lineIndex, options) }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? =
        primary.autoClose(document, offset, charTyped) ?: fallback.autoClose(document, offset, charTyped)

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? =
        primary.hoverDoc(document, offset) ?: fallback.hoverDoc(document, offset)

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String {
        val own = primary.format(document, options)
        return if (own != document.text) own else fallback.format(document, options)
    }

    override suspend fun formatRange(document: CodeDocument, range: IntRange, options: FormattingOptions): List<TextEdit> =
        primary.formatRange(document, range, options).ifEmpty { fallback.formatRange(document, range, options) }

    override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> =
        primary.codeActions(document, range) + fallback.codeActions(document, range)

    override suspend fun definition(document: CodeDocument, offset: Int): Location? =
        primary.definition(document, offset) ?: fallback.definition(document, offset)

    override suspend fun references(document: CodeDocument, offset: Int): List<Location> =
        (primary.references(document, offset) + fallback.references(document, offset)).distinct()

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? =
        primary.signatureHelp(document, offset) ?: fallback.signatureHelp(document, offset)

    override suspend fun documentSymbols(document: CodeDocument): List<DocumentSymbol> =
        primary.documentSymbols(document).ifEmpty { fallback.documentSymbols(document) }

    override suspend fun documentHighlights(document: CodeDocument, offset: Int): List<DocumentHighlight> =
        primary.documentHighlights(document, offset).ifEmpty { fallback.documentHighlights(document, offset) }

    override suspend fun selectionRanges(document: CodeDocument, offsets: List<Int>): List<SelectionRange> =
        primary.selectionRanges(document, offsets).ifEmpty { fallback.selectionRanges(document, offsets) }

    override suspend fun linkedEditingRanges(document: CodeDocument, offset: Int): LinkedEditingRanges? =
        primary.linkedEditingRanges(document, offset) ?: fallback.linkedEditingRanges(document, offset)

    override suspend fun documentLinks(document: CodeDocument): List<DocumentLink> =
        (primary.documentLinks(document) + fallback.documentLinks(document)).distinctBy { it.range }

    override suspend fun prepareRename(document: CodeDocument, offset: Int): IntRange? = renamer?.prepareRename(document, offset)

    override suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> =
        renamer?.rename(document, offset, newName).orEmpty()

    private val renamer: LanguageService?
        get() = when {
            primary.supportsRename -> primary
            fallback.supportsRename -> fallback
            else -> null
        }
}
