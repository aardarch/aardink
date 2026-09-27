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
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in tokenizers' incremental passes, against a stateless full scan, over random edits
 * that open and close comments, strings and tags.
 *
 * What the editor does with a `tokenizeLines` result: it replaces every line the result touches
 * (and every dirty line) with it, and keeps every other line's tokens, moved along with its text.
 * So two things must hold after each edit: the lines in the result tokenize exactly as a full scan
 * of the new text does, and every other line tokenizes exactly as its unchanged counterpart did
 * before the edit. The full scan used for comparison is the stateless one `tokenizeLines` does
 * without previous tokens, so the tokenizer's own state carries from edit to edit untouched.
 */
class IncrementalTokenizingTest {

    private val samples = mapOf(
        "kotlin" to """
            package demo
            /* a block
               comment */
            fun main(args: Array<String>) {
                val s = "text /* not a comment */"
                val raw = ""${'"'}multi
                line""${'"'}
                // line comment
                class Inner
            }
        """.trimIndent(),
        "typescript" to """
            /** doc */
            export function add(a: number, b: number): number {
              const t = `tpl ${'$'}{a}
              more`;
              return a + b; // sum
            }
        """.trimIndent(),
        "json" to """{ "a": [1, 2.5, null, true], "b": { "c": "d\"e" } }""",
        "toml" to """
            # comment
            [table]
            key = "value"
            multi = ""${'"'}
            text""${'"'}
            n = 42
        """.trimIndent(),
        "xml" to """
            <?xml version="1.0"?>
            <!-- a
                 comment -->
            <root attr="v" other='w'>
              <![CDATA[ raw <data> ]]>
              <child>text &amp; more</child>
            </root>
        """.trimIndent(),
        "html" to """
            <!DOCTYPE html>
            <html><body class="x">
            <p>Hello &amp; welcome<!-- note --></p>
            </body></html>
        """.trimIndent(),
        "css" to """
            /* header */
            .rule { color: #fff; margin: 0 4px; }
            a:hover { content: "x"; }
        """.trimIndent(),
        "markdown" to """
            # Title
            Some *emphasis* and `code`.
            ```
            fenced
            ```
            - item
        """.trimIndent(),
    )

    private val fragments = listOf(
        "x", " ", "\n", "/*", "*/", "\"", "'", "<", ">", "<!--", "-->", "`", "\"\"\"", "&amp;", "{", "}",
        "fun ", "class ", "// c\n", "<a b=\"c\">", "]]>", "<![CDATA[", "#", "\n\n", "=",
    )

    @Test
    fun `incremental passes agree with a full scan for every built-in language`() {
        for ((id, sample) in samples) {
            val tokenizer = BuiltInLanguages.all.single { it.id == id }.tokenizer
            repeat(3) { seed -> checkRandomEdits(id, tokenizer, sample, Random(seed * 31 + id.length)) }
        }
    }

    private fun checkRandomEdits(id: String, tokenizer: IncrementalTokenizer, sample: String, random: Random) {
        var text = sample
        var full = tokenizer.tokenizeFull(text)
        // What the editor passes back as previousTokens: the list the tokenizer returned last.
        var previousResult = full
        repeat(60) { step ->
            val old = text
            val oldFull = full
            // One edit, as a CodeDocument reports it: the dirty lines are the ones it touched.
            val edit: Edit = if (random.nextInt(3) < 2 || text.isEmpty()) {
                val offset = random.nextInt(text.length + 1)
                val inserted = fragments[random.nextInt(fragments.size)]
                text = text.substring(0, offset) + inserted + text.substring(offset)
                Edit(offset, 0, inserted)
            } else {
                val offset = random.nextInt(text.length)
                val length = random.nextInt(1, minOf(6, text.length - offset) + 1)
                val removed = text.substring(offset, offset + length)
                text = text.substring(0, offset) + text.substring(offset + length)
                Edit(offset, length, "", removedNewlines = removed.count { it == '\n' })
            }
            val startLine = lineOf(text, edit.offset)
            val dirty = startLine..(startLine + edit.inserted.count { it == '\n' })

            val incremental = tokenizer.tokenizeLines(text, dirty, previousResult)
            previousResult = incremental
            full = tokenizer.tokenizeLines(text, 0..lineCount(text), emptyList())
            val where = "$id step $step after ${edit.describe()}"

            val newLines = splitByLine(text, full)
            val oldLines = splitByLine(old, oldFull)
            val covered = coveredLines(text, incremental, dirty)
            val incrementalLines = splitByLine(text, incremental)
            for (line in 0 until lineCount(text)) {
                if (line in covered) {
                    assertEquals(newLines[line].orEmpty(), incrementalLines[line].orEmpty(), "$where: line $line from the incremental pass")
                } else {
                    val oldLine = when {
                        line < startLine -> line
                        else -> line - edit.inserted.count { it == '\n' } + edit.removedNewlines
                    }
                    assertEquals(
                        oldLines[oldLine].orEmpty(),
                        newLines[line].orEmpty(),
                        "$where: line $line kept from line $oldLine\nold=${old.quoted()}\nnew=${text.quoted()}" +
                            "\nincremental=$incremental\nfull=$full\noldFull=$oldFull",
                    )
                }
            }
        }
    }

    @Test
    fun `an edit in a large document rescans only a few lines`() {
        val line = "    val value = compute(\"a string\", 42) // comment\n"
        var text = "fun main() {\n" + line.repeat(2000) + "}\n"
        val previous = KotlinTokenizer.tokenizeFull(text)
        val offset = text.length / 2
        text = text.substring(0, offset) + "x" + text.substring(offset)
        val startLine = lineOf(text, offset)
        val result = KotlinTokenizer.tokenizeLines(text, startLine..startLine, previous)
        val real = result.filter { it.end > it.start } // without the zero-length markers at either end
        assertTrue(real.size < 120, "rescanned ${real.size} tokens")
        val full = KotlinTokenizer.tokenizeLines(text, 0..lineCount(text), emptyList())
        assertEquals(full.filter { it.start >= result.first().start && it.end <= result.last().end }, real)
    }

    @Test
    fun `a list the tokenizer did not return gets a full scan`() {
        val text = "val a = 1\nval b = 2\n"
        KotlinTokenizer.tokenizeFull(text)
        val result = KotlinTokenizer.tokenizeLines(text + "x", 2..2, listOf(Token(0, 3, TokenType.Keyword)))
        assertEquals(KotlinTokenizer.tokenizeLines(text + "x", 0..2, emptyList()), result)
    }

    @Test
    fun `opening a block comment recolours the lines it now covers`() {
        // A stray "*/" on line 1; opening "/*" on line 0 turns both lines into one comment.
        var text = "val a = 1\nval b = 2 */\nval c = 3\n"
        val previous = KotlinTokenizer.tokenizeFull(text)
        text = "/*" + text
        val result = KotlinTokenizer.tokenizeLines(text, 0..0, previous)
        val real = result.filter { it.end > it.start }
        val commentEnd = text.indexOf("*/", 2) + 2
        assertEquals(Token(0, commentEnd, TokenType.Comment), real.first())
        assertEquals(KotlinTokenizer.tokenizeLines(text, 0..3, emptyList()).take(real.size), real)
    }

    private class Edit(val offset: Int, val length: Int, val inserted: String, val removedNewlines: Int = 0) {
        fun describe(): String = if (length > 0) "delete $length at $offset" else "insert ${inserted.replace("\n", "\\n")} at $offset"
    }

    private fun String.quoted(): String = "\"" + replace("\n", "\\n") + "\""

    private fun lineOf(text: String, offset: Int): Int = text.substring(0, offset).count { it == '\n' }

    private fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** Tokens split into per-line (startColumn, endColumn, type) runs, as the editor stores them. */
    private fun splitByLine(text: String, tokens: List<Token>): Map<Int, List<Triple<Int, Int, TokenType>>> {
        val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        val out = HashMap<Int, MutableList<Triple<Int, Int, TokenType>>>()
        for (token in tokens) {
            var line = starts.indexOfLast { it <= token.start }
            var start = token.start
            while (start < token.end && line < starts.size) {
                val lineEnd = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
                val end = minOf(token.end, lineEnd)
                if (end > start) out.getOrPut(line) { mutableListOf() }.add(Triple(start - starts[line], end - starts[line], token.type))
                line++
                if (line >= starts.size) break
                start = starts[line]
            }
        }
        return out
    }

    /** The lines the editor replaces with a result: those it touches, and the dirty ones. */
    private fun coveredLines(text: String, tokens: List<Token>, dirty: IntRange): IntRange {
        if (tokens.isEmpty()) return dirty
        val first = lineOf(text, tokens.first().start)
        val last = lineOf(text, tokens.maxOf { it.end }.coerceAtMost(text.length))
        return minOf(first, dirty.first)..maxOf(last, dirty.last)
    }
}
