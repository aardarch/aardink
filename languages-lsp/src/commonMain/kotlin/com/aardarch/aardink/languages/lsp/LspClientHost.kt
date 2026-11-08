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

import kotlinx.serialization.json.JsonElement

/**
 * What an [LspClient] asks of the application: the things a server wants done that a protocol
 * bridge cannot do alone. Every member has a default, so a host overrides only what it can honour.
 *
 * The suspending members answer a server's request. Each runs in its own coroutine on the client's
 * scope and may take as long as it likes, or send requests through the client itself, while the
 * client goes on reading. They are cancelled if the connection closes first, and one that throws is
 * reported to the server as an error.
 *
 * The other members are notifications and run on the receive loop, so that they arrive in the
 * server's order. They must return quickly, and hand anything slow to a coroutine of their own.
 * There is one exception, which no server sent: the [logMessage] that [LspClient.initialize] makes
 * of a `positionEncoding` it cannot read runs in the coroutine that called `initialize`, so it can
 * overlap one from the receive loop.
 *
 * `type` is an LSP `MessageType`: 1 error, 2 warning, 3 info, 4 log, 5 debug.
 */
interface LspClientHost {
    /**
     * Applies [edit], which may reach several files, for `workspace/applyEdit`. [label] is the
     * server's name for the change, for an undo stack. All of [edit] comes here, for documents with
     * an attached [LspLanguageService] too: an editor state may only be edited on its own thread,
     * and which that is, only the host knows. A server whose positions are not UTF-16 is refused
     * without coming here, as every [LspPosition] in its edit would point at the wrong column.
     *
     * [LspLanguageService.rename] also comes here, with what its own document cannot carry out, so
     * the [edit] leaves that document's text edits out and [label] is null. That call runs in the
     * coroutine that asked for the rename, and on false, or an exception, the rename is given up:
     * the edits for its own document are not applied either.
     *
     * @return True only if every part of [edit] was applied. The default applies nothing.
     */
    suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit, label: String?): Boolean = false

    /**
     * Shows [message] with [actions] to choose from, for `window/showMessageRequest`.
     *
     * @return The title of the chosen action, or null when none was chosen, which is the default.
     */
    suspend fun showMessageRequest(type: Int, message: String, actions: List<String>): String? = null

    /** Shows [message], for `window/showMessage`. */
    fun showMessage(type: Int, message: String) = Unit

    /** Records [message], for `window/logMessage`. */
    fun logMessage(type: Int, message: String) = Unit

    /**
     * Reports progress, for `$/progress`. [token] is the `ProgressToken`, a number or a string, and
     * [value] is the payload as the server sent it: for work done progress an object whose `kind`
     * is `begin`, `report` or `end`.
     */
    fun progress(token: JsonElement, value: JsonElement) = Unit
}

/** The default [LspClientHost]: nothing overridden. */
internal object NoLspClientHost : LspClientHost
