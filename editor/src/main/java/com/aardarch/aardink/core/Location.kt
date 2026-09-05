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

/**
 * Points to a position or range within a specific document or file URI.
 *
 * @param uri Document or file URI (e.g. `"file:///src/Main.kt"`).
 * @param range Document-absolute character range within [uri]. Only meaningful when [uri] is the
 *   caller's own document; for a target in a different, unopened file this is [IntRange.EMPTY]
 *   since offsets cannot be computed without that file's text — use [line] and [column] instead.
 * @param line 0-based line of the target's start position, when reported independent of [range]
 *   (e.g. by an external language server that only speaks line/column coordinates).
 * @param column 0-based UTF-16 column of the target's start position, when reported independent
 *   of [range].
 */
data class Location(val uri: String, val range: IntRange, val line: Int? = null, val column: Int? = null)
