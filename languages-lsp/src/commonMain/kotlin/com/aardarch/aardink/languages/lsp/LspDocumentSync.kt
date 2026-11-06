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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * What a server's `textDocumentSync` capability asks of the client.
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
    companion object {
        /**
         * Capabilities not known count as supported, as everywhere in this module. Full is the one
         * form every server that syncs at all can read, where a range would corrupt the text of a
         * server that only takes whole documents.
         */
        private val Unknown = LspTextDocumentSync(openClose = true, change = LspChangeSync.Full, willSave = true, didSave = true)

        fun from(capabilities: JsonElement?): LspTextDocumentSync {
            val declared = capabilities as? JsonObject ?: return Unknown
            return when (val sync = declared["textDocumentSync"]) {
                is JsonObject -> fromOptions(sync)
                is JsonPrimitive -> fromKind(changeSyncOf(sync))
                else -> LspTextDocumentSync()
            }
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
 * it: a whole-text server is served from [mirror] instead.
 *
 * The queue lives from the first thing sent until [close]. A later session gets a new queue, which
 * starts once the last one has drained.
 */
internal class LspDocumentSync(private val client: LspClient, private val documentUri: String, private val languageId: String) {

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

    /** The document version last sent; 0 when the document is not open. Written by the queue only. */
    @Volatile
    var sentVersion: Int = 0
        private set

    val isAttached: Boolean
        get() = attached != null

    private var opened = false

    /** How [Batch]es are sent, read from the server's capabilities at [attach]. */
    private var changeSync = LspChangeSync.None

    /** The text the server has while no listener is following it, to catch up from at [attach]. */
    private var lastText: String? = null

    /**
     * The attached text as the batches so far left it, kept only for a whole-text server. Being
     * private to the queue it is safe to read here, and being built from the batches its text is
     * exactly the one the next version number stands for. The live document offers neither.
     */
    private var mirror: CodeDocument? = null

    /** The mirror has edits the server has not been sent. */
    private var unsent = false
    private var debounce: Job? = null

    private val contentListener: (List<ContentChange>) -> Unit = { changes -> queue?.trySend(Batch(changes)) }

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
        enqueue(Send { follow(text) })
        state.addContentListener(contentListener)
    }

    private suspend fun follow(text: String) {
        val sync = syncOptions()
        changeSync = sync.change
        mirror = if (sync.change == LspChangeSync.Full) CodeDocument(text) else null
        when {
            !opened -> sendOpen(text, sync)
            text != lastText -> if (sync.change != LspChangeSync.None) sendChanges(listOf(LspTextDocumentContentChangeEvent(text = text)))
        }
        lastText = null
    }

    /** Stops following. Edits already reported still reach the server, ahead of anything sent after. */
    fun detach() {
        val state = attached ?: return
        attached = null
        state.removeContentListener(contentListener)
        val text = state.document.text
        enqueue(
            Send {
                lastText = text
                mirror = null
            },
        )
    }

    suspend fun open(text: String) = submit(Send { sendOpen(text, syncOptions()) })

    suspend fun sendWholeText(text: String, version: Int) = submit(
        Send {
            lastText = text
            if (syncOptions().change != LspChangeSync.None) {
                sentVersion = version
                sendDidChange(version, listOf(LspTextDocumentContentChangeEvent(text = text)))
            }
        },
    )

    /** Ends the session, and with it this queue. Reaches the server even if the caller is cancelled. */
    suspend fun close() {
        val closing = queue ?: newQueue()
        queue = null
        val closed = Send {
            opened = false
            lastText = null
            sentVersion = 0
            if (syncOptions().openClose) {
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
     * client's scope lets [block] find out for itself that the connection is closed.
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

    private fun syncOptions() = LspTextDocumentSync.from(client.serverCapabilities)

    private suspend fun submit(item: Send) {
        if (enqueue(item)) item.done.await()
    }

    private fun enqueue(item: Item): Boolean = (queue ?: newQueue()).trySend(item).isSuccess

    private fun newQueue(): Channel<Item> {
        val channel = Channel<Item>(Channel.UNLIMITED, onUndeliveredElement = { it.dropped() })
        val previous = worker
        queue = channel
        worker = client.launchOnScope {
            previous?.join()
            drain(channel)
        }.also {
            // Not a `finally` in the coroutine: one launched into a cancelled scope never starts.
            it.invokeOnCompletion { channel.cancel() }
        }
        return channel
    }

    private suspend fun CoroutineScope.drain(channel: Channel<Item>) {
        for (item in channel) {
            when (item) {
                is Batch -> accept(item.changes, channel)

                DebounceElapsed -> flushWholeText()

                is Send -> try {
                    flushWholeText()
                    item.block()
                } finally {
                    item.done.complete(Unit)
                }

                is Turn -> {
                    flushWholeText()
                    item.reached.complete(Unit)
                    item.written.await()
                }
            }
        }
    }

    private suspend fun CoroutineScope.accept(changes: List<ContentChange>, channel: Channel<Item>) {
        when (changeSync) {
            LspChangeSync.None -> Unit

            LspChangeSync.Incremental -> sendChanges(changes.map { it.toEvent() })

            LspChangeSync.Full -> {
                val text = mirror ?: return
                changes.forEach { text.apply(it) }
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
        val text = mirror?.text ?: return
        sendChanges(listOf(LspTextDocumentContentChangeEvent(text = text)))
    }

    private suspend fun sendOpen(text: String, sync: LspTextDocumentSync) {
        opened = true
        lastText = text
        sentVersion = 1
        if (!sync.openClose) return
        client.sendNotification(
            "textDocument/didOpen",
            LspJson.encodeToJsonElement(
                LspDidOpenTextDocumentParams.serializer(),
                LspDidOpenTextDocumentParams(LspTextDocumentItem(uri = documentUri, languageId = languageId, version = 1, text = text)),
            ),
        )
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
    }

    private companion object {
        /** How long a whole-text server waits for typing to pause, unless a request needs it sooner. */
        const val FULL_SYNC_DEBOUNCE_MILLIS = 400L

        /** Each change is relative to the ones before it, as the protocol's `contentChanges` are. */
        fun ContentChange.toEvent(): LspTextDocumentContentChangeEvent = if (isReset) {
            LspTextDocumentContentChangeEvent(text = insertedText)
        } else {
            LspTextDocumentContentChangeEvent(
                range = LspRange(LspPosition(startLine, startColumn), LspPosition(oldEndLine, oldEndColumn)),
                text = insertedText,
            )
        }

        fun CodeDocument.apply(change: ContentChange) {
            if (change.isReset) {
                replaceAll(change.insertedText)
            } else {
                delete(change.offset, change.deletedLength)
                insert(change.offset, change.insertedText)
            }
        }
    }
}
