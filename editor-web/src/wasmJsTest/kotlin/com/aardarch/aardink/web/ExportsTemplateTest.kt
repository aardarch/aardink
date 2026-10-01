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

import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Drives the export template the way a JS host would, through its exports only — the template's
 * handle map is file-private, as it is in a consumer's copy.
 */
class ExportsTemplateTest {

    private val container = (document.createElement("div") as HTMLElement).also {
        it.id = "aardink-exports-test"
        it.style.width = "400px"
        it.style.height = "300px"
        document.body!!.appendChild(it)
    }

    @AfterTest
    fun tearDown() {
        container.remove()
    }

    @Test
    fun `an editor round-trips text through its integer handle`() {
        val id = aardinkCreate(container.id, "one", """{"language": "json"}""")
        try {
            aardinkSetValue(id, "two")
            assertEquals("two", aardinkGetValue(id))

            // A language change rebuilds the editor state; the text must survive it.
            aardinkUpdateOptions(id, """{"language": "kotlin", "readOnly": true}""")
            assertEquals("two", aardinkGetValue(id))

            assertFalse(aardinkUndo(id), "setValue and a language change leave no history")
        } finally {
            aardinkDispose(id)
        }
    }

    @Test
    fun `the language's own diagnostics reach the diagnostics callback as JSON`() = runTest {
        val id = aardinkCreate(container.id, "[1,]", """{"language": "json"}""")
        try {
            val reported = mutableListOf<String>()
            aardinkOnDiagnosticsChange(id) { reported += it }
            withContext(Dispatchers.Main) {
                withTimeout(5_000) {
                    while (reported.isEmpty()) delay(16)
                }
            }
            assertTrue(reported.last().contains("Trailing comma in array"), reported.last())
            // Asked again without an edit.
            val before = reported.size
            aardinkRevalidate(id)
            withContext(Dispatchers.Main) {
                withTimeout(5_000) {
                    while (reported.size == before) delay(16)
                }
            }
            aardinkSetDiagnostics(id, "[]")
            aardinkSetDiagnostics(id, "null")
        } finally {
            aardinkDispose(id)
        }
    }

    @Test
    fun `registering through the exports says what is wrong`() {
        val nothing: (String, Int, Int) -> Promise<JsString?> = { _, _, _ -> Promise.resolve<JsString?>(null) }
        val noDiagnostics: (String) -> Promise<JsString?> = { Promise.resolve<JsString?>(null) }
        val good = """{ "id": "exported", "grammar": { "tokenizer": { "root": [ ["a", "x"] ] } } }"""
        assertEquals("", aardinkRegisterLanguage(good, nothing, nothing, noDiagnostics))
        val bad = """{ "id": "exported2", "grammar": { "tokenizer": { "root": [ ["(?<=a)b", "x"] ] } } }"""
        assertTrue(aardinkRegisterLanguage(bad, nothing, nothing, noDiagnostics).contains("lookbehind"))
        assertEquals("", aardinkRegisterTheme("exported-theme", """{ "tokenColors": [] }"""))
        assertTrue(aardinkRegisterTheme("broken", "not json").isNotEmpty())
    }

    @Test
    fun `each editor gets its own id and a disposed id is rejected`() {
        val first = aardinkCreate(container.id, "", "{}")
        val second = aardinkCreate(container.id, "", "{}")
        assertNotEquals(first, second)

        aardinkDispose(first)
        aardinkDispose(first) // idempotent

        val error = assertFailsWith<IllegalStateException> { aardinkGetValue(first) }
        assertTrue(error.message!!.contains("$first"))
        aardinkDispose(second)
    }
}
