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

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** The sample app, as the benchmark build types build it (no `.debug` suffix). */
internal const val TARGET_PACKAGE = "com.aardarch.aardink.sample"

private const val WAIT_MS = 10_000L

/** From the start screen, opens the Kotlin sample in the editor. */
internal fun MacrobenchmarkScope.openKotlinEditor() {
    check(device.wait(Until.hasObject(By.text("Kotlin")), WAIT_MS)) { "the start screen has no Kotlin card" }
    device.findObject(By.text("Kotlin")).click()
    device.waitForIdle()
    // The editor's tokenizer and first frame.
    Thread.sleep(500)
}

/** What a user does first in an editor: puts the caret in the text, types, and scrolls down and back. */
internal fun MacrobenchmarkScope.typeAndScroll() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.click(width / 2, height / 2)
    device.waitForIdle()
    repeat(3) {
        device.executeShellCommand("input text val_x_=_compute")
        device.waitForIdle()
    }
    repeat(3) {
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 10)
        device.waitForIdle()
    }
    repeat(3) {
        device.swipe(width / 2, height / 4, width / 2, height * 3 / 4, 10)
        device.waitForIdle()
    }
}
