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

import androidx.compose.runtime.Stable

/**
 * The diagnostics on screen, kept on their text from one list to the next by [TrackedRanges]: a
 * squiggle stays under its word while the user types above it rather than drifting until the
 * next list arrives. A range keeps at least one character, as a squiggle needs one.
 */
@Stable
internal class DiagnosticsTracker :
    TrackedRanges<Diagnostic>(
        rangeOf = { it.range },
        withRange = { diagnostic, range -> diagnostic.copy(range = range) },
    ) {
    /** The tracked list, moved along with the edits made since it was given. */
    val diagnostics: List<Diagnostic> get() = items
}
