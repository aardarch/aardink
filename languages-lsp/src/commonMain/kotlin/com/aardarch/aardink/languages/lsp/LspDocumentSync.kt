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
import com.aardarch.aardink.core.ContentChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile

/** LSP's `TextDocumentSyncKind`: how a server wants to hear about edits. */
internal enum class LspChangeSync { None, Full, Incremental }

/**
 * What a server's `textDocumentSync` capability asks of the client, with what it registered since.
 *
 * The capability is a `TextDocumentSyncKind` number or a `TextDocumentSyncOptions` object, and left
 * out it means none. The number form reads as VS Code's client reads it: open and close, changes of
 * that kind, and `didSave` without the text, but no `willSave`.
 */
internal data class LspTextDocumentSync(
    val openClose: Boolean = false,
    val change: LspChangeSync = LspChangeSync.None,
    val willSave: Boolean = false,
    val didSave: Boolean = false,
    val saveIncludesText: Boolean = false,
) {
    /**
     * [registration] counts as supported, as the ledger has it. Its `documentSelector` is not read,
     * so when two disagree on the kind of change the narrower wins: a whole text is something any
     * server that syncs can read, where a range would corrupt one that only takes whole documents.
     */
    private fun with(registration: LspRegistration): LspTextDocumentSync = when (registration.method) {
        "textDocument/didOpen", "textDocument/didClose" -> copy(openClose = true)

        "textDocument/didChange" -> {
            val kind = registration.options?.get("syncKind")?.let(::changeSyncOf) ?: LspChangeSync.Full
            copy(change = if (change == LspChangeSync.None || kind == LspChangeSync.None) maxOf(change, kind) else minOf(change, kind))
        }

        "textDocument/willSave" -> copy(willSave = true)

        "textDocument/didSave" -> copy(
            didSave = true,
            saveIncludesText =
            saveIncludesText || registration.options?.flag("includeText") == true,
        )

        else -> this
    }

    companion object {
        /**
         * Capabilities not known count as supported, as everywhere in this module. Full is the one
         * form every server that syncs at all can read, where a range would corrupt the text of a
         * server that only takes whole documents.
         */
        private val Unknown = LspTextDocumentSync(openClose = true, change = LspChangeSync.Full, willSave = true, didSave = true)

        fun from(capabilities: JsonElement?, registrations: List<LspRegistration> = emptyList()): LspTextDocumentSync {
            val declared = capabilities as? JsonObject ?: return Unknown
            val initial = when (val sync = declared["textDocumentSync"]) {
                is JsonObject -> fromOptions(sync)
                is JsonPrimitive -> fromKind(changeSyncOf(sync))
                else -> LspTextDocumentSync()
            }
            val sync = registrations.fold(initial) { sync, registration -> sync.with(registration) }
            // A range would mean something else to the server. A whole text means the same to any.
            val readable = sync.change != LspChangeSync.Incremental || positionsAreUtf16(declared)
            return if (readable) sync else sync.copy(change = LspChangeSync.Full)
        }

        private fun fromKind(kind: LspChangeSync): LspTextDocumentSync =
            if (kind == LspChangeSync.None) LspTextDocumentSync() else LspTextDocumentSync(openClose = true, change = kind, didSave = true)

        private fun fromOptions(options: JsonObject): LspTextDocumentSync {
            val save = options["save"]
            return LspTextDocumentSync(
                openClose = options.flag("openClose"),
                change = changeSyncOf(options["change"]),
                willSave = options.flag("willSave"),
                didSave = save is JsonObject || (save as? JsonPrimitive)?.booleanOrNull == true,
                saveIncludesText = (save as? JsonObject)?.flag("includeText") == true,
            )
        }

        private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

        private fun changeSyncOf(kind: JsonElement?): LspChangeSync = when ((kind as? JsonPrimitive)?.intOrNull) {
            1 -> LspChangeSync.Full
            2 -> LspChangeSync.Incremental
            else -> LspChangeSync.None
        }
    }
}

/**
 * The attached text as the batches so far left it. Private to the queue, so it is safe to read
 * there, and built from the batches, so its text is exactly the one the next version number stands
 * for. The live document offers neither.
 *
 * It also knows when a range would not mean the same to the server. [CodeDocument] breaks lines at
 * `\n` alone, where the protocol also counts a `\r` of its own as a line ending, and a position
 * between the two halves of a `\r\n` is one a server cannot express. Everywhere else the two agree,
 * `\r\n` documents included.
 */
private class TextMirror(text: String) {
    private val document = CodeDocument(text)
    private var loneReturns = loneReturnsIn(0, document.length)

    val text: String
        get() = document.text

    /** Whether [change], about to be applied, can go to the server as a range. */
    fun canAddress(change: ContentChange): Boolean = change.isReset ||
        (loneReturns == 0 && !splitsLineBreak(change.offset) && !splitsLineBreak(change.offset + change.deletedLength))

    fun apply(change: ContentChange) {
        if (change.isReset) {
            document.replaceAll(change.insertedText)
            loneReturns = loneReturnsIn(0, document.length)
            return
        }
        // A `\r` just before the change is the only one outside it whose next character changes.
        val from = (change.offset - 1).coerceAtLeast(0)
        loneReturns -= loneReturnsIn(from, change.offset + change.deletedLength)
        document.delete(change.offset, change.deletedLength)
        document.insert(change.offset, change.insertedText)
        loneReturns += loneReturnsIn(from, change.offset + change.insertedText.length)
    }

    private fun splitsLineBreak(offset: Int) = offset in 1 until document.length && document[offset - 1] == '\r' && document[offset] == '\n'

    private fun loneReturnsIn(from: Int, until: Int) = (from until until).count {
        document[it] == '\r' && (it + 1 == document.length || document[it + 1] != '\n')
    }
}

/**
 * Keeps a server's copy of one document in step, for [LspLanguageService].
 *
 * Everything a document sends goes through one first-in, first-out queue, drained by one coroutine
 * on the client's scope. That is what keeps the wire in the order things happened: the content
 * listener is synchronous and a send suspends, so a request made straight after an edit would
 * otherwise overtake its own `didChange`. A request takes a turn in the queue and holds it until it
 * is written ([inOrder]), so an edit before it is on the wire first and an edit after it is not
 * written ahead of it, even though the answer is not waited for.
 *
 * The queue's coroutine owns all the state below that is not marked otherwise, so none of it is
 * locked. It never reads the attached document, which is not safe to read off the thread that edits
 * it: what the server should have comes from [mirror] instead.
 *
 * What a server declared decides what following a state sends, and is read for each thing the queue
 * takes up, since a registration can arrive at any time. A host's own [open] and [sendWholeText] are
 * sent whatever it declared, as they always were: a host calling them has said what it wants.
 *
 * [onTextSent] runs on the queue each time a text or a change of it has been written, and must
 * return at once.
 *
 * The queue lives from the first thing sent until [close] or the end of the client. A later session
 * gets a new queue, which starts once the last one has drained.
 */
internal class LspDocumentSync(
    private val client: LspClient,
    private val documentUri: String,
    private val languageId: String,
    private val onTextSent: () -> Unit = {},
) {

    private sealed interface Item {
        /** The queue is gone and this will never run: release whoever is waiting for it. */
        fun dropped() = Unit
    }

    private class Batch(val changes: List<ContentChange>) : Item

    private data object DebounceElapsed : Item

    private class Send(val block: suspend () -> Unit) : Item {
        val done = CompletableDeferred<Unit>()

        override fun dropped() {
            done.complete(Unit)
        }
    }

    private class Turn : Item {
        val reached = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()

        override fun dropped() {
            reached.complete(Unit)
        }
    }

    @Volatile
    private var queue: Channel<Item>? = null

    @Volatile
    private var worker: Job? = null

    /** Only touched by the host's calls, on the thread that edits the attached state. */
    @Volatile
    private var attached: CodeEditorState? = null

    /** The document's length at [attach] until the first batch since, on that thread too. */
    private var lengthAtAttach = NOT_ATTACHING

    /** The document version last sent; 0 when the document is not open. Written by the queue only. */
    @Volatile
    var sentVersion: Int = 0
        private set

    val isAttached: Boolean
        get() = attached != null

    /** Open as far as the host is concerned, whether or not the server was told. */
    private val opened = MutableStateFlow(false)

    /** Following a state opened the document for a server that had not asked to hear of it. */
    private var openWithheld = false

    /** How [Batch]es are sent, from what the server has declared so far. */
    private var changeSync = LspChangeSync.None

    /**
     * Moves with every change to the text [textAt] reads. Written by the queue only, so an
     * [inOrder] block, which the queue waits for, can read it to name the text it is asking about.
     */
    @Volatile
    var textRevision: Long = 0
        private set

    /** The text the server has while no listener is following it, to catch up from at [attach]. */
    private var lastText: String? = null
        set(value) {
            field = value
            textRevision += 1
        }

    /** What the server should have while a state is followed. */
    private var mirror: TextMirror? = null

    /** The server may not have [mirror]'s text, and the next thing written must be all of it. */
    private var unsent = false
    private var debounce: Job? = null

    private val contentListener: (List<ContentChange>) -> Unit = { changes -> queue?.trySend(Batch(sinceAttach(changes))) }

    /**
     * [changes] without what [attach] already sent. A change made straight on the document stays
     * pending in the state until its next edit if something else is listening, so it can reach this
     * listener with the first batch although it was in the text at [attach]. The versions that would
     * tell it apart cannot be compared to the document's, which is not public, but the length gives
     * it away, and then the whole text is told again rather than the change a second time.
     */
    private fun sinceAttach(changes: List<ContentChange>): List<ContentChange> {
        val expected = lengthAtAttach
        lengthAtAttach = NOT_ATTACHING
        val document = attached?.document
        if (expected == NOT_ATTACHING || document == null) return changes
        if (expected + changes.sumOf { it.insertedText.length - it.deletedLength } == document.length) return changes
        return listOf(changes.last().copy(insertedText = document.text, isReset = true))
    }

    /**
     * Follows [state]. The text and the listener are taken together on the caller's thread, the one
     * edits happen on, so that no edit can fall between what the server is sent here and the first
     * batch.
     */
    fun attach(state: CodeEditorState) {
        if (state === attached) return
        check(attached == null) { "$documentUri is already attached to an editor state; detach it first" }
        val text = state.document.text
        attached = state
        lengthAtAttach = text.length
        enqueue(Send { follow(text) })
        state.addContentListener(contentListener)
    }

    private suspend fun follow(text: String) {
        val sync = syncOptions()
        changeSync = sync.change
        mirror = TextMirror(text)
        when {
            !opened.value -> if (sync.openClose) {
                sendOpen(text)
            } else {
                opened.value = true
                openWithheld = true
                sentVersion = 1
            }

            text != lastText -> if (sync.change != LspChangeSync.None) sendChanges(wholeText(text))
        }
        lastText = null
    }

    /** Stops following. Edits already reported still reach the server, ahead of anything sent after. */
    fun detach() {
        if (!stopFollowing()) return
        enqueue(
            Send {
                // What was sent and not what the document says: a change made straight on it can be
                // in the live text with no report of it ever coming, and only [attach] can tell.
                lastText = if (unsent) null else mirror?.text
                mirror = null
            },
        )
    }

    private fun stopFollowing(): Boolean {
        val state = attached ?: return false
        attached = null
        state.removeContentListener(contentListener)
        return true
    }

    /**
     * A document still open is not opened twice, which is a protocol error: its text goes as a
     * change instead, if it is not what the server has.
     */
    suspend fun open(text: String) = submit(
        Send {
            when {
                !opened.value || openWithheld -> sendOpen(text)

                text != lastText -> {
                    lastText = text
                    sendChanges(wholeText(text))
                }
            }
        },
    )

    suspend fun sendWholeText(text: String, version: Int) = submit(
        Send {
            lastText = text
            sentVersion = version
            sendDidChange(version, wholeText(text))
        },
    )

    /**
     * Ends the session, and with it this queue. Reaches the server even if the caller is cancelled.
     * The server is told unless following a state is all that opened the document, for a server that
     * did not ask to hear of it. Stops following on the caller's thread, without reading the text.
     */
    suspend fun close() {
        stopFollowing()
        val closing = queue ?: newQueue()
        queue = null
        val closed = Send {
            val announced = !openWithheld
            opened.value = false
            openWithheld = false
            lastText = null
            sentVersion = 0
            mirror = null
            unsent = false
            debounce?.cancel()
            if (announced) {
                client.sendNotification(
                    "textDocument/didClose",
                    LspJson.encodeToJsonElement(
                        LspDidCloseTextDocumentParams.serializer(),
                        LspDidCloseTextDocumentParams(LspTextDocumentIdentifier(documentUri)),
                    ),
                )
            }
        }
        val queued = closing.trySend(closed).isSuccess
        closing.close()
        if (queued) closed.done.await()
    }

    suspend fun willSave(reason: LspSaveReason) = submit(
        Send {
            if (syncOptions().willSave) {
                client.sendNotification(
                    "textDocument/willSave",
                    buildJsonObject {
                        putJsonObject("textDocument") { put("uri", documentUri) }
                        put("reason", reason.code)
                    },
                )
            }
        },
    )

    /** [document] is only read when the server asked for the text, and on the caller's thread. */
    suspend fun didSave(document: CodeDocument) {
        val sync = syncOptions()
        if (!sync.didSave) return
        val text = if (sync.saveIncludesText) document.text else null
        submit(
            Send {
                client.sendNotification(
                    "textDocument/didSave",
                    buildJsonObject {
                        putJsonObject("textDocument") { put("uri", documentUri) }
                        if (text != null) put("text", text)
                    },
                )
            },
        )
    }

    /**
     * Runs [block] in the document's order: after every edit reported so far, including a whole-text
     * server's debounced ones, and with no later edit written until [block] calls the function it
     * is given, or ends. Requests pass their [block] on to [LspClient.sendRequest] so the queue waits
     * for the write and not the answer.
     *
     * With no queue there is nothing to be ordered against, and a queue that has gone with the
     * client lets [block] find out for itself that the connection is closed.
     */
    suspend fun <T> inOrder(block: suspend (written: () -> Unit) -> T): T {
        val turn = Turn()
        if (queue?.trySend(turn)?.isSuccess != true) return block {}
        try {
            turn.reached.await()
            return block { turn.written.complete(Unit) }
        } finally {
            // A caller cancelled before it wrote anything must not hold everyone else up.
            turn.written.complete(Unit)
        }
    }

    /**
     * The text at [revision], which is what the positions in an answer to a request of that revision
     * are about, or null when it has moved on since, or the server was given none. Whole-document
     * work, so only for answers that need it.
     */
    suspend fun textAt(revision: Long): String? {
        if (queue == null) return null
        var text: String? = null
        submit(Send { if (textRevision == revision) text = mirror?.text ?: lastText })
        return text
    }

    /**
     * Returns once the document is open, through [attach] or [open], whether or not the server
     * asked to be told. Until then this waits for as long as the caller does.
     */
    suspend fun awaitOpen() {
        opened.first { it }
    }

    private fun syncOptions() = LspTextDocumentSync.from(client.serverCapabilities, client.registrations)

    private suspend fun submit(item: Send) {
        if (enqueue(item)) item.done.await()
    }

    private fun enqueue(item: Item): Boolean = (queue ?: newQueue()).trySend(item).isSuccess

    private fun newQueue(): Channel<Item> {
        val channel = Channel<Item>(Channel.UNLIMITED, onUndeliveredElement = { it.dropped() })
        val previous = worker
        queue = channel
        // A worker waiting for more would outlive the client, and with it any scope a host gave it.
        val job = client.launchUntilClosed {
            previous?.join()
            drain(channel)
        }
        worker = job
        // Not a `finally` in the coroutine: one launched into a cancelled scope never starts.
        job.invokeOnCompletion { channel.cancel() }
        return channel
    }

    private suspend fun CoroutineScope.drain(channel: Channel<Item>) {
        for (item in channel) {
            attempt { followDeclared() }
            when (item) {
                is Batch -> attempt { accept(item.changes, channel) }

                DebounceElapsed -> attempt { flushWholeText() }

                is Send -> try {
                    attempt { flushWholeText() }
                    attempt { item.block() }
                } finally {
                    item.done.complete(Unit)
                }

                is Turn -> {
                    try {
                        attempt { flushWholeText() }
                    } finally {
                        item.reached.complete(Unit)
                    }
                    item.written.await()
                }
            }
        }
    }

    /**
     * Runs [write], which only this coroutine's own cancellation may end the queue from. Any other
     * cancellation is the transport's, a `withTimeout` of its own say, and means this one write is
     * lost while the connection lives on. What the server has is then not known, so the next thing
     * written for the text is all of it.
     */
    private inline fun CoroutineScope.attempt(write: () -> Unit) {
        try {
            write()
        } catch (_: CancellationException) {
            ensureActive()
            unsent = mirror != null
        }
    }

    /** Takes in what the server registered or took back since the last thing the queue took up. */
    private suspend fun followDeclared() {
        val sync = syncOptions()
        // Only when it is needed: this runs for every keystroke, and the text costs the whole document.
        val withheld = if (openWithheld && sync.openClose) mirror?.text ?: lastText else null
        if (withheld != null) {
            sendOpen(withheld)
            unsent = false
        } else if (changeSync == LspChangeSync.None && sync.change != LspChangeSync.None) {
            unsent = mirror != null
        }
        changeSync = sync.change
    }

    private suspend fun CoroutineScope.accept(changes: List<ContentChange>, channel: Channel<Item>) {
        val text = mirror ?: return
        var addressable = changeSync == LspChangeSync.Incremental && !unsent
        for (change in changes) {
            addressable = text.canAddress(change) && addressable
            text.apply(change)
        }
        textRevision += 1
        when {
            changeSync == LspChangeSync.None -> Unit

            addressable -> sendChanges(changes.map { it.toEvent() })

            else -> {
                unsent = true
                debounce?.cancel()
                debounce = launch {
                    delay(FULL_SYNC_DEBOUNCE_MILLIS)
                    channel.trySend(DebounceElapsed)
                }
            }
        }
    }

    private suspend fun flushWholeText() {
        if (!unsent) return
        unsent = false
        debounce?.cancel()
        if (changeSync == LspChangeSync.None) return
        val text = mirror?.text ?: return
        sendChanges(wholeText(text))
    }

    /** At the version already reached if a server that took no open has had edits. */
    private suspend fun sendOpen(text: String) {
        opened.value = true
        openWithheld = false
        lastText = text
        sentVersion = sentVersion.coerceAtLeast(1)
        client.sendNotification(
            "textDocument/didOpen",
            LspJson.encodeToJsonElement(
                LspDidOpenTextDocumentParams.serializer(),
                LspDidOpenTextDocumentParams(
                    LspTextDocumentItem(uri = documentUri, languageId = languageId, version = sentVersion, text = text),
                ),
            ),
        )
        onTextSent()
    }

    /** One version per batch, this adapter's own: `ContentChange.version` counts changes, as a Long. */
    private suspend fun sendChanges(events: List<LspTextDocumentContentChangeEvent>) {
        sentVersion += 1
        sendDidChange(sentVersion, events)
    }

    private suspend fun sendDidChange(version: Int, events: List<LspTextDocumentContentChangeEvent>) {
        client.sendNotification(
            "textDocument/didChange",
            LspJson.encodeToJsonElement(
                LspDidChangeTextDocumentParams.serializer(),
                LspDidChangeTextDocumentParams(LspVersionedTextDocumentIdentifier(documentUri, version), events),
            ),
        )
        onTextSent()
    }

    private companion object {
        /** How long a whole-text sync waits for typing to pause, unless a request needs it sooner. */
        const val FULL_SYNC_DEBOUNCE_MILLIS = 400L

        const val NOT_ATTACHING = -1

        fun wholeText(text: String) = listOf(LspTextDocumentContentChangeEvent(text = text))

        /** Each change is relative to the ones before it, as the protocol's `contentChanges` are. */
        fun ContentChange.toEvent(): LspTextDocumentContentChangeEvent = if (isReset) {
            LspTextDocumentContentChangeEvent(text = insertedText)
        } else {
            LspTextDocumentContentChangeEvent(
                range = LspRange(LspPosition(startLine, startColumn), LspPosition(oldEndLine, oldEndColumn)),
                text = insertedText,
            )
        }
    }
}
