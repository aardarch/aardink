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
import org.w3c.dom.HTMLElement
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
