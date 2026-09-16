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
package com.aardarch.aardink.platform

import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import kotlinx.browser.window

actual object PlatformInfo {
    actual val isMacOs: Boolean = detectMacOs()

    actual val hasSoftKeyboard: Boolean = maxTouchPoints() > 0

    // BottomHover tracks WindowInsets.ime, which is always zero on web, so the toolbar would
    // never appear. Touch devices get a permanently visible one instead.
    actual val defaultToolbarPlacement: KeyboardToolbarPlacement =
        if (hasSoftKeyboard) KeyboardToolbarPlacement.BottomFixed else KeyboardToolbarPlacement.Hidden
}

private fun detectMacOs(): Boolean {
    val navigator = window.navigator
    // `platform` is deprecated but still the most reliable signal; userAgent is the fallback.
    val platform = navigator.platform.ifEmpty { navigator.userAgent }
    return platform.startsWith("Mac", ignoreCase = true)
}

private fun maxTouchPoints(): Int = window.navigator.maxTouchPoints.toInt()
