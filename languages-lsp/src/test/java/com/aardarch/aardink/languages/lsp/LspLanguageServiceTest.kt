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

import com.aardarch.aardink.core.CodeDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LspLanguageServiceTest {

    @Test
    fun `didOpen sends didOpen notification`() = runBlocking {
        val transport = ChannelLspTransport()
        val client = LspClient(transport, CoroutineScope(Dispatchers.Default))
        val service = LspLanguageService(client, "file:///src/Main.kt", "kotlin")

        val doc = CodeDocument("fun main() {}")

        val job = launch {
            val sent = transport.sendChannel.receive()
            assertTrue(sent.contains("textDocument/didOpen"))
            assertTrue(sent.contains("file:///src/Main.kt"))
        }

        service.didOpen(doc)
        job.join()
        client.stop()
    }

    @Test
    fun `autoClose handles brackets and quotes`() {
        val transport = ChannelLspTransport()
        val client = LspClient(transport, CoroutineScope(Dispatchers.Default))
        val service = LspLanguageService(client, "file:///src/Main.kt", "kotlin")
        val doc = CodeDocument("")

        assertEquals("}", service.autoClose(doc, 0, '{'))
        assertEquals("]", service.autoClose(doc, 0, '['))
        assertEquals(")", service.autoClose(doc, 0, '('))
        assertEquals("\"", service.autoClose(doc, 0, '"'))

        client.stop()
    }

    @Test
    fun `hoverDoc delegates request to client`() = runBlocking {
        val transport = ChannelLspTransport()
        val client = LspClient(transport, CoroutineScope(Dispatchers.Default))
        val service = LspLanguageService(client, "file:///src/Main.kt", "kotlin")

        val doc = CodeDocument("val x = 1")

        val job = launch {
            val sent = transport.sendChannel.receive()
            assertTrue(sent.contains("textDocument/hover"))
            transport.receiveChannel.send("""{"jsonrpc":"2.0","id":1,"result":{"contents":"Doc"}}""")
        }

        val docResult = service.hoverDoc(doc, 4)
        assertNotNull(docResult)
        assertEquals("kotlin", docResult?.title)

        job.join()
        client.stop()
    }
}
