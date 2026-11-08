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
package com.aardarch.aardink.sample.lsp

import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.languages.lsp.LspLanguageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CSS sample's whole path, which the screenshot tests leave out: this app's attach to the demo
 * server, and what the server pushes back. It only hears anything because of the sync it declares.
 */
class DemoCssLspServerTest {

    @Test
    fun `an attached editor gets the demo server's diagnostics for each edit`() = runTest {
        val client = createDemoCssLspConnection(backgroundScope)
        client.initialize(rootUri = null)
        val service = LspLanguageService(client, DEMO_CSS_DOCUMENT_URI, "css")
        val published = Channel<Int>(Channel.UNLIMITED)
        client.addDiagnosticsListener { _, _, diagnostics -> published.trySend(diagnostics.size) }
        // Empty until the dispatcher is set: text given here starts tokenizing on the real pool, and
        // with this scope that pass would write the tokens there while this test edits.
        val state = CodeEditorState(tokenizeDebounceMs = 0, scope = CoroutineScope(Dispatchers.Unconfined))
        state.computeDispatcher = Dispatchers.Unconfined
        state.loadText("a { color: red }")

        service.attach(state)
        assertEquals("nothing flagged in the text it opened with", 0, withTimeout(5_000) { published.receive() })

        state.applyTextEdits(listOf(TextEdit(14 until 14, " !important")))
        assertEquals("the edit reached the server", 1, withTimeout(5_000) { published.receive() })
        assertEquals("!important is flagged where it is", 15..24, service.diagnostics(state.document.snapshot()).single().range)

        service.didClose()
    }
}
