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
package com.aardarch.aardink.languages.internal

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService

/**
 * No-op default for every [LanguageService] method. Subclasses override only what they actually
 * implement — the v1 built-in services only override [diagnostics].
 */
abstract class BaseLanguageService : LanguageService {

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

    override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = 0

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

    override suspend fun format(document: CodeDocument): String = document.text

    override val triggerCharacters: Set<Char> get() = emptySet()
}
