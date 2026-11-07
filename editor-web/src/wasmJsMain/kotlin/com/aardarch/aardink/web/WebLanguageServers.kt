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
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.LanguageService
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

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
    private class Followed(val document: ServerDocument, val onPublished: DiagnosticsListener)

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
                triggerCharacters = LspLanguageService.triggerCharactersFrom(connected.initialize(rootUri = null))
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
        // The service holds only what was pushed, and the editor asks it after a pause in typing, so
        // what comes in between waits for the next edit unless the editor is told. This listener
        // comes after the service's own, which has the list by the time this runs.
        val onPublished: DiagnosticsListener = { uri, _, _ -> if (uri == service.documentUri) AardinkWeb.revalidate(handle) }
        connected.addDiagnosticsListener(onPublished)
        service.attach(handle.state.value)
        val document = ServerDocument(service, triggerCharacters.orEmpty())
        followed[handle] = Followed(document, onPublished)
        handle.languageServers += document
    }

    /** Returns what closes the document on the server, if [handle] was being followed. */
    private fun stopFollowing(handle: AardinkEditorHandle): Job? {
        val one = followed.remove(handle) ?: return null
        val service = one.document.service
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
internal class ServerDocument(val service: LspLanguageService, override val triggerCharacters: Set<Char>) : LanguageService by service

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
 * language's goes after the server, as a real server knows more than its service does, except for
 * what it does as text is typed ([ServerOverLanguage]).
 */
internal fun servedLanguage(own: LanguageService?, servers: List<LanguageService>): LanguageService? {
    val server =
        servers.reduceOrNull<LanguageService, LanguageService> { first, next -> CompositeLanguageService(first, next) } ?: return own
    return when (own) {
        null -> server
        is WebLanguageService -> CompositeLanguageService(own, server)
        else -> ServerOverLanguage(server, own)
    }
}

/**
 * [server] with what a language does as text is typed left to [language]: indentation on Enter and
 * closing pairs are the language's rules, where [LspLanguageService] has only generic ones.
 */
private class ServerOverLanguage(server: LanguageService, language: LanguageService) : CompositeLanguageService(server, language) {
    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String =
        fallback.smartIndent(document, lineIndex, options).ifEmpty { primary.smartIndent(document, lineIndex, options) }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? =
        fallback.autoClose(document, offset, charTyped) ?: primary.autoClose(document, offset, charTyped)

    override fun surroundingClose(typed: Char): String? = fallback.surroundingClose(typed) ?: primary.surroundingClose(typed)
}

/** The first of [servers] that folds, asked when the editor asks, or else [own]. */
internal fun servedFolding(own: FoldingProvider, servers: List<ServerDocument>): FoldingProvider =
    if (servers.isEmpty()) own else ServedFoldingProvider(own, servers.map { it.service })

private class ServedFoldingProvider(private val own: FoldingProvider, private val servers: List<LspLanguageService>) : FoldingProvider {
    override fun foldableRanges(document: CodeDocument): List<FoldRange> = own.foldableRanges(document)

    override suspend fun requestFoldableRanges(document: CodeDocument): List<FoldRange> {
        val server = servers.firstOrNull { it.supports("foldingRangeProvider") } ?: return own.requestFoldableRanges(document)
        return LspFoldingProvider(server).requestFoldableRanges(document)
    }
}
