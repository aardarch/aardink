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
 */
class LspFoldingProvider(private val service: LspLanguageService) : FoldingProvider {

    /** Always empty: a server's answer has to be waited for, so only [requestFoldableRanges] has it. */
    override fun foldableRanges(document: CodeDocument): List<FoldRange> = emptyList()

    /**
     * What the server answers for [document], which should be what it was last sent.
     *
     * @throws IllegalStateException if it gave no answer.
     */
    override suspend fun requestFoldableRanges(document: CodeDocument): List<FoldRange> =
        service.foldingRanges(document) ?: error("${service.documentUri}: the language server gave no folding ranges")
}
