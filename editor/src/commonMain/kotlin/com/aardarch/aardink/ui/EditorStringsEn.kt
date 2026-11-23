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

import com.aardarch.aardink.core.SymbolKind

/** Default English [EditorStrings] implementation. */
object EditorStringsEn : EditorStrings {
    override val findPlaceholder: String = "Find"
    override val replacePlaceholder: String = "Replace"
    override val replaceButton: String = "Replace"
    override val replaceAllButton: String = "All"
    override val findShowReplaceDescription: String = "Show replace"
    override val findHideReplaceDescription: String = "Hide replace"
    override val findPreviousMatchDescription: String = "Previous match"
    override val findNextMatchDescription: String = "Next match"
    override val findCloseDescription: String = "Close find and replace"
    override val findMatchCaseDescription: String = "Match case"
    override val findWholeWordDescription: String = "Match whole word"
    override val findRegexDescription: String = "Use regular expression"
    override val findInSelection: String = "In selection"
    override val findPreserveCase: String = "Preserve case"
    override val findSearchAllFiles: String = "Search all files"

    override val goToLineTitle: String = "Go to line"
    override val goToLineLabel: String = "Line number"
    override val goToLineConfirm: String = "Go"
    override val renameTitle: String = "Rename Symbol"
    override fun renamePrompt(currentName: String): String = "Enter new name for '$currentName':"
    override val renameNewNameLabel: String = "New name"
    override val renameConfirm: String = "Rename"
    override val cancel: String = "Cancel"
    override val apply: String = "Apply"

    override val cut: String = "Cut"
    override val copy: String = "Copy"
    override val paste: String = "Paste"
    override val selectAll: String = "Select all"
    override val selectAllMenu: String = "Select All"
    override val info: String = "Info"
    override val format: String = "Format"
    override val formatDocument: String = "Format Document"
    override val formatSelection: String = "Format Selection"
    override val goToDefinition: String = "Go to Definition"
    override val findReferences: String = "Find References"
    override val color: String = "Colour"
    override val pickColor: String = "Pick colour\u2026"

    override val quickFix: String = "Quick Fix"
    override val quickFixEllipsis: String = "Quick Fix\u2026"
    override val quickFixChip: String = "\uD83D\uDCA1 Quick Fix"
    override val quickFixesAndActions: String = "Quick Fixes & Actions"
    override val dismissDiagnostic: String = "Dismiss diagnostic"
    override fun diagnosticCounter(message: String, current: Int, total: Int): String = "$message  ($current of $total)"

    override val toolbarUndo: String = "Undo"
    override val toolbarRedo: String = "Redo"
    override val toolbarMoveLeft: String = "Move cursor left"
    override val toolbarMoveRight: String = "Move cursor right"
    override fun toolbarInsertChar(char: String): String = "Insert $char"

    override val goToSymbolPlaceholder: String = "Go to symbol"
    override val noSymbols: String = "No symbols"
    override val noMatchingSymbols: String = "No matching symbols"
    override fun symbolKindLabel(kind: SymbolKind): String = when (kind) {
        SymbolKind.Class -> "class"
        SymbolKind.Method -> "method"
        SymbolKind.Function -> "fun"
        SymbolKind.Constructor -> "ctor"
        SymbolKind.Property -> "prop"
        SymbolKind.Field -> "field"
        SymbolKind.Variable -> "var"
        SymbolKind.Constant -> "const"
        SymbolKind.Interface -> "interface"
        SymbolKind.Enum -> "enum"
        SymbolKind.EnumMember -> "member"
        SymbolKind.Object -> "object"
        SymbolKind.Array -> "array"
        SymbolKind.Key -> "key"
        SymbolKind.Module -> "module"
        SymbolKind.Namespace -> "namespace"
        SymbolKind.Package -> "package"
        else -> kind.name.lowercase()
    }
    override fun referencesCount(count: Int): String = if (count == 1) "1 reference" else "$count references"

    override val diffAcceptChange: String = "Accept change"
    override val diffRevertChange: String = "Revert change"
    override val diffNextChange: String = "Next change"
    override val diffPreviousChange: String = "Previous change"
    override fun diffUnchangedLines(count: Int): String = if (count == 1) "1 unchanged line" else "$count unchanged lines"
}
