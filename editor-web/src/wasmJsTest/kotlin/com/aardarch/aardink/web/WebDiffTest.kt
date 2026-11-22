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
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.aardarch.aardink.web

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.TextDiff
import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web diff editor: its theme keys, its Monaco-shaped changes, and what a host can do with it through the exports. */
class WebDiffTest {

    private val container = (document.createElement("div") as HTMLElement).also {
        it.id = "aardink-diff-test"
        it.style.width = "1000px"
        it.style.height = "300px"
        document.body!!.appendChild(it)
    }

    @AfterTest
    fun tearDown() {
        container.remove()
    }

    private fun lineChanges(original: String, modified: String): JsonArray {
        val result = TextDiff.compute(original, modified)
        return Json.parseToJsonElement(lineChangesJson(CodeDocument(original), CodeDocument(modified), result.changes)).jsonArray
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    private fun JsonObject.intOf(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.lines() =
        listOf("originalStartLineNumber", "originalEndLineNumber", "modifiedStartLineNumber", "modifiedEndLineNumber").map { intOf(it) }

    @Test
    fun `a theme's diffEditor and editorGutter keys reach the chrome colours, from VS Code theme JSON and from Monaco's`() {
        AardinkWeb.registerTheme(
            "toy-diff-vscode",
            """{ "type": "dark", "colors": {
                "diffEditor.insertedLineBackground": "#00ff0033", "diffEditor.removedLineBackground": "#ff000033",
                "diffEditor.insertedTextBackground": "#00ff0066", "diffEditor.removedTextBackground": "#ff000066",
                "diffEditor.diagonalFill": "#80808040", "editorGutter.addedBackground": "#00aa00",
                "editorGutter.modifiedBackground": "#0000aa", "editorGutter.deletedBackground": "#aa0000" } }""",
        )
        val chrome = AardinkWeb.registeredThemes.getValue("toy-diff-vscode").chromeColors
        assertEquals(Color(0xFF00FF00).copy(alpha = chrome.diffInsertedLine.alpha), chrome.diffInsertedLine)
        assertEquals(0x33 / 255f, chrome.diffInsertedLine.alpha, 0.01f)
        assertEquals(Color(0xFFFF0000).copy(alpha = chrome.diffRemovedLine.alpha), chrome.diffRemovedLine)
        assertEquals(0x66 / 255f, chrome.diffInsertedText.alpha, 0.01f)
        assertEquals(0x66 / 255f, chrome.diffRemovedText.alpha, 0.01f)
        assertEquals(0x40 / 255f, chrome.diffFiller.alpha, 0.01f)
        assertEquals(Color(0xFF00AA00), chrome.gutterAdded)
        assertEquals(Color(0xFF0000AA), chrome.gutterModified)
        assertEquals(Color(0xFFAA0000), chrome.gutterDeleted)

        // Monaco's defineTheme data passes through the same parser.
        AardinkWeb.registerTheme(
            "toy-diff-monaco",
            """{ "base": "vs-dark", "inherit": true, "rules": [], "colors": {
                "diffEditor.insertedLineBackground": "#10ff1020", "editorGutter.deletedBackground": "#ee1111" } }""",
        )
        val monaco = AardinkWeb.registeredThemes.getValue("toy-diff-monaco").chromeColors
        assertEquals(0x20 / 255f, monaco.diffInsertedLine.alpha, 0.01f)
        assertEquals(Color(0xFFEE1111), monaco.gutterDeleted)
        // What it leaves out follows the editor's own colours.
        assertEquals(Color.Unspecified, monaco.diffRemovedLine)
    }

    @Test
    fun `a changed line is its lines and characters, 1-based`() {
        val changes = lineChanges("a\nb\nc", "a\nB\nc")
        assertEquals(1, changes.size)
        val change = changes[0].jsonObject
        assertEquals(listOf(2, 2, 2, 2), change.lines())
        val chars = change.getValue("charChanges").jsonArray
        assertEquals(1, chars.size)
        val char = chars[0].jsonObject
        assertEquals(
            listOf(2, 1, 2, 2, 2, 1, 2, 2),
            listOf(
                "originalStartLineNumber",
                "originalStartColumn",
                "originalEndLineNumber",
                "originalEndColumn",
                "modifiedStartLineNumber",
                "modifiedStartColumn",
                "modifiedEndLineNumber",
                "modifiedEndColumn",
            ).map { char.intOf(it) },
        )
    }

    @Test
    fun `an inserted block has no original lines: it ends at 0 and starts at the line before`() {
        val change = lineChanges("a\nc", "a\nb\nc")[0].jsonObject
        assertEquals(listOf(1, 0, 2, 2), change.lines())
        assertFalse("charChanges" in change, "a change with an empty side has no character changes")
        // At the top, the line before is 0.
        val top = lineChanges("a", "x\ny\na")[0].jsonObject
        assertEquals(listOf(0, 0, 1, 2), top.lines())
    }

    @Test
    fun `a deleted block has no modified lines`() {
        val change = lineChanges("x\na\nb", "a\nb")[0].jsonObject
        assertEquals(listOf(1, 1, 0, 0), change.lines())
        val last = lineChanges("a\nb\nc", "a\nb")[0].jsonObject
        assertEquals(listOf(3, 3, 2, 0), last.lines())
    }

    @Test
    fun `identical texts have no changes`() {
        assertEquals(0, lineChanges("a\nb", "a\nb").size)
    }

    @Test
    fun `the diff editor options are read in Monaco's names, with Monaco's defaults`() {
        val defaults = lenient.decodeFromString(WebDiffOptions.serializer(), "{}")
        assertEquals(WebDiffOptions(), defaults)
        assertTrue(defaults.renderSideBySide)
        assertEquals(900, defaults.renderSideBySideInlineBreakpoint)
        assertFalse(defaults.hideUnchangedRegions.enabled)
        assertEquals(3, defaults.hideUnchangedRegions.contextLineCount)
        val parsed = lenient.decodeFromString(
            WebDiffOptions.serializer(),
            """{ "renderSideBySide": false, "maxComputationTime": 0, "originalEditable": true, "language": "kotlin",
                "hideUnchangedRegions": { "enabled": true, "contextLineCount": 5 } }""",
        )
        assertFalse(parsed.renderSideBySide)
        assertEquals(0L, parsed.maxComputationTime)
        assertTrue(parsed.originalEditable)
        assertEquals(WebHideUnchangedRegions(true, 5), parsed.hideUnchangedRegions)
    }

    @Test
    fun `a diff editor is two editors, lands its changes, moves through them and reverts one`() = runTest {
        val created = Json.parseToJsonElement(
            aardinkCreateDiff(
                container.id,
                "one\ntwo\nthree\nfour\nfive",
                "one\n2\nthree\nfour\nfive\nsix",
                """{ "language": "plaintext" }""",
            ),
        ).jsonArray.map { it.jsonPrimitive.int }
        val (diff, original, modified) = created
        try {
            assertEquals("one\ntwo\nthree\nfour\nfive", aardinkGetValue(original))
            assertEquals("one\n2\nthree\nfour\nfive\nsix", aardinkGetValue(modified))
            assertEquals("[]", aardinkDiffLineChanges(diff), "nothing has been worked out yet")

            // The original is read-only unless originalEditable, for the API as for the keys.
            val edit = """[{ "range": { "startLineNumber": 1, "startColumn": 1, "endLineNumber": 1, "endColumn": 1 }, "text": "x" }]"""
            assertEquals("read-only", aardinkExecuteEdits(original, edit, "null"))
            aardinkDiffUpdateOptions(diff, """{ "originalEditable": true }""")
            assertEquals("", aardinkExecuteEdits(original, edit, "null"))
            assertTrue(aardinkUndo(original))
            aardinkDiffUpdateOptions(diff, """{ "originalEditable": false }""")
            assertEquals("one\ntwo\nthree\nfour\nfive", aardinkGetValue(original))

            var updates = 0
            aardinkOnDidUpdateDiff(diff) { updates++ }
            withContext(Dispatchers.Main) { withTimeout(10_000) { while (updates == 0) delay(16) } }
            val changes = Json.parseToJsonElement(aardinkDiffLineChanges(diff)).jsonArray
            assertEquals(2, changes.size)
            assertEquals(2, changes[0].jsonObject.intOf("modifiedStartLineNumber"))
            assertEquals(6, changes[1].jsonObject.intOf("modifiedStartLineNumber"))
            assertNull(changes[1].jsonObject["charChanges"])

            // Monaco's ids and the workbench's, on either editor; the caret moves to the change.
            assertTrue(aardinkCanTrigger(modified, "editor.action.accessibleDiffViewer.next"))
            assertTrue(aardinkTrigger(modified, "editor.action.diffReview.next", "null"))
            assertEquals(2, Json.parseToJsonElement(aardinkGetPosition(modified)).jsonObject.intOf("lineNumber"))
            assertTrue(aardinkDiffGoTo(diff, true))
            assertEquals(6, Json.parseToJsonElement(aardinkGetPosition(modified)).jsonObject.intOf("lineNumber"))
            assertTrue(aardinkDiffGoTo(diff, false))
            assertEquals(2, Json.parseToJsonElement(aardinkGetPosition(modified)).jsonObject.intOf("lineNumber"))
            withContext(Dispatchers.Main) {
                withTimeout(5_000) { while (!aardinkCanTrigger(original, "workbench.action.compareEditor.nextChange")) delay(16) }
            }
            assertTrue(aardinkTrigger(original, "workbench.action.compareEditor.nextChange", "null"))
            assertEquals("Go to Previous Difference", aardinkActionLabel(modified, "editor.action.diffReview.prev"))

            // Each side tells its cursor listener where its caret is, 1-based, as a single editor does.
            var modifiedCursor = 0 to 0
            var originalCursor = 0 to 0
            aardinkOnCursorChange(modified) { line, column -> modifiedCursor = line to column }
            aardinkOnCursorChange(original) { line, column -> originalCursor = line to column }
            aardinkSetPosition(modified, 3, 2)
            aardinkSetPosition(original, 4, 3)
            withContext(Dispatchers.Main) {
                withTimeout(5_000) { while (modifiedCursor != (3 to 2) || originalCursor != (4 to 3)) delay(16) }
            }

            // Reverting puts the original's lines back, as one undo step on the modified side.
            assertTrue(aardinkDiffRevert(diff, 0))
            assertEquals("one\ntwo\nthree\nfour\nfive\nsix", aardinkGetValue(modified))
            assertTrue(aardinkUndo(modified))
            assertEquals("one\n2\nthree\nfour\nfive\nsix", aardinkGetValue(modified))
            assertFalse(aardinkDiffRevert(diff, 9), "there is no such change")

            // New text lands as a new diff.
            val before = updates
            aardinkDiffSetValues(diff, null, "one\ntwo\nthree\nfour\nfive")
            withContext(Dispatchers.Main) { withTimeout(10_000) { while (updates == before) delay(16) } }
            assertEquals("[]", aardinkDiffLineChanges(diff))

            aardinkDiffUpdateOptions(diff, """{ "renderSideBySide": false, "hideUnchangedRegions": { "enabled": true } }""")
            aardinkDiffUpdateOptions(diff, """{ "hideUnchangedRegions": { "contextLineCount": 1 } }""")
        } finally {
            aardinkDiffDispose(diff)
            aardinkDiffDispose(diff)
        }
        // Both sides go with it.
        assertFailsWithError { aardinkGetValue(original) }
        assertFailsWithError { aardinkDiffLineChanges(diff) }
    }

    @Test
    fun `the diff editor's trigger ids run nothing in an editor that is not one`() {
        val id = aardinkCreate(container.id, "one", "{}")
        try {
            assertFalse(aardinkTrigger(id, "editor.action.accessibleDiffViewer.next", "null"))
            assertFalse(aardinkCanTrigger(id, "workbench.action.compareEditor.previousChange"))
        } finally {
            aardinkDispose(id)
        }
    }

    private fun assertFailsWithError(block: () -> Unit) {
        val failed = runCatching(block).isFailure
        assertTrue(failed, "expected an error for an editor that is disposed")
    }
}
