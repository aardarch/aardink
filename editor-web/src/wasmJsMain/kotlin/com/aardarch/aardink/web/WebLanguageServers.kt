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
package com.aardarch.aardink.web

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.languages.lsp.DiagnosticsListener
import com.aardarch.aardink.languages.lsp.LspClient
import com.aardarch.aardink.languages.lsp.LspFoldingProvider
import com.aardarch.aardink.languages.lsp.LspLanguageService
import com.aardarch.aardink.languages.lsp.LspTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** As long as the layout waits after an edit, and no longer: see [WebLanguageServer.showWhenSettled]. */
private const val TYPING_PAUSE_MS = 500L

/**
 * One connection to a language server, for [AardinkWeb.connectLanguageServer]: a client of its own,
 * and an [LspLanguageService] for each editor of its language, attached to that editor's state.
 *
 * Everything here runs on the page's one thread, as the rest of the bridge does, so none of it is
 * locked. It ends one way or another exactly once: [dispose], a connection that cannot be made, or
 * one that drops. Each hands the editors back to their language's own service, and [onEnded] then
 * takes it off the list of connections.
 */
internal class WebLanguageServer(
    private val url: String,
    private val languageId: String,
    private val connect: suspend (url: String) -> LspTransport,
    private val editors: () -> List<AardinkEditorHandle>,
    private val onEnded: (WebLanguageServer) -> Unit,
) : DisposableHandle {

    /** An editor being followed: its [document], and what tells it that the server has published. */
    private class Followed(val document: ServerDocument, val onPublished: DiagnosticsListener) {
        /** What is waiting to show a push, while one is. */
        var settling: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val followed = HashMap<AardinkEditorHandle, Followed>()
    private var opening: Job? = null
    private var client: LspClient? = null
    private var initialized = false
    private var triggerCharacters: Set<Char>? = null
    private var ended = false
    private var nextDocument = 1

    /** Connects, and once the server has answered `initialize` follows the editors there are by then. */
    fun start() {
        opening = scope.launch {
            try {
                val connected = LspClient(WatchedTransport(connect(url), ::lost), scope)
                client = connected
                val capabilities = connected.initialize(rootUri = null)
                // A socket gone by the time `initialized` is sent ends the connection inside that
                // call without failing it, and with nothing left to take any editors back.
                if (ended) return@launch
                triggerCharacters = LspLanguageService.triggerCharactersFrom(capabilities)
                initialized = true
                editors().forEach(::follow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: a browser refusing the URL outright throws a JavaScript exception.
                if (!ended) refused(e)
            }
        }
    }

    /** Starts or stops following [handle], as what it shows now says. */
    fun follow(handle: AardinkEditorHandle) {
        val wanted = !handle.disposed.value && (handle.options.value.language == languageId || handle.language.value.id == languageId)
        when {
            !wanted -> stopFollowing(handle)
            handle !in followed -> startFollowing(handle)
        }
    }

    private fun startFollowing(handle: AardinkEditorHandle) {
        val connected = client?.takeIf { initialized } ?: return
        val extension = handle.registry.byId(languageId)?.fileExtensions?.firstOrNull() ?: languageId
        val service = LspLanguageService(
            client = connected,
            // `file:` as servers such as gopls take no other scheme. The name stands for no file on
            // any disk: the server has the text from `didOpen`. The extension tells it what it is.
            documentUri = "file:///aardink/editor-${nextDocument++}.$extension",
            languageId = languageId,
        )
        // This listener comes after the service's own, which has the list by the time this runs.
        val onPublished: DiagnosticsListener = { uri, _, _ ->
            if (uri == service.documentUri) followed[handle]?.let { showWhenSettled(handle, it) }
        }
        connected.addDiagnosticsListener(onPublished)
        service.attach(handle.state.value)
        val document = ServerDocument(service, triggerCharacters.orEmpty())
        followed[handle] = Followed(document, onPublished)
        handle.languageServers += document
    }

    /**
     * Gets what the server just pushed on screen. The service only holds it, and the editor asks
     * after a pause in typing, so without this it would wait for the next edit.
     *
     * Not by revalidating at once: that ends the pause the layout is in, and with a server that
     * publishes after every change, each keystroke would pay for a pass over the whole document. So
     * this waits as long as that pause. If the text has moved by then, a pass is still to come and
     * will read the newest list; pushes in the meantime need no wait of their own for the same
     * reason. If a pass has read the list, there is nothing left to show.
     */
    private fun showWhenSettled(handle: AardinkEditorHandle, one: Followed) {
        one.document.unread = true
        if (one.settling?.isActive == true) return
        val version = handle.state.value.textVersion
        one.settling = scope.launch {
            delay(TYPING_PAUSE_MS)
            if (one.document.unread && handle.state.value.textVersion == version) AardinkWeb.revalidate(handle)
        }
    }

    /** Returns what closes the document on the server, if [handle] was being followed. */
    private fun stopFollowing(handle: AardinkEditorHandle): Job? {
        val one = followed.remove(handle) ?: return null
        val service = one.document.service
        one.settling?.cancel()
        handle.languageServers -= one.document
        service.client.removeDiagnosticsListener(one.onPublished)
        service.detach()
        return scope.launch { service.didClose() }
    }

    /** Closes every document and ends the session as the protocol asks. Safe to call twice. */
    override fun dispose() {
        if (ended) return
        end()
        opening?.cancel()
        val closing = followed.keys.toList().mapNotNull(::stopFollowing)
        val connected = client
        scope.launch {
            closing.joinAll()
            // An `initialize` not answered has no session to shut down, and leaves nothing to say.
            if (initialized) connected?.shutdown() else connected?.stop()
            scope.cancel()
        }
    }

    private fun refused(reason: Throwable) {
        println("Aardink: could not connect to the language server at $url (${reason.message}); the editor goes on without it.")
        end()
        client?.stop()
        scope.cancel()
    }

    /** [LspClient] closes its transport once, however the connection went. */
    private fun lost() {
        if (ended) return
        println("Aardink: the language server at $url closed the connection; the editor goes on without it.")
        end()
        followed.keys.toList().forEach(::stopFollowing)
        scope.cancel()
    }

    private fun end() {
        ended = true
        onEnded(this)
    }
}

/**
 * An editor's document on a server, as [service] answers for it, with the trigger characters the
 * server declared and no others: without any, the adapter would offer the editor's generic set, and
 * they would open the list for a language that never asked for them.
 */
internal class ServerDocument(val service: LspLanguageService, override val triggerCharacters: Set<Char>) : LanguageService by service {
    /** True from a push until the editor next reads the list. */
    var unread = false

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        unread = false
        return service.diagnostics(document)
    }
}

/** [transport], saying when [LspClient] closes it: the one thing every end of a connection does. */
private class WatchedTransport(private val transport: LspTransport, private val onClosed: () -> Unit) : LspTransport by transport {
    override fun close() {
        transport.close()
        onClosed()
    }
}

/**
 * What an editor of a language gets with [servers] following it, more than one taken in the order
 * they connected. A registered language's service stays first, so the host's providers keep their
 * word and the server fills in what they leave ([CompositeLanguageService]'s rules). A built-in
 * language's goes after the server, as a real server knows more than its service does. Either way
 * what happens as text is typed stays the language's ([TypedByLanguage]), and with no language there
 * it is as it would be without a server ([TypedAsWithNoService]).
 */
internal fun servedLanguage(own: LanguageService?, servers: List<LanguageService>): LanguageService? {
    val server =
        servers.reduceOrNull<LanguageService, LanguageService> { first, next -> CompositeLanguageService(first, next) } ?: return own
    return when (own) {
        null -> TypedAsWithNoService(server)
        is WebLanguageService -> TypedByLanguage(primary = own, fallback = server, language = own)
        else -> TypedByLanguage(primary = server, fallback = own, language = own)
    }
}

/**
 * [primary] and [fallback] with what happens as text is typed answered by [language] alone:
 * indentation on Enter, closing pairs and surrounding a selection. Its "nothing" here is a rule, as
 * no pair for `'` in a comment is, so nothing falls through to the generic table that
 * [LspLanguageService] has for a Kotlin host's editor with no language service at all.
 */
private class TypedByLanguage(primary: LanguageService, fallback: LanguageService, private val language: LanguageService) :
    CompositeLanguageService(primary, fallback) {

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String =
        language.smartIndent(document, lineIndex, options)

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = language.autoClose(document, offset, charTyped)

    override fun surroundingClose(typed: Char): String? = language.surroundingClose(typed)
}

/**
 * [server] for an editor with no language service, which types as it does without one: a server
 * never changes what typing does, and [LspLanguageService]'s generic table would make `don't` into
 * `don''t`. Answering nothing here is what having no service does in the editor: no indentation on
 * Enter, nothing closed, and a selection replaced.
 */
private class TypedAsWithNoService(server: LanguageService) : LanguageService by server {
    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

    override fun surroundingClose(typed: Char): String? = null
}

/**
 * The first of [servers] that folds, asked when the editor asks, or else [own]. [own] itself when
 * it is [NoOpFoldingProvider] and none folds, as the editor keeps a lane for any other provider and
 * the text would move over for folds that cannot come. What a server folds is settled by then:
 * [LspClient] offers no dynamic registration of it.
 */
internal fun servedFolding(own: FoldingProvider, servers: List<ServerDocument>): FoldingProvider {
    val services = servers.map { it.service }
    val nothingToFold = own === NoOpFoldingProvider && services.none { it.supports("foldingRangeProvider") }
    return if (services.isEmpty() || nothingToFold) own else ServedFoldingProvider(own, services)
}

private class ServedFoldingProvider(private val own: FoldingProvider, private val servers: List<LspLanguageService>) : FoldingProvider {
    override fun foldableRanges(document: CodeDocument): List<FoldRange> = own.foldableRanges(document)

    override suspend fun requestFoldableRanges(document: CodeDocument): List<FoldRange> {
        val server = servers.firstOrNull { it.supports("foldingRangeProvider") } ?: return own.requestFoldableRanges(document)
        return LspFoldingProvider(server).requestFoldableRanges(document)
    }
}
