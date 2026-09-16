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

actual object PlatformInfo {
    actual val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    actual val hasSoftKeyboard: Boolean = false

    // Desktop always has a hardware keyboard, so the character toolbar is just clutter.
    actual val defaultToolbarPlacement: KeyboardToolbarPlacement = KeyboardToolbarPlacement.Hidden
}
