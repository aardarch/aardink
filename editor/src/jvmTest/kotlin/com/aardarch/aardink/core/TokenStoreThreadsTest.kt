/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aardarch.aardink.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/** A pass that ends on another thread, which an unconfined scope lets it, as the first edit lands. */
class TokenStoreThreadsTest {

    @Test
    fun `an edit landing as the first pass ends finds its lines`() {
        // Busy cores are what stretch the moment the pass and the edit can meet in.
        val busy = List(Runtime.getRuntime().availableProcessors() / 2) {
            Thread {
                var spin = 0L
                while (!Thread.currentThread().isInterrupted) spin += spin xor 7
            }.apply {
                isDaemon = true
                start()
            }
        }
        try {
            val until = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < until) {
                val state = CodeEditorState("<a>x</a>", scope = CoroutineScope(Dispatchers.Unconfined))
                state.applyInput(2, 2, " ")
                assertEquals("<a >x</a>", state.text)
            }
        } finally {
            busy.forEach { it.interrupt() }
        }
    }
}
