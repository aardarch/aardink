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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LspClientTest {

    @Test
    fun `sendRequest frames JSON and returns deferred response`() = runBlocking {
        val transport = ChannelLspTransport()
        val client = LspClient(transport, CoroutineScope(Dispatchers.Default))

        val job = launch {
            val sent = transport.sendChannel.receive()
            assertTrue(sent.contains("textDocument/completion"))
            transport.receiveChannel.send("""{"jsonrpc":"2.0","id":1,"result":[]}""")
        }

        val res = client.sendRequest("textDocument/completion", "{}")
        assertTrue(res.contains("\"result\":[]"))

        job.join()
        client.stop()
    }

    @Test
    fun `client notifies diagnostic callback on publishDiagnostics notification`() = runBlocking {
        val transport = ChannelLspTransport()
        val client = LspClient(transport, CoroutineScope(Dispatchers.Default))

        var notifiedUri: String? = null
        client.onDiagnosticsPublished = { uri, _ ->
            notifiedUri = uri
        }

        client.start()

        transport.receiveChannel.send(
            """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///Main.kt","diagnostics":[]}}""",
        )

        delay(100)
        assertEquals("file:///Main.kt", notifiedUri)

        client.stop()
    }
}
