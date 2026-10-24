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
package com.aardarch.aardink.web

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.ui.RenderWhitespace
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Monaco-shaped surface around the editor: selections, undo, format, the change events, tokenize. */
class WebEditorApiTest {

    private val mounted = mutableListOf<AardinkEditorHandle>()
    private val containers = mutableListOf<HTMLElement>()

    @AfterTest
    fun tearDown() {
        mounted.forEach(AardinkWeb::dispose)
        containers.forEach { it.remove() }
    }

    private fun mount(text: String, options: WebEditorOptions = WebEditorOptions()): AardinkEditorHandle {
        val div = document.createElement("div") as HTMLElement
        div.id = "aardink-api-test-${containers.size}"
        div.style.width = "600px"
        div.style.height = "400px"
        document.body!!.appendChild(div)
        containers += div
        return AardinkWeb.mount(div.id, text, options).also { mounted += it }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(5_000) {
            while (!condition()) delay(16)
        }
    }

    @Test
    fun `selections go out and come back in Monaco's shape`() {
        val handle = mount("one\ntwo\nthree")
        val selections = listOf(WebSelection(2, 4, 2, 1), WebSelection(1, 1, 1, 1))
        AardinkWeb.setSelections(handle, selections)
        assertEquals(listOf(TextRange(7, 4), TextRange(0)), handle.state.value.selections)
        assertEquals(selections, AardinkWeb.getSelections(handle))
        AardinkWeb.setSelectionsJson(handle, AardinkWeb.getSelectionsJson(handle))
        assertEquals(selections, AardinkWeb.getSelections(handle))
    }

    @Test
    fun `undo state, undo stops and the alternative version id`() {
        val handle = mount("")
        val state = handle.state.value
        val saved = AardinkWeb.getAlternativeVersionId(handle)
        assertFalse(AardinkWeb.canUndo(handle))
        state.applyTextEdits(listOf(TextEdit(IntRange(0, -1), "a")))
        AardinkWeb.pushUndoStop(handle)
        state.applyTextEdits(listOf(TextEdit(IntRange(1, 0), "b")))
        assertTrue(AardinkWeb.canUndo(handle))
        assertNotEquals(saved, AardinkWeb.getAlternativeVersionId(handle))
        AardinkWeb.undo(handle)
        AardinkWeb.undo(handle)
        assertEquals("", AardinkWeb.getValue(handle))
        assertEquals(saved, AardinkWeb.getAlternativeVersionId(handle), "back where it was saved")
        assertTrue(AardinkWeb.canRedo(handle))
    }

    @Test
    fun `the detailed change listener says what each change was`() = runTest {
        val handle = mount("x")
        val seen = mutableListOf<Pair<Int, String>>()
        AardinkWeb.onContentChange(handle) { _, version, kind, _ -> seen += version to kind }
        handle.state.value.applyTextEdits(listOf(TextEdit(IntRange(1, 0), "y")))
        awaitUntil { seen.size == 1 }
        AardinkWeb.undo(handle)
        awaitUntil { seen.size == 2 }
        AardinkWeb.setValue(handle, "new")
        awaitUntil { seen.size == 3 }
        assertEquals(listOf("edit", "undo", "flush"), seen.map { it.second })
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first > a.first }, "versions go up: $seen")
    }

    @Test
    fun `the content listener gets the changes in Monaco's shape`() = runTest {
        val handle = mount("ab\ncd")
        val reports = mutableListOf<String>()
        AardinkWeb.onContentChange(handle) { _, _, _, changes -> reports += changes }
        handle.state.value.applyEdit(1, 3, "X", TextRange(2))
        awaitUntil { reports.size == 1 }
        val changes = Json.parseToJsonElement(reports.single()).jsonArray.map { it.jsonObject }
        val delete = changes.first { it["rangeLength"]!!.jsonPrimitive.int == 3 }
        val range = delete["range"]!!.jsonObject
        assertEquals(
            listOf(1, 2, 2, 2),
            listOf("startLineNumber", "startColumn", "endLineNumber", "endColumn").map {
                range[it]!!.jsonPrimitive.int
            },
        )
        assertEquals(1, delete["rangeOffset"]!!.jsonPrimitive.int)
        assertEquals("X", changes.last()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `selection changes are reported with their reason, and the position comes and goes`() {
        val handle = mount("one\ntwo")
        val events = mutableListOf<String>()
        AardinkWeb.onSelectionChange(handle) { events += it }
        AardinkWeb.setPosition(handle, 2, 3)
        assertEquals(WebPosition(2, 3), AardinkWeb.getPosition(handle))
        assertEquals(TextRange(6), handle.state.value.selection)
        handle.state.value.applyEdit(6, 0, "!", TextRange(7))
        val reasons = events.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("api", "edit"), reasons.map { it["reason"]!!.jsonPrimitive.content })
        assertEquals(listOf("api", "model"), reasons.map { it["source"]!!.jsonPrimitive.content })
        val primary = reasons.last()["selections"]!!.jsonArray.first().jsonObject
        assertEquals(4, primary["positionColumn"]!!.jsonPrimitive.int)
        assertEquals("{\"lineNumber\":2,\"column\":4}", AardinkWeb.getPositionJson(handle))
    }

    @Test
    fun `replaceValue is an edit that undo takes back, history and all`() = runTest {
        val handle = mount("one\ntwo\n")
        val seen = mutableListOf<Pair<Int, String>>()
        AardinkWeb.onContentChange(handle) { _, version, kind, _ -> seen += version to kind }
        handle.state.value.applyTextEdits(listOf(TextEdit(IntRange(8, 7), "three\n")))
        awaitUntil { seen.size == 1 }
        val typed = AardinkWeb.getAlternativeVersionId(handle)
        AardinkWeb.setSelections(handle, listOf(WebSelection(2, 2, 2, 2)))

        assertTrue(AardinkWeb.replaceValue(handle, "ONE\ntwo\nthree\n"))
        awaitUntil { seen.size == 2 }
        assertEquals("edit", seen[1].second, "not a flush: the history is kept")
        assertTrue(seen[1].first > seen[0].first, "the version goes up: $seen")
        assertEquals(listOf(WebSelection(2, 2, 2, 2)), AardinkWeb.getSelections(handle), "the caret in unchanged text stays")
        assertNotEquals(typed, AardinkWeb.getAlternativeVersionId(handle))

        assertTrue(AardinkWeb.undo(handle))
        assertEquals("one\ntwo\nthree\n", AardinkWeb.getValue(handle))
        assertEquals(typed, AardinkWeb.getAlternativeVersionId(handle))
        assertTrue(AardinkWeb.undo(handle), "the edit before it is still in the history")
        assertEquals("one\ntwo\n", AardinkWeb.getValue(handle))
        assertFalse(AardinkWeb.replaceValue(handle, "one\ntwo\n"))
    }

    @Test
    fun `executeEdits takes Monaco ranges and end selections, not in a read-only editor`() {
        val handle = mount("foo bar\nbaz")
        val edits = listOf(
            WebEdit(WebRange(1, 5, 1, 8), "qux"),
            // Past the end of the document: the end, as Monaco validates it.
            WebEdit(WebRange(9, 1, 9, 1), "!"),
            WebEdit(WebRange(2, 1, 2, 2), null),
        )
        assertTrue(AardinkWeb.executeEdits(handle, edits, listOf(WebSelection(1, 5, 1, 8))))
        assertEquals("foo qux\naz!", AardinkWeb.getValue(handle))
        assertEquals(listOf(WebSelection(1, 5, 1, 8)), AardinkWeb.getSelections(handle))
        AardinkWeb.undo(handle)
        assertEquals("foo bar\nbaz", AardinkWeb.getValue(handle), "one undo step")

        AardinkWeb.patchOptions(handle, """{ "readOnly": true }""")
        assertFalse(AardinkWeb.executeEdits(handle, edits))
        assertEquals("foo bar\nbaz", AardinkWeb.getValue(handle))
    }

    @Test
    fun `executeEdits places the end selections in the text after the edits, as Monaco does`() {
        val handle = mount("b\nd")
        val edits = listOf(
            WebEdit(WebRange(1, 1, 1, 1), "a\n"),
            WebEdit(WebRange(2, 1, 2, 1), "c\n"),
        )
        // Line 4 exists only after the edits: "a\nb\nc\nd".
        assertTrue(AardinkWeb.executeEdits(handle, edits, listOf(WebSelection(4, 1, 4, 2))))
        assertEquals("a\nb\nc\nd", AardinkWeb.getValue(handle))
        assertEquals(listOf(WebSelection(4, 1, 4, 2)), AardinkWeb.getSelections(handle))

        // Overlapping edits are still refused, with end selections too.
        val overlapping = listOf(WebEdit(WebRange(1, 1, 1, 2), "x"), WebEdit(WebRange(1, 1, 1, 2), "y"))
        assertFailsWith<IllegalArgumentException> {
            AardinkWeb.executeEdits(handle, overlapping, listOf(WebSelection(1, 1, 1, 1)))
        }
        assertEquals("a\nb\nc\nd", AardinkWeb.getValue(handle))
    }

    @Test
    fun `format changes only what differs, as one undo step`() = runTest {
        val handle = mount("{\"a\":1,\n\"b\":[1,2]}", WebEditorOptions(language = "json"))
        val done = CompletableDeferred<Boolean>()
        AardinkWeb.format(handle) { done.complete(it) }
        assertTrue(withContext(Dispatchers.Main) { withTimeout(5_000) { done.await() } })
        val formatted = AardinkWeb.getValue(handle)
        assertTrue(formatted.contains("\n    \"a\": 1"), formatted)
        AardinkWeb.undo(handle)
        assertEquals("{\"a\":1,\n\"b\":[1,2]}", AardinkWeb.getValue(handle))
    }

    @Test
    fun `the smallest change between two texts`() {
        assertEquals(TextEdit(2 until 3, "XY"), AardinkWeb.changeBetween("abcde", "abXYde"))
        assertEquals(null, AardinkWeb.changeBetween("same", "same"))
    }

    @Test
    fun `new options reach the editor`() {
        val handle = mount("x", WebEditorOptions(tabSize = 2, insertSpaces = true))
        assertEquals(WebEditorOptions(tabSize = 2), AardinkWeb.currentOptions(handle))
        AardinkWeb.patchOptions(handle, """{ "insertSpaces": false, "highlightCurrentLine": false, "bracketPairColorization": false }""")
        val options = AardinkWeb.currentOptions(handle)
        assertFalse(options.insertSpaces)
        assertFalse(options.highlightCurrentLine)
        assertFalse(options.bracketPairColorization)
    }

    @Test
    fun `renderWhitespace takes Monaco's values, selection by default`() {
        val handle = mount("x", WebEditorOptions())
        assertEquals("selection", AardinkWeb.currentOptions(handle).renderWhitespace)
        AardinkWeb.patchOptions(handle, """{ "renderWhitespace": "all" }""")
        assertEquals("all", AardinkWeb.currentOptions(handle).renderWhitespace)
        assertEquals(RenderWhitespace.Boundary, renderWhitespaceOf("boundary"))
        assertEquals(RenderWhitespace.Selection, renderWhitespaceOf("bogus"))
    }

    @Test
    fun `tokenize gives a grammar's tokens per line, gaps as the empty type`() {
        AardinkWeb.registerLanguage("""{ "id": "tokenize-toy", "grammar": { "tokenizer": { "root": [ ["\\d+", "number"] ] } } }""")
        val lines = Json.parseToJsonElement(AardinkWeb.tokenize("tokenize-toy", "a 12\n34")).jsonArray
        assertEquals(2, lines.size)
        val first = lines[0].jsonArray.map { it.jsonObject["offset"]!!.jsonPrimitive.int to it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf(0 to "", 2 to "number"), first)
        val second = lines[1].jsonArray.map { it.jsonObject["offset"]!!.jsonPrimitive.int to it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf(0 to "number"), second)
    }

    @Test
    fun `tokenize gives a grammar's own names and Monaco's names for the built-in types`() {
        AardinkWeb.registerLanguage(
            """{ "id": "tokenize-names", "grammar": { "tokenPostfix": ".toy", "tokenizer": { "root": [ ["#.*", "comment"], ["'[^']*'", "string.quoted"], ["\\w+", "word"] ] } } }""",
        )
        val types = { languageId: String, text: String ->
            Json.parseToJsonElement(AardinkWeb.tokenize(languageId, text)).jsonArray.single().jsonArray.map {
                it.jsonObject["type"]!!.jsonPrimitive.content
            }
        }
        assertEquals(listOf("word.toy", "", "string.quoted.toy", "", "comment.toy"), types("tokenize-names", "go 'x' # note"))
        assertEquals("keyword", types("kotlin", "val x = 1").first())
        assertEquals(listOf("keyword.flow", "", "string", "string.escape", "string"), types("kotlin", "return \"a\\nb\""))
    }
}
