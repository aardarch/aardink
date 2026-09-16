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
package com.aardarch.aardink.ui

/**
 * Stable `testTag` values for the editor's own UI tests.
 *
 * `internal`, so they are not part of the published API and hosts cannot depend on them. They
 * exist because matching on rendered text is too brittle for a code editor, where the text under
 * test is arbitrary user content.
 */
internal object EditorTestTags {
    const val TEXT_FIELD = "aardink.textField"
    const val FIND_PANEL = "aardink.find"
    const val GUTTER = "aardink.gutter"
}
