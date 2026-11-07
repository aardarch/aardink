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
package com.aardarch.aardink.core

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Paces work on the UI's thread that grows with a language service's answer rather than with an
 * edit: call [onProgress] with the running count of steps. Every [STEPS_PER_CHECK] of them it looks
 * at the clock, and once the work has run for [slice] since it last paused, it pauses again: half a
 * 60 Hz frame by default, leaving the other half for the frame itself.
 *
 * It pauses with `delay`, not `yield`: in the browser, kotlinx.coroutines runs a batch of queued
 * tasks per browser task, and a coroutine that only yields resumes inside the same batch, before
 * any frame is drawn. A timer lets the browser paint first.
 */
internal class CooperativePacer(private val slice: Duration = 8.milliseconds) {
    private var sliceStart = TimeSource.Monotonic.markNow()
    private var nextCheckAt = STEPS_PER_CHECK

    suspend fun onProgress(steps: Int) {
        if (steps < nextCheckAt) return
        nextCheckAt = steps + STEPS_PER_CHECK
        if (sliceStart.elapsedNow() < slice) return
        delay(1)
        sliceStart = TimeSource.Monotonic.markNow()
    }

    companion object {
        /** Steps between looks at the clock: a clock read per step would cost more than it saves. */
        const val STEPS_PER_CHECK = 256
    }
}
