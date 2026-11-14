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

import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.languages.internal.css.CssLanguageService
import com.aardarch.aardink.languages.internal.css.CssTokenizer
import com.aardarch.aardink.languages.internal.folding.BraceFoldingProvider
import com.aardarch.aardink.languages.internal.folding.MarkdownFoldingProvider
import com.aardarch.aardink.languages.internal.folding.TagFoldingProvider
import com.aardarch.aardink.languages.internal.folding.TomlFoldingProvider
import com.aardarch.aardink.languages.internal.grammar.C_LIKE_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.INI_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.INI_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.JAVA_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.PROPERTIES_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.PROPERTIES_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.PYTHON_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.PYTHON_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.SHELL_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.SHELL_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.SQL_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.SQL_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.YAML_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.YAML_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.grammarLanguage
import com.aardarch.aardink.languages.internal.html.HtmlTokenizer
import com.aardarch.aardink.languages.internal.json.JsonLanguageService
import com.aardarch.aardink.languages.internal.json.JsonTokenizer
import com.aardarch.aardink.languages.internal.kotlin.KotlinLanguageService
import com.aardarch.aardink.languages.internal.kotlin.KotlinTokenizer
import com.aardarch.aardink.languages.internal.markdown.MarkdownLanguageService
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
 *
 * From [Yaml] on, a language is a grammar written as data, with a language configuration for what
 * typing does (closing pairs, indentation on Enter); neither is read until the language is first
 * used.
 */
object BuiltInLanguages {

    val Kotlin: LanguageDefinition = LanguageDefinition(
        id = "kotlin",
        displayName = "Kotlin",
        fileExtensions = listOf("kt", "kts"),
        tokenizer = KotlinTokenizer,
        foldingProvider = BraceFoldingProvider(supportsTripleQuoted = true).withRegionMarkers(FoldingRegionMarkers.LineComment),
        languageService = KotlinLanguageService,
    )

    val TypeScript: LanguageDefinition = LanguageDefinition(
        id = "typescript",
        displayName = "TypeScript",
        fileExtensions = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs"),
        tokenizer = TypeScriptTokenizer,
        foldingProvider = BraceFoldingProvider(supportsTripleQuoted = false).withRegionMarkers(FoldingRegionMarkers.LineComment),
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
        foldingProvider = TomlFoldingProvider.withRegionMarkers(FoldingRegionMarkers.HashComment),
        languageService = TomlLanguageService,
    )

    val Xml: LanguageDefinition = LanguageDefinition(
        id = "xml",
        displayName = "XML",
        fileExtensions = listOf("xml", "xsd", "xsl", "svg"),
        tokenizer = XmlTokenizer,
        foldingProvider = TagFoldingProvider(htmlMode = false).withRegionMarkers(FoldingRegionMarkers.XmlComment),
        languageService = XmlLanguageService,
    )

    val Html: LanguageDefinition = LanguageDefinition(
        id = "html",
        displayName = "HTML",
        fileExtensions = listOf("html", "htm"),
        tokenizer = HtmlTokenizer,
        foldingProvider = TagFoldingProvider(htmlMode = true).withRegionMarkers(FoldingRegionMarkers.XmlComment),
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
        ).withRegionMarkers(FoldingRegionMarkers.BlockComment),
        languageService = CssLanguageService,
    )

    val Markdown: LanguageDefinition = LanguageDefinition(
        id = "markdown",
        displayName = "Markdown",
        fileExtensions = listOf("md", "markdown"),
        tokenizer = MarkdownTokenizer,
        foldingProvider = MarkdownFoldingProvider.withRegionMarkers(FoldingRegionMarkers.XmlComment),
        languageService = MarkdownLanguageService,
    )

    val Yaml: LanguageDefinition = grammarLanguage(
        id = "yaml",
        displayName = "YAML",
        fileExtensions = listOf("yaml", "yml"),
        grammar = YAML_GRAMMAR,
        configuration = YAML_CONFIGURATION,
        comments = HASH_COMMENTS,
        folding = IndentationFoldingProvider(tabSize = 2).withRegionMarkers(FoldingRegionMarkers.HashComment),
        toolbar = listOf(':', '-', '"', '\'', '#', '[', ']', '{', '}', '|', '>'),
    )

    /** Java's `.properties` files. */
    val Properties: LanguageDefinition = grammarLanguage(
        id = "properties",
        displayName = "Properties",
        fileExtensions = listOf("properties"),
        grammar = PROPERTIES_GRAMMAR,
        configuration = PROPERTIES_CONFIGURATION,
        comments = HASH_COMMENTS,
        folding = NoOpFoldingProvider,
        toolbar = listOf('=', ':', '.', '#', '\\', '$', '{', '}'),
    )

    val Ini: LanguageDefinition = grammarLanguage(
        id = "ini",
        displayName = "INI",
        fileExtensions = listOf("ini", "cfg", "editorconfig"),
        grammar = INI_GRAMMAR,
        configuration = INI_CONFIGURATION,
        comments = CommentSyntax(line = ";"),
        folding = TomlFoldingProvider,
        toolbar = listOf('[', ']', '=', ';', '"', '.'),
    )

    /** POSIX shell and Bash. */
    val Shell: LanguageDefinition = grammarLanguage(
        id = "shell",
        displayName = "Shell Script",
        fileExtensions = listOf("sh", "bash", "zsh", "ksh"),
        grammar = SHELL_GRAMMAR,
        configuration = SHELL_CONFIGURATION,
        comments = HASH_COMMENTS,
        folding = IndentationFoldingProvider().withRegionMarkers(FoldingRegionMarkers.HashComment),
        toolbar = listOf('$', '{', '}', '(', ')', '"', '\'', '|', '&', ';', '-', '/'),
    )

    val Python: LanguageDefinition = grammarLanguage(
        id = "python",
        displayName = "Python",
        fileExtensions = listOf("py", "pyi", "pyw"),
        grammar = PYTHON_GRAMMAR,
        configuration = PYTHON_CONFIGURATION,
        comments = HASH_COMMENTS,
        folding = IndentationFoldingProvider().withRegionMarkers(FoldingRegionMarkers.HashComment),
        toolbar = listOf(':', '(', ')', '[', ']', '{', '}', '"', '\'', '.', '=', '_'),
    )

    val Sql: LanguageDefinition = grammarLanguage(
        id = "sql",
        displayName = "SQL",
        fileExtensions = listOf("sql"),
        grammar = SQL_GRAMMAR,
        configuration = SQL_CONFIGURATION,
        comments = CommentSyntax(line = "--", blockStart = "/*", blockEnd = "*/"),
        folding = IndentationFoldingProvider().withRegionMarkers(
            FoldingRegionMarkers(Regex("""^\s*--\s*#?region\b"""), Regex("""^\s*--\s*#?endregion\b""")),
        ),
        toolbar = listOf('(', ')', '\'', ',', ';', '*', '=', '.', '<', '>'),
    )

    val Java: LanguageDefinition = grammarLanguage(
        id = "java",
        displayName = "Java",
        fileExtensions = listOf("java"),
        grammar = JAVA_GRAMMAR,
        configuration = C_LIKE_CONFIGURATION,
        comments = C_COMMENTS,
        folding = BraceFoldingProvider(supportsTripleQuoted = true).withRegionMarkers(FoldingRegionMarkers.LineComment),
        toolbar = listOf('{', '}', '(', ')', ';', '"', '.', '<', '>', '=', '@'),
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
        Yaml,
        Properties,
        Ini,
        Shell,
        Python,
        Sql,
        Java,
        PlainText,
    )
}

private val HASH_COMMENTS = CommentSyntax(line = "#")

private val C_COMMENTS = CommentSyntax(line = "//", blockStart = "/*", blockEnd = "*/")
