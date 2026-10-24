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
 * A single item in a completion list.
 *
 * @param label Display text shown in the dropdown.
 * @param kind Visual classification controlling the icon shown beside the label.
 * @param insertText Text inserted into the document when the item is accepted. May differ from
 *   [label]; with [isSnippet] it is a snippet, such as `<text>$0</text>` for the label `text`.
 * @param documentation Optional secondary text shown below the label in the dropdown.
 * @param filterText What the text typed so far is matched against when the editor filters the list
 *   itself (a service with [LanguageService.completionsAreIncomplete] false): its characters in
 *   order, ignoring case, the first at the start of [filterText] or of a word in it. Defaults to
 *   [label].
 * @param sortPriority Lower values sort earlier in the list (0 = highest priority). The editor
 *   keeps the service's order among items of the same priority.
 * @param replaceRange Exact document range [insertText] replaces when the item is accepted, for
 *   providers that know it (a language server's `textEdit`). When null the editor falls back to
 *   replacing the token before the cursor.
 * @param additionalEdits Further edits elsewhere in the same document that belong to this
 *   completion and are applied with it, in one undo batch — a language server's
 *   `additionalTextEdits`. Accepting an auto-import completion inserts the symbol *and* its import;
 *   applying only the first would leave the symbol unresolved. Offsets are into the document as it
 *   stands before the completion is accepted.
 * @param isSnippet [insertText] is in VS Code's snippet syntax, as with Monaco's
 *   `CompletionItemInsertTextRule.InsertAsSnippet`: `$1`, `${1:placeholder}` and `${1|one,two|}`
 *   are tab stops that Tab and Shift+Tab step through after the item is accepted, the first one
 *   selected, and `$0` is where the caret ends up. Its lines after the first are indented as the
 *   line it is accepted on, and a tab in their indentation becomes the editor's indent.
 * @param keepWhitespace With [isSnippet], insert the snippet's whitespace as written, without the
 *   indentation adjustment (Monaco's `KeepWhitespace`).
 * @param detail A short line about the item, such as a type or a signature, shown beside its label.
 * @param commitCharacters Characters that, typed while this item is selected in the list at the
 *   caret, accept it first and are then typed after it (LSP's and Monaco's `commitCharacters`).
 *   Not for snippets.
 */
data class CompletionItem(
    val label: String,
    val kind: CompletionKind,
    val insertText: String,
    val documentation: String? = null,
    val filterText: String = label,
    val sortPriority: Int = 0,
    val replaceRange: IntRange? = null,
    val additionalEdits: List<TextEdit> = emptyList(),
    val isSnippet: Boolean = false,
    val keepWhitespace: Boolean = false,
    val detail: String? = null,
    val commitCharacters: Set<Char> = emptySet(),
)

/**
 * What a [CompletionItem] completes, for its icon: the eight kinds the aardflex markup language
 * needs, then the rest of Monaco's `CompletionItemKind` (and LSP's) for code.
 */
enum class CompletionKind {
    /** XML element name (e.g. `text`, `layer`). */
    Element,

    /** XML attribute name (e.g. `color`, `align`). */
    Attribute,

    /** An attribute value, often an enum (e.g. `center`, `start`). */
    Value,

    /** A multi-token snippet (e.g. full `<module .../>` boilerplate). */
    Snippet,

    /** A data module type or declared module ID, or a code module or package. */
    Module,

    /** A module property name, or a property of an object. */
    Property,

    /** An expression transform name (e.g. `uppercase`, `round`). */
    Transform,

    /** A colour reference name (e.g. `primary` after `@`). */
    ColorRef,

    Method,
    Function,
    Constructor,
    Field,
    Variable,
    Class,
    Struct,
    Interface,
    Enum,
    EnumMember,
    Keyword,
    Text,

    /** A colour value, such as a hex code. */
    Color,
    File,
    Folder,
    Reference,
    Constant,
    Event,
    Operator,
    TypeParameter,
    Unit,
    User,
    Issue,
}
