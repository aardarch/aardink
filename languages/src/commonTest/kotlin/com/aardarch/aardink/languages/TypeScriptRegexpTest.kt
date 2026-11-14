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

import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.typescript.TypeScriptTokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A `/` in TypeScript: a regular expression literal, or division, by the token before it. */
class TypeScriptRegexpTest {

    /** The texts of [text]'s regexp tokens. */
    private fun regexps(text: String): List<String> =
        TypeScriptTokenizer.tokenizeFull(text).filter { it.type.scope == "regexp" }.map { text.substring(it.start, it.end) }

    @Test
    fun `a slash where a value is to come starts a regular expression`() {
        assertEquals(listOf("/a\"b/g"), regexps("const r = /a\"b/g;"), "a quote inside is the literal's, not a string")
        assertEquals(listOf("/^\\d+$/"), regexps("if (/^\\d+$/.test(s)) {}"))
        assertEquals(listOf("/[/]x/i"), regexps("s.split(/[/]x/i)"), "a slash in a class does not end it")
        assertEquals(listOf("/\\/+/"), regexps("return /\\/+/;"))
        assertEquals(listOf("/a/", "/b/"), regexps("f(/a/, [/b/])"))
        assertEquals(listOf("/x/"), regexps("x =/x/"), "the operator before it is split off")
        assertEquals(listOf("/=/"), regexps("s.replace(/=/, '')"))
        assertEquals(listOf("/a/"), regexps("// c\n/a/.exec(s)"), "a comment is looked past")
        assertEquals(listOf("/a/"), regexps("typeof /a/"))
        assertEquals(listOf("/y/u"), regexps("const f = (s) => /y/u.test(s)"))
    }

    @Test
    fun `a slash after a value divides`() {
        for (text in listOf(
            "a / b / c",
            "f(x) / 2 / 3",
            "arr[0] / n / m",
            "this / 2 / k",
            "1 / 2 / 3",
            "i++ / 2 / j",
            "x /= 2; y /= 3",
            "\"s\" / 2 / 1",
            "a\n/ b /\nc",
        )) {
            assertEquals(emptyList(), regexps(text), text)
        }
    }

    @Test
    fun `JSX tags and comments stay as they were`() {
        assertEquals(emptyList(), regexps("<a></a><b></b>"))
        assertEquals(emptyList(), regexps("<p>x</p>"))
        assertEquals(emptyList(), regexps("a /* c */ / b /* d */"))
        val tokens = TypeScriptTokenizer.tokenizeFull("x = 1 /* not / a */ + /* b */ 2")
        assertTrue(tokens.none { it.type.scope == "regexp" })
        assertTrue(tokens.count { it.type == TokenType.Comment } == 2, "$tokens")
    }

    @Test
    fun `a literal does not run past its line`() {
        assertEquals(emptyList(), regexps("x = /unclosed\ny = 1 / 2"))
    }
}
