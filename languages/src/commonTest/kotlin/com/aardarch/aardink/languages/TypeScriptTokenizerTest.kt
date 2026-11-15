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

import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.languages.internal.typescript.TypeScriptTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each token's text and Monaco scope, in order, gaps left out: what a theme colours. */
internal fun IncrementalTokenizer.scopes(text: String): List<Pair<String, String>> =
    tokenizeFull(text).filter { it.end > it.start }.map { text.substring(it.start, it.end) to it.type.scope }

class TypeScriptTokenizerTest {

    @Test
    fun `imports, declarations and their types`() {
        assertEquals(
            listOf(
                "import" to "keyword", "{" to "delimiter", "a" to "identifier", "}" to "delimiter", "from" to "keyword",
                "'m'" to "string", ";" to "delimiter",
                "export" to "keyword", "const" to "keyword", "x" to "identifier", ":" to "delimiter", "number" to "keyword",
                "=" to "operator", "1" to "number", ";" to "delimiter",
            ),
            TypeScriptTokenizer.scopes("import { a } from 'm';\nexport const x: number = 1;"),
        )
    }

    @Test
    fun `numbers say what kind they are`() {
        assertEquals(
            listOf("0x1F" to "number.hex", "+" to "operator", "2.5e3" to "number.float", "+" to "operator", "7" to "number"),
            TypeScriptTokenizer.scopes("0x1F + 2.5e3 + 7"),
        )
    }

    @Test
    fun `comments, line and block`() {
        assertEquals(
            listOf("/* block */" to "comment", "a" to "identifier", "// line" to "comment"),
            TypeScriptTokenizer.scopes("/* block */ a // line"),
        )
    }

    @Test
    fun `strings in each quote, a template literal whole`() {
        assertEquals(
            listOf("'a'" to "string", "\"b\"" to "string", "`t \${x} u`" to "string"),
            TypeScriptTokenizer.scopes("'a' \"b\" `t \${x} u`"),
        )
    }

    @Test
    fun `classes, generics, functions and control flow`() {
        assertEquals(
            listOf(
                "class" to "keyword", "C" to "type", "<" to "operator", "T" to "type", ">" to "operator", "extends" to "keyword",
                "B" to "type", "{" to "delimiter", "async" to "keyword", "f" to "function", "(" to "delimiter", "p" to "identifier",
                "?" to "operator", ":" to "delimiter", "string" to "keyword", ")" to "delimiter", ":" to "delimiter",
                "Promise" to "type", "<" to "operator", "void" to "keyword", ">" to "operator", "{" to "delimiter",
                "return" to "keyword.flow", "null" to "keyword", "}" to "delimiter", "}" to "delimiter",
            ),
            TypeScriptTokenizer.scopes("class C<T> extends B { async f(p?: string): Promise<void> { return null } }"),
        )
    }

    @Test
    fun `literals are keywords`() {
        assertEquals(
            listOf("true", "false", "null", "undefined").map { it to "keyword" },
            TypeScriptTokenizer.scopes("true false null undefined"),
        )
    }
}
