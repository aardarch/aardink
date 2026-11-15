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
package com.aardarch.aardink.sample.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android gate's measurements (docs/AARDINK_ROADMAP.md §8.4): a cold start, and the frames of
 * typing and scrolling in an editor. Compiled with the baseline profile when the app has one, as
 * an install from the Play Store would be. On an emulator these are only comparable with another
 * run on the same emulator, which is how CI uses them.
 */
@RunWith(AndroidJUnit4::class)
class SampleBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val compilation = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable)

    @Test
    fun startup() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun typeAndScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = 5,
        // No startup mode: COLD would kill the app between the setup and what is measured, and
        // the keys would go to the launcher. The setup starts it afresh itself.
        startupMode = null,
        setupBlock = {
            killProcess()
            startActivityAndWait()
            openKotlinEditor()
        },
    ) {
        typeAndScroll()
    }
}
