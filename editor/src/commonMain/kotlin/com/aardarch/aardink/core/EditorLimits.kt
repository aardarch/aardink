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

import com.aardarch.aardink.platform.EditorDispatchers

/**
 * Document-size thresholds that keep a single-threaded host responsive.
 *
 * They only apply where [EditorDispatchers.computeIsMainThread] is true — wasmJs today, where
 * tokenization and folding share the one event loop with painting and input. On Android and JVM
 * those passes already run on a background pool, so these values are never consulted and
 * behaviour there does not depend on document size.
 *
 * Read on every tokenization and folding pass; set them once at startup, before any editor is
 * created, rather than while editors are live.
 */
object EditorLimits {
    /**
     * Above this many characters, tokenization runs through
     * [IncrementalTokenizer.tokenizeFullCooperative], which suspends between chunks so the host
     * keeps painting and handling input while a large document is highlighted.
     */
    var cooperativeTokenizeThresholdChars: Int = 64 * 1024

    /**
     * Above this many characters, tokenization and folding are skipped entirely and the document
     * renders in the plain text colour. Highlighting comes back once the document shrinks below
     * the limit again.
     */
    var plainTextFallbackChars: Int = 2 * 1024 * 1024
}
