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
package com.aardarch.editor.languages

import com.aardarch.editor.core.NoOpFoldingProvider
import com.aardarch.editor.core.PlainTextTokenizer
import com.aardarch.editor.languages.internal.css.CssTokenizer
import com.aardarch.editor.languages.internal.folding.BraceFoldingProvider
import com.aardarch.editor.languages.internal.folding.MarkdownFoldingProvider
import com.aardarch.editor.languages.internal.folding.TagFoldingProvider
import com.aardarch.editor.languages.internal.html.HtmlTokenizer
import com.aardarch.editor.languages.internal.json.JsonLanguageService
import com.aardarch.editor.languages.internal.json.JsonTokenizer
import com.aardarch.editor.languages.internal.kotlin.KotlinTokenizer
import com.aardarch.editor.languages.internal.markdown.MarkdownTokenizer
import com.aardarch.editor.languages.internal.typescript.TypeScriptTokenizer
import com.aardarch.editor.languages.internal.xml.HtmlLanguageService
import com.aardarch.editor.languages.internal.xml.XmlLanguageService
import com.aardarch.editor.languages.internal.xml.XmlTokenizer

/**
 * Pre-built [LanguageDefinition] instances shipped with `:languages`.
 *
 * Use [LanguageRegistry.withBuiltIns] for a registry pre-populated with all of these, or
 * reference individual entries to wire one language directly into a [com.aardarch.editor.core.CodeEditorState].
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
        Xml,
        Html,
        Css,
        Markdown,
        PlainText,
    )
}
