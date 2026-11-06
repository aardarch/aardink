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

import com.aardarch.aardink.platform.EditorDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile

/**
 * Callback invoked when the server pushes `textDocument/publishDiagnostics` for [uri].
 *
 * [version] is the document version the diagnostics were computed against, or null when the server
 * did not report one; listeners should drop a notification older than the last version they sent.
 */
typealias DiagnosticsListener = (uri: String, version: Int?, diagnostics: List<LspDiagnostic>) -> Unit

/**
 * Thrown by [LspClient.sendRequest] when the server answers with a JSON-RPC error, or when the
 * connection is closed while a request is still pending.
 */
class LspRequestException(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        /** JSON-RPC: the requested method is not supported by the receiver. */
        const val METHOD_NOT_FOUND: Int = -32601

        /** Implementation-defined: the transport closed before a response arrived. */
        const val CONNECTION_CLOSED: Int = -32001
    }
}

/**
 * Asynchronous Coroutine JSON-RPC 2.0 client managing communication with a Language Server.
 *
 * One client normally serves many documents: register one [DiagnosticsListener] per open
 * document with [addDiagnosticsListener]; every listener receives every `publishDiagnostics`
 * notification and filters by URI itself.
 *
 * Internal mutable state ([nextRequestId], [pendingRequests], [listeningJob], [transportClosed])
 * is guarded by [lock], a `Mutex`, rather than `java.util.concurrent`'s `AtomicLong`/
 * `ConcurrentHashMap`/`@Synchronized` (none are available in common Kotlin — this class is
 * shared across android/jvm/wasmJs). [start] and [stop] are non-suspend public API used both from
 * suspend call sites ([sendRequest], [sendNotification]) and potentially by a host calling them
 * directly, so they take the lock with [Mutex.tryLock] and fall back to an async acquisition on
 * [scope] when it's already held — contention here is a receive-loop-starts-or-stops event, not a
 * hot path. [diagnosticsListeners] is a `@Volatile` copy-on-write list outside [lock] entirely:
 * both the read in [handleNotification] (the receive loop's hot path) and the writes in
 * [addDiagnosticsListener]/[removeDiagnosticsListener] are lock-free, relying on `@Volatile` for
 * cross-thread visibility. Keeping listener writes off [lock] is what preserves their program
 * order — see [addDiagnosticsListener].
 *
 * Requests from the server each run in their own coroutine, a child of the receive loop, because
 * answering one can take as long as a person takes to answer a prompt and can itself need a response
 * from the server. They are concurrent rather than queued for the same reason: a server may ask the
 * client something else before it answers a request a running callback is waiting on.
 *
 * @param transport The connection to the server.
 * @param scope Runs the receive loop and whatever answers the server's requests.
 * @param host What the client asks about things only the application can decide.
 */
class LspClient(
    val transport: LspTransport,
    private val scope: CoroutineScope = CoroutineScope(EditorDispatchers.io),
    private val host: LspClientHost = NoLspClientHost,
) {
    private val lock = Mutex()
    private var nextRequestId: Long = 1L // guarded by lock
    private val pendingRequests = HashMap<Long, CompletableDeferred<LspMessage.Response>>() // guarded by lock
    private var listeningJob: Job? = null // guarded by lock

    @Volatile
    private var diagnosticsListeners: List<DiagnosticsListener> = emptyList()

    /**
     * Set once the connection is gone — the server closed the stream, or [stop] was called. A
     * closed client never starts another receive loop, because nothing could complete the requests
     * it would accept.
     */
    @Volatile
    private var closed = false

    /** Guarded by [lock]; see [claimTransportClose]. */
    private var transportClosed = false

    /**
     * Set by [shutdown], under [lock]. The protocol allows the client no request but `shutdown` and
     * no notification but `exit` from here on, which also rules out `$/cancelRequest` for a
     * `shutdown` that timed out.
     */
    @Volatile
    private var shuttingDown = false

    /**
     * The `ServerCapabilities` the server answered [initialize] with: null until that answer
     * arrives, and when it carried none. Registrations made later through
     * `client/registerCapability` are not reflected here.
     */
    @Volatile
    var serverCapabilities: JsonElement? = null
        private set

    /**
     * Registers [listener]; it stays registered until [removeDiagnosticsListener] is called.
     * Takes effect before this call returns.
     *
     * Deliberately does not go through [withLockOrAsync]. Under contention that would defer the
     * mutation to an independent `scope.launch`, and callers depend on program order here:
     * `LspLanguageService.didOpen` registers and then immediately sends `textDocument/didOpen`,
     * so a deferred registration can miss the diagnostics the server publishes in reply; an
     * add followed by a remove can land in the opposite order and leak the listener; and
     * `didClose` can be overtaken by a late notification that repopulates its cache. The
     * previous `@Synchronized` implementation gave program order for free.
     *
     * [diagnosticsListeners] is a `@Volatile` copy-on-write list and every write here replaces
     * it wholesale, so a plain read-modify-write is safe enough without the lock: concurrent
     * registrations are last-writer-wins, which loses no ordering guarantee the lock preserved.
     */
    fun addDiagnosticsListener(listener: DiagnosticsListener) {
        val current = diagnosticsListeners
        if (listener !in current) diagnosticsListeners = current + listener
    }

    /** Unregisters [listener]. Takes effect before this call returns; see [addDiagnosticsListener]. */
    fun removeDiagnosticsListener(listener: DiagnosticsListener) {
        diagnosticsListeners = diagnosticsListeners - listener
    }

    /**
     * Runs [block] under [lock], taking it synchronously via [Mutex.tryLock] when uncontended (the
     * overwhelmingly common case — these are rare, low-frequency calls), or asynchronously on
     * [scope] when another coroutine already holds it.
     *
     * A coroutine launched into a cancelled scope never runs, so the async path would silently
     * drop [block] once the host has cancelled [scope] — and [stop] promises unconditional
     * teardown. In that case run [block] inline without the lock instead: the scope is dead, so
     * whoever holds the lock can no longer make progress either, and losing the teardown
     * entirely is strictly worse than running it unsynchronized.
     *
     * @param block Returns what [claimTransportClose] returned, and the transport closes after the
     *   lock is released.
     */
    private fun withLockOrAsync(block: () -> Boolean) {
        if (lock.tryLock()) {
            val mustClose = try {
                block()
            } finally {
                lock.unlock()
            }
            if (mustClose) transport.close()
        } else if (scope.isActive) {
            scope.launch { if (lock.withLock { block() }) transport.close() }
        } else {
            if (block()) transport.close()
        }
    }

    /**
     * Starts the receive loop. Idempotent; also called implicitly by the send functions.
     *
     * Does nothing once the client is closed: without a loop to complete them, any request accepted
     * afterwards would wait forever.
     */
    fun start() {
        withLockOrAsync { startLocked() }
    }

    private fun startLocked(): Boolean {
        if (closed || listeningJob != null) return false
        if (!scope.isActive) {
            // A coroutine launched into a cancelled scope never runs, so no loop would ever answer
            // a request registered afterwards; the client is as good as closed.
            closed = true
            return claimTransportClose()
        }
        listeningJob = scope.launch {
            try {
                while (true) {
                    val payload = transport.receivePayload() ?: break
                    handleIncomingPayload(payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A broken pipe or a killed server process is how a connection ordinarily ends,
                // and a transport closed underneath the read may fail with anything — the
                // contract promises no particular type. Letting it out of this root coroutine
                // would reach the scope's uncaught handler and take the host process down over a
                // language server going away.
            } finally {
                // An answer in progress has nowhere left to go, and a callback waiting on a person
                // would otherwise outlive the connection.
                coroutineContext.cancelChildren()
                // NonCancellable: onReceiveLoopEnded suspends on lock.withLock, and a suspend
                // call in a finally block throws CancellationException before running its body
                // once this coroutine is cancelled. Without this, a host cancelling the shared
                // scope while the loop is alive would leave `closed` unset, every pending
                // request waiting forever and the transport never closed. sendRequest's finally
                // already does the same thing.
                withContext(NonCancellable) { onReceiveLoopEnded() }
            }
        }
        return false
    }

    /**
     * True for the one teardown path that closes the transport: every path may reach here, and only
     * the first should close. Must be called with [lock] held, and the caller closes after releasing
     * it, because a close can block behind a read or write that a hung server is holding up, and
     * nothing that needs [lock] should wait on that.
     */
    private fun claimTransportClose(): Boolean {
        if (transportClosed) return false
        transportClosed = true
        return true
    }

    /**
     * The receive loop is over — because the server closed the stream, or because [stop] cancelled
     * it. Either way the connection is finished: mark the client closed so no later send can
     * register a request nothing will answer, release the transport, and fail what is in flight.
     */
    private suspend fun onReceiveLoopEnded() {
        val mustClose = lock.withLock {
            closed = true
            listeningJob = null
            failPendingRequestsLocked("Language server connection closed")
            claimTransportClose()
        }
        if (mustClose) transport.close()
    }

    /**
     * Performs the mandatory LSP handshake: sends `initialize` with [rootUri] and [capabilities],
     * waits for the server's response, then sends the `initialized` notification.
     *
     * Must be the first request/notification exchanged with the server — servers are entitled to
     * reject anything sent beforehand with a `ServerNotInitialized` (-32002) error.
     *
     * @param rootUri Workspace root, or null for a server with no workspace context.
     * @param capabilities Client capabilities object. Servers withhold what the client does not
     *   declare, so replace [DefaultClientCapabilities] only to narrow or extend it.
     * @return The server's reported `ServerCapabilities` (the `capabilities` member of the
     *   `InitializeResult`), or null if the response carried none. Also kept in
     *   [serverCapabilities].
     */
    suspend fun initialize(rootUri: String?, capabilities: JsonElement = DefaultClientCapabilities): JsonElement? {
        val params = buildJsonObject {
            put("processId", JsonNull)
            put("rootUri", rootUri?.let { JsonPrimitive(it) } ?: JsonNull)
            put("capabilities", capabilities)
        }
        val result = sendRequest("initialize", params)
        // Before `initialized`: the server may ask the client something as soon as it reads that.
        val declared = (result as? JsonObject)?.get("capabilities")
        serverCapabilities = declared
        sendNotification("initialized", buildJsonObject { })
        return declared
    }

    /**
     * Ends the session as the protocol asks: the `shutdown` request, its answer, the `exit`
     * notification, and then the same teardown as [stop]. Requests still pending fail with
     * [LspRequestException.CONNECTION_CLOSED], as do requests sent meanwhile, and notifications
     * sent meanwhile are dropped.
     *
     * Never fails on the server's account: `exit` follows an error too, and a server that gives no
     * answer within five seconds is treated the same way. One that has stopped reading altogether
     * is closed without `exit` after ten. Closing the transport runs on the client's scope and is
     * waited for for at most a second more, so this returns within eleven seconds even if that
     * close blocks, unless the scope is already cancelled and only the caller is left to run it. A
     * connection that is already closed makes this a no-op, as does a [shutdown] already running.
     * If the caller is cancelled, the client still closes, without `exit`.
     */
    suspend fun shutdown() {
        if (!claimShutdown()) return
        // On the client's scope, not the caller's coroutine: a blocking transport finishes a write
        // it has begun even when cancelled, so a server that stopped reading would hold the caller
        // there for good, short of the very teardown that frees the write.
        val farewell = scope.launch {
            try {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { sendRequest("shutdown") }
            } catch (_: LspRequestException) {
                // Whatever the server made of it, the session is over.
            }
            sendNotification("exit")
        }
        try {
            withTimeoutOrNull(2 * SHUTDOWN_TIMEOUT_MILLIS) { farewell.join() }
        } finally {
            farewell.cancel()
            // NonCancellable: a caller cancelled above must still see the teardown through.
            withContext(NonCancellable) { stopOffCaller() }
        }
    }

    /**
     * [stop] on the client's scope, waited for only briefly: a transport's close can block until the
     * server goes away, and [shutdown] promises its caller a bound.
     */
    private suspend fun stopOffCaller() {
        val stopping = scope.launch { stop() }
        withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) { stopping.join() }
        // A coroutine launched into a cancelled scope never runs, and stop() is safe to repeat.
        if (stopping.isCancelled) stop()
    }

    /**
     * True for the one caller that gets to run [shutdown]. NonCancellable because a caller cancelled
     * here must still reach the teardown; [lock] is never held across a suspension or a transport's
     * close, so this is brief.
     */
    private suspend fun claimShutdown(): Boolean = withContext(NonCancellable) {
        lock.withLock {
            val first = !shuttingDown
            shuttingDown = true
            first
        }
    }

    /**
     * Sends an LSP request and suspends until the matching response arrives.
     *
     * Cancelling the caller sends `$/cancelRequest` for it. That goes out on the client's scope, so
     * the caller is released at once even when the write is stuck behind a hung server, and a late
     * answer from the server is ignored.
     *
     * @return The `result` member of the response, or null when the server returned `null`.
     * @throws LspRequestException with [LspRequestException.CONNECTION_CLOSED] when the connection
     *   is already closed or shutting down, or closes before a response is received, or with the
     *   server's code when it answers with an `error`.
     */
    suspend fun sendRequest(method: String, params: JsonElement? = null): JsonElement? = sendRequest(method, params, onWritten = {})

    /**
     * [sendRequest], calling [onWritten] as soon as the request is on the wire rather than when it
     * is answered: what a document's sync queue waits for before it lets a later edit out.
     */
    internal suspend fun sendRequest(method: String, params: JsonElement?, onWritten: () -> Unit): JsonElement? {
        start()
        val deferred = CompletableDeferred<LspMessage.Response>()
        // Checking `closed` and registering the deferred must happen together: between a bare check
        // and the insert, teardown could run its `failPendingRequests()` and miss this entry, and a
        // transport whose closed write does not throw would then leave the caller awaiting forever.
        val id =
            registerPending(deferred, method)
                ?: throw LspRequestException(LspRequestException.CONNECTION_CLOSED, "Language server connection is closed")
        var abandoned = false
        try {
            val request = LspMessage.Request(id = id, method = method, params = params)
            sendOrLoseConnection(LspJson.encodeToString(LspMessage.Request.serializer(), request))?.let { failure ->
                throw LspRequestException(LspRequestException.CONNECTION_CLOSED, "Language server connection is closed: ${failure.message}")
            }
            onWritten()
            val response = deferred.await()
            response.error?.let { throw LspRequestException(it.code, it.message) }
            return response.result?.takeUnless { it is JsonNull }
        } catch (e: CancellationException) {
            abandoned = true
            throw e
        } finally {
            // NonCancellable: this cleanup must run even if the caller's own coroutine was
            // cancelled while awaiting the response — a suspend call in a `finally` otherwise
            // throws immediately under cancellation instead of taking the lock.
            val unanswered = withContext(NonCancellable) { lock.withLock { pendingRequests.remove(id) != null } }
            if (abandoned && unanswered) cancelOnServer(id)
        }
    }

    /**
     * Tells the server nobody is waiting for request [id] any more.
     *
     * A blocking transport finishes its write even when the caller is cancelled during it, so a
     * cancelled write may or may not have reached the server. Sending this either way is safe: a
     * server ignores a cancel for an id it does not know.
     */
    private fun cancelOnServer(id: Long) {
        scope.launch { sendNotification("\$/cancelRequest", buildJsonObject { put("id", id) }) }
    }

    /**
     * Runs [block] on the client's scope, so that it ends with the connection's owner. A scope
     * already cancelled gives back a job that has finished without running [block].
     */
    internal fun launchOnScope(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /**
     * Allocates a request id and registers [deferred] against it, unless the client is already
     * closed, or [shutdown] has begun: the protocol allows no request but `shutdown` from there on.
     *
     * Shares [lock] with [onReceiveLoopEnded] and [onConnectionLost], so a request is either
     * registered before teardown runs — and therefore failed by it — or refused outright.
     *
     * @return the allocated id, or null when the caller must not wait.
     */
    private suspend fun registerPending(deferred: CompletableDeferred<LspMessage.Response>, method: String): Long? = lock.withLock {
        if (closed || (shuttingDown && method != "shutdown")) return@withLock null
        val id = nextRequestId
        nextRequestId += 1
        pendingRequests[id] = deferred
        id
    }

    /**
     * Marks the connection finished after a send failed, cancelling the receive loop.
     *
     * [onReceiveLoopEnded] covers the read side; this is the write side, where nothing has ended
     * on its own yet. Cancelling the loop makes it run its own teardown, which fails whatever is
     * still pending and closes the transport.
     */
    private suspend fun onConnectionLost() {
        val mustClose = lock.withLock {
            closed = true
            listeningJob?.cancel()
            listeningJob = null
            failPendingRequestsLocked("Language server connection closed")
            claimTransportClose()
        }
        if (mustClose) transport.close()
    }

    /**
     * Sends a one-way notification without expecting a response.
     *
     * Notifications are fire-and-forget, so one sent after the connection closed, or once
     * [shutdown] has begun, is dropped rather than reported — unlike [sendRequest], there is no
     * caller waiting on an answer.
     */
    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        start()
        if (closed || (shuttingDown && method != "exit")) return
        val notification = LspMessage.Notification(method = method, params = params)
        // Nobody is waiting on a notification, so the failure itself is dropped; the connection
        // teardown it triggers is what matters, so pending requests do not wait on a dead server.
        sendOrLoseConnection(LspJson.encodeToString(LspMessage.Notification.serializer(), notification))
    }

    /**
     * Writes [payload], treating any failure as the connection ending.
     *
     * [LspTransport.sendPayload] promises nothing about *how* a write fails: a stream throws
     * `IOException`, a channel closed by [stop] racing this send throws
     * `ClosedSendChannelException`, and a custom transport may throw anything. Whatever it was, the
     * write half is gone while the receive loop may still be blocked on a read that will never
     * return, so every pending request would wait forever; tear the connection down here rather
     * than let the raw exception escape to one caller. Cancellation is not a failure and passes.
     *
     * @return the failure, or null when the write succeeded.
     */
    private suspend fun sendOrLoseConnection(payload: String): Throwable? = try {
        transport.sendPayload(payload)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onConnectionLost()
        e
    }

    /**
     * Stops the receive loop, fails any pending requests and closes the transport, without a word
     * to the server: this is the forced close, and [shutdown] the orderly one. The client cannot be
     * restarted afterwards; construct a new one for a new connection. The transport closes on the
     * calling thread, once pending requests have failed.
     *
     * Teardown is unconditional, including when [scope] has already been cancelled (see
     * [withLockOrAsync]). It is not, however, ordered against a [sendRequest] already in flight
     * on another coroutine: such a request either registers before this runs and is failed by
     * it, or is refused outright afterwards.
     */
    fun stop() {
        withLockOrAsync { stopLocked() }
    }

    private fun stopLocked(): Boolean {
        // Set before cancelling: the loop's own teardown runs later, on the transport's thread, and
        // a send in between must not see an idle-but-open client and relaunch the loop.
        closed = true
        listeningJob?.cancel()
        listeningJob = null
        failPendingRequestsLocked("Language server client stopped")
        return claimTransportClose()
    }

    /** Must be called with [lock] held. */
    private fun failPendingRequestsLocked(reason: String) {
        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        for (deferred in pending) {
            deferred.completeExceptionally(LspRequestException(LspRequestException.CONNECTION_CLOSED, reason))
        }
    }

    /** Runs on the receive loop, with the loop's coroutine as the receiver. */
    private suspend fun CoroutineScope.handleIncomingPayload(payload: String) {
        val message = parseObjectOrNull(payload) ?: return
        val id = message["id"]?.takeUnless { it is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        when {
            method != null && id != null -> launchServerRequest(id, method, message["params"])
            method != null -> handleNotification(method, message["params"])
            id != null -> handleResponse(payload)
        }
    }

    private suspend fun handleResponse(payload: String) {
        val response = try {
            LspJson.decodeFromString(LspMessage.Response.serializer(), payload)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        val id = (response.id as? JsonPrimitive)?.longOrNull ?: return
        lock.withLock { pendingRequests.remove(id) }?.complete(response)
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        if (method != "textDocument/publishDiagnostics" || params == null) return
        val published = decodeOrNull(LspPublishDiagnosticsParams.serializer(), params) ?: return
        for (listener in diagnosticsListeners) {
            try {
                listener(published.uri, published.version, published.diagnostics)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A misbehaving listener must not tear down the connection for everyone else.
            }
        }
    }

    /**
     * Answers a server→client request in its own coroutine, so the loop goes on reading responses
     * while it runs.
     *
     * Every request gets a reply unless the client itself is closing, because the server may be
     * blocked on it. That includes a callback ending in a cancellation of its own, such as a
     * `withTimeout` it let escape, which is why only this coroutine's cancellation passes silently.
     * An exception must not leave this coroutine either: it is a child of the receive loop, and
     * would take the connection with it.
     */
    private fun CoroutineScope.launchServerRequest(id: JsonElement, method: String, params: JsonElement?) {
        launch {
            val response = try {
                answerServerRequest(id, method, params)
            } catch (e: CancellationException) {
                ensureActive()
                internalErrorResponse(id, method, e)
            } catch (e: Exception) {
                internalErrorResponse(id, method, e)
            }
            sendOrLoseConnection(LspJson.encodeToString(LspMessage.Response.serializer(), response))
        }
    }

    /**
     * The reply to a server→client request; anything without a safe generic reply gets a
     * `MethodNotFound` error so the server does not block waiting on us.
     *
     * - `client/registerCapability` / `client/unregisterCapability`: acknowledged with a `null`
     *   result. This bridge always sends its full capabilities up front, declares
     *   `dynamicRegistration: false` and does not track registrations, so there is nothing further
     *   to do.
     * - `window/workDoneProgress/create`: acknowledged with a `null` result.
     * - `window/showMessageRequest`: [LspClientHost.showMessageRequest]'s choice, or a `null` result
     *   for none.
     * - `workspace/configuration`: answered with one `null` per requested item, per spec, meaning
     *   "no configuration value for this scope".
     */
    private suspend fun answerServerRequest(id: JsonElement, method: String, params: JsonElement?): LspMessage.Response {
        val result: JsonElement = when (method) {
            "client/registerCapability",
            "client/unregisterCapability",
            "window/workDoneProgress/create",
            -> JsonNull

            "window/showMessageRequest" -> showMessageRequestResult(params)

            "workspace/configuration" -> workspaceConfigurationResult(params)

            else -> return LspMessage.Response(
                id = id,
                error = LspError(LspRequestException.METHOD_NOT_FOUND, "Method not found: $method"),
            )
        }
        return LspMessage.Response(id = id, result = result)
    }

    private fun internalErrorResponse(id: JsonElement, method: String, cause: Exception) = LspMessage.Response(
        id = id,
        error = LspError(INTERNAL_ERROR, "Client failed to answer $method: ${cause.message}"),
    )

    /**
     * Read member by member: an internal `@Serializable` class here would put its generated
     * serializer in the public klib ABI. A request too malformed to show is answered with no choice.
     */
    private suspend fun showMessageRequestResult(params: JsonElement?): JsonElement {
        val request = params as? JsonObject ?: return JsonNull
        val type = (request["type"] as? JsonPrimitive)?.intOrNull ?: return JsonNull
        val message = (request["message"] as? JsonPrimitive)?.contentOrNull ?: return JsonNull
        val actions = (request["actions"] as? JsonArray).orEmpty().mapNotNull { action ->
            ((action as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull
        }
        val choice = host.showMessageRequest(type, message, actions) ?: return JsonNull
        return buildJsonObject { put("title", choice) }
    }

    private fun workspaceConfigurationResult(params: JsonElement?): JsonElement {
        val itemCount = ((params as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
        return JsonArray(List(itemCount) { JsonNull })
    }

    private fun parseObjectOrNull(payload: String): JsonObject? = try {
        LspJson.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? = try {
        LspJson.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        /** JSON-RPC: the receiver failed while handling the request. */
        const val INTERNAL_ERROR = -32603

        /**
         * How long [shutdown] waits for the server's answer before going on to `exit`, and then
         * for that to be written.
         */
        const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L

        /** How long [shutdown] waits for the transport to close before leaving it to finish alone. */
        const val CLOSE_TIMEOUT_MILLIS = 1_000L
    }
}
