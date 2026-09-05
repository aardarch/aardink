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

import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.languages.internal.css.CssTokenizer
import com.aardarch.aardink.languages.internal.folding.BraceFoldingProvider
import com.aardarch.aardink.languages.internal.folding.MarkdownFoldingProvider
import com.aardarch.aardink.languages.internal.folding.TagFoldingProvider
import com.aardarch.aardink.languages.internal.folding.TomlFoldingProvider
import com.aardarch.aardink.languages.internal.html.HtmlTokenizer
import com.aardarch.aardink.languages.internal.json.JsonLanguageService
import com.aardarch.aardink.languages.internal.json.JsonTokenizer
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import com.aardarch.aardink.languages.internal.markdown.MarkdownTokenizer
import com.aardarch.aardink.languages.internal.toml.TomlLanguageService
import com.aardarch.aardink.languages.internal.toml.TomlTokenizer
import com.aardarch.aardink.languages.internal.typescript.TypeScriptTokenizer
import com.aardarch.aardink.languages.internal.xml.HtmlLanguageService
import com.aardarch.aardink.languages.internal.xml.XmlLanguageService
import com.aardarch.aardink.languages.internal.xml.XmlTokenizer

/**
 * Pre-built [LanguageDefinition] instances shipped with `:languages`.
 *
 * Use [LanguageRegistry.withBuiltIns] for a registry pre-populated with all of these, or
 * reference individual entries to wire one language directly into a [com.aardarch.aardink.core.CodeEditorState].
 */
object BuiltInLanguages {

    val Kotlin: LanguageDefinition = LanguageDefinition(
        id = "kotlin",
        displayName = "Kotlin",
        fileExtensions = listOf("kt", "kts"),
        tokenizer = KotlinTokenizer,
        foldingProvider = BraceFoldingProvider(supportsTripleQuoted = true),
    )

    val TypeScript: LanguageDefinition = LanguageDefinition(
        id = "typescript",
        displayName = "TypeScript",
        fileExtensions = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs"),
        tokenizer = TypeScriptTokenizer,
        foldingProvider = BraceFoldingProvider(supportsTripleQuoted = false),
    )

    val Json: LanguageDefinition = LanguageDefinition(
        id = "json",
        displayName = "JSON",
        fileExtensions = listOf("json", "jsonc"),
        tokenizer = JsonTokenizer,
        foldingProvider = BraceFoldingProvider(
            supportsLineComments = false,
            supportsBlockComments = false,
            supportsSingleQuoted = false,
        ),
        languageService = JsonLanguageService,
    )

    val Toml: LanguageDefinition = LanguageDefinition(
        id = "toml",
        displayName = "TOML",
        fileExtensions = listOf("toml"),
        tokenizer = TomlTokenizer,
        foldingProvider = TomlFoldingProvider,
        languageService = TomlLanguageService,
    )

    val Xml: LanguageDefinition = LanguageDefinition(
        id = "xml",
        displayName = "XML",
        fileExtensions = listOf("xml", "xsd", "xsl", "svg"),
        tokenizer = XmlTokenizer,
        foldingProvider = TagFoldingProvider(htmlMode = false),
        languageService = XmlLanguageService,
    )

    val Html: LanguageDefinition = LanguageDefinition(
        id = "html",
        displayName = "HTML",
        fileExtensions = listOf("html", "htm"),
        tokenizer = HtmlTokenizer,
        foldingProvider = TagFoldingProvider(htmlMode = true),
        languageService = HtmlLanguageService,
    )

    val Css: LanguageDefinition = LanguageDefinition(
        id = "css",
        displayName = "CSS",
        fileExtensions = listOf("css"),
        tokenizer = CssTokenizer,
        foldingProvider = BraceFoldingProvider(
            supportsLineComments = false,
            supportsSingleQuoted = true,
        ),
    )

    val Markdown: LanguageDefinition = LanguageDefinition(
        id = "markdown",
        displayName = "Markdown",
        fileExtensions = listOf("md", "markdown"),
        tokenizer = MarkdownTokenizer,
        foldingProvider = MarkdownFoldingProvider,
    )

    val PlainText: LanguageDefinition = LanguageDefinition(
        id = "plaintext",
        displayName = "Plain Text",
        fileExtensions = listOf("txt"),
        tokenizer = PlainTextTokenizer,
        foldingProvider = NoOpFoldingProvider,
        keyboardToolbarChars = emptyList(),
    )

    /** All built-in definitions, in display order suitable for a UI list. */
    val all: List<LanguageDefinition> = listOf(
        Kotlin,
        TypeScript,
        Json,
        Toml,
        Xml,
        Html,
        Css,
        Markdown,
        PlainText,
    )
}
