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
 * An immutable, document-absolute span with a classification.
 *
 * [start] and [end] are character offsets into the full document text (not line-relative).
 * [end] is exclusive — the token covers `text[start, end)`.
 */
data class Token(val start: Int, val end: Int, val type: TokenType) {
    init {
        require(start >= 0) { "Token start must be ≥ 0, got $start" }
        require(end >= start) { "Token end must be ≥ start, got end=$end start=$start" }
    }

    val length: Int get() = end - start
    val isEmpty: Boolean get() = start == end
}
