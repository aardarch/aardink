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
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.TextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NO_SYNC = "0"
private const val FULL_SYNC = "1"
private const val INCREMENTAL_SYNC = "2"

/** How long a whole-text server's edits wait for typing to pause. */
private const val DEBOUNCE_MILLIS = 400L

private const val DID_OPEN = "textDocument/didOpen"
private const val DID_CHANGE = "textDocument/didChange"
private const val DID_CLOSE = "textDocument/didClose"
private const val WILL_SAVE = "textDocument/willSave"
private const val DID_SAVE = "textDocument/didSave"
private const val COMPLETION = "textDocument/completion"

private fun withSync(sync: String, block: suspend Harness.() -> Unit) = withServer("") {
    serverDeclares("""{"textDocumentSync":$sync}""")
    block()
}

private fun CodeEditorState.insert(offset: Int, text: String) = applyTextEdits(listOf(TextEdit(offset until offset, text)))

private val JsonObject.version: Int get() = params["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int

private val JsonObject.changes: JsonArray get() = params["contentChanges"]!!.jsonArray

private fun JsonObject.expect(method: String, version: Int): JsonObject {
    assertEquals(method, this.method)
    assertEquals(version, this.version, "version of $method")
    return this
}

/** Where each line starts as the protocol counts lines, not as `CodeDocument` does: `\r` ends one too. */
private fun protocolLineStarts(text: String): List<Int> = buildList {
    add(0)
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char == '\n' || (char == '\r' && text.getOrNull(index) != '\n')) add(index)
    }
}

/** What a server does with `contentChanges`: applies them one after the other, a column clamped to its line. */
private fun applyChanges(text: String, changes: JsonArray): String = changes.fold(text) { current, element ->
    val change = element.jsonObject
    val newText = change["text"]!!.jsonPrimitive.content
    val range = change["range"]?.jsonObject ?: return@fold newText
    val starts = protocolLineStarts(current)
    fun offset(name: String): Int {
        val position = range[name]!!.jsonObject
        val line = position["line"]!!.jsonPrimitive.int
        val character = position["character"]!!.jsonPrimitive.int
        if (line >= starts.size) return current.length
        var end = starts.getOrElse(line + 1) { current.length }
        while (end > starts[line] && current[end - 1] in "\r\n") end--
        return (starts[line] + character).coerceAtMost(end)
    }
    current.replaceRange(offset("start"), offset("end"), newText)
}

/** Waits out the debounce and plays everything written since onto [text], as a server would. */
private fun Harness.replayed(text: String): String {
    advanceBy(DEBOUNCE_MILLIS)
    var replayed = text
    while (true) {
        val sent = transport.sendChannel.tryReceive().getOrNull() ?: return replayed
        replayed = applyChanges(replayed, LspJson.parseToJsonElement(sent).jsonObject.changes)
    }
}

/** Runs the handshake with no sync declared, and the server then registers [registrations], given as JSON. */
private suspend fun Harness.serverRegisters(vararg registrations: String) {
    serverAsks("1", "client/registerCapability", """{"registrations":[${registrations.joinToString()}]}""")
    nextSent()
}

private fun registration(method: String, options: String = "{}") = """{"id":"$method","method":"$method","registerOptions":$options}"""

private val JsonObject.openedText: String get() = params["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content

class LspDocumentSyncTest {

    // ── Incremental ──────────────────────────────────────────────────────────

    @Test
    fun `attach opens the document with its text`() = withSync(INCREMENTAL_SYNC) {
        service.attach(editorState("val a = 1"))

        val sent = nextSent()
        assertEquals(DID_OPEN, sent.method)
        val document = sent.params["textDocument"]!!.jsonObject
        assertEquals(1, document["version"]!!.jsonPrimitive.int)
        assertEquals("val a = 1", document["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an insertion is an empty range at its position`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("val a = 1\nval b = 2")
        service.attach(state)
        nextSent()

        state.insert(14, "bb")

        val change = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertEquals(range(1, 4, 1, 4), change["range"].toString())
        assertEquals("bb", change["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a deletion across lines ends at the old end, with no text`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("val a = 1\nval b = 2")
        service.attach(state)
        nextSent()

        state.applyTextEdits(listOf(TextEdit(8 until 13, "")))

        val change = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertEquals(range(0, 8, 1, 3), change["range"].toString())
        assertEquals("", change["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `loadText is one whole-text event with no range`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("old")
        service.attach(state)
        nextSent()

        state.loadText("brand\nnew")

        val change = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertNull(change["range"])
        assertEquals("brand\nnew", change["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `one edit at several places is one didChange, with one version for the batch`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("a\nb\nc")
        service.attach(state)
        nextSent()

        state.applyTextEdits(listOf(TextEdit(0 until 0, "1"), TextEdit(2 until 2, "2"), TextEdit(4 until 4, "3")))
        state.insert(0, "x")

        // ContentChange.version counts changes, so three here; the wire counts batches.
        val batch = nextSent().expect(DID_CHANGE, 2)
        assertEquals(3, batch.changes.size)
        nextSent().expect(DID_CHANGE, 3)
    }

    @Test
    fun `a server replaying every change in order ends up with the document's text`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("fun main() {\n    println(\"hi\")\n}\n")
        service.attach(state)
        var serverText = nextSent().params["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content
        var batches = 0

        fun edit(block: CodeEditorState.() -> Unit) {
            state.block()
            batches++
        }
        edit { insert(0, "// héllo 😀\n") }
        edit { applyTextEdits(listOf(TextEdit(0 until 3, "#"))) }
        edit { applyTextEdits(listOf(TextEdit(5 until 5, "a\nb\n"), TextEdit(9 until 14, ""), TextEdit(20 until 22, "é"))) }
        edit { undo() }
        edit { undo() }
        edit { redo() }
        edit { applyTextEdits(listOf(TextEdit(0 until state.document.length, "one\ntwo\n"))) }
        edit { insert(state.document.length, "😀") }
        edit { loadText("reset\ntext") }
        edit { applyTextEdits(listOf(TextEdit(4 until 7, ""))) }

        repeat(batches) { index ->
            val sent = nextSent().expect(DID_CHANGE, index + 2)
            serverText = applyChanges(serverText, sent.changes)
        }
        assertEquals(state.document.text, serverText)
    }

    // ── Line endings ─────────────────────────────────────────────────────────

    @Test
    fun `a lone carriage return sends the text whole, since the server sees another line there`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("a\rb\nc")
        service.attach(state)
        nextSent()

        state.insert(5, "X")
        assertNothingSent("the whole text waits for typing to pause")

        advanceBy(DEBOUNCE_MILLIS)
        val change = sentNow().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertNull(change["range"])
        assertEquals("a\rb\ncX", change["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ranges come back once the lone carriage return has gone`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("a\rb")
        service.attach(state)
        nextSent()

        state.applyTextEdits(listOf(TextEdit(1 until 2, "")))
        advanceBy(DEBOUNCE_MILLIS)
        assertNull(sentNow().expect(DID_CHANGE, 2).changes.single().jsonObject["range"])

        state.insert(2, "c")
        assertTrue("range" in sentNow().expect(DID_CHANGE, 3).changes.single().jsonObject)
    }

    @Test
    fun `a CRLF document stays on ranges`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("one\r\ntwo\r\n")
        service.attach(state)
        var serverText = nextSent().openedText

        state.insert(3, "!")
        state.insert(6, "?")
        state.insert(state.document.length, "\r\nend")

        repeat(3) { index ->
            val sent = sentNow().expect(DID_CHANGE, index + 2)
            assertTrue("range" in sent.changes.single().jsonObject)
            serverText = applyChanges(serverText, sent.changes)
        }
        assertEquals(state.document.text, serverText)
    }

    @Test
    fun `a server counting lines as the protocol does keeps the document's text`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("one\r\ntwo\rthree\n")
        service.attach(state)
        var serverText = nextSent().openedText

        fun edit(block: CodeEditorState.() -> Unit) {
            state.block()
            serverText = replayed(serverText)
            assertEquals(state.document.text, serverText)
        }
        edit { insert(state.document.length, "tail") }
        edit { applyTextEdits(listOf(TextEdit(8 until 9, ""))) }
        edit { insert(4, "between") }
        edit { undo() }
        edit { applyTextEdits(listOf(TextEdit(4 until 5, ""))) }
        edit { insert(2, "\r") }
        edit { insert(3, "\n") }
        edit { applyTextEdits(listOf(TextEdit(0 until 3, ""), TextEdit(0 until 0, "\r\n\r"))) }
        edit { loadText("x\r\ny") }
        edit { insert(1, "z") }
    }

    // ── Order on the wire ────────────────────────────────────────────────────

    @Test
    fun `a completion typed after a dot waits for its own didChange on the wire`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.hold(DID_CHANGE)

        state.insert(1, ".")
        val completion = async { service.completions(state.document.snapshot(), 2) }

        assertNothingSent("the didChange is stuck in the transport, and the request must not pass it")
        wire.release()
        val change = nextSent().expect(DID_CHANGE, 2)
        assertEquals(".", change.changes.single().jsonObject["text"]!!.jsonPrimitive.content)
        val position = respond(COMPLETION, "[]").params["position"]!!.jsonObject
        assertEquals(2, position["character"]!!.jsonPrimitive.int, "after the dot the didChange put there")
        completion.await()
    }

    @Test
    fun `an edit made after a request is not written ahead of it`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        state.insert(1, ".")
        val completion = async(start = CoroutineStart.UNDISPATCHED) { service.completions(state.document.snapshot(), 2) }
        state.insert(2, "y")

        // Written the other way round, the server would read column 2 against text it no longer has.
        nextSent().expect(DID_CHANGE, 2)
        respond(COMPLETION, "[]")
        nextSent().expect(DID_CHANGE, 3)
        completion.await()
    }

    @Test
    fun `waiting for an answer does not hold edits back`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        val completion = async { service.completions(state.document.snapshot(), 1) }
        assertEquals(COMPLETION, nextSent().method)
        state.insert(1, ".")

        nextSent().expect(DID_CHANGE, 2)
        completion.cancelAndJoin()
    }

    @Test
    fun `two requests are both in flight at once`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        val first = async { service.completions(state.document.snapshot(), 1) }
        val second = async { service.hoverDoc(state.document.snapshot(), 0) }

        assertEquals(COMPLETION, nextSent().method)
        assertEquals("textDocument/hover", nextSent().method, "the second does not wait for the first's answer")
        first.cancelAndJoin()
        second.cancelAndJoin()
    }

    @Test
    fun `a request cancelled before its turn does not stall the queue`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.hold(DID_CHANGE)

        state.insert(1, ".")
        val abandoned = async { service.completions(state.document.snapshot(), 2) }
        settle()
        abandoned.cancelAndJoin()
        state.insert(2, "y")
        wire.release()

        nextSent().expect(DID_CHANGE, 2)
        nextSent().expect(DID_CHANGE, 3)
        assertNothingSent("the request was cancelled before anything was written")
    }

    @Test
    fun `save notifications and the close come after the edits before them`() = withSync(
        """{"openClose":true,"change":2,"willSave":true,"save":true}""",
    ) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.hold(DID_CHANGE)

        state.insert(1, "a")
        val saving = async {
            service.willSave(LspSaveReason.Manual)
            service.didSave(state.document)
        }
        assertNothingSent("the didChange is stuck in the transport")
        wire.release()
        saving.await()

        nextSent().expect(DID_CHANGE, 2)
        assertEquals(WILL_SAVE, nextSent().method)
        assertEquals(DID_SAVE, nextSent().method)

        wire.hold(DID_CHANGE)
        state.insert(2, "b")
        val closing = async { service.didClose() }
        assertNothingSent("the didChange is stuck in the transport")
        wire.release()
        closing.await()

        nextSent().expect(DID_CHANGE, 3)
        assertEquals(DID_CLOSE, nextSent().method)
    }

    // ── Full ─────────────────────────────────────────────────────────────────

    @Test
    fun `a whole-text server hears nothing until typing pauses, and then once`() = withSync(FULL_SYNC) {
        val state = editorState("a")
        service.attach(state)
        nextSent()

        state.insert(1, "b")
        advanceBy(DEBOUNCE_MILLIS - 100)
        state.insert(2, "c")
        advanceBy(DEBOUNCE_MILLIS - 100)
        assertNothingSent("each edit starts the wait again")

        advanceBy(100)
        val change = sentNow().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertNull(change["range"])
        assertEquals("abc", change["text"]!!.jsonPrimitive.content)
        advanceBy(DEBOUNCE_MILLIS * 2)
        assertNothingSent("nothing has changed since")
    }

    @Test
    fun `a whole-text server's text survives everything an edit can be`() = withSync(FULL_SYNC) {
        val state = editorState("one\ntwo\nthree")
        service.attach(state)
        nextSent()

        state.applyTextEdits(listOf(TextEdit(0 until 3, "1"), TextEdit(4 until 7, "2"), TextEdit(8 until 8, "3 ")))
        state.undo()
        state.redo()
        state.insert(0, "😀\n")
        state.loadText("reset\n")
        state.insert(6, "tail")
        state.applyTextEdits(listOf(TextEdit(0 until 2, "")))

        advanceBy(DEBOUNCE_MILLIS)
        assertEquals("set\ntail", state.document.text)
        assertEquals(state.document.text, sentNow().changes.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a request does not wait out the debounce`() = withSync(FULL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        state.insert(1, ".")
        val completion = async { service.completions(state.document.snapshot(), 2) }

        val change = sentNow().expect(DID_CHANGE, 2)
        assertEquals("x.", change.changes.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(COMPLETION, sentNow().method)
        advanceBy(DEBOUNCE_MILLIS)
        assertNothingSent("the debounce has nothing left to send")
        completion.cancelAndJoin()
    }

    @Test
    fun `detach and didClose send what a whole-text server has not heard`() = withSync(FULL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        state.insert(1, "a")
        service.detach()
        assertEquals("xa", sentNow().expect(DID_CHANGE, 2).changes.single().jsonObject["text"]!!.jsonPrimitive.content)

        service.attach(state)
        state.insert(2, "b")
        service.didClose()
        assertEquals("xab", sentNow().expect(DID_CHANGE, 3).changes.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(DID_CLOSE, sentNow().method)
    }

    // ── textDocumentSync ─────────────────────────────────────────────────────

    @Test
    fun `a server that declared no sync hears nothing about the document`() {
        fun check(capabilities: String) = withServer("") {
            serverDeclares(capabilities)
            val state = editorState("x")

            service.attach(state)
            state.insert(1, "y")
            service.willSave(LspSaveReason.Manual)
            service.didSave(state.document)
            advanceBy(DEBOUNCE_MILLIS)
            service.didClose()

            assertNothingSent("for $capabilities")
        }
        check("""{"textDocumentSync":$NO_SYNC}""")
        check("""{"hoverProvider":true}""")
        check("""{"textDocumentSync":{}}""")
    }

    @Test
    fun `requests still go to a server that declared no sync`() = withSync(NO_SYNC) {
        val state = editorState("x")
        service.attach(state)
        state.insert(1, "y")

        val completion = async { service.completions(state.document.snapshot(), 2) }

        respond(COMPLETION, "[]")
        completion.await()
    }

    @Test
    fun `a host's own notifications reach a server that declared no sync`() = withSync(NO_SYNC) {
        service.didOpen(doc)
        service.didChange(doc, 2)
        service.didClose()

        assertEquals(DID_OPEN, nextSent().method)
        nextSent().expect(DID_CHANGE, 2)
        assertEquals(DID_CLOSE, nextSent().method)
    }

    @Test
    fun `didClose is left out only for a document attach did not open`() = withSync("""{"change":2}""") {
        service.didOpen(doc)
        assertEquals(DID_OPEN, nextSent().method)
        service.didClose()
        assertEquals(DID_CLOSE, nextSent().method)

        service.attach(editorState("x"))
        service.didClose()
        assertNothingSent("the server was never told it was open")
    }

    @Test
    fun `openClose without change opens and closes but sends no edits`() = withSync("""{"openClose":true,"change":0}""") {
        val state = editorState("x")

        service.attach(state)
        assertEquals(DID_OPEN, nextSent().method)
        state.insert(1, "y")
        assertNothingSent("the server asked for no changes")
        service.didClose()
        assertEquals(DID_CLOSE, nextSent().method)
    }

    @Test
    fun `change without openClose sends edits but no open or close`() = withSync("""{"change":2}""") {
        val state = editorState("x")

        service.attach(state)
        state.insert(1, "y")
        nextSent().expect(DID_CHANGE, 2)
        service.didClose()
        assertNothingSent("the server did not ask for openClose")
    }

    @Test
    fun `the options form's change kind is honoured as the number's is`() = withSync("""{"openClose":true,"change":1}""") {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        state.insert(1, "y")
        assertNothingSent("a whole-text server waits for typing to pause")
        advanceBy(DEBOUNCE_MILLIS)
        assertNull(sentNow().changes.single().jsonObject["range"])
    }

    @Test
    fun `capabilities not known count as whole-text sync with saves supported`() = withServer("") {
        val state = editorState("x")

        service.attach(state)
        assertEquals(DID_OPEN, nextSent().method)
        state.insert(1, "y")
        assertNothingSent("a range would corrupt a server that only takes whole documents")
        advanceBy(DEBOUNCE_MILLIS)
        assertNull(sentNow().changes.single().jsonObject["range"])

        service.willSave(LspSaveReason.Manual)
        assertEquals(WILL_SAVE, nextSent().method)
        service.didSave(state.document)
        assertNull(nextSent().params["text"])
    }

    // ── client/registerCapability ────────────────────────────────────────────

    @Test
    fun `sync registered instead of declared is followed`() = withServer("") {
        serverDeclares("{}")
        serverRegisters(
            registration(DID_OPEN),
            registration(DID_CLOSE),
            registration(DID_CHANGE, """{"syncKind":2}"""),
            registration(WILL_SAVE),
            registration(DID_SAVE, """{"includeText":true}"""),
        )
        val state = editorState("x")

        service.attach(state)
        assertEquals(DID_OPEN, nextSent().method)
        state.insert(1, "y")
        assertTrue("range" in nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject)
        service.willSave(LspSaveReason.Manual)
        assertEquals(WILL_SAVE, nextSent().method)
        service.didSave(state.document)
        assertEquals("xy", nextSent().params["text"]!!.jsonPrimitive.content)
        service.didClose()
        assertEquals(DID_CLOSE, nextSent().method)
    }

    @Test
    fun `a registered whole-text server is debounced, and one that gave no kind counts as that`() {
        fun check(options: String) = withServer("") {
            serverDeclares("{}")
            serverRegisters(registration(DID_OPEN), registration(DID_CHANGE, options))
            val state = editorState("x")
            service.attach(state)
            nextSent()

            state.insert(1, "y")
            assertNothingSent("for $options")
            advanceBy(DEBOUNCE_MILLIS)
            assertNull(sentNow().changes.single().jsonObject["range"])
        }
        check("""{"syncKind":1}""")
        check("{}")
    }

    @Test
    fun `when registrations disagree on the kind the whole text wins`() = withServer("") {
        serverDeclares("""{"textDocumentSync":$INCREMENTAL_SYNC}""")
        serverRegisters(registration(DID_CHANGE, """{"syncKind":1}"""))
        val state = editorState("x")
        service.attach(state)
        nextSent()

        state.insert(1, "y")

        assertNothingSent("a range could corrupt whichever document the registration was for")
    }

    @Test
    fun `sync registered after attach opens the document and follows it from there`() = withServer("") {
        serverDeclares("{}")
        val state = editorState("x")
        service.attach(state)
        state.insert(1, "y")
        assertNothingSent("the server has declared nothing yet")

        serverRegisters(registration(DID_OPEN), registration(DID_CHANGE, """{"syncKind":2}"""))
        state.insert(2, "z")

        val opened = nextSent()
        assertEquals(DID_OPEN, opened.method)
        assertEquals("xy", opened.openedText)
        assertEquals(1, opened.version)
        val change = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertEquals(range(0, 2, 0, 2), change["range"].toString())
    }

    @Test
    fun `change registered late for an open document starts from the whole text`() = withSync("""{"openClose":true}""") {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        state.insert(1, "y")

        serverRegisters(registration(DID_CHANGE, """{"syncKind":2}"""))
        state.insert(2, "z")
        advanceBy(DEBOUNCE_MILLIS)

        val catchUp = sentNow().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertEquals("xyz", catchUp["text"]!!.jsonPrimitive.content)
        state.insert(3, "!")
        assertTrue("range" in sentNow().expect(DID_CHANGE, 3).changes.single().jsonObject)
    }

    @Test
    fun `a registration taken back stops counting`() = withServer("") {
        serverDeclares("{}")
        serverRegisters(registration(WILL_SAVE))
        service.willSave(LspSaveReason.Manual)
        assertEquals(WILL_SAVE, nextSent().method)

        serverAsks("2", "client/unregisterCapability", """{"unregisterations":[{"id":"$WILL_SAVE","method":"$WILL_SAVE"}]}""")
        nextSent()
        service.willSave(LspSaveReason.Manual)

        assertNothingSent("the server took it back")
    }

    // ── willSave and didSave ─────────────────────────────────────────────────

    @Test
    fun `willSave carries the protocol's number for each reason`() = withSync("""{"willSave":true}""") {
        for ((reason, code) in listOf(LspSaveReason.Manual to 1, LspSaveReason.AfterDelay to 2, LspSaveReason.FocusOut to 3)) {
            service.willSave(reason)

            val sent = nextSent()
            assertEquals(WILL_SAVE, sent.method)
            assertEquals(code, sent.params["reason"]!!.jsonPrimitive.int)
            assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `willSave is only sent to a server that asked for it`() {
        fun check(sync: String) = withSync(sync) {
            service.willSave(LspSaveReason.Manual)
            assertNothingSent("for $sync")
        }
        check("""{"openClose":true,"change":2}""")
        check("""{"willSave":false,"save":true}""")
        check(INCREMENTAL_SYNC)
    }

    @Test
    fun `didSave carries the text only when the server's save options ask for it`() {
        fun check(sync: String, expected: String?) = withSync(sync) {
            service.didSave(CodeDocument("saved"))

            val sent = nextSent()
            assertEquals(DID_SAVE, sent.method)
            assertEquals(URI, sent.params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
            assertEquals(expected, sent.params["text"]?.jsonPrimitive?.content, "for $sync")
        }
        check("""{"save":true}""", null)
        check("""{"save":{}}""", null)
        check("""{"save":{"includeText":false}}""", null)
        check("""{"save":{"includeText":true}}""", "saved")
        check(FULL_SYNC, null)
        check(INCREMENTAL_SYNC, null)
    }

    @Test
    fun `didSave is only sent to a server that asked for it`() {
        fun check(sync: String) = withSync(sync) {
            service.didSave(CodeDocument("saved"))
            assertNothingSent("for $sync")
        }
        check("""{"openClose":true,"change":2}""")
        check("""{"save":false}""")
        check(NO_SYNC)
    }

    @Test
    fun `a save reaches a whole-text server after the text it saved`() = withSync("""{"change":1,"save":{"includeText":true}}""") {
        val state = editorState("x")
        service.attach(state)

        state.insert(1, "y")
        service.didSave(state.document)

        sentNow().expect(DID_CHANGE, 2)
        assertEquals("xy", sentNow().params["text"]!!.jsonPrimitive.content)
    }

    // ── Mixing attach with didOpen and didChange ─────────────────────────────

    @Test
    fun `didChange and didOpen refuse while attached, and work again after detach`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        assertFailsWith<IllegalStateException> { service.didChange(state.document, 9) }
        assertFailsWith<IllegalStateException> { service.didOpen(state.document) }
        assertNothingSent("a refused call sends nothing")

        service.detach()
        service.didChange(state.document, 9)
        nextSent().expect(DID_CHANGE, 9)
    }

    @Test
    fun `didOpen after detach does not open the document again`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        state.insert(1, "y")
        nextSent().expect(DID_CHANGE, 2)
        service.detach()

        service.didOpen(state.document)
        assertNothingSent("detach left it open, and the server has this text")

        state.insert(2, "z")
        service.didOpen(state.document)
        val catchUp = nextSent().expect(DID_CHANGE, 3).changes.single().jsonObject
        assertNull(catchUp["range"])
        assertEquals("xyz", catchUp["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a second didOpen from a host is not a second open`() = withSync(INCREMENTAL_SYNC) {
        service.didOpen(CodeDocument("a"))
        nextSent()

        service.didOpen(CodeDocument("a"))
        assertNothingSent("the server has this text")

        service.didOpen(CodeDocument("b"))
        nextSent().expect(DID_CHANGE, 2)
    }

    @Test
    fun `an edit made straight on the document before detach is caught up on`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        state.document.insert(1, "y")
        service.detach()
        service.attach(state)

        val catchUp = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertNull(catchUp["range"])
        assertEquals("xy", catchUp["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an edit made straight on the document before attach is not applied twice`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        state.addContentListener { }
        state.document.insert(1, "y")

        service.attach(state)
        val serverText = nextSent().openedText
        assertEquals("xy", serverText)
        state.insert(2, "z")

        assertEquals("xyz", applyChanges(serverText, nextSent().expect(DID_CHANGE, 2).changes))
        state.insert(3, "!")
        assertTrue("range" in nextSent().expect(DID_CHANGE, 3).changes.single().jsonObject, "and it is followed from there")
    }

    @Test
    fun `versions carry on from a host's own didChange`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.didOpen(state.document)
        service.didChange(state.document, 7)
        nextSent()
        nextSent()

        service.attach(state)
        state.insert(1, "y")

        nextSent().expect(DID_CHANGE, 8)
    }

    @Test
    fun `attaching a document already open and unchanged sends nothing`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.didOpen(state.document)
        nextSent()

        service.attach(state)

        assertNothingSent("the server has this text, and a second didOpen is a protocol error")
    }

    @Test
    fun `attaching a document edited since didOpen brings the server up to date`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.didOpen(state.document)
        nextSent()
        state.insert(1, "y")

        service.attach(state)
        state.insert(2, "z")

        val catchUp = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertNull(catchUp["range"])
        assertEquals("xy", catchUp["text"]!!.jsonPrimitive.content)
        assertTrue("range" in nextSent().expect(DID_CHANGE, 3).changes.single().jsonObject, "and it is followed from there")
    }

    @Test
    fun `detach stops following, and a second attach catches up on what was missed`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        service.detach()
        state.insert(1, "y")
        assertNothingSent("nothing is following the state")

        service.attach(state)
        val catchUp = nextSent().expect(DID_CHANGE, 2).changes.single().jsonObject
        assertEquals("xy", catchUp["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `detaching and attaching with nothing missed sends nothing`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        state.insert(1, "y")
        nextSent().expect(DID_CHANGE, 2)

        service.detach()
        service.attach(state)

        assertNothingSent("the server is up to date and the document is open")
    }

    @Test
    fun `attaching the same state twice follows it once`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        service.attach(state)
        nextSent()

        state.insert(1, "y")

        nextSent().expect(DID_CHANGE, 2)
        assertNothingSent("one listener, one didChange")
    }

    @Test
    fun `a second state must wait for detach`() = withSync(INCREMENTAL_SYNC) {
        service.attach(editorState("x"))
        nextSent()

        assertFailsWith<IllegalStateException> { service.attach(editorState("other")) }
    }

    @Test
    fun `detach with nothing attached does nothing`() = withSync(INCREMENTAL_SYNC) {
        service.detach()

        assertNothingSent("there was nothing to stop")
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    @Test
    fun `didClose stops following the state`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        service.didClose()
        assertEquals(DID_CLOSE, nextSent().method)
        state.insert(1, "y")

        assertNothingSent("the document is closed")
    }

    @Test
    fun `a closed document opens again from version one`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        state.insert(1, "y")
        nextSent().expect(DID_CHANGE, 2)
        service.didClose()
        nextSent()

        service.attach(state)
        state.insert(2, "z")

        val reopened = nextSent()
        assertEquals(DID_OPEN, reopened.method)
        assertEquals("xy", reopened.params["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, reopened.version)
        nextSent().expect(DID_CHANGE, 2)
    }

    @Test
    fun `an open right after a close still follows it on the wire`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.hold(DID_CLOSE)

        val closing = async(start = CoroutineStart.UNDISPATCHED) { service.didClose() }
        service.attach(state)
        assertNothingSent("the didClose is stuck in the transport, and the next session must not pass it")
        wire.release()
        closing.await()

        assertEquals(DID_CLOSE, nextSent().method)
        assertEquals(DID_OPEN, nextSent().method)
    }

    @Test
    fun `didClose reaches the server from a cancelled coroutine`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        val effect = launch {
            try {
                awaitCancellation()
            } finally {
                service.didClose()
            }
        }
        settle()

        effect.cancelAndJoin()

        assertEquals(DID_CLOSE, nextSent().method)
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    @Test
    fun `diagnostics for a version before the last batch are stale`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("val a = 1")
        service.attach(state)
        nextSent()
        state.insert(0, "x")
        nextSent().expect(DID_CHANGE, 2)

        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"stale"}]""", version = 1)
        assertTrue(service.diagnostics(state.document).isEmpty())

        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"current"}]""", version = 2)
        assertEquals("current", service.diagnostics(state.document).single().message)
    }

    @Test
    fun `attach listens for diagnostics again after a close`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("val a = 1")
        service.didClose()
        nextSent()

        service.attach(state)
        nextSent()
        publish(URI, """[{"range":${range(0, 0, 0, 1)},"message":"seen"}]""", version = 1)

        assertEquals("seen", service.diagnostics(state.document).single().message)
    }

    // ── The connection going away ────────────────────────────────────────────

    @Test
    fun `a stopped client fails requests instead of hanging them, and edits are harmless`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()

        client.stop()
        state.insert(1, "y")

        withTimeout(5_000) {
            assertTrue(service.completions(state.document.snapshot(), 2).isEmpty())
            service.willSave(LspSaveReason.Manual)
            service.didClose()
        }
    }

    @Test
    fun `a request waiting its turn is released when the client's scope is cancelled`() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val wire = HoldableLspTransport(ChannelLspTransport())
        val service = LspLanguageService(LspClient(wire, scope), URI, "kotlin")
        val state = inPlaceEditorState("x")
        service.attach(state)
        wire.hold(DID_OPEN)
        val request = async { service.completions(state.document.snapshot(), 1) }
        testScheduler.runCurrent()
        assertFalse(request.isCompleted, "the didOpen is stuck, so the request has not had its turn")

        scope.cancel()

        assertTrue(withTimeout(5_000) { request.await() }.isEmpty())
    }

    @Test
    fun `a request in the worker's hands is released when the scope is cancelled during its flush`() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        val wire = HoldableLspTransport(ChannelLspTransport())
        val service = LspLanguageService(LspClient(wire, scope), URI, "kotlin")
        val state = inPlaceEditorState("x")
        service.attach(state)
        testScheduler.runCurrent()
        wire.hold(DID_CHANGE)
        state.insert(1, "y")
        val request = async { service.completions(state.document.snapshot(), 2) }
        testScheduler.runCurrent()
        assertFalse(request.isCompleted, "the flush for its turn is stuck in the transport")

        scope.cancel()

        assertTrue(withTimeout(5_000) { request.await() }.isEmpty())
    }

    @Test
    fun `a request in the worker's hands is released when the client stops`() = withSync(FULL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.hold(DID_CHANGE)
        state.insert(1, "y")
        val request = async { service.completions(state.document.snapshot(), 2) }
        settle()
        assertFalse(request.isCompleted, "the flush for its turn is stuck in the transport")

        client.stop()

        assertTrue(withTimeout(5_000) { request.await() }.isEmpty())
    }

    @Test
    fun `a host's scope completes once the client has stopped or shut down`() {
        fun check(end: suspend LspClient.() -> Unit, use: suspend LspLanguageService.(CodeEditorState) -> Unit) = runTest {
            val state = inPlaceEditorState("x")
            withTimeout(60_000) {
                coroutineScope {
                    val client = LspClient(ChannelLspTransport(), this)
                    LspLanguageService(client, URI, "kotlin").use(state)
                    client.end()
                }
            }
        }
        val stop: suspend LspClient.() -> Unit = { stop() }
        val shutdown: suspend LspClient.() -> Unit = { shutdown() }
        for (end in listOf(stop, shutdown)) {
            check(end) { didOpen(it.document) }
            check(end) { attach(it) }
            check(end) {
                attach(it)
                it.insert(1, "y")
            }
            check(end) { willSave(LspSaveReason.Manual) }
        }
    }

    @Test
    fun `a document used after its client stopped leaves nothing running`() = runTest {
        withTimeout(5_000) {
            coroutineScope {
                val client = LspClient(ChannelLspTransport(), this)
                val service = LspLanguageService(client, URI, "kotlin")
                service.didOpen(CodeDocument("x"))
                client.stop()

                service.didClose()
                service.didOpen(CodeDocument("x"))
            }
        }
    }

    @Test
    fun `a write lost to the transport's own cancellation does not end the queue`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.loseNext(DID_CHANGE)

        state.insert(1, "a")
        assertNothingSent("that write was lost")
        state.insert(2, "b")
        assertNothingSent("the server is missing an edit, so a range would land in the wrong place")
        advanceBy(DEBOUNCE_MILLIS)

        val catchUp = sentNow().expect(DID_CHANGE, 3).changes.single().jsonObject
        assertNull(catchUp["range"])
        assertEquals("xab", catchUp["text"]!!.jsonPrimitive.content)
        state.insert(3, "c")
        assertTrue("range" in sentNow().expect(DID_CHANGE, 4).changes.single().jsonObject, "and it is back on ranges")
        service.didClose()
        assertEquals(DID_CLOSE, sentNow().method)
    }

    @Test
    fun `a write still lost at detach is caught up on at the next attach`() = withSync(INCREMENTAL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.loseNext(DID_CHANGE)
        state.insert(1, "y")
        settle()

        wire.loseNext(DID_CHANGE)
        service.detach()
        settle()
        service.attach(state)

        val catchUp = sentNow().changes.single().jsonObject
        assertNull(catchUp["range"])
        assertEquals("xy", catchUp["text"]!!.jsonPrimitive.content, "the mirror has this text, and the server does not")
    }

    @Test
    fun `a flush lost to the transport's own cancellation still gives a request its turn, in order`() = withSync(FULL_SYNC) {
        val state = editorState("x")
        service.attach(state)
        nextSent()
        wire.loseNext(DID_CHANGE)

        state.insert(1, "a")
        val completion = async(start = CoroutineStart.UNDISPATCHED) { service.completions(state.document.snapshot(), 2) }
        state.insert(2, "b")

        respond(COMPLETION, "[]")
        completion.await()
        advanceBy(DEBOUNCE_MILLIS)
        assertEquals("xab", sentNow().changes.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a client whose scope has ended does not hang anything`() = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        scope.cancel()
        val service = LspLanguageService(LspClient(ChannelLspTransport(), scope), URI, "kotlin")
        val state = inPlaceEditorState("x")

        withTimeout(5_000) {
            service.attach(state)
            state.insert(1, "y")
            assertTrue(service.completions(state.document.snapshot(), 2).isEmpty())
            service.didSave(state.document)
            service.detach()
            service.didClose()
        }
    }
}
