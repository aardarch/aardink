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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Optional JSON loader so a host application can override any subset of [EditorStrings] keys
 * while falling back to [EditorStringsEn] (or another [EditorStrings]) for missing keys.
 */
object EditorStringsParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a flat JSON object of string keys (plus optional `_one`/`_other` suffix for plural
     * keys `referencesCount` and `diffUnchangedLines`) into an [EditorStrings], falling back to
     * [fallback] on missing keys. Returns `null` if [rawJson] is not a valid JSON object.
     */
    fun fromJson(rawJson: String, fallback: EditorStrings = EditorStringsEn): EditorStrings? {
        val obj = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            return null
        }
        return JsonEditorStrings(obj, fallback)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private class JsonEditorStrings(private val map: JsonObject, private val fallback: EditorStrings) : EditorStrings {
        private fun get(key: String, default: String): String = map.str(key) ?: default

        override val findPlaceholder: String get() = get("findPlaceholder", fallback.findPlaceholder)
        override val replacePlaceholder: String get() = get("replacePlaceholder", fallback.replacePlaceholder)
        override val replaceButton: String get() = get("replaceButton", fallback.replaceButton)
        override val replaceAllButton: String get() = get("replaceAllButton", fallback.replaceAllButton)
        override val findShowReplaceDescription: String get() = get("findShowReplaceDescription", fallback.findShowReplaceDescription)
        override val findHideReplaceDescription: String get() = get("findHideReplaceDescription", fallback.findHideReplaceDescription)
        override val findPreviousMatchDescription: String get() = get("findPreviousMatchDescription", fallback.findPreviousMatchDescription)
        override val findNextMatchDescription: String get() = get("findNextMatchDescription", fallback.findNextMatchDescription)
        override val findCloseDescription: String get() = get("findCloseDescription", fallback.findCloseDescription)
        override val findMatchCaseDescription: String get() = get("findMatchCaseDescription", fallback.findMatchCaseDescription)
        override val findWholeWordDescription: String get() = get("findWholeWordDescription", fallback.findWholeWordDescription)
        override val findRegexDescription: String get() = get("findRegexDescription", fallback.findRegexDescription)
        override val findInSelection: String get() = get("findInSelection", fallback.findInSelection)
        override val findPreserveCase: String get() = get("findPreserveCase", fallback.findPreserveCase)
        override val findSearchAllFiles: String get() = get("findSearchAllFiles", fallback.findSearchAllFiles)

        override val goToLineTitle: String get() = get("goToLineTitle", fallback.goToLineTitle)
        override val goToLineLabel: String get() = get("goToLineLabel", fallback.goToLineLabel)
        override val goToLineConfirm: String get() = get("goToLineConfirm", fallback.goToLineConfirm)
        override val renameTitle: String get() = get("renameTitle", fallback.renameTitle)
        override fun renamePrompt(currentName: String): String =
            map.str("renamePrompt")?.replace("{name}", currentName) ?: fallback.renamePrompt(currentName)
        override val renameNewNameLabel: String get() = get("renameNewNameLabel", fallback.renameNewNameLabel)
        override val renameConfirm: String get() = get("renameConfirm", fallback.renameConfirm)
        override val cancel: String get() = get("cancel", fallback.cancel)
        override val apply: String get() = get("apply", fallback.apply)

        override val cut: String get() = get("cut", fallback.cut)
        override val copy: String get() = get("copy", fallback.copy)
        override val paste: String get() = get("paste", fallback.paste)
        override val selectAll: String get() = get("selectAll", fallback.selectAll)
        override val selectAllMenu: String get() = get("selectAllMenu", fallback.selectAllMenu)
        override val info: String get() = get("info", fallback.info)
        override val format: String get() = get("format", fallback.format)
        override val formatDocument: String get() = get("formatDocument", fallback.formatDocument)
        override val formatSelection: String get() = get("formatSelection", fallback.formatSelection)
        override val goToDefinition: String get() = get("goToDefinition", fallback.goToDefinition)
        override val findReferences: String get() = get("findReferences", fallback.findReferences)
        override val color: String get() = get("color", fallback.color)
        override val pickColor: String get() = get("pickColor", fallback.pickColor)

        override val quickFix: String get() = get("quickFix", fallback.quickFix)
        override val quickFixEllipsis: String get() = get("quickFixEllipsis", fallback.quickFixEllipsis)
        override val quickFixChip: String get() = get("quickFixChip", fallback.quickFixChip)
        override val quickFixesAndActions: String get() = get("quickFixesAndActions", fallback.quickFixesAndActions)
        override val dismissDiagnostic: String get() = get("dismissDiagnostic", fallback.dismissDiagnostic)
        override fun diagnosticCounter(message: String, current: Int, total: Int): String = map.str("diagnosticCounter")
            ?.replace("{message}", message)
            ?.replace("{current}", current.toString())
            ?.replace("{total}", total.toString())
            ?: fallback.diagnosticCounter(message, current, total)

        override val toolbarUndo: String get() = get("toolbarUndo", fallback.toolbarUndo)
        override val toolbarRedo: String get() = get("toolbarRedo", fallback.toolbarRedo)
        override val toolbarMoveLeft: String get() = get("toolbarMoveLeft", fallback.toolbarMoveLeft)
        override val toolbarMoveRight: String get() = get("toolbarMoveRight", fallback.toolbarMoveRight)
        override fun toolbarInsertChar(char: String): String =
            map.str("toolbarInsertChar")?.replace("{char}", char) ?: fallback.toolbarInsertChar(char)

        override val goToSymbolPlaceholder: String get() = get("goToSymbolPlaceholder", fallback.goToSymbolPlaceholder)
        override val noSymbols: String get() = get("noSymbols", fallback.noSymbols)
        override val noMatchingSymbols: String get() = get("noMatchingSymbols", fallback.noMatchingSymbols)
        override fun symbolKindLabel(kind: SymbolKind): String = map.str("symbolKind.${kind.name}") ?: fallback.symbolKindLabel(kind)
        override fun referencesCount(count: Int): String {
            val tpl = if (count == 1) map.str("referencesCount_one") else map.str("referencesCount_other")
            return tpl?.replace("{count}", count.toString()) ?: fallback.referencesCount(count)
        }

        override val diffAcceptChange: String get() = get("diffAcceptChange", fallback.diffAcceptChange)
        override val diffRevertChange: String get() = get("diffRevertChange", fallback.diffRevertChange)
        override val diffNextChange: String get() = get("diffNextChange", fallback.diffNextChange)
        override val diffPreviousChange: String get() = get("diffPreviousChange", fallback.diffPreviousChange)
        override fun diffUnchangedLines(count: Int): String {
            val tpl = if (count == 1) map.str("diffUnchangedLines_one") else map.str("diffUnchangedLines_other")
            return tpl?.replace("{count}", count.toString()) ?: fallback.diffUnchangedLines(count)
        }
    }
}
