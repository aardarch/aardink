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

/**
 * One occurrence of the symbol at the caret, as LSP's `DocumentHighlight`: what
 * [LanguageService.documentHighlights] returns, and what the editor highlights while the caret
 * rests on a symbol ([com.aardarch.aardink.ui.EditorOptions.occurrencesHighlight]).
 *
 * @property range The occurrence, as document offsets, first to last character (inclusive).
 * @property kind Whether it reads or writes the symbol, or neither.
 */
@Immutable
data class DocumentHighlight(val range: IntRange, val kind: DocumentHighlightKind = DocumentHighlightKind.Text)

/** LSP's `DocumentHighlightKind`. */
enum class DocumentHighlightKind {
    /** A textual occurrence. */
    Text,

    /** A read of the symbol. */
    Read,

    /** A write to it. */
    Write,
}

/**
 * A range the selection can grow to, and the larger one around it, as LSP's `SelectionRange`:
 * Shift+Alt+Right selects [range], then [parent]'s, and so on outwards.
 */
@Immutable
data class SelectionRange(val range: IntRange, val parent: SelectionRange? = null)

/**
 * Ranges that always hold the same text, as LSP's `LinkedEditingRanges`: an XML element's name in
 * its opening and its closing tag. With [com.aardarch.aardink.ui.EditorOptions.linkedEditing], an
 * edit inside one is made in all of them.
 *
 * @property ranges The ranges, as document offsets, first to last character (inclusive); empty
 *   ranges (`at until at`) for a name not typed yet.
 */
@Immutable
data class LinkedEditingRanges(val ranges: List<IntRange>)

/**
 * A link in the document, as LSP's `DocumentLink`: a URL, or a reference the language service
 * resolves (an `<include component="…">`). Ctrl+click (Cmd+click on macOS) opens it through
 * [com.aardarch.aardink.ui.CodeEditorLayout]'s `onOpenLink`.
 *
 * @property range The link's text, first to last character (inclusive).
 * @property target What it opens: a URL, or whatever the host's `onOpenLink` understands.
 */
@Immutable
data class DocumentLink(val range: IntRange, val target: String)

/** Finds plain URLs in text, for links no language service reports. */
internal object UrlDetector {
    private val URL = Regex("""https?://[^\s"'<>()\[\]{}`]+""")

    /** The URL in [line] that holds [column] (or ends just before it), as `start until end` in the line; null for none. */
    fun urlAt(line: CharSequence, column: Int): IntRange? {
        for (match in URL.findAll(line)) {
            var end = match.range.last + 1
            while (end > match.range.first && line[end - 1] in TRAILING) end--
            if (column >= match.range.first && column <= end) return match.range.first until end
        }
        return null
    }

    private const val TRAILING = ".,;:!?"
}
