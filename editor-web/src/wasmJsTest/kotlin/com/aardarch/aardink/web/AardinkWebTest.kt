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

import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AardinkWebTest {

    private val mounted = mutableListOf<AardinkEditorHandle>()
    private val containers = mutableListOf<HTMLElement>()

    @AfterTest
    fun tearDown() {
        mounted.forEach(AardinkWeb::dispose)
        containers.forEach { it.remove() }
    }

    private fun mount(
        text: String = "hello",
        options: WebEditorOptions = WebEditorOptions(),
        width: String = "600px",
    ): AardinkEditorHandle {
        val id = "aardink-test-${nextId++}"
        val div = document.createElement("div") as HTMLElement
        div.id = id
        div.style.width = width
        div.style.height = "400px"
        document.body!!.appendChild(div)
        containers += div
        return AardinkWeb.mount(id, text, options).also { mounted += it }
    }

    /**
     * Waits in real time, on the browser's event loop rather than runTest's virtual clock:
     * change callbacks only fire once Compose has rendered a frame.
     */
    private suspend fun awaitUntil(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(5_000) {
            while (!condition()) delay(16)
        }
    }

    /** Gives the editor a few real frames, for asserting that something does NOT happen. */
    private suspend fun letFramesRun() = withContext(Dispatchers.Main) { delay(200) }

    /**
     * The width Compose has measured [handle] at, in CSS pixels: its canvas's drawing buffer, which
     * Compose sizes, not the canvas element's layout width, which follows the page's CSS alone.
     * 0 before there is a canvas.
     */
    private fun canvasWidth(handle: AardinkEditorHandle): Int {
        // viewport > positioning container > shadow host > (shadow root) > ... canvas
        val shadowHost = handle.viewportElement?.firstElementChild?.firstElementChild ?: return 0
        val canvas = shadowHost.shadowRoot?.querySelector("canvas") as? HTMLCanvasElement ?: return 0
        return (canvas.width / window.devicePixelRatio).roundToInt()
    }

    /** Counts the window's `resize` events while [block] runs. */
    private suspend fun countResizes(block: suspend () -> Unit): Int {
        var count = 0
        val listener: (Event) -> Unit = { count++ }
        window.addEventListener("resize", listener)
        try {
            block()
        } finally {
            window.removeEventListener("resize", listener)
        }
        return count
    }

    @Test
    fun `getValue returns what setValue stored`() {
        val handle = mount("first")
        assertEquals("first", AardinkWeb.getValue(handle))

        AardinkWeb.setValue(handle, "second\nline")

        assertEquals("second\nline", AardinkWeb.getValue(handle))
    }

    @Test
    fun `onChange fires with the new text after a programmatic edit`() = runTest {
        val handle = mount("before")
        val seen = mutableListOf<String>()
        AardinkWeb.onChange(handle) { seen += it }

        AardinkWeb.setValue(handle, "after")

        awaitUntil { seen.isNotEmpty() }
        assertEquals("after", seen.last())
    }

    @Test
    fun `mounting does not report a change`() = runTest {
        val handle = mount("unchanged")
        val seen = mutableListOf<String>()
        AardinkWeb.onChange(handle) { seen += it }

        letFramesRun()

        assertEquals(emptyList(), seen)
    }

    @Test
    fun `dispose is idempotent and silences listeners`() = runTest {
        val handle = mount()
        val seen = mutableListOf<String>()
        AardinkWeb.onChange(handle) { seen += it }

        AardinkWeb.dispose(handle)
        AardinkWeb.dispose(handle)
        AardinkWeb.setValue(handle, "after dispose")
        letFramesRun()

        assertTrue(AardinkWeb.isDisposed(handle))
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `dispose takes the editor's elements out of its container and leaves the host's`() {
        val handle = mount()
        val container = containers.last()
        val own = document.createElement("span")
        container.appendChild(own)
        assertTrue(container.childElementCount >= 2, "the viewport's element and the host's")

        AardinkWeb.dispose(handle)

        assertEquals(1, container.childElementCount)
        assertSame(own, container.firstElementChild)
    }

    // One editor for both: every mount takes a WebGL context, which Compose Multiplatform 1.12
    // does not give back on dispose (W-1), and headless Chrome runs out of them.
    @Test
    fun `the editor follows its container's size until it is disposed`() = runTest {
        val handle = mount()
        val container = containers.last()
        awaitUntil { canvasWidth(handle) == 600 }

        container.style.width = "300px"
        awaitUntil { canvasWidth(handle) == 300 }

        AardinkWeb.dispose(handle)
        val resizes = countResizes {
            container.style.width = "500px"
            letFramesRun()
        }
        assertEquals(0, resizes, "a disposed editor no longer watches its container")
    }

    // A collapsed container rather than a `display: none` one, which measures the same (0 wide)
    // but makes headless Chrome fail to create the WebGL context once many editors have run.
    @Test
    fun `an editor mounted into a collapsed container takes its size once it opens`() = runTest {
        val handle = mount(width = "0px")
        letFramesRun()

        containers.last().style.width = "600px"

        awaitUntil { canvasWidth(handle) == 600 }
    }

    @Test
    fun `changing language keeps the text and swaps the tokenizer`() {
        val handle = mount("val x = 1", WebEditorOptions(language = "plaintext"))

        AardinkWeb.updateOptions(handle, WebEditorOptions(language = "kotlin"))

        assertEquals("val x = 1", AardinkWeb.getValue(handle))
        assertSame(KotlinTokenizer, handle.state.value.tokenizer)
        assertEquals("kotlin", AardinkWeb.currentOptions(handle).language)
    }

    @Test
    fun `changing language keeps undo history`() {
        val handle = mount("val x = 1", WebEditorOptions(language = "plaintext"))
        val state = handle.state.value
        state.applyEdit(9, 0, "0", androidx.compose.ui.text.TextRange(10))

        AardinkWeb.updateOptions(handle, WebEditorOptions(language = "kotlin"))

        assertSame(state, handle.state.value, "the same editor state, not a rebuilt one")
        assertTrue(AardinkWeb.undo(handle))
        assertEquals("val x = 1", AardinkWeb.getValue(handle))
    }

    @Test
    fun `an unknown language falls back to plain text`() {
        val handle = mount(options = WebEditorOptions(language = "cobol"))

        assertEquals("plaintext", handle.language.value.id)
    }

    @Test
    fun `patchOptions changes only the keys it names`() {
        val handle = mount(options = WebEditorOptions(theme = "vscode-light", fontSize = 16f))

        AardinkWeb.patchOptions(handle, """{"readOnly": true, "notAnOption": 1}""")

        val options = AardinkWeb.currentOptions(handle)
        assertTrue(options.readOnly)
        assertEquals("vscode-light", options.theme)
        assertEquals(16f, options.fontSize)
    }

    @Test
    fun `parseOptions fills in defaults for missing fields`() {
        val options = AardinkWeb.parseOptions("""{"language": "json"}""")

        assertEquals(WebEditorOptions(language = "json"), options)
    }

    @Test
    fun `web diagnostics convert from 1-based exclusive columns to an inclusive range`() {
        val handle = mount("first\nsecond line")

        AardinkWeb.setDiagnosticsJson(
            handle,
            """
            [
              {"line": 2, "startColumn": 1, "endColumn": 7, "message": "whole word", "severity": "warning"},
              {"line": 2, "startColumn": 3, "endColumn": 3, "message": "zero width"},
              {"line": 9, "startColumn": 1, "endColumn": 2, "message": "no such line"}
            ]
            """.trimIndent(),
        )

        val diagnostics = handle.diagnostics.value!!
        assertEquals(2, diagnostics.size, "the out-of-range line is dropped")
        // "second" is offsets 6..11 in "first\nsecond line".
        assertEquals(6..11, diagnostics[0].range)
        assertEquals(1, diagnostics[0].lineNumber)
        assertEquals(DiagnosticSeverity.Warning, diagnostics[0].severity)
        assertEquals(8..8, diagnostics[1].range, "a zero-width marker keeps one character")
        assertEquals(DiagnosticSeverity.Error, diagnostics[1].severity)
    }

    @Test
    fun `the language's own diagnostics are reported once the editor is up`() = runTest {
        val handle = mount("{\n  \"a\": 1,\n}", WebEditorOptions(language = "json"))
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }

        awaitUntil { reported.isNotEmpty() }

        val markers = Json.decodeFromString(ListSerializer(WebDiagnostic.serializer()), reported.last())
        assertEquals(listOf(WebDiagnostic(line = 3, startColumn = 1, endColumn = 2, message = "Trailing comma in object")), markers)
        assertEquals(null, handle.diagnostics.value, "no host list: the language's own are shown")
    }

    @Test
    fun `the host's list replaces the language's own until it is set to null`() = runTest {
        val handle = mount("{\"a\": }", WebEditorOptions(language = "json"))
        val reported = mutableListOf<String>()
        AardinkWeb.onDiagnosticsChange(handle) { reported += it }
        awaitUntil { reported.isNotEmpty() }

        AardinkWeb.setDiagnosticsJson(handle, """[{"line": 1, "startColumn": 2, "endColumn": 5, "message": "from the host"}]""")
        assertEquals("from the host", handle.diagnostics.value!!.single().message)
        reported.clear()
        AardinkWeb.setValue(handle, "{\"b\": }")
        letFramesRun()
        assertTrue(reported.isEmpty(), "the language is not asked while the host's list is shown")

        AardinkWeb.setDiagnosticsJson(handle, "null")
        assertEquals(null, handle.diagnostics.value)
        awaitUntil { reported.isNotEmpty() }
        assertEquals(
            "Unexpected character '}'",
            Json.decodeFromString(ListSerializer(WebDiagnostic.serializer()), reported.last()).single().message,
        )
    }

    @Test
    fun `a diagnostic running over several lines is reported up to the end of its first`() {
        val handle = mount("first\nsecond")
        val document = handle.state.value.document
        val markers = AardinkWeb.toWebDiagnostics(
            document,
            listOf(Diagnostic(range = 2..8, lineNumber = 0, message = "wide", severity = DiagnosticSeverity.Info)),
        )
        assertEquals(listOf(WebDiagnostic(line = 1, startColumn = 3, endColumn = 6, message = "wide", severity = "info")), markers)
    }

    @Test
    fun `navigateTo clamps to the document`() {
        val handle = mount("ab\ncd")

        AardinkWeb.navigateTo(handle, line = 2, column = 99)
        assertEquals(5, handle.state.value.pendingNavigation?.targetOffset)

        AardinkWeb.navigateTo(handle, line = 0, column = 0)
        assertEquals(0, handle.state.value.pendingNavigation?.targetOffset)
    }

    @Test
    fun `undo and redo report whether anything changed`() {
        val handle = mount("abc")
        assertFalse(AardinkWeb.undo(handle), "a freshly mounted editor has nothing to undo")

        handle.state.value.applyTextEdits(listOf(TextEdit(range = 0..0, newText = "X")))
        assertTrue(AardinkWeb.undo(handle))
        assertEquals("abc", AardinkWeb.getValue(handle))
        assertTrue(AardinkWeb.redo(handle))
        assertEquals("Xbc", AardinkWeb.getValue(handle))
    }

    @Test
    fun `mounting into a missing element fails clearly`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AardinkWeb.mount("no-such-element", "")
        }
        assertTrue(error.message!!.contains("no-such-element"))
    }

    private companion object {
        var nextId = 0
    }
}
