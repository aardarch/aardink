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
import com.aardarch.aardink.languages.internal.cooperativeDiagnostics
import com.aardarch.aardink.languages.internal.cooperativeSlice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class CooperativeDiagnosticsTest {

    private val defaultSlice = cooperativeSlice
    private val defaultCooperative = cooperativeDiagnostics

    @AfterTest
    fun restore() {
        cooperativeSlice = defaultSlice
        cooperativeDiagnostics = defaultCooperative
    }

    // Large enough for several clock checks each, and each with one problem at the very end, so
    // the paced pass has to get all the way through to find the same list.
    private val samples = mapOf(
        "kotlin" to "fun f() { val s = \"text\" }\n".repeat(2_000) + "val open = \"never closed\n",
        "xml" to "<item id=\"1\">fun &amp; games</item>\n".repeat(2_000) + "<b>this & that</b>\n",
        "toml" to (0 until 2_000).joinToString("") { "[table$it]\nkey = \"value\"\n" } + "[table0]\nkey = \"again\"\n",
        "json" to "[" + (0 until 2_000).joinToString(",") { "{\"id\": $it, \"name\": \"x\"}" } + ",{\"id\": 1, \"id\": 2}]",
    )

    /**
     * runTest's dispatcher is single-threaded, like wasmJs: a coroutine launched beside the pass
     * only runs while the pass pauses, so its tick count shows that it did. A zero time slice
     * makes the pass pause at every clock check.
     */
    @Test
    fun `built-in diagnostics pause for the browser and find the same problems`() = runTest {
        for ((id, text) in samples) {
            val service = BuiltInLanguages.all.first { it.id == id }.languageService!!
            val document = CodeDocument(text)
            cooperativeDiagnostics = false
            val plain = service.diagnostics(document)
            assertTrue(plain.isNotEmpty(), "$id: the sample has a problem to find")

            cooperativeDiagnostics = true
            cooperativeSlice = Duration.ZERO
            var ticks = 0
            val ticker = launch {
                while (true) {
                    delay(1)
                    ticks++
                }
            }
            val paced = service.diagnostics(document)
            ticker.cancel()

            assertEquals(plain, paced, "$id: pausing changed what was found")
            assertTrue(ticks >= 2, "$id: other work ran only $ticks times during the pass")
        }
    }
}
