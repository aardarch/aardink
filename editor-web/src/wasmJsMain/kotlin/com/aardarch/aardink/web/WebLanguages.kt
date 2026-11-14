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
import androidx.compose.ui.graphics.takeOrElse
import com.aardarch.aardink.core.ChromeColors
import com.aardarch.aardink.core.CodeAction
import com.aardarch.aardink.core.CodeActionKind
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.ColorInfo
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.FoldKind
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.core.InlineCompletionProvider
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import com.aardarch.aardink.languages.IndentationFoldingProvider
import com.aardarch.aardink.languages.LanguageConfiguration
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.languages.XmlSchema
import com.aardarch.aardink.languages.XmlSchemaLanguageService
import com.aardarch.aardink.ui.EditorThemeParser
import com.aardarch.aardink.ui.EditorThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
 * - [documentSymbols]: an array of [WebDocumentSymbol], the document's structure as a tree.
 * - [documentColors]: an array of Monaco's `IColorInformation`, `{ range, color: { red, green,
 *   blue, alpha } }` with each channel from 0 to 1, for the colour swatches. The picker writes a
 *   picked colour back as CSS hex.
 * - [inlayHints]: asked about a range, as a [WebRange] in JSON: an array of `{ position: { lineNumber,
 *   column }, label }`, 1-based as Monaco's, for what `inlayHints` shows among the characters.
 * - [codeLenses]: an array of Monaco's `CodeLens`, `{ range, command: { id, title, arguments? } }`,
 *   for what `codeLens` shows in a row above the line `range` starts on.
 * - [runCodeLensCommand]: a lens was clicked. It gets the command's `id` and its `arguments` as a
 *   JSON array, `[]` for none, and what it answers is not read. A click comes back here because an
 *   answer is JSON, and a language's service has no hold of any one editor.
 * - [formatOnType]: one of the definition's `autoFormatTriggerCharacters` was typed, with the caret
 *   after it at the position given and `options` as `{ tabSize, insertSpaces }`: an array of Monaco's
 *   `TextEdit`, `{ range, text }`, for the editor to apply with the `formatOnType` option on. Enter
 *   is `"\n"`.
 * - [definition] and [references]: Monaco's `Location`, `{ uri?, range }`, one or an array (and
 *   `LocationLink`'s `targetUri` and `targetRange`). A location without `uri` is in this document;
 *   one with a `uri` is another file's, which the editor does not open.
 * - [formatDocument] and [formatRange]: an array of Monaco's `TextEdit`. They get the options as
 *   [formatOnType] does, and [formatRange] the range as a [WebRange] in JSON first.
 * - [signatureHelp]: Monaco's `SignatureHelpResult`, `{ value: { signatures, activeSignature,
 *   activeParameter } }`, or the `SignatureHelp` in it. A parameter's `label` is its text or Monaco's
 *   `[start, end]` in the signature's label; `documentation` a string or `{ value }`.
 * - [codeActions]: asked about a range (a [WebRange] in JSON) with the diagnostics there as an array
 *   of [WebDiagnostic]: Monaco's `CodeActionList`, `{ actions }`, or the array. An action is `{ title,
 *   kind?, isPreferred?, edit?: { edits: [{ textEdit: { range, text } }] }, command?: { id, arguments? } }`;
 *   edits with a `resource` are another file's and leave the action out. Its command goes to
 *   [runCodeLensCommand], as a lens's does, after its edits.
 * - [rename]: the new name after the place: Monaco's `WorkspaceEdit`, `{ edits: [{ textEdit }] }`,
 *   or `{ rejectReason }`, which renames nothing. Edits to other files leave it all undone.
 * - [resolveRename]: Monaco's `RenameLocation`, `{ range, text }`, the name to rename; `{ rejectReason }`
 *   or null where nothing can be renamed.
 * - [foldingRanges]: an array of Monaco's `FoldingRange`, `{ start, end, kind? }`, 1-based lines,
 *   `kind` `comment`, `imports` or `region`. These replace the language's own folds.
 * - [semanticTokens]: Monaco's `SemanticTokens`, `{ data }`, read by the definition's
 *   `semanticTokensLegend`, `{ tokenTypes, tokenModifiers }`.
 * - [resolveCompletionItem]: asked with an item as the provider gave it, when it is selected in the
 *   list: the item with more filled in (`documentation`, `detail`), or null to keep it.
 * - [resolveCodeLens]: asked with a lens that came without a `command`: the lens with one.
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
    val documentSymbols: (suspend (text: String) -> String?)? = null,
    val documentColors: (suspend (text: String) -> String?)? = null,
    val inlayHints: (suspend (text: String, rangeJson: String) -> String?)? = null,
    val codeLenses: (suspend (text: String) -> String?)? = null,
    val runCodeLensCommand: (suspend (id: String, argsJson: String) -> Unit)? = null,
    val formatOnType: (suspend (text: String, line: Int, column: Int, ch: String, optionsJson: String) -> String?)? = null,
    val definition: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val references: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val formatDocument: (suspend (text: String, optionsJson: String) -> String?)? = null,
    val formatRange: (suspend (text: String, rangeJson: String, optionsJson: String) -> String?)? = null,
    val signatureHelp: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val codeActions: (suspend (text: String, rangeJson: String, diagnosticsJson: String) -> String?)? = null,
    val rename: (suspend (text: String, line: Int, column: Int, newName: String) -> String?)? = null,
    val resolveRename: (suspend (text: String, line: Int, column: Int) -> String?)? = null,
    val foldingRanges: (suspend (text: String) -> String?)? = null,
    val semanticTokens: (suspend (text: String) -> String?)? = null,
    val resolveCompletionItem: (suspend (itemJson: String) -> String?)? = null,
    val resolveCodeLens: (suspend (text: String, lensJson: String) -> String?)? = null,
)

/**
 * [AardinkWeb.registerLanguage]'s one function for every provider, as [WebLanguageProviders]: each
 * goes by its member's name, with what it takes beyond the text as a JSON array. A new provider is
 * one more line here, and one in the npm package's `PROVIDERS`. A template's copy changes in
 * nothing: a dispatcher answers null for a name it does not know, which is no provider.
 */
internal fun dispatchedProviders(
    dispatcher: suspend (method: String, text: String, argsJson: String) -> String?,
    provided: Set<String>? = null,
) = WebLanguageProviders(
    completions = { text, line, column -> dispatcher("completions", text, "[$line,$column]") },
    hover = { text, line, column -> dispatcher("hover", text, "[$line,$column]") },
    diagnostics = { text -> dispatcher("diagnostics", text, "[]") },
    documentSymbols = { text -> dispatcher("documentSymbols", text, "[]") },
    documentColors = { text -> dispatcher("documentColors", text, "[]") },
    inlayHints = { text, rangeJson -> dispatcher("inlayHints", text, "[$rangeJson]") },
    codeLenses = { text -> dispatcher("codeLenses", text, "[]") },
    // No text: a click is about a command, and the document may have moved on since the answer.
    runCodeLensCommand = { id, argsJson ->
        dispatcher("runCodeLensCommand", "", "[${JsonPrimitive(id)},$argsJson]")
    },
    formatOnType = { text, line, column, ch, optionsJson ->
        dispatcher("formatOnType", text, "[$line,$column,${JsonPrimitive(ch)},$optionsJson]")
    },
    // Asked only where the page has the provider, when it says which it has: whether there is one
    // decides whether a rename is offered and whose folds are shown.
    definition = provided.ifHas("definition") { text, line, column -> dispatcher("definition", text, "[$line,$column]") },
    references = provided.ifHas("references") { text, line, column -> dispatcher("references", text, "[$line,$column]") },
    formatDocument = provided.ifHas("formatDocument") { text, optionsJson -> dispatcher("formatDocument", text, "[$optionsJson]") },
    formatRange = provided.ifHas("formatRange") { text, rangeJson, optionsJson ->
        dispatcher("formatRange", text, "[$rangeJson,$optionsJson]")
    },
    signatureHelp = provided.ifHas("signatureHelp") { text, line, column -> dispatcher("signatureHelp", text, "[$line,$column]") },
    codeActions = provided.ifHas("codeActions") { text, rangeJson, diagnosticsJson ->
        dispatcher("codeActions", text, "[$rangeJson,$diagnosticsJson]")
    },
    rename = provided.ifHas("rename") { text, line, column, newName ->
        dispatcher("rename", text, "[$line,$column,${JsonPrimitive(newName)}]")
    },
    resolveRename = provided.ifHas("resolveRename") { text, line, column -> dispatcher("resolveRename", text, "[$line,$column]") },
    foldingRanges = provided.ifHas("foldingRanges") { text -> dispatcher("foldingRanges", text, "[]") },
    semanticTokens = provided.ifHas("semanticTokens") { text -> dispatcher("semanticTokens", text, "[]") },
    resolveCompletionItem = provided.ifHas("resolveCompletionItem") { itemJson -> dispatcher("resolveCompletionItem", "", "[$itemJson]") },
    resolveCodeLens = provided.ifHas("resolveCodeLens") { text, lensJson -> dispatcher("resolveCodeLens", text, "[$lensJson]") },
)

/** [provider] if the page has the provider [name] (see [has]), else null. */
private fun <F> Set<String>?.ifHas(name: String, provider: F): F? = if (has(name)) provider else null

/** Whether a page that says which providers it has ([provided]) has [name]; one that does not say has them all. */
private fun Set<String>?.has(name: String): Boolean = this == null || name in this

/** The names of the providers a definition says the page has (its `provided`), or null when it does not say. */
internal fun providedIn(definitionJson: String): Set<String>? =
    ((parsed(definitionJson) as? JsonObject)?.get("provided") as? JsonArray)?.mapNotNullTo(HashSet()) {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    }

/**
 * A registered language's service: the host's providers ([JsProviderService]) first, then a
 * language [server]'s if one follows the editor, then what [base] (the language it extends) has,
 * each by [CompositeLanguageService]'s rules. With [inheritCompletions] the base's completions come
 * last, and [ownTriggerCharacters] (those the definition gave, if any) add to the base's; without
 * it, they replace them. On top of the composite's rules: an answer `{ suggestions, exclusive: true }`
 * leaves out the base's completions for that request (a server's still come), and whether the list
 * is asked for again on each keystroke is the host's answer's `incomplete` alone.
 */
internal class WebLanguageService private constructor(
    private val js: JsProviderService,
    private val base: LanguageService,
    private val server: LanguageService?,
    inheritCompletions: Boolean,
) : CompositeLanguageService(
    primary = js,
    fallback = server?.let { CompositeLanguageService(it, base) } ?: base,
    inheritCompletions = inheritCompletions,
) {
    constructor(
        base: LanguageService?,
        providers: WebLanguageProviders,
        ownTriggerCharacters: Set<Char>? = null,
        inheritCompletions: Boolean = true,
        autoFormatTriggerCharacters: Set<Char> = emptySet(),
        semanticLegend: WebSemanticLegend? = null,
    ) : this(
        js = JsProviderService(
            providers,
            ownTriggerCharacters ?: base?.triggerCharacters.orEmpty(),
            autoFormatTriggerCharacters,
            semanticLegend,
        ),
        base = base ?: NoLanguageService,
        server = null,
        inheritCompletions = inheritCompletions,
    )

    /** This language with [server] between the host's providers and the base: the host, then the server, then the base. */
    fun withServer(server: LanguageService): WebLanguageService = WebLanguageService(js, base, server, inheritCompletions)

    override val completionsAreIncomplete: Boolean
        get() = js.lastIncomplete

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val own = js.completions(document, cursorOffset)
        if (!inheritCompletions || js.lastExclusive) {
            return server?.let { merged(own, it.completions(document, cursorOffset)) } ?: own
        }
        return merged(own, fallback.completions(document, cursorOffset))
    }
}

/** No language at all: what a registered language without `extends` falls back to. */
private object NoLanguageService : LanguageService {
    override val triggerCharacters: Set<Char> get() = emptySet()

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
}

/**
 * What a host's JavaScript providers answer, and nothing else: completions, hover and diagnostics.
 * [triggerCharacters] are the definition's own, or the extended language's when it gave none.
 * [formatOnTypeCharacters] are the definition's `autoFormatTriggerCharacters`, and none without a
 * provider to ask.
 */
private class JsProviderService(
    private val providers: WebLanguageProviders,
    override val triggerCharacters: Set<Char>,
    autoFormatTriggerCharacters: Set<Char>,
    private val semanticLegend: WebSemanticLegend?,
) : LanguageService {

    override val supportsRename: Boolean get() = providers.rename != null

    /** The provider's last diagnostics, for a code action provider's `markers`. */
    private var lastDiagnostics: List<WebDiagnostic> = emptyList()

    /** The items of the last completion answer, as the provider gave them, for [resolveCompletion]. */
    private var given: Map<CompletionItem, JsonObject> = emptyMap()

    override val formatOnTypeCharacters: Set<Char> = if (providers.formatOnType == null) emptySet() else autoFormatTriggerCharacters

    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** False only after an answer of `{ suggestions, incomplete: false }`: asked once per word then. */
    var lastIncomplete = true
        private set

    /** The last answer was `{ suggestions, exclusive: true }`. */
    var lastExclusive = false
        private set

    override val completionsAreIncomplete: Boolean
        get() = lastIncomplete

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        lastIncomplete = true
        lastExclusive = false
        val (line, column) = document.offsetToLineCol(cursorOffset)
        val answer = asked {
            providers.completions?.invoke(document.text, line + 1, column + 1)
        }?.let { parseCompletions(it, document, cursorOffset) }
        lastExclusive = answer?.exclusive == true
        given = answer?.given.orEmpty()
        return answer?.items.orEmpty()
    }

    override suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
        val resolve = providers.resolveCompletionItem ?: return item
        val original = given[item] ?: return item
        val resolved = parsed(asked { resolve(original.toString()) } ?: return item) as? JsonObject ?: return item
        return item.copy(
            documentation = resolved.text("documentation") ?: item.documentation,
            detail = resolved.string("detail") ?: item.detail,
        )
    }

    override suspend fun definition(document: CodeDocument, offset: Int): Location? {
        val (line, column) = document.offsetToLineCol(offset)
        return asked { providers.definition?.invoke(document.text, line + 1, column + 1) }
            ?.let { parseLocations(document, it) }?.firstOrNull()
    }

    override suspend fun references(document: CodeDocument, offset: Int): List<Location> {
        val (line, column) = document.offsetToLineCol(offset)
        return asked { providers.references?.invoke(document.text, line + 1, column + 1) }
            ?.let { parseLocations(document, it) }.orEmpty()
    }

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String {
        val format = providers.formatDocument ?: return document.text
        val edits = asked { format(document.text, optionsJson(options)) }?.let { AardinkWeb.parseTextEdits(document, it) }
        return if (edits.isNullOrEmpty()) document.text else applied(document.text, edits)
    }

    override suspend fun formatRange(document: CodeDocument, range: IntRange, options: FormattingOptions): List<TextEdit> {
        val format = providers.formatRange ?: return emptyList()
        return asked { format(document.text, AardinkWeb.rangeJson(document, range), optionsJson(options)) }
            ?.let { AardinkWeb.parseTextEdits(document, it) }.orEmpty()
    }

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? {
        val (line, column) = document.offsetToLineCol(offset)
        return asked { providers.signatureHelp?.invoke(document.text, line + 1, column + 1) }?.let(::parseSignatureHelp)
    }

    override suspend fun codeActions(document: CodeDocument, range: IntRange): List<CodeAction> {
        val ask = providers.codeActions ?: return emptyList()
        // Monaco's `context.markers`: those of this provider's last diagnostics that touch the range.
        val touching = lastDiagnostics.filter { web ->
            val shown = AardinkWeb.toDiagnostics(document, listOf(web)).firstOrNull()?.range ?: return@filter false
            shown.first <= range.last + 1 && range.first <= shown.last + 1
        }
        val diagnostics = AardinkWeb.diagnosticsJson(touching)
        return asked { ask(document.text, AardinkWeb.rangeJson(document, range), diagnostics) }
            ?.let { parseCodeActions(document, it) }.orEmpty()
    }

    override suspend fun prepareRename(document: CodeDocument, offset: Int): IntRange? {
        if (providers.rename == null) return null
        val (line, column) = document.offsetToLineCol(offset)
        val resolve = providers.resolveRename ?: return wordAt(document, offset)
        val answer = parsed(asked { resolve(document.text, line + 1, column + 1) } ?: return null) as? JsonObject ?: return null
        if (answer.given("rejectReason") != null) return null
        return rangeIn(document, answer["range"])?.takeIf { !it.isEmpty() }
    }

    override suspend fun rename(document: CodeDocument, offset: Int, newName: String): List<TextEdit> {
        val (line, column) = document.offsetToLineCol(offset)
        val answer = parsed(asked { providers.rename?.invoke(document.text, line + 1, column + 1, newName) } ?: return emptyList())
            as? JsonObject ?: return emptyList()
        if (answer.given("rejectReason") != null) return emptyList()
        // Another file's edits leave the rename undone: half a rename is worse than none.
        return workspaceEdits(document, answer) ?: emptyList()
    }

    override suspend fun semanticTokens(document: CodeDocument): List<Token> {
        val legend = semanticLegend ?: return emptyList()
        val answer = parsed(asked { providers.semanticTokens?.invoke(document.text) } ?: return emptyList()) as? JsonObject
            ?: return emptyList()
        val data = answer["data"] as? JsonArray ?: return emptyList()
        return decodeSemanticTokens(data, legend, document)
    }

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? {
        val (line, column) = document.offsetToLineCol(offset)
        return asked { providers.hover?.invoke(document.text, line + 1, column + 1) }?.let(::parseHover)
    }

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val web = asked { providers.diagnostics?.invoke(document.text) }?.let { AardinkWeb.parseDiagnostics(it) }.orEmpty()
        lastDiagnostics = web
        return AardinkWeb.toDiagnostics(document, web)
    }

    override suspend fun documentSymbols(document: CodeDocument): List<DocumentSymbol> = asked {
        providers.documentSymbols?.invoke(document.text)
    }?.let { AardinkWeb.parseSymbols(document, it) }.orEmpty()

    override suspend fun documentColors(document: CodeDocument): List<ColorInfo> = asked {
        providers.documentColors?.invoke(document.text)
    }?.let { AardinkWeb.parseColors(document, it) }.orEmpty()

    override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> = asked {
        providers.inlayHints?.invoke(document.text, AardinkWeb.rangeJson(document, range))
    }?.let { AardinkWeb.parseInlayHints(document, it) }.orEmpty()

    override suspend fun codeLenses(document: CodeDocument): List<CodeLens> {
        val answer = asked { providers.codeLenses?.invoke(document.text) } ?: return emptyList()
        val resolve = providers.resolveCodeLens
        // Those without a command, resolved now, a few at once, at most MAX_RESOLVED_LENSES of them.
        val resolved = if (resolve == null) {
            answer
        } else {
            val lenses = (parsed(answer) as? JsonArray).orEmpty()
            val text = document.text
            coroutineScope {
                lenses.mapIndexed { index, lens ->
                    async {
                        val bare = lens is JsonObject && lens["command"] !is JsonObject && index < MAX_RESOLVED_LENSES
                        if (bare) parsed(asked { resolve(text, lens.toString()) } ?: "null") as? JsonObject ?: lens else lens
                    }
                }.awaitAll()
            }.let { JsonArray(it).toString() }
        }
        return AardinkWeb.parseCodeLenses(document, resolved, ::runCommand)
    }

    /** A click on a lens, for the host. It has been asked for: nothing here calls it off. */
    private fun runCommand(id: String, argsJson: String) {
        commands.launch {
            asked {
                providers.runCodeLensCommand?.invoke(id, argsJson)
                null
            }
        }
    }

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

    override suspend fun formatOnType(document: CodeDocument, offset: Int, typed: Char, options: FormattingOptions): List<TextEdit> {
        val (line, column) = document.offsetToLineCol(offset)
        return asked {
            providers.formatOnType?.invoke(document.text, line + 1, column + 1, typed.toString(), optionsJson(options))
        }?.let { AardinkWeb.parseTextEdits(document, it) }.orEmpty()
    }

    /** Monaco's `FormattingOptions`. */
    private fun optionsJson(options: FormattingOptions): String =
        """{"tabSize":${options.tabSize},"insertSpaces":${options.insertSpaces}}"""

    /** Monaco's `CodeActionList` or the array of actions, as actions in [document]. */
    private fun parseCodeActions(document: CodeDocument, json: String): List<CodeAction> {
        val root = parsed(json)
        val actions = ((root as? JsonObject)?.get("actions") ?: root) as? JsonArray ?: return emptyList()
        return actions.mapNotNull { element ->
            val action = element as? JsonObject ?: return@mapNotNull null
            val title = action.string("title") ?: return@mapNotNull null
            val edits = (action["edit"] as? JsonObject)?.let { workspaceEdits(document, it) ?: return@mapNotNull null }.orEmpty()
            val command = action["command"] as? JsonObject
            val id = command?.string("id")
            if (edits.isEmpty() && id == null) return@mapNotNull null
            CodeAction(
                title = title,
                kind = codeActionKind(action.string("kind")),
                edits = edits,
                isPreferred = (action["isPreferred"] as? JsonPrimitive)?.booleanOrNull == true,
                onApply = id?.let { commandId ->
                    val argsJson = (command["arguments"] as? JsonArray)?.toString() ?: "[]"
                    suspend { runCommand(commandId, argsJson) }
                },
            )
        }
    }

    /** What a host's provider answered; nothing when it failed (a rejected Promise), which costs its answer, not the editor. */
    private suspend fun asked(provider: suspend () -> String?): String? = try {
        provider()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        println("Aardink: a language provider failed: ${e.message}")
        null
    }

    /** The host's completions, filtered and ordered, whether they leave out the base's, and each as it was given. */
    private class Answer(val items: List<CompletionItem>, val exclusive: Boolean, val given: Map<CompletionItem, JsonObject>)

    /** A host's item with the `filterText` and `sortText` it gave, if any, and the item as it was given. */
    private class Offered(val item: CompletionItem, val filterText: String?, val sortText: String?, val json: JsonObject)

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
                    kind = completionKind(item.string("kind") ?: item.int("kind")?.toString()),
                    insertText = item.string("insertText") ?: label,
                    documentation = item.text("documentation"),
                    filterText = filterText ?: label,
                    replaceRange = rangeIn(document, item["range"]),
                    isSnippet = rules and INSERT_AS_SNIPPET != 0,
                    keepWhitespace = rules and KEEP_WHITESPACE != 0,
                    detail = item.string("detail"),
                    commitCharacters = (item["commitCharacters"] as? JsonArray).orEmpty()
                        .mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.contentOrNull?.singleOrNull() },
                ),
                filterText = filterText,
                sortText = item.string("sortText"),
                json = item,
            )
        }
        if ((list?.get("incomplete") as? JsonPrimitive)?.booleanOrNull == false) lastIncomplete = false
        val kept = offered.filter { it.filterText == null || fuzzyMatches(typedFor(document, cursor, it.item), it.filterText) }
        val ordered = if (kept.any { it.sortText != null }) kept.sortedBy { it.sortText ?: it.item.label } else kept
        return Answer(
            ordered.map { it.item },
            exclusive = (list?.get("exclusive") as? JsonPrimitive)?.booleanOrNull == true,
            given = ordered.associate { it.item to it.json },
        )
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

/**
 * What the hosts' providers of one language offer, for [AardinkWeb.registerInlineCompletionsProvider]:
 * all of them at once, oldest first, as Monaco's providers for a language are. One that fails costs
 * its own answer. An answer that comes after the editor stopped waiting is dropped; a Promise has no
 * way to be called off, so the host's work goes on, and nothing here asks it to stop.
 */
internal class WebInlineCompletions(private val providers: List<suspend (text: String, line: Int, column: Int) -> String?>) :
    InlineCompletionProvider {

    override suspend fun provideInlineCompletions(document: CodeDocument, cursorOffset: Int): List<InlineCompletion> = coroutineScope {
        val (line, column) = document.offsetToLineCol(cursorOffset)
        val text = document.text
        providers.map { provider ->
            async {
                try {
                    provider(text, line + 1, column + 1)?.let { parseInlineCompletions(document, it) }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("Aardink: an inline completions provider failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }
}

/**
 * Monaco's `InlineCompletions`, `{ items: [{ insertText, range? }] }`, or the bare array of items. An
 * `insertText` that is a snippet, `{ snippet }`, shows and goes in as its text: placeholders as their
 * default text, a choice as its first, tab stops and variables as nothing; one that is then empty is
 * left out. So is an item whose `range` is not one in [document], which would otherwise read as no
 * range given.
 */
internal fun parseInlineCompletions(document: CodeDocument, json: String): List<InlineCompletion> {
    val root = parsed(json)
    val items = ((root as? JsonObject)?.get("items") ?: root) as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val text = item.string("insertText")
            ?: (item["insertText"] as? JsonObject)?.string("snippet")?.let(::snippetText)?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val range = item["range"].takeUnless { it is JsonNull } ?: return@mapNotNull InlineCompletion(text)
        rangeIn(document, range)?.let { InlineCompletion(text, it) }
    }
}

/**
 * A completion's `kind` by its name in `index.d.ts`, Monaco's `CompletionItemKind` name for the
 * same thing, or Monaco's `CompletionItemKind` number. `function` and `color` stay the aardflex
 * names for `transform` and `colorRef` they were in 0.6.
 */
private fun completionKind(name: String?): CompletionKind {
    name?.toIntOrNull()?.let { return MONACO_KINDS.getOrElse(it) { CompletionKind.Value } }
    return when (name?.lowercase()) {
        "element" -> CompletionKind.Element
        "attribute" -> CompletionKind.Attribute
        "snippet" -> CompletionKind.Snippet
        "module" -> CompletionKind.Module
        "property" -> CompletionKind.Property
        "transform", "function" -> CompletionKind.Transform
        "colorref", "color" -> CompletionKind.ColorRef
        "method" -> CompletionKind.Method
        "constructor" -> CompletionKind.Constructor
        "field" -> CompletionKind.Field
        "variable" -> CompletionKind.Variable
        "class" -> CompletionKind.Class
        "struct" -> CompletionKind.Struct
        "interface" -> CompletionKind.Interface
        "enum" -> CompletionKind.Enum
        "enummember" -> CompletionKind.EnumMember
        "keyword" -> CompletionKind.Keyword
        "text" -> CompletionKind.Text
        "file" -> CompletionKind.File
        "folder" -> CompletionKind.Folder
        "reference" -> CompletionKind.Reference
        "constant" -> CompletionKind.Constant
        "event" -> CompletionKind.Event
        "operator" -> CompletionKind.Operator
        "typeparameter" -> CompletionKind.TypeParameter
        "unit" -> CompletionKind.Unit
        "user" -> CompletionKind.User
        "issue" -> CompletionKind.Issue
        else -> CompletionKind.Value
    }
}

/** Monaco's `CompletionItemKind`, by number. */
private val MONACO_KINDS = listOf(
    CompletionKind.Method, CompletionKind.Function, CompletionKind.Constructor, CompletionKind.Field,
    CompletionKind.Variable, CompletionKind.Class, CompletionKind.Struct, CompletionKind.Interface,
    CompletionKind.Module, CompletionKind.Property, CompletionKind.Event, CompletionKind.Operator,
    CompletionKind.Unit, CompletionKind.Value, CompletionKind.Constant, CompletionKind.Enum,
    CompletionKind.EnumMember, CompletionKind.Keyword, CompletionKind.Text, CompletionKind.Color,
    CompletionKind.File, CompletionKind.Reference, CompletionKind.Color, CompletionKind.Folder,
    CompletionKind.TypeParameter, CompletionKind.User, CompletionKind.Issue, CompletionKind.Snippet,
)

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

/**
 * The registered language [definitionJson] describes; see [AardinkWeb.registerLanguage]. Its grammar
 * joins [grammars], by the language's id, for any grammar's `nextEmbedded` to find. Its tokenizer
 * reads [grammars] each time it needs one, not now, so a language can embed one registered after it.
 */
internal fun languageFrom(
    definitionJson: String,
    providers: WebLanguageProviders,
    registry: LanguageRegistry,
    grammars: MutableMap<String, DeclarativeGrammar>,
): LanguageDefinition {
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
    fun characters(name: String): Set<Char>? = root.given(name)?.let { value ->
        val given = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (given == null || given.any { it == null || it.length != 1 }) {
            throw IllegalArgumentException("$name: an array of one-character strings")
        }
        given.mapTo(LinkedHashSet()) { it!!.single() }
    }
    val triggerCharacters = characters("triggerCharacters")
    val autoFormatTriggerCharacters = characters("autoFormatTriggerCharacters").orEmpty()
    val semanticLegend = root.given("semanticTokensLegend")?.let { value ->
        val legend = value as? JsonObject ?: throw IllegalArgumentException("semanticTokensLegend: { tokenTypes, tokenModifiers }")
        fun names(key: String) = (legend[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        WebSemanticLegend(names("tokenTypes"), names("tokenModifiers"))
    }
    val inheritCompletions = root.given("inheritCompletions")?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("inheritCompletions: true or false")
    } ?: true
    // An XML dialect's vocabulary: completions, hover and checks from it, over the language extended.
    val schema = root.given("schema")?.let { value ->
        val obj = value as? JsonObject ?: throw IllegalArgumentException("schema: an XML schema object")
        try {
            XmlSchema.parse(obj.toString())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("schema: ${e.message}", e)
        }
    }
    val baseService = when {
        schema != null -> XmlSchemaLanguageService(schema, base?.languageService ?: BuiltInLanguages.Xml.languageService)
        else -> base?.languageService
    }
    val definition = LanguageDefinition(
        id = id,
        displayName = root.string("displayName") ?: id,
        fileExtensions = extensions,
        tokenizer = DeclarativeTokenizer(grammar) { grammars[it] },
        // A language with no folding of its own folds by indentation, as VS Code does.
        foldingProvider =
        base?.foldingProvider ?: if (schema != null) BuiltInLanguages.Xml.foldingProvider else IndentationFoldingProvider(),
        languageService = baseService,
    )
    // Under the providers, which answer nothing a configuration does: a language with nothing else
    // then gets the configuration's own service, which keeps a line's indentation on Enter.
    val configured = root.given("configuration")?.let { definition.withConfiguration(configurationFrom(it)) } ?: definition
    grammars[id] = grammar
    return configured.copy(
        languageService =
        WebLanguageService(
            configured.languageService,
            providers,
            triggerCharacters,
            inheritCompletions,
            autoFormatTriggerCharacters,
            semanticLegend,
        ),
        // The page's folds replace the language's own, as a Monaco folding provider's do.
        foldingProvider = providers.foldingRanges?.let { WebFoldingProvider(it, configured.foldingProvider) } ?: configured.foldingProvider,
    )
}

/** A definition's `configuration`: the object, or the text of a `language-configuration.json`. */
private fun configurationFrom(value: JsonElement): LanguageConfiguration = try {
    LanguageConfiguration.parse((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString())
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("configuration.${e.message}", e)
}

/**
 * A theme from VS Code theme JSON, or from Monaco's `defineTheme` data (`{ base, inherit, rules,
 * colors }`, told apart by its `rules`), which [EditorThemeParser.fromMonacoJson] reads with its
 * rules' backgrounds and its `hc-black` and `hc-light` bases. What VS Code JSON does not set comes
 * from its base: `base` (a theme key) if given, else by its `type`, `vscode-light` for light themes
 * and `vscode-dark` otherwise. Its rules' scopes stay in its token colours by name (`tag.aardflex`),
 * which the editor resolves by dotted prefix as Monaco does.
 */
internal fun themeFrom(themeJson: String, themes: Map<String, EditorTheme>): EditorTheme {
    val root = parsed(themeJson) as? JsonObject ?: throw IllegalArgumentException("a theme is a JSON object")
    if (root["rules"] is JsonArray) {
        return EditorThemeParser.fromMonacoJson(themeJson) ?: throw IllegalArgumentException("not Monaco theme data")
    }
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
        chromeColors = parsed.chromeColors.orElse(base.chromeColors),
        tokenBackgrounds = base.tokenBackgrounds + parsed.tokenBackgrounds,
    )
    return theme
}

/** Each of these colours the theme set, else [base]'s. */
private fun ChromeColors.orElse(base: ChromeColors): ChromeColors {
    fun Color.or(other: Color) = takeOrElse { other }
    return ChromeColors(
        minimapBackground = minimapBackground.or(base.minimapBackground),
        minimapSlider = minimapSlider.or(base.minimapSlider),
        scrollbarSlider = scrollbarSlider.or(base.scrollbarSlider),
        scrollbarSliderHover = scrollbarSliderHover.or(base.scrollbarSliderHover),
        indentGuide = indentGuide.or(base.indentGuide),
        activeIndentGuide = activeIndentGuide.or(base.activeIndentGuide),
        bracketPairGuide = bracketPairGuide.or(base.bracketPairGuide),
        ruler = ruler.or(base.ruler),
        widgetBackground = widgetBackground.or(base.widgetBackground),
        widgetForeground = widgetForeground.or(base.widgetForeground),
        widgetBorder = widgetBorder.or(base.widgetBorder),
        inlayHint = inlayHint.or(base.inlayHint),
        inlayHintBackground = inlayHintBackground.or(base.inlayHintBackground),
        codeLens = codeLens.or(base.codeLens),
        ghostText = ghostText.or(base.ghostText),
    )
}

/** How many lenses that came without a command [LanguageService.codeLenses] resolves at a time. */
private const val MAX_RESOLVED_LENSES = 100

/**
 * The page's folds ([ask], Monaco's `FoldingRange`s), in place of [own]: the language's, used while
 * there is no answer yet, and when the page's provider fails or answers nothing.
 */
private class WebFoldingProvider(private val ask: suspend (text: String) -> String?, private val own: FoldingProvider) : FoldingProvider {
    override fun foldableRanges(document: CodeDocument): List<FoldRange> = own.foldableRanges(document)

    override suspend fun requestFoldableRanges(document: CodeDocument): List<FoldRange> {
        val answer = try {
            ask(document.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("Aardink: a folding provider failed: ${e.message}")
            null
        } ?: return own.requestFoldableRanges(document)
        val ranges = (parsed(answer) as? JsonArray ?: return own.requestFoldableRanges(document)).mapNotNull { element ->
            val range = element as? JsonObject ?: return@mapNotNull null
            val start = range.int("start") ?: return@mapNotNull null
            val end = range.int("end") ?: return@mapNotNull null
            if (start < 1 || end <= start || end > document.lineCount) return@mapNotNull null
            FoldRange(
                startLine = start - 1,
                endLine = end - 1,
                kind = when (range.string("kind")) {
                    "comment" -> FoldKind.Comment
                    "imports" -> FoldKind.Imports
                    "region" -> FoldKind.Region
                    else -> null
                },
            )
        }
        return ranges
    }
}

/**
 * Monaco's `Location` or `LocationLink`, one or an array, as locations: in [document] when it has
 * no `uri` (`targetUri`), else another file's, with an empty range and the 1-based place given.
 */
private fun parseLocations(document: CodeDocument, json: String): List<Location> {
    val root = parsed(json) ?: return emptyList()
    val items = root as? JsonArray ?: JsonArray(listOf(root))
    return items.mapNotNull { element ->
        val location = element as? JsonObject ?: return@mapNotNull null
        val uri = location.string("uri") ?: location.string("targetUri")
        val range = (location.given("range") ?: location.given("targetSelectionRange") ?: location.given("targetRange")) as? JsonObject
            ?: return@mapNotNull null
        if (uri == null) {
            rangeIn(document, range)?.let { Location("", it) }
        } else {
            val line = range.int("startLineNumber") ?: return@mapNotNull null
            val column = range.int("startColumn") ?: 1
            Location(uri, IntRange.EMPTY, line - 1, column - 1)
        }
    }
}

/**
 * The edits of Monaco's `WorkspaceEdit`, `{ edits: [{ textEdit: { range, text } }] }`, in [document];
 * null when one is another file's (it has a `resource`) or cannot be read, as nothing of it applies then.
 */
private fun workspaceEdits(document: CodeDocument, edit: JsonObject): List<TextEdit>? {
    val edits = edit["edits"] as? JsonArray ?: return emptyList()
    return edits.map { element ->
        val item = element as? JsonObject ?: return null
        if (item.given("resource") != null) return null
        val textEdit = item["textEdit"] as? JsonObject ?: return null
        val range = rangeIn(document, textEdit["range"]) ?: return null
        TextEdit(range, textEdit.string("text").orEmpty())
    }
}

/** [edits] (offsets in [text], not overlapping) made to [text]. */
private fun applied(text: String, edits: List<TextEdit>): String {
    val out = StringBuilder(text)
    for (edit in edits.sortedByDescending { it.range.first }) {
        val start = edit.range.first.coerceIn(0, out.length)
        val end = (edit.range.last + 1).coerceIn(start, out.length)
        out.setRange(start, end, edit.newText)
    }
    return out.toString()
}

/** The word at [offset] in [document], for a rename with no `resolveRenameLocation`; null where there is none. */
private fun wordAt(document: CodeDocument, offset: Int): IntRange? {
    fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    var start = offset.coerceIn(0, document.length)
    var end = start
    while (start > 0 && isWord(document[start - 1])) start--
    while (end < document.length && isWord(document[end])) end++
    return if (end > start) start until end else null
}

/** Monaco's `CodeActionKind` value. */
private fun codeActionKind(kind: String?): CodeActionKind = when {
    kind == null -> CodeActionKind.QuickFix
    kind.startsWith("refactor") -> CodeActionKind.Refactor
    kind.startsWith("source.organizeImports") -> CodeActionKind.SourceOrganizeImports
    else -> CodeActionKind.QuickFix
}

/** Monaco's `SignatureHelpResult`, or the `SignatureHelp` in it. */
private fun parseSignatureHelp(json: String): SignatureHelp? {
    val root = parsed(json) as? JsonObject ?: return null
    val help = root["value"] as? JsonObject ?: root
    val signatures = (help["signatures"] as? JsonArray).orEmpty().mapNotNull { element ->
        val signature = element as? JsonObject ?: return@mapNotNull null
        val label = signature.string("label") ?: return@mapNotNull null
        SignatureInformation(
            label = label,
            documentation = signature.text("documentation"),
            parameters = (signature["parameters"] as? JsonArray).orEmpty().mapNotNull { p ->
                val parameter = p as? JsonObject ?: return@mapNotNull null
                when (val given = parameter["label"]) {
                    is JsonPrimitive -> given.contentOrNull?.let { ParameterInformation(it, parameter.text("documentation")) }

                    is JsonArray -> {
                        val start = (given.getOrNull(0) as? JsonPrimitive)?.doubleOrNull?.toInt()
                        val end = (given.getOrNull(1) as? JsonPrimitive)?.doubleOrNull?.toInt()
                        if (start == null || end == null || start < 0 || end > label.length || end < start) {
                            null
                        } else {
                            ParameterInformation(label.substring(start, end), parameter.text("documentation"), start until end)
                        }
                    }

                    else -> null
                }
            },
        )
    }
    if (signatures.isEmpty()) return null
    return SignatureHelp(
        signatures = signatures,
        activeSignature = help.int("activeSignature")?.coerceIn(0, signatures.lastIndex) ?: 0,
        activeParameter = help.int("activeParameter") ?: 0,
    )
}

/**
 * What the numbers in Monaco's semantic tokens stand for: a language's `semanticTokensLegend`. A
 * token's type is named as a language server's are (`type.class`, `variable.parameter`), with its
 * modifiers after it, so a theme colours both alike.
 */
internal class WebSemanticLegend(private val tokenTypes: List<String>, private val tokenModifiers: List<String>) {
    private val named = HashMap<Long, NamedTokenType>()

    fun typeOf(type: Int, modifiers: Int): NamedTokenType? {
        val name = tokenTypes.getOrNull(type)?.takeIf { it.isNotEmpty() } ?: return null
        return named.getOrPut(type.toLong() shl Int.SIZE_BITS or modifiers.toUInt().toLong()) {
            NamedTokenType(
                buildString {
                    append(THEME_NAMES[name] ?: name)
                    for (bit in 0 until minOf(tokenModifiers.size, Int.SIZE_BITS)) {
                        if (modifiers ushr bit and 1 == 1) append('.').append(tokenModifiers[bit])
                    }
                },
            )
        }
    }

    private companion object {
        /** As `:languages-lsp`'s legend names them, which a theme colours by. */
        val THEME_NAMES = mapOf(
            "namespace" to "type.namespace",
            "class" to "type.class",
            "enum" to "type.enum",
            "interface" to "type.interface",
            "struct" to "type.struct",
            "typeParameter" to "type.typeParameter",
            "parameter" to "variable.parameter",
            "property" to "variable.property",
            "enumMember" to "variable.enumMember",
            "event" to "variable.event",
            "method" to "function.method",
            "macro" to "function.macro",
            "modifier" to "keyword.modifier",
            "decorator" to "annotation.decorator",
        )
    }
}

/**
 * Monaco's `SemanticTokens.data`, five integers a token (line and start relative to the token
 * before, length, type, modifiers), as tokens in [document]; as `:languages-lsp` decodes a server's,
 * leaving out what the document does not have. Integers that are not counts are no answer.
 */
internal fun decodeSemanticTokens(data: JsonArray, legend: WebSemanticLegend, document: CodeDocument): List<Token> {
    val tokens = ArrayList<Token>(data.size / 5)
    var line = 0
    var column = 0
    for (index in 0..data.size - 5 step 5) {
        val numbers = IntArray(5) {
            (data[index + it] as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { n -> n >= 0 }
                ?: return emptyList()
        }
        line += numbers[0]
        column = if (numbers[0] == 0) column + numbers[1] else numbers[1]
        if (line >= document.lineCount) break
        val start = document.lineStart(line) + column
        if (start >= document.lineEnd(line)) continue
        val end = minOf(start + numbers[2], document.length)
        val type = legend.typeOf(numbers[3], numbers[4]) ?: continue
        if (end > start) tokens += Token(start, end, type)
    }
    return tokens
}

/**
 * A snippet's text as it reads with nothing filled in: `${1:name}` its default text, `${1|a,b|}` its
 * first choice, `$1`, `$0` and variables (`$TM_FILENAME`, `${TM_SELECTED_TEXT:default}` its default)
 * nothing, `\$`, `\}` and `\\` the character.
 */
internal fun snippetText(snippet: String): String {
    val out = StringBuilder(snippet.length)
    var i = 0
    fun readTo(end: Int) {
        while (i < end) {
            val c = snippet[i]
            when {
                c == '\\' && i + 1 < snippet.length && snippet[i + 1] in "$}\\,|" -> {
                    out.append(snippet[i + 1])
                    i += 2
                }

                c == '$' && i + 1 < snippet.length && (snippet[i + 1].isLetterOrDigit() || snippet[i + 1] == '_') -> {
                    i++
                    while (i < snippet.length && (snippet[i].isLetterOrDigit() || snippet[i] == '_')) i++
                }

                c == '$' && i + 1 < snippet.length && snippet[i + 1] == '{' -> {
                    i += 2
                    while (i < snippet.length && (snippet[i].isLetterOrDigit() || snippet[i] == '_')) i++
                    when (snippet.getOrNull(i)) {
                        ':' -> {
                            i++
                            readTo(closingBrace(snippet, i))
                            i++
                        }

                        '|' -> {
                            val close = snippet.indexOf("|}", i + 1).takeIf { it >= 0 } ?: snippet.length
                            out.append(snippet.substring(i + 1, close).split(',').first())
                            i = close + 2
                        }

                        else -> i = closingBrace(snippet, i) + 1
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }
    readTo(snippet.length)
    return out.toString()
}

/** The index of the `}` closing a placeholder whose text starts at [from], past nested ones and escapes. */
private fun closingBrace(snippet: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < snippet.length) {
        when {
            snippet[i] == '\\' -> i++
            snippet[i] == '$' && snippet.getOrNull(i + 1) == '{' -> depth++
            snippet[i] == '}' -> if (depth == 0) return i else depth--
        }
        i++
    }
    return snippet.length
}
