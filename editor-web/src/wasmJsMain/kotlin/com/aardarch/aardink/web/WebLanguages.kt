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
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.NoOpFoldingProvider
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.EditorThemeParser
import com.aardarch.aardink.ui.EditorThemes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What a language registered with [AardinkWeb.registerLanguage] knows beyond its grammar, answered
 * by the host (on the web, JavaScript functions returning Promises, which the export template
 * wraps). Each takes the document's text and, where it asks about a place, a 1-based line and
 * column, and answers with JSON, or null for nothing:
 *
 * - [completions]: an array of items (Monaco's `CompletionItem` fields), or Monaco's completion
 *   list `{ suggestions: [...], exclusive? }`. An item is `{ label, insertText?, insertTextRules?,
 *   kind?, detail?, documentation?, filterText?, sortText?, range? }`:
 *   - `kind` is one of `element`, `attribute`, `value`, `snippet`, `module`, `property`,
 *     `transform` (or Monaco's `function`) and `colorRef` (or `color`); anything else is `value`;
 *   - `insertTextRules` holds Monaco's flags: 4 (`InsertAsSnippet`) makes `insertText` a snippet,
 *     and 1 (`KeepWhitespace`) inserts its whitespace as written;
 *   - `range` is the text the item replaces, 1-based and end-exclusive as Monaco's `IRange`
 *     (`startLineNumber`, `startColumn`, `endLineNumber`, `endColumn`), or Monaco's
 *     `{ insert, replace }`, of which `replace` is used. Without it the editor replaces the word
 *     before the caret, back to the first of `<>{}()[]"'=,;.@|:` or whitespace;
 *   - with `filterText`, the item is offered only while what has been typed of it (from the start
 *     of what it replaces to the caret) matches: its characters in order, ignoring case, the first
 *     at the start of `filterText` or of a word in it. Items without one are offered as they come;
 *   - `sortText`, when any item has one, orders the host's items by it (by `label` for those
 *     without one), as Monaco does; otherwise they keep the order given.
 * - [hover]: `{ title?, contents, example? }`, where `example` is shown as code below the text;
 * - [diagnostics]: an array of [WebDiagnostic].
 *
 * A language that `extends` a built-in one gets its answers as well: completions and diagnostics
 * from both, the host's first; hover from the host, or else from the built-in language. A built-in
 * completion with the same kind and label as one of the host's is left out: the host's wins. The
 * definition's `inheritCompletions: false` leaves out the built-in completions altogether, and an
 * answer `{ suggestions, exclusive: true }` leaves them out for that request only.
 */
class WebLanguageProviders(
    val completions: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val hover: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val diagnostics: (suspend (text: String) -> String?)? = null,
)

/**
 * A registered language's service: the host's providers first, then what [base] (the language it
 * extends) has. With [inheritCompletions] the base's completions follow the host's, and
 * [ownTriggerCharacters] (those the definition gave, if any) add to the base's; without it, they
 * replace them.
 */
internal class WebLanguageService(
    private val base: LanguageService?,
    private val providers: WebLanguageProviders,
    private val ownTriggerCharacters: Set<Char>? = null,
    private val inheritCompletions: Boolean = true,
) : LanguageService {

    override val triggerCharacters: Set<Char> = run {
        val inherited = base?.triggerCharacters.orEmpty()
        when {
            ownTriggerCharacters == null -> inherited
            inheritCompletions -> ownTriggerCharacters + inherited
            else -> ownTriggerCharacters
        }
    }

    override val supportsRename: Boolean get() = base?.supportsRename ?: false

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val (line, column) = document.offsetToLineCol(cursorOffset)
        val answer = asked {
            providers.completions?.invoke(document.text, line + 1, column + 1)
        }?.let { parseCompletions(it, document, cursorOffset) }
        val own = answer?.items.orEmpty()
        if (!inheritCompletions || answer?.exclusive == true) return own
        val inherited = base?.completions(document, cursorOffset).orEmpty()
        if (own.isEmpty()) return inherited
        val offered = own.mapTo(HashSet()) { it.kind to it.label }
        return own + inherited.filterNot { (it.kind to it.label) in offered }
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

    /** The host's completions, filtered and ordered, and whether they leave out the base's. */
    private class Answer(val items: List<CompletionItem>, val exclusive: Boolean)

    /** A host's item with the `filterText` and `sortText` it gave, if any. */
    private class Offered(val item: CompletionItem, val filterText: String?, val sortText: String?)

    private fun parseCompletions(json: String, document: CodeDocument, cursor: Int): Answer? {
        val root = parsed(json)
        val list = root as? JsonObject
        val array = (list?.get("suggestions") ?: root) as? JsonArray ?: return null
        val offered = array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val label = item.string("label") ?: return@mapNotNull null
            val rules = (item["insertTextRules"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val filterText = item.string("filterText")
            Offered(
                CompletionItem(
                    label = label,
                    kind = completionKind(item.string("kind")),
                    insertText = item.string("insertText") ?: label,
                    documentation = item.text("documentation") ?: item.string("detail"),
                    filterText = filterText ?: label,
                    replaceRange = rangeIn(document, item["range"]),
                    isSnippet = rules and INSERT_AS_SNIPPET != 0,
                    keepWhitespace = rules and KEEP_WHITESPACE != 0,
                ),
                filterText = filterText,
                sortText = item.string("sortText"),
            )
        }
        val kept = offered.filter { it.filterText == null || fuzzyMatches(typedFor(document, cursor, it.item), it.filterText) }
        val ordered = if (kept.any { it.sortText != null }) kept.sortedBy { it.sortText ?: it.item.label } else kept
        return Answer(ordered.map { it.item }, exclusive = (list?.get("exclusive") as? JsonPrimitive)?.booleanOrNull == true)
    }

    private fun parseHover(json: String): HoverDoc? {
        val root = parsed(json) as? JsonObject ?: return null
        val contents = root.string("contents") ?: return null
        return HoverDoc(
            title = root.string("title") ?: "",
            content = contents,
            example = root.string("example")?.takeIf {
                it.isNotEmpty()
            },
        )
    }
}

/** A completion's `kind` by its name in `index.d.ts`, or Monaco's `CompletionItemKind` name for the same thing. */
private fun completionKind(name: String?): CompletionKind = when (name?.lowercase()) {
    "element" -> CompletionKind.Element
    "attribute" -> CompletionKind.Attribute
    "snippet" -> CompletionKind.Snippet
    "module" -> CompletionKind.Module
    "property" -> CompletionKind.Property
    "transform", "function" -> CompletionKind.Transform
    "colorref", "color" -> CompletionKind.ColorRef
    else -> CompletionKind.Value
}

/**
 * Monaco's `IRange` (1-based, end-exclusive), or the `replace` range of its `{ insert, replace }`,
 * as the document range it covers; null when absent or not a range in [document].
 */
private fun rangeIn(document: CodeDocument, value: JsonElement?): IntRange? {
    val given = value as? JsonObject ?: return null
    val range = given["replace"] as? JsonObject ?: given
    val startLine = range.int("startLineNumber") ?: return null
    val startColumn = range.int("startColumn") ?: return null
    val endLine = range.int("endLineNumber") ?: return null
    val endColumn = range.int("endColumn") ?: return null
    if (startLine < 1 || endLine > document.lineCount || endLine < startLine) return null
    val start = document.lineColToOffset(startLine - 1, maxOf(0, startColumn - 1))
    val end = document.lineColToOffset(endLine - 1, maxOf(0, endColumn - 1))
    return if (end < start) null else start until end
}

/**
 * Characters the editor's guess of what a completion replaces stops at, going back from the caret:
 * `COMPLETION_BOUNDARY_CHARS` in `:editor`'s `CodeEditorLayout`, which this must match.
 */
private val WORD_BOUNDARIES = setOf('<', '>', '{', '}', '(', ')', '[', ']', '"', '\'', '=', ',', ';', '.', ' ', '\n', '\t', '@', '|', ':')

/** What has been typed of [item]: from the start of what it replaces to the [cursor]. */
private fun typedFor(document: CodeDocument, cursor: Int, item: CompletionItem): String {
    val end = cursor.coerceIn(0, document.length)
    val start = item.replaceRange?.first ?: run {
        var from = end
        while (from > 0 && document[from - 1] !in WORD_BOUNDARIES) from--
        from
    }
    return document.subSequence(start.coerceIn(0, end), end).toString()
}

/**
 * Whether [typed] matches [candidate] as Monaco's filter does, simplified: its characters appear in
 * [candidate] in order, ignoring case, the first at the start of [candidate] or of a word in it
 * (after a character that is not a letter or digit, or a capital after a small letter). Nothing
 * typed matches everything.
 */
internal fun fuzzyMatches(typed: String, candidate: String): Boolean {
    if (typed.isEmpty()) return true
    for (start in candidate.indices) {
        if (!candidate[start].equals(typed[0], ignoreCase = true) || !isWordStart(candidate, start)) continue
        var next = start + 1
        var matched = 1
        while (matched < typed.length && next < candidate.length) {
            if (candidate[next].equals(typed[matched], ignoreCase = true)) matched++
            next++
        }
        if (matched == typed.length) return true
    }
    return false
}

private fun isWordStart(text: String, index: Int): Boolean {
    if (index == 0) return true
    val before = text[index - 1]
    return !before.isLetterOrDigit() || (text[index].isUpperCase() && before.isLowerCase())
}

private fun parsed(json: String): JsonElement? = try {
    Json.parseToJsonElement(json)
} catch (_: Exception) {
    null
}

/** Monaco's `CompletionItemInsertTextRule` flags. */
private const val KEEP_WHITESPACE = 1
private const val INSERT_AS_SNIPPET = 4

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** A string, or the `value` of Monaco's `IMarkdownString` (`{ value }`), shown as it is. */
private fun JsonObject.text(key: String): String? = string(key) ?: (this[key] as? JsonObject)?.string("value")

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt()

/** The value at [key], unless it is absent or `null`. */
private fun JsonObject.given(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

/** The registered language [definitionJson] describes; see [AardinkWeb.registerLanguage]. */
internal fun languageFrom(definitionJson: String, providers: WebLanguageProviders, registry: LanguageRegistry): LanguageDefinition {
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
    val triggerCharacters = root.given("triggerCharacters")?.let { value ->
        val given = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (given == null || given.any { it == null || it.length != 1 }) {
            throw IllegalArgumentException("triggerCharacters: an array of one-character strings")
        }
        given.mapTo(LinkedHashSet()) { it!!.single() }
    }
    val inheritCompletions = root.given("inheritCompletions")?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("inheritCompletions: true or false")
    } ?: true
    val definition = LanguageDefinition(
        id = id,
        displayName = root.string("displayName") ?: id,
        fileExtensions = extensions,
        tokenizer = DeclarativeTokenizer(grammar),
        foldingProvider = base?.foldingProvider ?: NoOpFoldingProvider,
        languageService = WebLanguageService(base?.languageService, providers, triggerCharacters, inheritCompletions),
    )
    return definition
}

/**
 * A theme from VS Code theme JSON. What it does not set comes from its base: `base` (a theme key)
 * if given, else by its `type`, `vscode-light` for light themes and `vscode-dark` otherwise. Its
 * rules' scopes stay in its token colours by name (`tag.aardflex`), which the editor resolves by
 * dotted prefix as Monaco does.
 */
internal fun themeFrom(themeJson: String, themes: Map<String, EditorTheme>): EditorTheme {
    val root = parsed(themeJson) as? JsonObject ?: throw IllegalArgumentException("a theme is a JSON object")
    val parsed = EditorThemeParser.fromJson(themeJson) ?: throw IllegalArgumentException("not a VS Code theme")
    val base = root.string("base")?.let { themes[it] ?: throw IllegalArgumentException("base: no theme named '$it'") }
        ?: if (root.string("type") == "light") EditorThemes.VsCodeLight else EditorThemes.VsCodeDark
    // The parser fills what a theme leaves out from VS Code Dark; take the base's instead.
    val dark = EditorThemes.VsCodeDark
    val colors = root["colors"] as? JsonObject
    fun <T> pick(key: String, own: T, fromBase: T): T = if (colors?.get(key) != null) own else fromBase
    val ownForeground = colors?.get("editor.foreground") != null
    // The colours the theme set: those that are not the parser's fill-in (or the foreground it set).
    val own = parsed.tokenColors.filter { (type, color) -> dark.tokenColors[type] != color || (type == TokenType.Default && ownForeground) }
    // The base's sub-names stay, but not under a type the theme colours itself: a theme with its
    // own keyword colour does not keep the base's `keyword.flow`.
    val inherited = base.tokenColors.filterKeys { key ->
        key !is NamedTokenType || own.keys.none { it !is NamedTokenType && it.scope.isNotEmpty() && key.name.startsWith(it.scope + ".") }
    }
    val tokenColors = inherited + own
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
        tokenFontStyles = base.tokenFontStyles + parsed.tokenFontStyles,
        errorColor = pick("editorError.foreground", parsed.errorColor, base.errorColor),
        warningColor = pick("editorWarning.foreground", parsed.warningColor, base.warningColor),
        infoColor = pick("editorInfo.foreground", parsed.infoColor, base.infoColor),
    )
    return theme
}
