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
package com.aardarch.editor.ui

/**
 * Where [KeyboardToolbarRow] is rendered within [CodeEditorLayout].
 */
enum class KeyboardToolbarPlacement {
    /** Anchored just above the IME — slides in/out as the keyboard shows/hides. Default. */
    BottomHover,

    /** Always visible at the bottom of the editor column. */
    BottomFixed,

    /** Always visible at the top of the editor column, between any find panel and the editor body. */
    Top,

    /** Toolbar is not rendered. */
    Hidden,
}
