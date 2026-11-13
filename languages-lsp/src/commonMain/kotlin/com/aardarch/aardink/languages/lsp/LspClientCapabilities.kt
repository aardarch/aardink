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
package com.aardarch.aardink.languages.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `ClientCapabilities` [LspClient.initialize] declares unless told otherwise: what this module
 * and the editor can really handle, since servers shape their answers to it.
 *
 * Every feature says `dynamicRegistration: false`, because of what `client/registerCapability`
 * covers only text sync is acted on. Positions are UTF-16, the one encoding `ContentChange` columns come in, and
 * `workspaceEdit` declares no `resourceOperations`, so a server keeps to text edits.
 *
 * `completionItem.resolveSupport` leaves out `additionalTextEdits` on purpose: a server would then
 * hold an auto-import's edit back until an item is resolved, and accepting one does not wait for
 * that.
 *
 * `textDocument.diagnostic` is what makes a server with pull diagnostics offer them, and it may stop
 * pushing once it does: [LspLanguageService] pulls for its document, but a host driving [LspClient]
 * without one, and relying on [LspClient.addDiagnosticsListener], should leave that member out.
 */
val DefaultClientCapabilities: JsonObject = buildJsonObject {
    putJsonObject("general") {
        put("positionEncodings", strings("utf-16"))
    }
    putJsonObject("window") {
        put("workDoneProgress", true)
        putJsonObject("showMessage") {
            putJsonObject("messageActionItem") { put("additionalPropertiesSupport", false) }
        }
    }
    putJsonObject("workspace") {
        put("applyEdit", true)
        put("configuration", true)
        putJsonObject("workspaceEdit") { put("documentChanges", true) }
        feature("executeCommand")
    }
    putJsonObject("textDocument") {
        feature("synchronization") {
            put("willSave", true)
            put("didSave", true)
        }
        feature("publishDiagnostics", dynamic = false) {
            put("relatedInformation", true)
            put("versionSupport", true)
            put("codeDescriptionSupport", true)
            put("dataSupport", true)
            putJsonObject("tagSupport") { put("valueSet", numbers(1..2)) }
        }
        feature("completion") {
            putJsonObject("completionItem") {
                put("snippetSupport", true)
                put("commitCharactersSupport", true)
                put("documentationFormat", MarkupKinds)
                put("insertReplaceSupport", true)
                putJsonObject("resolveSupport") { put("properties", strings("documentation", "detail")) }
            }
            putJsonObject("completionItemKind") { put("valueSet", numbers(1..25)) }
        }
        feature("diagnostic") { put("relatedDocumentSupport", false) }
        feature("hover") { put("contentFormat", MarkupKinds) }
        feature("signatureHelp") {
            putJsonObject("signatureInformation") {
                put("documentationFormat", MarkupKinds)
                put("activeParameterSupport", true)
                putJsonObject("parameterInformation") { put("labelOffsetSupport", true) }
            }
        }
        for (navigation in listOf("definition", "declaration", "typeDefinition", "implementation")) {
            feature(navigation) { put("linkSupport", true) }
        }
        feature("references")
        feature("documentHighlight")
        feature("documentSymbol") {
            put("hierarchicalDocumentSymbolSupport", true)
            putJsonObject("symbolKind") { put("valueSet", numbers(1..26)) }
        }
        feature("codeAction") {
            put("isPreferredSupport", true)
            put("disabledSupport", true)
            putJsonObject("codeActionLiteralSupport") {
                putJsonObject("codeActionKind") {
                    put(
                        "valueSet",
                        strings(
                            "",
                            "quickfix",
                            "refactor",
                            "refactor.extract",
                            "refactor.inline",
                            "refactor.rewrite",
                            "source",
                            "source.organizeImports",
                        ),
                    )
                }
            }
        }
        feature("formatting")
        feature("rangeFormatting")
        feature("onTypeFormatting")
        feature("rename") {
            put("prepareSupport", true)
            put("prepareSupportDefaultBehavior", 1)
        }
        feature("documentLink")
        feature("colorProvider")
        feature("foldingRange") {
            put("lineFoldingOnly", true)
            putJsonObject("foldingRangeKind") { put("valueSet", strings("comment", "imports", "region")) }
            putJsonObject("foldingRange") { put("collapsedText", true) }
        }
        feature("selectionRange")
        feature("linkedEditingRange")
        feature("codeLens")
        feature("inlayHint")
        feature("semanticTokens") {
            putJsonObject("requests") { put("full", true) }
            put("formats", strings("relative"))
            put("tokenTypes", SemanticTokenTypes)
            put("tokenModifiers", SemanticTokenModifiers)
            put("multilineTokenSupport", true)
            put("overlappingTokenSupport", false)
            // Colours over the syntax's, so a server need not send what a grammar already knows.
            put("augmentsSyntaxTokens", true)
        }
    }
}

/**
 * Whether what a server declared in [capabilities] leaves its positions in UTF-16, the only encoding
 * this module can read: left out, it means that. Anything else is a server that did not keep to what
 * it was offered, or a host's own capabilities offering more than this module can take.
 */
internal fun positionsAreUtf16(capabilities: JsonElement?): Boolean {
    val encoding = (capabilities as? JsonObject)?.get("positionEncoding") ?: return true
    return encoding is JsonNull || (encoding is JsonPrimitive && encoding.isString && encoding.content == "utf-16")
}

/** The order is the client's preference. */
private val MarkupKinds: JsonArray get() = strings("markdown", "plaintext")

private val SemanticTokenTypes: JsonArray
    get() = strings(
        "namespace",
        "type",
        "class",
        "enum",
        "interface",
        "struct",
        "typeParameter",
        "parameter",
        "variable",
        "property",
        "enumMember",
        "event",
        "function",
        "method",
        "macro",
        "keyword",
        "modifier",
        "comment",
        "string",
        "number",
        "regexp",
        "operator",
        "decorator",
    )

private val SemanticTokenModifiers: JsonArray
    get() = strings(
        "declaration",
        "definition",
        "readonly",
        "static",
        "deprecated",
        "abstract",
        "async",
        "modification",
        "documentation",
        "defaultLibrary",
    )

/** One capability; [dynamic] is false for the few the protocol gives no `dynamicRegistration`. */
private fun JsonObjectBuilder.feature(name: String, dynamic: Boolean = true, details: JsonObjectBuilder.() -> Unit = {}) {
    putJsonObject(name) {
        if (dynamic) put("dynamicRegistration", false)
        details()
    }
}

private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

private fun numbers(values: IntRange) = JsonArray(values.map { JsonPrimitive(it) })
