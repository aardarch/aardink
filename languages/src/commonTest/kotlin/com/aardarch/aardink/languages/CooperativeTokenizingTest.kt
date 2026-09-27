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
import com.aardarch.aardink.languages.internal.COOPERATIVE_TOKENS_PER_CHECK
import com.aardarch.aardink.languages.internal.cooperativeSlice
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import com.aardarch.aardink.languages.internal.xml.XmlTokenizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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

    // Enough that the regex and XML tokenizers each pass several clock checks (asserted below). Kept small on purpose: wasm runs the whole pass on the browser thread,
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

    @AfterTest
    fun restoreSlice() {
        cooperativeSlice = defaultSlice
    }

    private val defaultSlice = cooperativeSlice

    @Test
    fun `regex tokenizer yields while tokenizing a large document`() = runTest {
        assertYieldsWhileTokenizing { KotlinTokenizer.tokenizeFullCooperative(largeText) }
    }

    @Test
    fun `xml tokenizer yields while tokenizing a large document`() = runTest {
        assertYieldsWhileTokenizing { XmlTokenizer.tokenizeFullCooperative(largeText) }
    }

    @Test
    fun `a pass that fits in its time slice never pauses`() = runTest {
        cooperativeSlice = 1.hours
        var otherWorkRan = false
        launch { otherWorkRan = true }
        KotlinTokenizer.tokenizeFullCooperative(largeText)
        assertFalse(otherWorkRan, "a pass inside its slice should not give up the thread")
    }

    /**
     * runTest's dispatcher is single-threaded, like wasmJs: a coroutine launched beside the pass
     * can only make progress when the pass suspends, so its tick count shows that it did. A zero
     * time slice makes the pass pause at every clock check.
     */
    private suspend fun TestScope.assertYieldsWhileTokenizing(tokenize: suspend () -> List<Token>) {
        cooperativeSlice = Duration.ZERO
        var ticks = 0
        // Ticks on the test's virtual clock, which only moves while the pass is paused.
        val ticker = launch {
            while (true) {
                delay(1)
                ticks++
            }
        }
        val tokens = tokenize()
        ticker.cancel()

        val expectedYields = tokens.size / COOPERATIVE_TOKENS_PER_CHECK - 1
        assertTrue(expectedYields >= 2, "test input too small: ${tokens.size} tokens")
        assertTrue(ticks >= expectedYields, "expected >= $expectedYields yields, other work ran $ticks times")
    }
}
