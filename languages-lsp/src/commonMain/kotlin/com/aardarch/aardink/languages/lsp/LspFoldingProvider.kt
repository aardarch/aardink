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
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider

/**
 * Folding from a language server's `textDocument/foldingRange`, for
 * [CodeEditorLayout][com.aardarch.aardink.ui.CodeEditorLayout]'s `foldingProvider`.
 *
 * A server that does not fold gets no request, and has nothing to fold. A server that fails, or
 * answers `null`, has given no answer rather than an empty one, which would open whatever is folded
 * at the first hiccup: [requestFoldableRanges] throws, and the editor keeps the ranges it has.
 *
 * This can go to the editor before the server is ready. The first request waits for the document
 * to open, through [LspLanguageService.attach] or [LspLanguageService.didOpen], and the editor asks
 * again only after an edit, so a host that opens the document some other way gets no folds. A
 * document that will not open, because the handshake failed or the connection ended, is no answer. A
 * server that says to try later, as one still starting up may, is asked a few more times.
 */
class LspFoldingProvider(private val service: LspLanguageService) : FoldingProvider {

    /** Always empty: a server's answer has to be waited for, so only [requestFoldableRanges] has it. */
    override fun foldableRanges(document: CodeDocument): List<FoldRange> = emptyList()

    /**
     * What the server answers for [document], which should be what it was last sent, once the
     * document is open. The editor's cancellation ends the wait.
     *
     * @throws IllegalStateException if it gave no answer.
     */
    override suspend fun requestFoldableRanges(document: CodeDocument): List<FoldRange> =
        service.foldingRanges(document) ?: error("${service.documentUri}: the language server gave no folding ranges")
}
