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
package com.aardarch.aardink.ui

import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A fence is coloured by the tokenizer object a document may be using: the built-in languages are
 * singletons. Each kind of built-in keeps its last few scans for its incremental passes, so a
 * snippet that took a place there would push out the document's scan, and the document's next
 * edit would rescan all of it.
 */
class CodeFenceScanTest {

    private val grammar = DeclarativeGrammar.parse(
        """
        {
          "tokenizer": {
            "root": [
              ["[a-z]+", "identifier"],
              ["[0-9]+", "number"],
              ["\\s+", ""]
            ]
          }
        }
        """.trimIndent(),
    )

    private fun assertSnippetsLeaveTheDocumentIncremental(tokenizer: IncrementalTokenizer, documentLine: String, snippet: String) {
        val lines = 500
        val document = documentLine.repeat(lines)
        val previous = tokenizer.tokenizeFull(document)
        repeat(8) { tokensOf("$snippet\n$it", tokenizer) }

        val line = lines / 2
        val offset = line * documentLine.length + 2
        val edited = document.substring(0, offset) + "x" + document.substring(offset)
        val result = tokenizer.tokenizeLines(edited, line..line, previous).filter { it.end > it.start }

        assertTrue(result.size < previous.size / 10, "rescanned ${result.size} of ${previous.size} tokens")
    }

    @Test
    fun `snippets leave a regex tokenizer's document incremental`() = assertSnippetsLeaveTheDocumentIncremental(
        BuiltInLanguages.Kotlin.tokenizer,
        documentLine = "val value = compute(\"a string\", 42) // comment\n",
        snippet = "val x = 1",
    )

    @Test
    fun `snippets leave the XML tokenizer's document incremental`() = assertSnippetsLeaveTheDocumentIncremental(
        BuiltInLanguages.Xml.tokenizer,
        documentLine = "<child name=\"value\">text</child>\n",
        snippet = "<a b=\"c\"/>",
    )

    @Test
    fun `snippets leave a grammar's document incremental`() = assertSnippetsLeaveTheDocumentIncremental(
        DeclarativeTokenizer(grammar),
        documentLine = "word 42 another 7\n",
        snippet = "word 1",
    )
}
