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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.intl.Locale
import com.aardarch.aardink.core.SymbolKind

/**
 * User-visible UI strings and accessibility descriptions for [CodeEditorLayout] and
 * [CodeDiffEditorLayout].
 *
 * Provide a custom implementation via [LocalEditorStrings] or select a built-in language via
 * [EditorOptions.languageTag].
 */
@Immutable
interface EditorStrings {
    // ── Find & Replace ──────────────────────────────────────────────────────
    val findPlaceholder: String
    val replacePlaceholder: String
    val replaceButton: String
    val replaceAllButton: String
    val findShowReplaceDescription: String
    val findHideReplaceDescription: String
    val findPreviousMatchDescription: String
    val findNextMatchDescription: String
    val findCloseDescription: String
    val findMatchCaseDescription: String
    val findWholeWordDescription: String
    val findRegexDescription: String
    val findInSelection: String
    val findPreserveCase: String
    val findSearchAllFiles: String

    // ── Dialogs ─────────────────────────────────────────────────────────────
    val goToLineTitle: String
    val goToLineLabel: String
    val goToLineConfirm: String
    val renameTitle: String
    fun renamePrompt(currentName: String): String
    val renameNewNameLabel: String
    val renameConfirm: String
    val cancel: String
    val apply: String

    // ── Selection & Context Menus ───────────────────────────────────────────
    val cut: String
    val copy: String
    val paste: String
    val selectAll: String
    val selectAllMenu: String
    val info: String
    val format: String
    val formatDocument: String
    val formatSelection: String
    val goToDefinition: String
    val findReferences: String
    val color: String
    val pickColor: String

    // ── Diagnostics & Code Actions ──────────────────────────────────────────
    val quickFix: String
    val quickFixEllipsis: String
    val quickFixChip: String
    val quickFixesAndActions: String
    val dismissDiagnostic: String
    fun diagnosticCounter(message: String, current: Int, total: Int): String

    // ── Keyboard Toolbar ────────────────────────────────────────────────────
    val toolbarUndo: String
    val toolbarRedo: String
    val toolbarMoveLeft: String
    val toolbarMoveRight: String
    fun toolbarInsertChar(char: String): String

    // ── Symbols & References ────────────────────────────────────────────────
    val goToSymbolPlaceholder: String
    val noSymbols: String
    val noMatchingSymbols: String
    fun symbolKindLabel(kind: SymbolKind): String
    fun referencesCount(count: Int): String

    // ── Diff Editor ─────────────────────────────────────────────────────────
    val diffAcceptChange: String
    val diffRevertChange: String
    val diffNextChange: String
    val diffPreviousChange: String
    fun diffUnchangedLines(count: Int): String
}

/**
 * Active [EditorStrings] for the editor subtree. Null means the editor resolves from
 * [EditorOptions.languageTag] or the system [Locale.current].
 */
val LocalEditorStrings: ProvidableCompositionLocal<EditorStrings?> = compositionLocalOf { null }

/**
 * Built-in and registered [EditorStrings] languages with BCP-47 fallback (`pt-BR` -> `pt` -> `en`).
 */
object EditorStringsRegistry {
    private val languages: Map<String, EditorStrings> = mapOf(
        "en" to EditorStringsEn,
        "pt" to EditorStringsPtBr,
        "pt-br" to EditorStringsPtBr,
    )

    /** Returns the [EditorStrings] matching [languageTag] using subtag fallback, defaulting to English. */
    fun resolve(languageTag: String?): EditorStrings {
        if (languageTag.isNullOrBlank()) return EditorStringsEn
        val normalized = languageTag.trim().replace('_', '-').lowercase()
        languages[normalized]?.let { return it }
        var prefix = normalized
        while ('-' in prefix) {
            prefix = prefix.substringBeforeLast('-')
            languages[prefix]?.let { return it }
        }
        return EditorStringsEn
    }

    /** All registered BCP-47 language tags (excluding the default `"en"` alias checks as needed). */
    fun registeredTags(): Set<String> = languages.keys

    /** All distinct [EditorStrings] implementations registered. */
    internal fun allImplementations(): Map<String, EditorStrings> = languages
}

@Composable
internal fun currentEditorStrings(languageTag: String? = null): EditorStrings =
    LocalEditorStrings.current ?: EditorStringsRegistry.resolve(languageTag ?: Locale.current.toLanguageTag())
