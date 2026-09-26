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
import com.aardarch.aardink.languages.internal.COOPERATIVE_TOKENS_PER_YIELD
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import com.aardarch.aardink.languages.internal.xml.XmlTokenizer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CooperativeTokenizingTest {

    // Not valid in any one language — it only has to give every tokenizer plenty to emit: tags,
    // attributes, strings, numbers, comments, keywords, punctuation, markdown headings.
    private val snippet = """
        # Heading
        <item id="42" enabled='true'><!-- note --><name>fun &amp; games</name></item>
        fun main(args: Array<String>) { val x = 3.14 // comment
            println("hello ${'$'}x") }
        [table] key = "value" # trailing
        .rule { color: #fff; margin: 0 4px; }
        { "a": [1, 2, null, true] }
    """.trimIndent() + "\n"

    // Just enough that the regex and XML tokenizers each cross at least two yield boundaries
    // (asserted below). Kept small on purpose: wasm runs the whole pass on the browser thread,
    // and a much larger input stalls Karma past its ping timeout.
    private val largeText = snippet.repeat(200)

    @Test
    fun `cooperative tokenization matches the full pass for every built-in language`() = runTest {
        for (language in BuiltInLanguages.all) {
            val tokenizer = language.tokenizer
            assertEquals(
                tokenizer.tokenizeFull(largeText),
                tokenizer.tokenizeFullCooperative(largeText),
                "cooperative tokens differ for ${language.id}",
            )
        }
    }

    @Test
    fun `regex tokenizer yields while tokenizing a large document`() = runTest {
        assertYieldsWhileTokenizing { KotlinTokenizer.tokenizeFullCooperative(largeText) }
    }

    @Test
    fun `xml tokenizer yields while tokenizing a large document`() = runTest {
        assertYieldsWhileTokenizing { XmlTokenizer.tokenizeFullCooperative(largeText) }
    }

    @Test
    fun `a document under one chunk never yields`() = runTest {
        val tokens = KotlinTokenizer.tokenizeFull(snippet)
        assertTrue(tokens.size < COOPERATIVE_TOKENS_PER_YIELD, "snippet must fit in one chunk")

        var otherWorkRan = false
        launch { otherWorkRan = true }
        KotlinTokenizer.tokenizeFullCooperative(snippet)
        assertFalse(otherWorkRan, "a single-chunk pass should not give up the thread")
    }

    /**
     * runTest's dispatcher is single-threaded, like wasmJs: a coroutine launched beside the pass
     * can only make progress when the pass suspends, so its tick count shows that it did.
     */
    private suspend fun TestScope.assertYieldsWhileTokenizing(tokenize: suspend () -> List<Token>) {
        var ticks = 0
        val ticker = launch {
            while (true) {
                ticks++
                yield()
            }
        }
        val tokens = tokenize()
        ticker.cancel()

        val expectedYields = tokens.size / COOPERATIVE_TOKENS_PER_YIELD
        assertTrue(expectedYields >= 2, "test input too small: ${tokens.size} tokens")
        assertTrue(ticks >= expectedYields, "expected >= $expectedYields yields, other work ran $ticks times")
    }
}
