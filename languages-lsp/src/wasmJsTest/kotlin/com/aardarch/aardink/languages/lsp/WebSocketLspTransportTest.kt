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
package com.aardarch.aardink.languages.lsp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Karma serves these tests over HTTP but runs no WebSocket endpoint, so there is no way to
 * drive a successful session from here — the connection-refused path is what a browser test
 * can genuinely exercise, and it is the one that used to hang.
 *
 * The happy path is covered by `LspClientTest` against `ChannelLspTransport`, which is exactly
 * why that transport is in `commonMain`: everything above the socket is transport-agnostic.
 */
class WebSocketLspTransportTest {

    /** Discard-prefix port; nothing accepts here, so the socket errors almost immediately. */
    private val unreachable = "ws://127.0.0.1:1/lsp"

    @Test
    fun `connect to a refused endpoint fails instead of hanging`() = runTest {
        val failure = assertFailsWith<LspTransportException> {
            WebSocketLspTransport.connect(unreachable)
        }
        // Either the error event or the close-before-open event may win the race; both are
        // reported as a transport failure rather than left to hang the caller forever.
        assertTrue(
            failure.message.orEmpty().isNotEmpty(),
            "the failure should say what went wrong, got: ${failure.message}",
        )
    }

    @Test
    fun `a refused connect leaves nothing to clean up`() = runTest {
        // connect() closes the half-open socket on its way out, so a second attempt behaves
        // identically rather than being affected by the first.
        assertFailsWith<LspTransportException> { WebSocketLspTransport.connect(unreachable) }
        assertFailsWith<LspTransportException> { WebSocketLspTransport.connect(unreachable) }
    }
}
