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

import androidx.compose.runtime.Immutable
import com.aardarch.aardink.core.text.DocumentChange

/**
 * One element of a document's structure, as LSP's `DocumentSymbol`: an element of an XML file, a
 * property of a JSON object, a class or a function. [LanguageService.documentSymbols] returns them
 * as a tree, and the editor uses them for Go to Symbol (Ctrl+Shift+O), the breadcrumbs row and
 * sticky scroll; [CodeEditorState.symbolPathAt] maps the caret to the symbols around it.
 *
 * @property name What the symbol is called, as the picker and the breadcrumbs show it.
 * @property kind What sort of symbol it is; it picks the icon.
 * @property range The whole symbol, from its first character to its last (inclusive), as document
 *   offsets: an element from `<` to its closing `>`.
 * @property selectionRange The part that names it, inside [range]: an element's tag name. Go to
 *   Symbol selects this.
 * @property detail More about it, shown beside the name: a function's signature, say.
 * @property children The symbols inside it, in document order.
 */
@Immutable
data class DocumentSymbol(
    val name: String,
    val kind: SymbolKind,
    val range: IntRange,
    val selectionRange: IntRange = range,
    val detail: String? = null,
    val children: List<DocumentSymbol> = emptyList(),
)

/** What sort of thing a [DocumentSymbol] is: LSP's `SymbolKind`, in its order. */
enum class SymbolKind {
    File,
    Module,
    Namespace,
    Package,
    Class,
    Method,
    Property,
    Field,
    Constructor,
    Enum,
    Interface,
    Function,
    Variable,
    Constant,
    String,
    Number,
    Boolean,
    Array,
    Object,
    Key,
    Null,
    EnumMember,
    Struct,
    Event,
    Operator,
    TypeParameter,
}

/**
 * The symbols in [symbols] (a tree, in document order) whose range holds [offset], outermost
 * first. The caret just after a symbol's last character still counts as in it, as it does for a
 * word.
 */
internal fun symbolPath(symbols: List<DocumentSymbol>, offset: Int): List<DocumentSymbol> {
    val path = ArrayList<DocumentSymbol>()
    var level = symbols
    while (true) {
        val inside = level.lastOrNull { offset >= it.range.first && offset <= it.range.last + 1 } ?: break
        path += inside
        level = inside.children
    }
    return path
}

/** Every symbol in [symbols], depth first in document order, with how deep it is (0 for the top). */
internal fun flattenSymbols(symbols: List<DocumentSymbol>): List<Pair<DocumentSymbol, Int>> {
    val out = ArrayList<Pair<DocumentSymbol, Int>>()
    fun walk(level: List<DocumentSymbol>, depth: Int) {
        for (symbol in level) {
            out += symbol to depth
            walk(symbol.children, depth + 1)
        }
    }
    walk(symbols, 0)
    return out
}

/**
 * The document's symbols, kept on their text between one answer of the language service and the
 * next, as diagnostics are: an edit moves each symbol's range, its name's and its children's. A
 * symbol whose text is deleted collapses where it was rather than vanishing; the next answer
 * drops it.
 */
internal class SymbolTracker :
    TrackedRanges<DocumentSymbol>(
        rangeOf = { it.range },
        withRange = { symbol, range -> symbol.copy(range = range) },
        keepsOneCharacter = false,
    ) {
    override fun moved(item: DocumentSymbol, change: DocumentChange): DocumentSymbol {
        val range = mapRange(item.range, change, keepsOneCharacter = false)
        val selection = mapRange(item.selectionRange, change, keepsOneCharacter = false)
        var childMoved = false
        val children = item.children.map { child ->
            moved(child, change).also { if (it !== child) childMoved = true }
        }
        if (range == item.range && selection == item.selectionRange && !childMoved) return item
        return item.copy(range = range, selectionRange = selection, children = if (childMoved) children else item.children)
    }
}
