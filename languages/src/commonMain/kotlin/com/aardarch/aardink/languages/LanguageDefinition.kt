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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NoOpFoldingProvider

/**
 * Bundle of everything the editor needs to render a particular language.
 *
 * Consumers normally consume these through [BuiltInLanguages] (one per supported language) or
 * via a [LanguageRegistry]. They can also build their own and pass them to a registry, or
 * `copy(...)` an existing definition to swap a single component (e.g. a custom tokenizer) while
 * inheriting the rest.
 *
 * @param id Stable identifier (e.g. `"kotlin"`). Used as the registry key.
 * @param displayName Human-readable label for UI (e.g. `"Kotlin"`).
 * @param fileExtensions Lower-case extensions, no leading dot (e.g. `["kt", "kts"]`).
 * @param tokenizer Syntax-highlighting tokenizer.
 * @param foldingProvider Fold-region producer. Defaults to [NoOpFoldingProvider].
 * @param languageService Optional completions / diagnostics / hover provider.
 * @param keyboardToolbarChars Characters surfaced in the editor's quick-input toolbar. Defaults
 *   to whatever the [tokenizer] reports.
 */
data class LanguageDefinition(
    val id: String,
    val displayName: String,
    val fileExtensions: List<String>,
    val tokenizer: IncrementalTokenizer,
    val foldingProvider: FoldingProvider = NoOpFoldingProvider,
    val languageService: LanguageService? = null,
    val keyboardToolbarChars: List<Char> = tokenizer.keyboardToolbarChars(),
)
