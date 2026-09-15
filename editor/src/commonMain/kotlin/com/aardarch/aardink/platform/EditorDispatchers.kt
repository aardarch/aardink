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

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-appropriate coroutine dispatchers. `Dispatchers.IO` does not exist on wasmJs, and
 * `Dispatchers.Default` collapses onto the single JS event loop there — code that needs to pick a
 * dispatcher without hardcoding an Android/JVM assumption should use this object instead of
 * referring to `kotlinx.coroutines.Dispatchers` members directly.
 */
expect object EditorDispatchers {
    /** Off-main background work (tokenization, folding, find, LSP JSON handling). */
    val compute: CoroutineDispatcher

    /** Blocking I/O (LSP transports backed by streams or sockets). */
    val io: CoroutineDispatcher

    /** True when [compute] runs on the same thread as the UI — callers should chunk long work. */
    val computeIsMainThread: Boolean
}
