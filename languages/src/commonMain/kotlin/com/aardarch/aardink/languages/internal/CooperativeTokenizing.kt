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
package com.aardarch.aardink.languages.internal

import kotlinx.coroutines.yield

/** Tokens emitted between suspension points — keeps one chunk well inside a 16 ms frame. */
internal const val COOPERATIVE_TOKENS_PER_YIELD: Int = 2_000

/**
 * Paces a cooperative tokenization pass: call [onProgress] with the running token count once per
 * scanner step, and it [yield]s each time another [every] tokens have been produced.
 *
 * Counting tokens rather than steps keeps the check to one comparison per step, and a step that
 * emits several tokens (a whole XML tag) can only overshoot a chunk, never skip a yield.
 */
internal class CooperativePacer(private val every: Int = COOPERATIVE_TOKENS_PER_YIELD) {
    private var nextYieldAt = every

    suspend fun onProgress(tokenCount: Int) {
        if (tokenCount >= nextYieldAt) {
            nextYieldAt = tokenCount + every
            yield()
        }
    }
}
