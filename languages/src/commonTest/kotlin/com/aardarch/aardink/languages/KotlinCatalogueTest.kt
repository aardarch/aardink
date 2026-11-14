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

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.SignatureHelp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kotlin's hover and signature help, from the service's catalogue of the standard library. */
class KotlinCatalogueTest {

    private val service = BuiltInLanguages.Kotlin.languageService!!

    private suspend fun hover(text: String, at: String) = service.hoverDoc(CodeDocument(text), text.indexOf(at) + 1)

    /** Signature help with the caret at the end of [text]. */
    private suspend fun help(text: String): SignatureHelp? = service.signatureHelp(CodeDocument(text), text.length)

    @Test
    fun `a called function shows its overloads and what it does`() = runTest {
        val map = assertNotNull(hover("val n = items.map { it.size }", "map"))
        assertEquals("map", map.title)
        assertTrue("fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R>" in map.content, map.content)
        assertEquals(14 until 17, map.range)
        val count = assertNotNull(hover("xs.count()", "count"))
        assertTrue("count(): Int\nfun <T> Iterable<T>.count(predicate" in count.content, count.content)
        assertNotNull(hover("listOf<String>()", "listOf"))
        assertNull(hover("val map = 1", "map"), "a variable of the same name is not the function")
        assertNull(hover("unknown()", "unknown"))
    }

    @Test
    fun `a type and an annotation show what they are`() = runTest {
        assertEquals("String", hover("val s: String = \"\"", "String")?.title)
        val composable = assertNotNull(hover("@Composable\nfun A() {}", "Composable"))
        assertEquals("@Composable", composable.title)
        assertEquals(0 until 11, composable.range)
        assertNull(hover("Composable", "Composable"), "an annotation's name without its @")
    }

    @Test
    fun `signature help follows the arguments typed so far`() = runTest {
        val listOf = assertNotNull(help("val xs = listOf(1, 2, "))
        assertEquals(0, listOf.activeSignature)
        assertEquals(0, listOf.activeParameter, "every argument is the vararg")

        val require = assertNotNull(help("require(x > 0, "))
        assertEquals(1, require.activeSignature, "the overload with a second parameter")
        assertEquals(1, require.activeParameter)
        val signature = require.signatures[1]
        val lazyMessage = signature.parameters[1]
        assertEquals("lazyMessage: () -> Any", signature.label.substring(lazyMessage.labelRange!!))
        assertEquals("The exception's message, made only when it is thrown.", lazyMessage.documentation)

        val println = assertNotNull(help("repeat(3) { println("))
        assertEquals(2, println.signatures.size)
        assertEquals(1, println.activeSignature, "println(message) for the first argument")

        assertEquals(1, help("println(\"a, (b\", ")?.activeParameter, "a string's comma and bracket are its own")
        assertNotNull(help("listOf<String>("))
    }

    @Test
    fun `no signature help outside a known call's parentheses`() = runTest {
        assertNull(help("items.map { x -> "), "inside a lambda")
        assertNull(help("unknown(1, "))
        assertNull(help("listOf(1) "))
        assertNull(help("val a = [1, "))
    }
}
