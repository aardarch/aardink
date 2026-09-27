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

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Tokens between looks at the clock: a clock read per token would cost more than it saves. */
internal const val COOPERATIVE_TOKENS_PER_CHECK: Int = 256

/**
 * How long a cooperative pass works before it lets the browser draw a frame: half a 60 Hz frame,
 * leaving the other half for the frame itself. A variable so tests can make every check suspend.
 */
internal var cooperativeSlice: Duration = 8.milliseconds

/**
 * Paces a cooperative tokenization pass: call [onProgress] with the running token count once per
 * scanner step. Every [COOPERATIVE_TOKENS_PER_CHECK] tokens it looks at the clock, and once the
 * pass has worked for [cooperativeSlice] since it last paused, it pauses again.
 *
 * It pauses with `delay`, not `yield`: in the browser, kotlinx.coroutines runs a batch of queued
 * tasks per browser task, and a coroutine that only yields resumes inside the same batch, before
 * any frame is drawn. A timer lets the browser paint first.
 */
internal class CooperativePacer {
    private var sliceStart = TimeSource.Monotonic.markNow()
    private var nextCheckAt = COOPERATIVE_TOKENS_PER_CHECK

    suspend fun onProgress(tokenCount: Int) {
        if (tokenCount < nextCheckAt) return
        nextCheckAt = tokenCount + COOPERATIVE_TOKENS_PER_CHECK
        if (sliceStart.elapsedNow() < cooperativeSlice) return
        delay(1)
        sliceStart = TimeSource.Monotonic.markNow()
    }
}
