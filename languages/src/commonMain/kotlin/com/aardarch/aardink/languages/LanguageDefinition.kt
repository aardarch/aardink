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

import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.languages.internal.CommentedTokenizer
import com.aardarch.aardink.languages.internal.configuredService

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
 * @param fileNames Whole file names that are this language whatever their extension, as VS Code's
 *   `filenames` and `filenamePatterns` (e.g. `["Dockerfile", "Dockerfile.*"]`): matched without
 *   regard to case, with `*` standing for any run of characters. [LanguageRegistry.byFileName]
 *   reads them before the extension.
 */
data class LanguageDefinition(
    val id: String,
    val displayName: String,
    val fileExtensions: List<String>,
    val tokenizer: IncrementalTokenizer,
    val foldingProvider: FoldingProvider = NoOpFoldingProvider,
    val languageService: LanguageService? = null,
    val keyboardToolbarChars: List<Char> = tokenizer.keyboardToolbarChars(),
    val fileNames: List<String> = emptyList(),
) {
    /**
     * This language with [configuration] over it: what it says about closing pairs, surrounding a
     * selection and Enter comes first, and the language's service, if it has one, answers the rest.
     * Its `comments` replace the tokenizer's, and its fold markers add to the [foldingProvider].
     * A language with no service gets one, and keeps a line's indentation on Enter.
     */
    fun withConfiguration(configuration: LanguageConfiguration): LanguageDefinition = copy(
        tokenizer = configuration.comments?.let { CommentedTokenizer(tokenizer, it) } ?: tokenizer,
        foldingProvider = configuration.foldingMarkers?.let { foldingProvider.withRegionMarkers(it) } ?: foldingProvider,
        languageService = configuredService(configuration, configuration.comments ?: tokenizer.commentSyntax, languageService),
    )

    companion object {
        /**
         * A language built on [base]: [overlay] answers first and [base]'s language service fills in
         * what it does not, by [CompositeLanguageService]'s rules, so a dialect keeps its base's
         * diagnostics, closing tags, indentation and formatting (the Kotlin side of the web's
         * `registerLanguage({ extends })`). Without [inheritCompletions], [base]'s completions are
         * left out. Everything else comes from [base] unless given: a dialect usually has an [id]
         * and a [tokenizer] (a grammar) of its own.
         */
        fun extending(
            base: LanguageDefinition,
            overlay: LanguageService,
            inheritCompletions: Boolean = true,
            id: String = base.id,
            displayName: String = base.displayName,
            fileExtensions: List<String> = base.fileExtensions,
            tokenizer: IncrementalTokenizer = base.tokenizer,
        ): LanguageDefinition = base.copy(
            id = id,
            displayName = displayName,
            fileExtensions = fileExtensions,
            tokenizer = tokenizer,
            languageService = base.languageService?.let { CompositeLanguageService(overlay, it, inheritCompletions) } ?: overlay,
            keyboardToolbarChars = if (tokenizer === base.tokenizer) base.keyboardToolbarChars else tokenizer.keyboardToolbarChars(),
        )
    }
}
