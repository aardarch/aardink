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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorStringsTest {

    private fun allStrings(s: EditorStrings): Map<String, String> = mapOf(
        "findPlaceholder" to s.findPlaceholder,
        "replacePlaceholder" to s.replacePlaceholder,
        "replaceButton" to s.replaceButton,
        "replaceAllButton" to s.replaceAllButton,
        "findShowReplaceDescription" to s.findShowReplaceDescription,
        "findHideReplaceDescription" to s.findHideReplaceDescription,
        "findPreviousMatchDescription" to s.findPreviousMatchDescription,
        "findNextMatchDescription" to s.findNextMatchDescription,
        "findCloseDescription" to s.findCloseDescription,
        "findMatchCaseDescription" to s.findMatchCaseDescription,
        "findWholeWordDescription" to s.findWholeWordDescription,
        "findRegexDescription" to s.findRegexDescription,
        "findInSelection" to s.findInSelection,
        "findPreserveCase" to s.findPreserveCase,
        "findSearchAllFiles" to s.findSearchAllFiles,
        "goToLineTitle" to s.goToLineTitle,
        "goToLineLabel" to s.goToLineLabel,
        "goToLineConfirm" to s.goToLineConfirm,
        "renameTitle" to s.renameTitle,
        "renameNewNameLabel" to s.renameNewNameLabel,
        "renameConfirm" to s.renameConfirm,
        "cancel" to s.cancel,
        "apply" to s.apply,
        "cut" to s.cut,
        "copy" to s.copy,
        "paste" to s.paste,
        "selectAll" to s.selectAll,
        "selectAllMenu" to s.selectAllMenu,
        "info" to s.info,
        "format" to s.format,
        "formatDocument" to s.formatDocument,
        "formatSelection" to s.formatSelection,
        "goToDefinition" to s.goToDefinition,
        "findReferences" to s.findReferences,
        "color" to s.color,
        "pickColor" to s.pickColor,
        "quickFix" to s.quickFix,
        "quickFixEllipsis" to s.quickFixEllipsis,
        "quickFixChip" to s.quickFixChip,
        "quickFixesAndActions" to s.quickFixesAndActions,
        "dismissDiagnostic" to s.dismissDiagnostic,
        "toolbarUndo" to s.toolbarUndo,
        "toolbarRedo" to s.toolbarRedo,
        "toolbarMoveLeft" to s.toolbarMoveLeft,
        "toolbarMoveRight" to s.toolbarMoveRight,
        "goToSymbolPlaceholder" to s.goToSymbolPlaceholder,
        "noSymbols" to s.noSymbols,
        "noMatchingSymbols" to s.noMatchingSymbols,
        "diffAcceptChange" to s.diffAcceptChange,
        "diffRevertChange" to s.diffRevertChange,
        "diffNextChange" to s.diffNextChange,
        "diffPreviousChange" to s.diffPreviousChange,
    )

    @Test
    fun `all registered languages provide complete non-blank translations and matching placeholders`() {
        val englishKeys = allStrings(EditorStringsEn).keys
        for ((tag, impl) in EditorStringsRegistry.allImplementations()) {
            val map = allStrings(impl)
            assertEquals(englishKeys, map.keys, "Key mismatch for '$tag'")
            for ((key, value) in map) {
                assertTrue(value.isNotBlank(), "Blank translation for '$key' in '$tag'")
            }

            // Parameterized strings must preserve their arguments
            val rename = impl.renamePrompt("fooBar")
            assertTrue("fooBar" in rename, "renamePrompt missing symbol name in '$tag': $rename")

            val diag = impl.diagnosticCounter("Syntax error", 2, 5)
            assertTrue("Syntax error" in diag && "2" in diag && "5" in diag, "diagnosticCounter missing placeholders in '$tag': $diag")

            val insert = impl.toolbarInsertChar("{")
            assertTrue("{" in insert, "toolbarInsertChar missing char in '$tag': $insert")

            // Plurals (0, 1, 2)
            assertTrue("1" in impl.referencesCount(1), "referencesCount(1) missing '1' in '$tag'")
            assertTrue("2" in impl.referencesCount(2), "referencesCount(2) missing '2' in '$tag'")
            assertNotEquals(impl.referencesCount(1), impl.referencesCount(2), "referencesCount singular/plural identical in '$tag'")

            assertTrue("1" in impl.diffUnchangedLines(1), "diffUnchangedLines(1) missing '1' in '$tag'")
            assertTrue("3" in impl.diffUnchangedLines(3), "diffUnchangedLines(3) missing '3' in '$tag'")
            assertNotEquals(
                impl.diffUnchangedLines(1),
                impl.diffUnchangedLines(3),
                "diffUnchangedLines singular/plural identical in '$tag'",
            )

            for (kind in SymbolKind.entries) {
                assertTrue(impl.symbolKindLabel(kind).isNotBlank(), "Blank symbolKindLabel($kind) in '$tag'")
            }
        }
    }

    @Test
    fun `language resolution falls back through BCP-47 subtags to English`() {
        assertSame(EditorStringsPtBr, EditorStringsRegistry.resolve("pt-BR"))
        assertSame(EditorStringsPtBr, EditorStringsRegistry.resolve("pt_BR"))
        assertSame(EditorStringsPtBr, EditorStringsRegistry.resolve("pt-PT"))
        assertSame(EditorStringsPtBr, EditorStringsRegistry.resolve("pt"))
        assertSame(EditorStringsEn, EditorStringsRegistry.resolve("en-US"))
        assertSame(EditorStringsEn, EditorStringsRegistry.resolve("fr-FR"))
        assertSame(EditorStringsEn, EditorStringsRegistry.resolve(null))
        assertSame(EditorStringsEn, EditorStringsRegistry.resolve(""))
    }

    @Test
    fun `json parser overrides given keys and falls back for missing keys`() {
        val json = """
            {
              "findPlaceholder": "Search",
              "renamePrompt": "Rename {name} to:",
              "referencesCount_one": "1 ref",
              "referencesCount_other": "{count} refs"
            }
        """.trimIndent()
        val parsed = EditorStringsParser.fromJson(json, fallback = EditorStringsEn)
        assertNotNull(parsed)
        assertEquals("Search", parsed.findPlaceholder)
        assertEquals(EditorStringsEn.replacePlaceholder, parsed.replacePlaceholder)
        assertEquals("Rename myVar to:", parsed.renamePrompt("myVar"))
        assertEquals("1 ref", parsed.referencesCount(1))
        assertEquals("4 refs", parsed.referencesCount(4))
    }

    @Test
    fun `json parser returns null on invalid json`() {
        assertNull(EditorStringsParser.fromJson("{not valid json"))
    }
}
