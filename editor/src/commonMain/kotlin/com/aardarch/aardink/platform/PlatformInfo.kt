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

/**
 * Static facts about the host platform that the editor UI has to branch on.
 *
 * Kept here because [expect]/[actual] declarations live only under this package; UI code reads
 * these properties rather than testing for a platform itself.
 */
expect object PlatformInfo {
    /**
     * True on macOS, where the primary shortcut modifier is Command rather than Control.
     * Always false where no hardware keyboard convention applies.
     */
    val isMacOs: Boolean

    /** True where text input normally comes from an on-screen keyboard that occludes the UI. */
    val hasSoftKeyboard: Boolean

    /** The [KeyboardToolbarPlacement] that suits this platform's input model. */
    val defaultToolbarPlacement: KeyboardToolbarPlacement
}
