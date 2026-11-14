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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LanguageRegistryTest {

    @Test
    fun `withBuiltIns includes every built-in language`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertEquals(BuiltInLanguages.all.size, registry.all.size)
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
        assertEquals(BuiltInLanguages.all.size + 1, registry.all.size)
        assertSame(custom, registry.byId("log"))
        assertSame(custom, registry.byExtension("log"))
    }

    @Test
    fun `override mutates existing definition`() {
        val registry = LanguageRegistry.withBuiltIns()
        registry.override("kotlin") { it.copy(displayName = "Kotlin (custom)") }
        assertEquals("Kotlin (custom)", registry.byId("kotlin")?.displayName)
        assertEquals(BuiltInLanguages.all.size, registry.all.size)
    }

    @Test
    fun `override on unknown id throws`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertFailsWith<IllegalStateException> {
            registry.override("nonexistent") { it }
        }
    }

    @Test
    fun `unregister removes language`() {
        val registry = LanguageRegistry.withBuiltIns()
        assertTrue(registry.unregister("plaintext"))
        assertNull(registry.byId("plaintext"))
        assertEquals(BuiltInLanguages.all.size - 1, registry.all.size)
    }

    @Test
    fun `byFileName reads a file's name before its extension`() {
        val registry = LanguageRegistry.withBuiltIns()
        for ((path, id) in listOf(
            "Dockerfile" to "dockerfile",
            "docker/Dockerfile.dev" to "dockerfile",
            "containerfile" to "dockerfile",
            "web.dockerfile" to "dockerfile",
            "Jenkinsfile" to "groovy",
            "build.gradle" to "groovy",
            "~/.bashrc" to "shell",
            "C:\\Users\\me\\.gitconfig" to "ini",
            ".editorconfig" to "ini",
            "src\\Main.kt" to "kotlin",
            "README.md" to "markdown",
        )) {
            assertEquals(id, registry.byFileName(path)?.id, path)
        }
        assertNull(registry.byFileName("Makefile"))
        assertNull(registry.byFileName("notes."))
        assertNull(registry.byFileName("dir/"))
    }

    @Test
    fun `a registered language's file names come before a built-in's extension`() {
        val registry = LanguageRegistry.withBuiltIns()
        val log = LanguageDefinition(
            id = "buildlog",
            displayName = "Build log",
            fileExtensions = listOf("log"),
            tokenizer = PlainTextTokenizer,
            fileNames = listOf("build*.txt"),
        )
        registry.register(log)
        assertSame(log, registry.byFileName("build-2026.txt"))
        assertEquals("plaintext", registry.byFileName("notes.txt")?.id)
    }

    @Test
    fun `file name patterns match whole names, without regard to case`() {
        assertTrue(fileNameMatches("Dockerfile", "dockerfile"))
        assertTrue(fileNameMatches("Dockerfile.*", "Dockerfile.prod"))
        assertTrue(fileNameMatches("*.Dockerfile", "web.dockerfile"))
        assertTrue(fileNameMatches("a*b*c", "aXbYbZc"))
        assertTrue(fileNameMatches("*", ""))
        assertTrue(!fileNameMatches("Dockerfile", "Dockerfile.dev"))
        assertTrue(!fileNameMatches("Dockerfile.*", "MyDockerfile.dev"))
        assertTrue(!fileNameMatches("a*b", "aXbY"))
    }
}
