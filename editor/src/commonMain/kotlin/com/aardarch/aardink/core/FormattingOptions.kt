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
 * How the editor indents, as a [LanguageService] needs to know it to indent a new line or format
 * a document: LSP's `FormattingOptions`, taken from the editor's own
 * [EditorOptions.tabSize][com.aardarch.aardink.ui.EditorOptions.tabSize] and
 * [EditorOptions.insertSpaces][com.aardarch.aardink.ui.EditorOptions.insertSpaces].
 *
 * Indentation is measured in columns, a tab reaching the next multiple of [tabSize]. A service
 * works out how many columns a line should be indented and turns that into whitespace with
 * [indentOf], so the same code produces spaces or tabs as the editor is set up.
 *
 * @property tabSize Columns a tab and one indentation level are wide, 1 to 16.
 * @property insertSpaces Indentation is spaces; without it, tabs (and spaces for a remainder that
 *   is not a whole tab).
 */
data class FormattingOptions(val tabSize: Int = 4, val insertSpaces: Boolean = true) {
    init {
        require(tabSize in 1..16) { "tabSize must be 1..16, was $tabSize" }
    }

    /** One indentation level: [tabSize] spaces, or a tab. */
    val indentUnit: String
        get() = if (insertSpaces) " ".repeat(tabSize) else "\t"

    /** Whitespace [columns] wide: spaces, or as many tabs as fit and spaces for the rest. */
    fun indentOf(columns: Int): String {
        if (columns <= 0) return ""
        if (insertSpaces) return " ".repeat(columns)
        return "\t".repeat(columns / tabSize) + " ".repeat(columns % tabSize)
    }

    /** How many columns the spaces and tabs at the start of [line] are wide. */
    fun indentWidth(line: CharSequence): Int {
        var width = 0
        for (c in line) {
            when (c) {
                ' ' -> width++
                '\t' -> width += tabSize - width % tabSize
                else -> return width
            }
        }
        return width
    }

    /**
     * The indentation of [line] changed by [levels] levels (negative to outdent, never below
     * none), as whitespace: what a line after or below [line] starts with.
     */
    fun indentAfter(line: CharSequence, levels: Int = 0): String = indentOf((indentWidth(line) + levels * tabSize).coerceAtLeast(0))
}
