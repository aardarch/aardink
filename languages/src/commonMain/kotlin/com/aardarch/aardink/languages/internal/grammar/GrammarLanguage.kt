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
package com.aardarch.aardink.languages.internal.grammar

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import com.aardarch.aardink.languages.LanguageConfiguration
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.internal.BaseLanguageService
import com.aardarch.aardink.languages.internal.ConfigurationLanguageService

/**
 * A built-in language written as data: a [DeclarativeGrammar] and a [LanguageConfiguration], each as
 * JSON. Neither is read until the language is first used, so a registry with every built-in costs
 * nothing for those never opened: the grammar when the first line is tokenized, the configuration
 * at the first key that asks it something.
 *
 * [comments] stand for the grammar's and the configuration's (they say the same), so toggling a
 * comment reads neither. [folding] and [toolbar] are given here for the same reason. [service], when
 * given, answers what the configuration does not (CSS's colours for SCSS and LESS).
 */
internal fun grammarLanguage(
    id: String,
    displayName: String,
    fileExtensions: List<String>,
    grammar: String,
    configuration: String,
    comments: CommentSyntax?,
    folding: FoldingProvider,
    toolbar: List<Char>,
    service: LanguageService? = null,
    fileNames: List<String> = emptyList(),
): LanguageDefinition = LanguageDefinition(
    id = id,
    displayName = displayName,
    fileExtensions = fileExtensions,
    tokenizer = LazyGrammarTokenizer(grammar, comments, toolbar),
    foldingProvider = folding,
    languageService = lazyConfiguredService(configuration, comments, service),
    keyboardToolbarChars = toolbar,
    fileNames = fileNames,
)

/** A [DeclarativeTokenizer] for [grammar], parsed at its first use. */
internal class LazyGrammarTokenizer(
    private val grammar: String,
    override val commentSyntax: CommentSyntax?,
    private val toolbar: List<Char>,
) : IncrementalTokenizer {
    private val tokenizer by lazy { DeclarativeTokenizer(DeclarativeGrammar.parse(grammar)) }

    override fun tokenizeFull(text: String): List<Token> = tokenizer.tokenizeFull(text)

    override suspend fun tokenizeFullCooperative(text: String): List<Token> = tokenizer.tokenizeFullCooperative(text)

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> =
        tokenizer.tokenizeLines(text, dirtyRange, previousTokens)

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = tokenizer.canSpanLines(lineIndex, tokens)

    override fun keyboardToolbarChars(): List<Char> = toolbar
}

/**
 * What [configuration] says about typing, read at its first use, over [service] when there is one:
 * the configuration answers for typing, and [service] for the rest (and for what typing the
 * configuration has no rule for).
 */
internal fun lazyConfiguredService(configuration: String, comments: CommentSyntax?, service: LanguageService? = null): LanguageService {
    val configured = LazyConfiguredService(configuration, comments)
    return if (service == null) configured else CompositeLanguageService(configured, service)
}

/** What [configuration] says about typing (closing pairs, surrounding, Enter), read at its first use. */
internal class LazyConfiguredService(private val configuration: String, private val comments: CommentSyntax?) : BaseLanguageService() {
    private val service by lazy { ConfigurationLanguageService(LanguageConfiguration.parse(configuration), comments) }

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String =
        service.smartIndent(document, lineIndex, options)

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = service.autoClose(document, offset, charTyped)

    override fun surroundingClose(typed: Char): String? = service.surroundingClose(typed)
}
