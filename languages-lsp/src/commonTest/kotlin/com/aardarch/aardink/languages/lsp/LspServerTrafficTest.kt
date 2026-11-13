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

import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val APPLY_EDIT = "workspace/applyEdit"
private const val PULL = "textDocument/diagnostic"
private const val RENAME = "textDocument/rename"
private const val PULL_DEBOUNCE_MILLIS = 400L
private const val CONTENT_MODIFIED = -32801

private const val FULL_SYNC = "\"textDocumentSync\":1"
private const val INCREMENTAL_SYNC = "\"textDocumentSync\":2"
private const val PULLING_SERVER = """{$INCREMENTAL_SYNC,"diagnosticProvider":{"identifier":"lint"}}"""

/** An edit whose other file gets `y`, and this document too unless [here] says otherwise. */
private fun renameAnswer(here: Boolean = true): String {
    val thisDocument = if (here) """"$URI":[{"range":${range(0, 4, 0, 5)},"newText":"y"}],""" else ""
    return """{"changes":{$thisDocument"$OTHER_URI":[{"range":${range(0, 0, 0, 1)},"newText":"y"}]}}"""
}

private fun fullReport(message: String) = """{"kind":"full","items":[{"range":${range(0, 0, 0, 1)},"message":"$message"}]}"""

private fun notification(method: String, params: String) = """{"jsonrpc":"2.0","method":"$method","params":$params}"""

private fun CodeEditorState.insert(offset: Int, text: String) = applyTextEdits(listOf(TextEdit(offset until offset, text)))

/** Remembers what it is told, in order, and applies edits as the test says. */
private class RecordingHost : LspClientHost {
    val told = mutableListOf<String>()
    val edits = mutableListOf<Pair<LspWorkspaceEdit, String?>>()
    var onApply: suspend () -> Boolean = { true }
    var onNotification: () -> Unit = {}

    override suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit, label: String?): Boolean {
        edits += edit to label
        return onApply()
    }

    override fun showMessage(type: Int, message: String) {
        onNotification()
        told += "show $type $message"
    }

    override fun logMessage(type: Int, message: String) {
        onNotification()
        told += "log $type $message"
    }

    override fun progress(token: JsonElement, value: JsonElement) {
        onNotification()
        told += "progress $token $value"
    }
}

private fun withHost(text: String = "val x = 1", block: suspend Harness.(RecordingHost) -> Unit) =
    RecordingHost().let { host -> withServer(text, host) { block(host) } }

/** Runs a rename the server answers with [answerJson], and returns what it left this document to do. */
private suspend fun Harness.renamed(answerJson: String): List<TextEdit> {
    val server = async { respond(RENAME, answerJson) }
    val edits = service.rename(doc, 4, "y")
    server.await()
    return edits
}

/** Closes the document from a coroutine already cancelled, as a host's `finally` can. Leaves the queue to catch up. */
private fun Harness.closeOnceCancelled(): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    cancel()
    service.didClose()
}

/** The text of a `didChange` that carries the whole document, as opposed to a range of it. */
private val JsonObject.wholeText: String
    get() {
        val change = params["contentChanges"]!!.jsonArray.single().jsonObject
        assertFalse("range" in change, "a range means something else to this server")
        return change["text"]!!.jsonPrimitive.content
    }

class LspServerTrafficTest {

    // ── workspace/applyEdit ──────────────────────────────────────────────────

    @Test
    fun `applyEdit hands the host the edit and its label and answers what the host says`() = withHost { host ->
        val edit = """{"changes":{"$OTHER_URI":[{"range":${range(0, 0, 0, 1)},"newText":"y"}]}}"""

        serverAsks("\"edit\"", APPLY_EDIT, """{"label":"Fix all","edit":$edit}""")

        val reply = nextSent()
        assertEquals("edit", reply["id"]!!.jsonPrimitive.content)
        assertTrue(reply["result"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean)
        assertFalse("failureReason" in reply["result"]!!.jsonObject)
        val (received, label) = host.edits.single()
        assertEquals("Fix all", label)
        assertEquals(listOf(LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 1)), "y")), received.changes!![OTHER_URI])
    }

    @Test
    fun `applyEdit keeps what documentChanges says for the host to read`() = withHost { host ->
        val changes = """[{"kind":"create","uri":"$OTHER_URI"}]"""

        serverAsks("1", APPLY_EDIT, """{"edit":{"documentChanges":$changes}}""")
        nextSent()

        val (received, label) = host.edits.single()
        assertNull(label)
        assertEquals(LspJson.parseToJsonElement(changes), received.documentChanges)
    }

    @Test
    fun `applyEdit does not claim what the host did not apply`() = withHost { host ->
        host.onApply = { false }

        serverAsks("1", APPLY_EDIT, """{"edit":{}}""")

        val result = nextSent()["result"]!!.jsonObject
        assertFalse(result["applied"]!!.jsonPrimitive.boolean)
        assertTrue("failureReason" in result)
    }

    @Test
    fun `applyEdit with a default host applies nothing and says so`() = withServer("") {
        serverAsks("1", APPLY_EDIT, """{"edit":{}}""")

        assertFalse(nextSent()["result"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an edit too malformed to read is refused without the host`() = withHost { host ->
        serverAsks("1", APPLY_EDIT, """{"edit":{"changes":{"$OTHER_URI":[{"newText":"no range"}]}}}""")
        assertFalse(nextSent()["result"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean)

        serverAsks("2", APPLY_EDIT, "{}")
        assertFalse(nextSent()["result"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean)

        assertTrue(host.edits.isEmpty())
    }

    @Test
    fun `a host that fails to apply is reported as an error and the connection survives`() = withHost { host ->
        host.onApply = { throw IllegalStateException("disk full") }

        serverAsks("1", APPLY_EDIT, """{"edit":{}}""")

        val reply = nextSent()
        assertFalse("result" in reply, "only the host can say it applied, and it did not")
        assertTrue("disk full" in reply["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)

        val server = async { respond("custom/stillThere", "1") }
        assertEquals(1, client.sendRequest("custom/stillThere")!!.jsonPrimitive.int)
        server.await()
    }

    @Test
    fun `applying an edit does not hold up the receive loop`() = withHost { host ->
        // On the receive loop this waits for a response only the receive loop can read.
        host.onApply = { client.sendRequest("custom/mayI")!!.jsonPrimitive.boolean }

        serverAsks("1", APPLY_EDIT, """{"edit":{}}""")
        respond("custom/mayI", "true")

        assertTrue(nextSent()["result"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean)
    }

    // ── Multi-file rename ────────────────────────────────────────────────────

    @Test
    fun `a rename reaching another file goes there through the host and here through the editor`() = withHost("val x = 1") { host ->
        val edits = renamed(renameAnswer())

        assertEquals(listOf(TextEdit(4..4, "y")), edits)
        val (elsewhere, label) = host.edits.single()
        assertEquals(setOf(OTHER_URI), elsewhere.changes!!.keys, "this document's edits are the editor's to apply")
        assertNull(label)
    }

    @Test
    fun `a rename in documentChanges form divides the same way`() = withHost("val x = 1") { host ->
        val other = """{"textDocument":{"uri":"$OTHER_URI","version":3},"edits":[{"range":${range(0, 0, 0, 1)},"newText":"y"}]}"""
        val here = """{"textDocument":{"uri":"$URI","version":2},"edits":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]}"""

        val edits = renamed("""{"documentChanges":[$here,$other]}""")

        assertEquals(listOf(TextEdit(4..4, "y")), edits)
        val elsewhere = host.edits.single().first
        assertNull(elsewhere.changes)
        assertEquals(LspJson.parseToJsonElement("[$other]"), elsewhere.documentChanges)
    }

    @Test
    fun `a rename whose every edit is elsewhere still reaches the host`() = withHost("val x = 1") { host ->
        assertTrue(renamed(renameAnswer(here = false)).isEmpty())

        assertEquals(1, host.edits.size)
    }

    @Test
    fun `a file operation on another file goes to the host with the edits around it`() = withHost("val x = 1") { host ->
        val create = """{"kind":"create","uri":"$OTHER_URI"}"""
        val here = """{"textDocument":{"uri":"$URI","version":2},"edits":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]}"""

        val edits = renamed("""{"documentChanges":[$create,$here]}""")

        assertEquals(listOf(TextEdit(4..4, "y")), edits)
        assertEquals(LspJson.parseToJsonElement("[$create]"), host.edits.single().first.documentChanges)
    }

    @Test
    fun `a rename inside this document does not trouble the host`() = withHost("val x = 1") { host ->
        val edits = renamed("""{"changes":{"$URI":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]}}""")

        assertEquals(listOf(TextEdit(4..4, "y")), edits)
        assertTrue(host.edits.isEmpty())
    }

    @Test
    fun `the editor gets nothing when the host could not apply the rest`() = withHost("val x = 1") { host ->
        host.onApply = { false }

        assertTrue(renamed(renameAnswer()).isEmpty(), "half a rename leaves references to a name that no longer exists")
    }

    @Test
    fun `a host that fails during a rename counts as not having applied it`() = withHost("val x = 1") { host ->
        host.onApply = { throw IllegalStateException("disk full") }
        assertTrue(renamed(renameAnswer()).isEmpty())

        host.onApply = { withTimeout(10) { CompletableDeferred<Boolean>().await() } }
        assertTrue(renamed(renameAnswer()).isEmpty(), "a timeout of the host's own is not the caller being cancelled")
    }

    @Test
    fun `an operation on this document itself is refused whole`() = withHost("val x = 1") { host ->
        val here = """{"textDocument":{"uri":"$URI","version":2},"edits":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]}"""

        val moved = renamed("""{"documentChanges":[$here,{"kind":"rename","oldUri":"$URI","newUri":"$OTHER_URI"}]}""")
        val deleted = renamed("""{"documentChanges":[$here,{"kind":"delete","uri":"$URI"}]}""")

        assertTrue(moved.isEmpty())
        assertTrue(deleted.isEmpty())
        assertTrue(host.edits.isEmpty(), "nothing can have been done elsewhere for a rename given up")
    }

    @Test
    fun `an edit with a part too malformed to read is refused whole`() = withHost("val x = 1") { host ->
        val other = """{"textDocument":{"uri":"$OTHER_URI","version":3},"edits":[]}"""

        val unreadableEntry = renamed("""{"documentChanges":[$other,"not an entry"]}""")
        val unreadableEdits = renamed("""{"documentChanges":[$other,{"textDocument":{"uri":"$URI","version":2},"edits":[{}]}]}""")

        assertTrue(unreadableEntry.isEmpty())
        assertTrue(unreadableEdits.isEmpty())
        assertTrue(host.edits.isEmpty())
    }

    @Test
    fun `an entry that is neither an edit nor a whole file operation refuses the rename`() = withHost("val x = 1") { host ->
        val here = """{"textDocument":{"uri":"$URI","version":2},"edits":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]}"""
        val unreadable = listOf(
            """{"textDocument":{"version":2},"edits":[]}""",
            """{"textDocument":"$OTHER_URI","edits":[]}""",
            """{"textDocument":{"uri":7},"edits":[]}""",
            """{"textDocument":{"uri":"$OTHER_URI"}}""",
            """{"textDocument":{"uri":"$OTHER_URI"},"edits":"none"}""",
            """{"textDocument":{"uri":"$OTHER_URI"},"edits":[{}]}""",
            """{"foo":1}""",
            """{"uri":"$OTHER_URI"}""",
            """{"kind":"move","uri":"$OTHER_URI"}""",
            """{"kind":"create"}""",
            """{"kind":"delete","uri":7}""",
            """{"kind":"rename","oldUri":"$OTHER_URI"}""",
            """{"kind":"rename","uri":"$OTHER_URI"}""",
        )

        for (entry in unreadable) {
            assertTrue(renamed("""{"documentChanges":[$here,$entry]}""").isEmpty(), "a host could skip $entry and say it applied")
        }

        assertTrue(host.edits.isEmpty())
    }

    @Test
    fun `documentChanges that is not a list refuses the rename and a null one is no change`() = withHost("val x = 1") { host ->
        val here = """"changes":{"$URI":[{"range":${range(0, 4, 0, 5)},"newText":"y"}]}"""

        assertTrue(renamed("""{$here,"documentChanges":{"kind":"delete","uri":"$OTHER_URI"}}""").isEmpty())
        assertTrue(renamed("""{$here,"documentChanges":"everything"}""").isEmpty())
        assertTrue(host.edits.isEmpty())

        assertEquals(listOf(TextEdit(4..4, "y")), renamed("""{$here,"documentChanges":null}"""))
    }

    @Test
    fun `the host is not asked once the text has moved on from what the server was told`() = withHost("") { host ->
        serverDeclares("""{$INCREMENTAL_SYNC,"renameProvider":true}""")
        val state = editorState("val x = 1")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        val renaming = async { service.rename(state.document.snapshot(), 4, "y") }
        val id = nextSent()["id"]!!.jsonPrimitive.int
        state.insert(0, "z")
        assertEquals("textDocument/didChange", nextSent().method)
        serverSends("""{"jsonrpc":"2.0","id":$id,"result":${renameAnswer()}}""")

        assertTrue(renaming.await().isEmpty(), "the editor drops edits for text that has moved on")
        assertTrue(host.edits.isEmpty(), "and the other files must not go without them")
    }

    @Test
    fun `a code action reaching another file is left out rather than sent to the host`() = withHost("val x = 1") { host ->
        val server = async {
            respond("textDocument/codeAction", """[{"title":"Fix everywhere","edit":${renameAnswer()}}]""")
        }

        assertTrue(service.codeActions(doc, 0..0).isEmpty())
        server.await()

        assertTrue(host.edits.isEmpty(), "listing an action is not choosing it")
    }

    @Test
    fun `a chosen code action reaching another file goes to the host, then this document's part`() = withHost("") { host ->
        serverDeclares("""{$INCREMENTAL_SYNC,"codeActionProvider":true}""")
        val state = editorState("val x = 1")
        service.attach(state)
        assertEquals("textDocument/didOpen", nextSent().method)

        val listing = async { service.codeActions(state.document.snapshot(), 0..0) }
        respond("textDocument/codeAction", """[{"title":"Fix everywhere","edit":${renameAnswer()}}]""")
        val action = listing.await().single()
        assertTrue(action.edits.isEmpty(), "this document's part waits for the host")
        assertTrue(host.edits.isEmpty(), "listing an action is not choosing it")

        action.onApply!!()

        assertEquals(1, host.edits.size)
        assertEquals("val y = 1", state.document.text)
    }

    @Test
    fun `a host that refuses the other files leaves this document as it was`() = withHost("") { host ->
        serverDeclares("""{$INCREMENTAL_SYNC,"codeActionProvider":true}""")
        host.onApply = { false }
        val state = editorState("val x = 1")
        service.attach(state)
        nextSent()

        val listing = async { service.codeActions(state.document.snapshot(), 0..0) }
        respond("textDocument/codeAction", """[{"title":"Fix everywhere","edit":${renameAnswer()}}]""")
        listing.await().single().onApply!!()

        assertEquals("val x = 1", state.document.text)
    }

    // ── Messages and progress ────────────────────────────────────────────────

    @Test
    fun `messages and progress reach the host in the order the server sent them`() = withHost { host ->
        client.start()
        serverSends(notification("window/showMessage", """{"type":1,"message":"broken"}"""))
        serverSends(notification("window/logMessage", """{"type":4,"message":"working"}"""))
        serverSends(notification("\$/progress", """{"token":7,"value":{"kind":"begin","title":"Indexing"}}"""))
        serverSends(notification("\$/progress", """{"token":"seven","value":{"kind":"end"}}"""))
        settle()

        assertEquals(
            listOf(
                "show 1 broken",
                "log 4 working",
                """progress 7 {"kind":"begin","title":"Indexing"}""",
                """progress "seven" {"kind":"end"}""",
            ),
            host.told,
        )
    }

    @Test
    fun `progress follows the create that announced its token`() = withHost { host ->
        serverAsks("\"create\"", "window/workDoneProgress/create", """{"token":"indexing"}""")
        val reply = nextSent()
        assertEquals("create", reply["id"]!!.jsonPrimitive.content)
        assertFalse("error" in reply)
        assertTrue(host.told.isEmpty())

        serverSends(notification("\$/progress", """{"token":"indexing","value":{"kind":"report","percentage":50}}"""))
        settle()

        assertEquals("""progress "indexing" {"kind":"report","percentage":50}""", host.told.single())
    }

    @Test
    fun `notifications too malformed to pass on are dropped`() = withHost { host ->
        client.start()
        serverSends(notification("window/showMessage", """{"message":"no type"}"""))
        serverSends(notification("window/showMessage", """{"type":"error","message":"not a type"}"""))
        serverSends(notification("window/logMessage", """{"type":3}"""))
        serverSends(notification("window/logMessage", "[]"))
        serverSends(notification("\$/progress", """{"value":{}}"""))
        serverSends(notification("\$/progress", """{"token":1}"""))
        serverSends(notification("\$/progress", """{"token":null,"value":{}}"""))
        settle()

        assertTrue(host.told.isEmpty(), "was ${host.told}")
    }

    @Test
    fun `a host that throws on a notification keeps the connection`() = withHost { host ->
        client.start()
        host.onNotification = { throw IllegalStateException("no window") }
        serverSends(notification("window/showMessage", """{"type":1,"message":"lost"}"""))
        settle()
        host.onNotification = { throw CancellationException("not the loop's own") }
        serverSends(notification("window/logMessage", """{"type":1,"message":"lost"}"""))
        settle()
        host.onNotification = {}
        serverSends(notification("window/logMessage", """{"type":3,"message":"heard"}"""))
        settle()

        assertEquals("log 3 heard", host.told.single())
    }

    @Test
    fun `a notification without a host goes nowhere`() = withServer("") {
        client.start()
        serverSends(notification("window/showMessage", """{"type":1,"message":"lost"}"""))
        serverSends(notification("\$/progress", """{"token":1,"value":{}}"""))

        val server = async { respond("custom/stillThere", "1") }
        assertEquals(1, client.sendRequest("custom/stillThere")!!.jsonPrimitive.int)
        server.await()
    }

    // ── positionEncoding ─────────────────────────────────────────────────────

    @Test
    fun `utf-16 said out loud or left out is what the adapter reads`() = withServer("val x = 1") {
        assertTrue(positionsAreUtf16(null))
        assertTrue(positionsAreUtf16(LspJson.parseToJsonElement("""{}""")))
        assertTrue(positionsAreUtf16(LspJson.parseToJsonElement("""{"positionEncoding":null}""")))
        assertTrue(positionsAreUtf16(LspJson.parseToJsonElement("""{"positionEncoding":"utf-16"}""")))
        assertFalse(positionsAreUtf16(LspJson.parseToJsonElement("""{"positionEncoding":"utf-8"}""")))
        assertFalse(positionsAreUtf16(LspJson.parseToJsonElement("""{"positionEncoding":"utf-32"}""")))
        assertFalse(positionsAreUtf16(LspJson.parseToJsonElement("""{"positionEncoding":16}""")))

        serverDeclares("""{"positionEncoding":"utf-16","hoverProvider":true,"renameProvider":true}""")
        assertTrue(service.supports("hoverProvider"))
        assertTrue(service.supportsRename)
    }

    @Test
    fun `a server counting positions another way is asked nothing about one`() = withHost { host ->
        serverDeclares(
            """{"positionEncoding":"utf-8","textDocumentSync":2,"hoverProvider":true,"completionProvider":{"resolveProvider":true},
                "definitionProvider":true,"referencesProvider":true,"documentFormattingProvider":true,"foldingRangeProvider":true,
                "renameProvider":{"prepareProvider":true},"codeActionProvider":true,"diagnosticProvider":{}}""",
        )

        assertFalse(service.supports("hoverProvider"))
        assertFalse(service.supports("diagnosticProvider"))
        assertFalse(service.supportsRename)
        assertFalse(serviceWithRenameSupport(LspRenameSupport.RenameWithPrepare).supportsRename, "whatever the host says")
        assertTrue(service.supports("textDocumentSync"))

        assertNull(service.hoverDoc(doc, 0))
        assertTrue(service.completions(doc, 0).isEmpty())
        assertNull(service.definition(doc, 0))
        assertTrue(service.references(doc, 0).isEmpty())
        assertEquals(doc.text, service.format(doc, FormattingOptions()))
        assertTrue(service.codeActions(doc, 0..0).isEmpty())
        assertNull(service.prepareRename(doc, 4))
        assertTrue(service.rename(doc, 4, "y").isEmpty())
        assertNull(serviceWithRenameSupport(LspRenameSupport.RenameWithPrepare).prepareRename(doc, 4))
        assertNothingSent("every position would be misread")
    }

    @Test
    fun `the host hears why a server has gone quiet`() = withHost { host ->
        serverDeclares("""{"positionEncoding":"utf-32"}""")

        assertEquals(1, host.told.size)
        assertTrue(host.told.single().startsWith("log 1 "), "as an error: was ${host.told}")
        assertTrue("utf-32" in host.told.single())
    }

    @Test
    fun `a server that keeps to utf-16 gives the host nothing to hear`() = withHost { host ->
        serverDeclares("""{"positionEncoding":"utf-16"}""")

        assertTrue(host.told.isEmpty())
    }

    @Test
    fun `a host that throws on hearing it does not stop the handshake`() = withHost { host ->
        host.onNotification = { throw IllegalStateException("no log") }

        serverDeclares("""{"positionEncoding":"utf-8"}""")

        assertFalse(service.supports("hoverProvider"))
    }

    @Test
    fun `edits go as whole text to a server counting positions another way`() = withServer("") {
        serverDeclares("""{"positionEncoding":"utf-8",$INCREMENTAL_SYNC}""")
        val state = editorState("val x = 1")
        service.attach(state)
        assertEquals("val x = 1", nextSent().params["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        state.insert(0, "😀")

        val change = nextSent()
        assertEquals("textDocument/didChange", change.method)
        assertEquals("😀val x = 1", change.wholeText)
    }

    @Test
    fun `applyEdit from a server counting positions another way is refused without the host`() = withHost { host ->
        serverDeclares("""{"positionEncoding":"utf-8"}""")

        serverAsks("1", APPLY_EDIT, """{"edit":{"changes":{"$OTHER_URI":[{"range":${range(0, 2, 0, 3)},"newText":"y"}]}}}""")
        val refused = nextSent()["result"]!!.jsonObject
        assertFalse(refused["applied"]!!.jsonPrimitive.boolean)
        assertTrue("encoding" in refused["failureReason"]!!.jsonPrimitive.content)

        serverAsks("2", APPLY_EDIT, """{"edit":{"documentChanges":[{"kind":"create","uri":"$OTHER_URI"}]}}""")
        assertFalse(nextSent()["result"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean)

        assertTrue(host.edits.isEmpty(), "byte offsets read as UTF-16 columns would land in the wrong place")
    }

    @Test
    fun `what such a server pushes stays on its line`() = withServer("val x = 1\nval y = 2") {
        serverDeclares("""{"positionEncoding":"utf-8"}""")

        publish(URI, """[{"range":${range(1, 4, 1, 500)},"message":"unused"}]""")

        val diagnostic = service.diagnostics(doc).single()
        assertEquals("unused", diagnostic.message)
        assertEquals(14..18, diagnostic.range, "a column past the end of the line stops there")
    }

    // ── Pull diagnostics ─────────────────────────────────────────────────────

    @Test
    fun `an opened document is asked about and the answer reaches every listener`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        val heard = mutableListOf<String>()
        client.addDiagnosticsListener { uri, version, diagnostics -> heard += "$uri $version ${diagnostics.map { it.message }}" }

        service.didOpen(doc)
        assertEquals("textDocument/didOpen", nextSent().method)
        val request = respond(PULL, fullReport("unused"))
        settle()

        assertEquals(URI, request.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals("lint", request.params["identifier"]!!.jsonPrimitive.content)
        assertEquals(listOf("$URI 1 [unused]"), heard)
        assertEquals("unused", service.diagnostics(doc).single().message)
    }

    @Test
    fun `typing is asked about once, when it pauses`() = withServer("") {
        serverDeclares(PULLING_SERVER)
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        respond(PULL, fullReport("first"))
        settle()

        state.insert(0, "a")
        advanceBy(PULL_DEBOUNCE_MILLIS - 100)
        state.insert(0, "b")
        advanceBy(PULL_DEBOUNCE_MILLIS - 100)
        assertEquals("textDocument/didChange", sentNow().method)
        assertEquals("textDocument/didChange", sentNow().method)
        assertNothingSent("typing has not paused for long enough")

        respond(PULL, fullReport("second"))
        settle()

        assertEquals("second", service.diagnostics(state.document).single().message)
    }

    @Test
    fun `a whole-text server is asked after it has the text`() = withServer("") {
        serverDeclares("""{$FULL_SYNC,"diagnosticProvider":{}}""")
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        respond(PULL, fullReport("first"))

        state.insert(0, "x")

        assertEquals("xval a = 1", nextSent().wholeText)
        respond(PULL, fullReport("second"))
    }

    @Test
    fun `a host's own didChange is asked about too`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()
        respond(PULL, fullReport("first"))

        service.didChange(doc, 5)

        assertEquals("textDocument/didChange", nextSent().method)
        respond(PULL, fullReport("second"))
        settle()
        assertEquals("second", service.diagnostics(doc).single().message)
    }

    @Test
    fun `a server that did not declare it is left to push`() = withServer("val a = 1") {
        serverDeclares("""{$INCREMENTAL_SYNC}""")
        service.didOpen(doc)
        nextSent()

        advanceBy(10 * PULL_DEBOUNCE_MILLIS)

        assertNothingSent("nothing to pull from")
    }

    @Test
    fun `capabilities not known count as a server to pull from`() = withServer("val a = 1") {
        assertTrue(service.supports("diagnosticProvider"))
        service.didOpen(doc)
        nextSent()

        respond(PULL, fullReport("pulled"))
        settle()

        assertEquals("pulled", service.diagnostics(doc).single().message)
    }

    @Test
    fun `a server that turns out to have no pull is not asked again`() = withServer("val a = 1") {
        service.didOpen(doc)
        nextSent()
        respondWithMethodNotFound(PULL)
        settle()

        service.didChange(doc, 2)
        assertEquals("textDocument/didChange", nextSent().method)
        advanceBy(10 * PULL_DEBOUNCE_MILLIS)

        assertNothingSent("it has said it has no such method")
    }

    @Test
    fun `capabilities that arrive in time call off a pull they do not allow`() = withServer("val a = 1") {
        service.didOpen(doc)
        nextSent()

        serverDeclares("""{$INCREMENTAL_SYNC}""")
        advanceBy(10 * PULL_DEBOUNCE_MILLIS)

        assertNothingSent("the server has said it pushes")
    }

    @Test
    fun `a pull registered later starts with the next edit`() = withServer("val a = 1") {
        serverDeclares("""{$INCREMENTAL_SYNC}""")
        service.didOpen(doc)
        nextSent()
        serverRegisters(registration(PULL))

        service.didChange(doc, 2)
        nextSent()

        respond(PULL, fullReport("registered"))
    }

    @Test
    fun `an answer about a version already overtaken is dropped`() = withServer("") {
        serverDeclares(PULLING_SERVER)
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        val id = nextSent()["id"]!!.jsonPrimitive.int

        // The edit is counted but still being written, so nothing has replaced the pull in flight.
        wire.hold("textDocument/didChange")
        state.insert(0, "x")
        settle()
        val heard = CompletableDeferred<Int?>()
        client.addDiagnosticsListener { _, version, _ -> heard.complete(version) }
        serverSends("""{"jsonrpc":"2.0","id":$id,"result":${fullReport("stale")}}""")

        assertEquals<Int?>(1, heard.await(), "a listener hears the version it was about")
        assertTrue(service.diagnostics(state.document).isEmpty(), "and a stale one goes no further here")
        wire.release()
    }

    @Test
    fun `an edit cancels the pull it has made pointless`() = withServer("") {
        serverDeclares(PULLING_SERVER)
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        val id = nextSent()["id"]!!.jsonPrimitive.int

        state.insert(0, "x")

        assertEquals("textDocument/didChange", nextSent().method)
        val cancel = nextSent()
        assertEquals("\$/cancelRequest", cancel.method)
        assertEquals(id, cancel.params["id"]!!.jsonPrimitive.int)
        respond(PULL, fullReport("current"))
    }

    @Test
    fun `an unchanged report or one without items leaves what is there`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()
        respond(PULL, fullReport("kept"))
        settle()

        service.didChange(doc, 2)
        nextSent()
        respond(PULL, """{"kind":"unchanged","resultId":"1"}""")
        settle()
        service.didChange(doc, 3)
        nextSent()
        respond(PULL, """{"kind":"full"}""")
        settle()
        service.didChange(doc, 4)
        nextSent()
        respond(PULL, """{"kind":"full","items":[{"message":"no range"}]}""")
        settle()

        assertEquals("kept", service.diagnostics(doc).single().message)
    }

    @Test
    fun `an empty report clears what was there`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()
        respond(PULL, fullReport("fixed"))
        settle()
        assertEquals("fixed", service.diagnostics(doc).single().message)

        service.didChange(doc, 2)
        nextSent()
        respond(PULL, """{"kind":"full","items":[]}""")
        settle()

        assertTrue(service.diagnostics(doc).isEmpty())
    }

    @Test
    fun `a server still starting up is asked again`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()

        respondWithError(PULL, CONTENT_MODIFIED)
        respond(PULL, fullReport("ready"))
        settle()

        assertEquals("ready", service.diagnostics(doc).single().message)
    }

    @Test
    fun `a server that fails is left alone until the next edit`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()

        respondWithError(PULL)
        advanceBy(10_000)

        assertNothingSent("nothing says it would answer any better")
    }

    @Test
    fun `closing the document calls off the pull`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()

        service.didClose()
        assertEquals("textDocument/didClose", nextSent().method)
        advanceBy(10 * PULL_DEBOUNCE_MILLIS)

        assertNothingSent("the server has no such document any more")
    }

    @Test
    fun `a cancelled close with a whole text still to send starts no pull`() = withServer("") {
        serverDeclares("""{$FULL_SYNC,"diagnosticProvider":{}}""")
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        respond(PULL, fullReport("first"))
        settle()
        state.insert(0, "x")
        settle()

        closeOnceCancelled().join()

        assertEquals("xval a = 1", sentNow().wholeText)
        assertEquals("textDocument/didClose", sentNow().method)
        advanceBy(10 * PULL_DEBOUNCE_MILLIS)
        assertNothingSent("the server has no such document any more")
    }

    @Test
    fun `a cancelled close with an edit still queued starts no pull`() = withServer("") {
        serverDeclares(PULLING_SERVER)
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        respond(PULL, fullReport("first"))
        settle()
        state.insert(0, "x")

        closeOnceCancelled().join()

        assertEquals("textDocument/didChange", sentNow().method)
        assertEquals("textDocument/didClose", sentNow().method)
        advanceBy(10 * PULL_DEBOUNCE_MILLIS)
        assertNothingSent("the server has no such document any more")
    }

    @Test
    fun `a document opened again is asked about again`() = withServer("val a = 1") {
        serverDeclares(PULLING_SERVER)
        service.didOpen(doc)
        nextSent()
        service.didClose()
        nextSent()

        service.didOpen(doc)
        nextSent()

        respond(PULL, fullReport("reopened"))
        settle()
        assertEquals("reopened", service.diagnostics(doc).single().message)
    }

    @Test
    fun `listeners hear one delivery at a time whoever makes it`() = withServer("val a = 1") {
        val heard = mutableListOf<String>()
        var running = 0
        var most = 0
        client.addDiagnosticsListener { uri, _, _ ->
            running++
            most = maxOf(most, running)
            heard += uri
            // Arrives during this call, as a pulled answer can on another thread of the client's scope.
            if (uri == OTHER_URI) launch(start = CoroutineStart.UNDISPATCHED) { client.deliverDiagnostics(URI, null, emptyList()) }
            running--
        }

        publish(OTHER_URI, "[]")
        settle()

        assertEquals(listOf(OTHER_URI, URI), heard)
        assertEquals(1, most)
    }
}
