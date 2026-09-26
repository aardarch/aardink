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

import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.RegexTokenizer
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [KotlinTokenizer] against the rule set it had before its two lookbehind rules were
 * replaced by `refine()`, across every Kotlin source in this repository.
 *
 * One deliberate difference: the old rules looked *through* the preceding token, so `@fun name`
 * or a line comment ending in "fun" followed by a name on the next line highlighted that name
 * as a function. `refine()` only follows a real keyword token. Neither case occurs in the corpus.
 *
 * JVM-only on purpose: the old `(?<=\b(?:class|object|interface|enum)\s)` rule is exactly what
 * was too slow for Kotlin/wasm's regex engine, so this comparison cannot run in the browser.
 */
class KotlinTokenizerGoldenTest {

    private object LookbehindKotlinTokenizer : RegexTokenizer() {
        private val keywords = setOf(
            "as", "break", "by", "class", "companion", "const", "continue", "data", "do", "dynamic",
            "else", "enum", "external", "false", "final", "for", "fun", "get", "if", "import", "in",
            "init", "inline", "inner", "interface", "internal", "is", "lateinit", "let", "noinline",
            "null", "object", "open", "operator", "out", "override", "package", "private", "protected",
            "public", "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this",
            "throw", "true", "try", "typealias", "val", "var", "when", "where", "while", "with",
            "yield",
        )

        override val rules: List<Pair<Regex, TokenType>> = listOf(
            Regex("/\\*[\\s\\S]*?\\*/") to TokenType.Comment,
            Regex("//[^\\n]*") to TokenType.Comment,
            Regex("\"\"\"[\\s\\S]*?\"\"\"") to TokenType.StringLiteral,
            Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"") to TokenType.StringLiteral,
            Regex("'(?:\\\\u[0-9A-Fa-f]{4}|\\\\.|[^'\\\\\\n])'") to TokenType.StringLiteral,
            Regex("(?<=\\bfun\\s)[A-Za-z_][A-Za-z0-9_]*") to TokenType.FunctionCall,
            Regex("(?<=\\b(?:class|object|interface|enum)\\s)[A-Za-z_][A-Za-z0-9_]*") to TokenType.TypeName,
            Regex("@[A-Za-z_][A-Za-z0-9_]*") to TokenType.Annotation,
            Regex("\\b(?:0[xX][\\dA-Fa-f_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?[fFlLuU]*)\\b") to TokenType.Number,
            Regex("\\b(?:" + keywords.joinToString("|") + ")\\b") to TokenType.Keyword,
            Regex("\\b[A-Z][A-Za-z0-9_]*\\b") to TokenType.TypeName,
            Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()") to TokenType.FunctionCall,
            Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b") to TokenType.Identifier,
            Regex("[{}\\[\\]();,.:]") to TokenType.Punctuation,
            Regex("[+\\-*/%=!<>&|^~?]+") to TokenType.Operator,
        )
    }

    private val edgeCases = """
        fun main() {}
        fun  twoSpaces() {}
        fun
        nextLine() {}
        class Foo; object Bar; interface Baz; enum class Qux { A }
        data class Point(val x: Int)
        private fun <T> generic(t: T) = t
        fun String.ext() = this
        val funny = 1; val classy = 2
        "fun inString" + 'c'
    """.trimIndent()

    @Test
    fun `refine reproduces the lookbehind rules across the repository's Kotlin sources`() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = repoRoot.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(sources.size > 50, "expected to find the repo's sources, found ${sources.size}")

        val mismatches = (sources.map { it.path to it.readText() } + ("edge cases" to edgeCases))
            .mapNotNull { (name, text) ->
                val expected = LookbehindKotlinTokenizer.tokenizeFull(text)
                val actual = KotlinTokenizer.tokenizeFull(text)
                if (expected == actual) null else "$name: ${firstDifference(text, expected, actual)}"
            }

        assertEquals(emptyList(), mismatches)
    }

    private fun firstDifference(text: String, expected: List<Token>, actual: List<Token>): String {
        val i = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: expected.size
        val e = expected.getOrNull(i)
        val a = actual.getOrNull(i)
        val at = (e ?: a)?.start ?: 0
        return "token $i near '${text.substring(maxOf(0, at - 20), minOf(text.length, at + 20)).replace("\n", "\\n")}': expected $e, got $a"
    }
}
