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

import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
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

    private fun mount(text: String = "hello", options: WebEditorOptions = WebEditorOptions()): AardinkEditorHandle {
        val id = "aardink-test-${nextId++}"
        val div = document.createElement("div") as HTMLElement
        div.id = id
        div.style.width = "600px"
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
    fun `changing language keeps the text and swaps the tokenizer`() {
        val handle = mount("val x = 1", WebEditorOptions(language = "plaintext"))

        AardinkWeb.updateOptions(handle, WebEditorOptions(language = "kotlin"))

        assertEquals("val x = 1", AardinkWeb.getValue(handle))
        assertSame(KotlinTokenizer, handle.state.value.tokenizer)
        assertEquals("kotlin", AardinkWeb.currentOptions(handle).language)
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

        val diagnostics = handle.diagnostics.value
        assertEquals(2, diagnostics.size, "the out-of-range line is dropped")
        // "second" is offsets 6..11 in "first\nsecond line".
        assertEquals(6..11, diagnostics[0].range)
        assertEquals(1, diagnostics[0].lineNumber)
        assertEquals(DiagnosticSeverity.Warning, diagnostics[0].severity)
        assertEquals(8..8, diagnostics[1].range, "a zero-width marker keeps one character")
        assertEquals(DiagnosticSeverity.Error, diagnostics[1].severity)
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
