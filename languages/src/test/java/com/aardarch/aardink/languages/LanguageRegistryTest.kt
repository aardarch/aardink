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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.PlainTextTokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguageRegistryTest {

    @Test
    fun `withBuiltIns includes all 9 built-in languages`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertEquals(9, registry.all.size)
        assertNotNull(registry.byId("kotlin"))
        assertNotNull(registry.byId("typescript"))
        assertNotNull(registry.byId("json"))
        assertNotNull(registry.byId("toml"))
        assertNotNull(registry.byId("xml"))
        assertNotNull(registry.byId("html"))
        assertNotNull(registry.byId("css"))
        assertNotNull(registry.byId("markdown"))
        assertNotNull(registry.byId("plaintext"))
    }

    @Test
    fun `byExtension resolves with and without leading dot`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertEquals("kotlin", registry.byExtension("kt")?.id)
        assertEquals("kotlin", registry.byExtension(".kt")?.id)
        assertEquals("kotlin", registry.byExtension("KT")?.id)
        assertEquals("typescript", registry.byExtension("tsx")?.id)
        assertEquals("toml", registry.byExtension("toml")?.id)
        assertEquals("xml", registry.byExtension("xml")?.id)
        assertNull(registry.byExtension("unknown"))
        assertNull(registry.byExtension(""))
    }

    @Test
    fun `register adds new language`() {
        val registry = LanguageRegistry.withBuiltIns()
        val custom = LanguageDefinition(
            id = "log",
            displayName = "Log",
            fileExtensions = listOf("log"),
            tokenizer = PlainTextTokenizer,
        )
        registry.register(custom)
        assertEquals(10, registry.all.size)
        assertSame(custom, registry.byId("log"))
        assertSame(custom, registry.byExtension("log"))
    }

    @Test
    fun `override mutates existing definition`() {
        val registry = LanguageRegistry.withBuiltIns()
        registry.override("kotlin") { it.copy(displayName = "Kotlin (custom)") }
        assertEquals("Kotlin (custom)", registry.byId("kotlin")?.displayName)
        assertEquals(9, registry.all.size)
    }

    @Test
    fun `override on unknown id throws`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertThrows(IllegalStateException::class.java) {
            registry.override("nonexistent") { it }
        }
    }

    @Test
    fun `unregister removes language`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertTrue(registry.unregister("plaintext"))
        assertNull(registry.byId("plaintext"))
        assertEquals(8, registry.all.size)
    }
}
