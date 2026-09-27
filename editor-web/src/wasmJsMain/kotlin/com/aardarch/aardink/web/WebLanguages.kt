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
package com.aardarch.aardink.web

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.languages.NamedTokenType
import com.aardarch.aardink.ui.EditorThemeParser
import com.aardarch.aardink.ui.EditorThemes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What a language registered with [AardinkWeb.registerLanguage] knows beyond its grammar, answered
 * by the host (on the web, JavaScript functions returning Promises, which the export template
 * wraps). Each takes the document's text and, where it asks about a place, a 1-based line and
 * column, and answers with JSON, or null for nothing:
 *
 * - [completions]: an array of `{ label, insertText?, kind?, detail?, documentation? }`, where
 *   `kind` is one of `element`, `attribute`, `value`, `snippet`, `module`, `property`;
 * - [hover]: `{ title?, contents }`;
 * - [diagnostics]: an array of [WebDiagnostic].
 *
 * A language that `extends` a built-in one gets its answers as well: completions and diagnostics
 * from both, the host's first; hover from the host, or else from the built-in language.
 */
class WebLanguageProviders(
    val completions: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val hover: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val diagnostics: (suspend (text: String) -> String?)? = null,
)

/** A registered language's service: the host's providers first, then what [base] (the language it extends) has. */
internal class WebLanguageService(private val base: LanguageService?, private val providers: WebLanguageProviders) : LanguageService {

    override val triggerCharacters: Set<Char> get() = base?.triggerCharacters ?: emptySet()

    override val supportsRename: Boolean get() = base?.supportsRename ?: false

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val (line, column) = document.offsetToLineCol(cursorOffset)
        val own = asked { providers.completions?.invoke(document.text, line + 1, column + 1) }?.let(::parseCompletions).orEmpty()
        return own + base?.completions(document, cursorOffset).orEmpty()
    }

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? {
        val (line, column) = document.offsetToLineCol(offset)
        return asked { providers.hover?.invoke(document.text, line + 1, column + 1) }?.let(::parseHover) ?: base?.hoverDoc(document, offset)
    }

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val own = asked {
            providers.diagnostics?.invoke(document.text)
        }?.let { AardinkWeb.toDiagnostics(document, AardinkWeb.parseDiagnostics(it)) }.orEmpty()
        return own + base?.diagnostics(document).orEmpty()
    }

    override fun smartIndent(document: CodeDocument, lineIndex: Int): Int = base?.smartIndent(document, lineIndex) ?: 0

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = base?.autoClose(document, offset, charTyped)

    override suspend fun format(document: CodeDocument): String = base?.format(document) ?: document.text

    override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> =
        base?.codeActions(document, range).orEmpty()

    override suspend fun definition(document: CodeDocument, offset: Int): Location? = base?.definition(document, offset)

    override suspend fun references(document: CodeDocument, offset: Int): List<Location> = base?.references(document, offset).orEmpty()

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? = base?.signatureHelp(document, offset)

    override suspend fun prepareRename(document: CodeDocument, offset: Int): IntRange? = base?.prepareRename(document, offset)

    override suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> =
        base?.rename(document, offset, newName).orEmpty()

    override suspend fun formatRange(document: CodeDocument, range: IntRange): List<TextEdit> = base?.formatRange(document, range).orEmpty()

    /** What a host's provider answered; nothing when it failed (a rejected Promise), which costs its answer, not the editor. */
    private suspend fun asked(provider: suspend () -> String?): String? = try {
        provider()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        println("Aardink: a language provider failed: ${e.message}")
        null
    }

    private fun parseCompletions(json: String): List<CompletionItem> = parsed(json)?.let { root ->
        (root as? JsonArray)?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val label = item.string("label") ?: return@mapNotNull null
            CompletionItem(
                label = label,
                kind = when (item.string("kind")?.lowercase()) {
                    "element" -> CompletionKind.Element
                    "attribute" -> CompletionKind.Attribute
                    "snippet" -> CompletionKind.Snippet
                    "module" -> CompletionKind.Module
                    "property" -> CompletionKind.Property
                    else -> CompletionKind.Value
                },
                insertText = item.string("insertText") ?: label,
                documentation = item.string("documentation") ?: item.string("detail"),
            )
        }
    }.orEmpty()

    private fun parseHover(json: String): HoverDoc? {
        val root = parsed(json) as? JsonObject ?: return null
        val contents = root.string("contents") ?: return null
        return HoverDoc(title = root.string("title") ?: "", content = contents)
    }
}

private fun parsed(json: String): JsonElement? = try {
    Json.parseToJsonElement(json)
} catch (_: Exception) {
    null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** The registered language [definitionJson] describes; see [AardinkWeb.registerLanguage]. */
internal fun languageFrom(
    definitionJson: String,
    providers: WebLanguageProviders,
    registry: LanguageRegistry,
): Pair<LanguageDefinition, Set<String>> {
    val root = parsed(definitionJson) as? JsonObject ?: throw IllegalArgumentException("a language is a JSON object")
    val id = root.string("id")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("id: a language needs an id")
    val base = root.string("extends")?.let { registry.byId(it) ?: throw IllegalArgumentException("extends: no language with id '$it'") }
    val grammarJson = root["grammar"] as? JsonObject ?: throw IllegalArgumentException("grammar: a language needs a grammar object")
    val grammar = try {
        DeclarativeGrammar.parse(grammarJson.toString())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("grammar.${e.message}", e)
    }
    val extensions = (root["extensions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.removePrefix(".") }.orEmpty()
    val definition = LanguageDefinition(
        id = id,
        displayName = root.string("displayName") ?: id,
        fileExtensions = extensions,
        tokenizer = DeclarativeTokenizer(grammar),
        foldingProvider = base?.foldingProvider ?: NoOpFoldingProvider,
        languageService = WebLanguageService(base?.languageService, providers),
    )
    return definition to grammar.tokenNames
}

/**
 * A theme from VS Code theme JSON, and the colours it gives by scope name. What it does not set
 * comes from its base: `base` (a theme key) if given, else by its `type`, `vscode-light` for
 * light themes and `vscode-dark` otherwise.
 */
internal fun themeFrom(themeJson: String, themes: Map<String, EditorTheme>): Pair<EditorTheme, Map<String, Color>> {
    val root = parsed(themeJson) as? JsonObject ?: throw IllegalArgumentException("a theme is a JSON object")
    val parsed = EditorThemeParser.fromJson(themeJson) ?: throw IllegalArgumentException("not a VS Code theme")
    val base = root.string("base")?.let { themes[it] ?: throw IllegalArgumentException("base: no theme named '$it'") }
        ?: if (root.string("type") == "light") EditorThemes.VsCodeLight else EditorThemes.VsCodeDark
    // The parser fills what a theme leaves out from VS Code Dark; take the base's instead.
    val dark = EditorThemes.VsCodeDark
    val colors = root["colors"] as? JsonObject
    fun <T> pick(key: String, own: T, fromBase: T): T = if (colors?.get(key) != null) own else fromBase
    val tokenColors = base.tokenColors.toMutableMap()
    val ownForeground = colors?.get("editor.foreground") != null
    for ((type, color) in parsed.tokenColors) {
        // A colour the theme set: one that is not the parser's fill-in (or the foreground it set).
        if (dark.tokenColors[type] != color || (type == TokenType.Default && ownForeground)) tokenColors[type] = color
    }
    val theme = parsed.copy(
        background = pick("editor.background", parsed.background, base.background),
        gutterBackground = if (colors?.get("editorGutter.background") != null ||
            colors?.get("editor.background") != null
        ) {
            parsed.gutterBackground
        } else {
            base.gutterBackground
        },
        gutterForeground = pick("editorLineNumber.foreground", parsed.gutterForeground, base.gutterForeground),
        lineHighlight = pick("editor.lineHighlightBackground", parsed.lineHighlight, base.lineHighlight),
        selectionColor = pick("editor.selectionBackground", parsed.selectionColor, base.selectionColor),
        findMatchColor = pick("editor.findMatchHighlightBackground", parsed.findMatchColor, base.findMatchColor),
        cursorColor = pick("editorCursor.foreground", parsed.cursorColor, base.cursorColor),
        tokenColors = tokenColors,
        errorColor = base.errorColor,
        warningColor = base.warningColor,
        infoColor = base.infoColor,
    )
    return theme to scopeColors(root)
}

/** Every `tokenColors` scope's foreground, by scope name. */
private fun scopeColors(root: JsonObject): Map<String, Color> {
    val result = LinkedHashMap<String, Color>()
    val entries = root["tokenColors"] as? JsonArray ?: return result
    for (entry in entries) {
        val obj = entry as? JsonObject ?: continue
        val foreground = (obj["settings"] as? JsonObject)?.string("foreground") ?: continue
        val color = hexColor(foreground) ?: continue
        val scopes = when (val scope = obj["scope"]) {
            is JsonArray -> scope.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> scope.content.split(',').map { it.trim() }
            else -> continue
        }
        for (scope in scopes) if (scope.isNotEmpty()) result[scope] = color
    }
    return result
}

private fun hexColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(((value and 0xFF) shl 24) or (value ushr 8))
        else -> null
    }
}

/**
 * [theme] with a colour for each of a grammar's [names]: the most specific of the name and its
 * dotted prefixes that [named] (the theme's own scope colours) or the built-in types (by Monaco's
 * standard names: `keyword`, `tag`, `attribute.name`, ...) give a colour. A name nothing colours is
 * left to the default text colour.
 */
internal fun themeWithNames(theme: EditorTheme, named: Map<String, Color>, names: Set<String>): EditorTheme {
    if (names.isEmpty()) return theme
    val extra = HashMap<TokenType, Color>()
    for (name in names) {
        var candidate = name
        while (true) {
            val color = named[candidate] ?: BUILT_IN_NAMES[candidate]?.let { theme.tokenColors[it] }
            if (color != null) {
                extra[NamedTokenType(name)] = color
                break
            }
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(0, dot)
        }
    }
    return if (extra.isEmpty()) theme else theme.copy(tokenColors = theme.tokenColors + extra)
}

/** Monaco's standard token names, and the editor's types that colour them in the built-in themes. */
private val BUILT_IN_NAMES: Map<String, TokenType> = mapOf(
    "keyword" to TokenType.Keyword,
    "number" to TokenType.Number,
    "constant" to TokenType.Number,
    "type" to TokenType.TypeName,
    "identifier" to TokenType.Identifier,
    "variable" to TokenType.Identifier,
    "operator" to TokenType.Operator,
    "delimiter" to TokenType.Punctuation,
    "annotation" to TokenType.Annotation,
    "metatag" to TokenType.Annotation,
    "invalid" to TokenType.Invalid,
    "function" to TokenType.FunctionCall,
    "predefined" to TokenType.FunctionCall,
    "regexp" to TokenType.StringLiteral,
    // As the built-in XML highlighting colours them.
    "tag" to TokenType.TypeName,
    "attribute.name" to TokenType.Identifier,
    "attribute.value" to TokenType.StringLiteral,
    "entity" to TokenType.Number,
)
