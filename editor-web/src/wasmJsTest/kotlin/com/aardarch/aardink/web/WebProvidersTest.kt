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

import com.aardarch.aardink.core.CodeActionKind
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldKind
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.InlineCompletion
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.Location
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TextEdit
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.languages.LanguageDefinition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.10's JavaScript providers, in Monaco's shapes. */
class WebProvidersTest {

    private val range12 = """{ "startLineNumber": 1, "startColumn": 1, "endLineNumber": 1, "endColumn": 3 }"""

    private fun register(id: String, providers: WebLanguageProviders, extra: String = ""): LanguageDefinition {
        AardinkWeb.registerLanguage("""{ "id": "$id", "grammar": { "tokenizer": { "root": [] } }$extra }""", providers)
        return AardinkWeb.registry.byId(id)!!
    }

    private fun service(id: String, providers: WebLanguageProviders, extra: String = ""): LanguageService =
        register(id, providers, extra).languageService!!

    @Test
    fun `definition and references in this text, and a uri for another file's`() = runTest {
        val service = service(
            "toy-definition",
            WebLanguageProviders(
                definition = { _, _, _ -> """[{ "range": $range12 }, { "uri": "file:///b", "range": $range12 }]""" },
                references = { _, _, _ ->
                    """[{ "range": $range12 }, { "uri": "file:///b", "range": { "startLineNumber": 3, "startColumn": 4, "endLineNumber": 3, "endColumn": 5 } }]"""
                },
            ),
        )
        val document = CodeDocument("ab cd")
        assertEquals(Location("", 0..1), service.definition(document, 3))
        assertEquals(listOf(Location("", 0..1), Location("file:///b", IntRange.EMPTY, 2, 3)), service.references(document, 3))
    }

    @Test
    fun `a LocationLink is read by its target`() = runTest {
        val service = service(
            "toy-location-link",
            WebLanguageProviders(definition = { _, _, _ -> """{ "targetUri": "file:///c", "targetRange": $range12 }""" }),
        )
        assertEquals(Location("file:///c", IntRange.EMPTY, 0, 0), service.definition(CodeDocument("ab"), 0))
    }

    @Test
    fun `formatting edits make the formatted text, and a range is formatted by its own`() = runTest {
        var asked = ""
        val service = service(
            "toy-format",
            WebLanguageProviders(
                formatDocument = { _, options ->
                    asked = options
                    """[{ "range": $range12, "text": "AB" }]"""
                },
                formatRange = { _, range, _ -> """[{ "range": $range, "text": "x" }]""" },
            ),
        )
        assertEquals("AB cd", service.format(CodeDocument("ab cd"), FormattingOptions(tabSize = 2)))
        assertEquals("""{"tabSize":2,"insertSpaces":true}""", asked)
        assertEquals(listOf(TextEdit(3..4, "x")), service.formatRange(CodeDocument("ab cd"), 3..4))
    }

    @Test
    fun `signature help in Monaco's result, parameters by text or by offsets`() = runTest {
        val service = service(
            "toy-signature",
            WebLanguageProviders(
                signatureHelp = { _, _, _ ->
                    """{ "value": { "signatures": [{ "label": "f(a: Int, b: String)", "documentation": { "value": "**f**" },
                         "parameters": [{ "label": "a: Int" }, { "label": [10, 19], "documentation": "the b" }] }],
                         "activeSignature": 0, "activeParameter": 1 }, "dispose": null }"""
                },
            ),
        )
        val help = service.signatureHelp(CodeDocument("f(1, "), 5)!!
        val signature = help.signatures.single()
        assertEquals("**f**", signature.documentation)
        assertEquals(listOf("a: Int", "b: String"), signature.parameters.map { it.label })
        assertEquals(10 until 19, signature.parameters[1].labelRange)
        assertEquals(1, help.activeParameter)
    }

    @Test
    fun `code actions with edits and a command, given the markers there; another file's edit leaves one out`() = runTest {
        var markers = ""
        var ran: Pair<String, String>? = null
        val service = service(
            "toy-actions",
            WebLanguageProviders(
                diagnostics = { """[{ "line": 1, "startColumn": 1, "endColumn": 3, "message": "bad" }]""" },
                codeActions = { _, _, diagnostics ->
                    markers = diagnostics
                    """{ "actions": [
                        { "title": "Fix", "kind": "quickfix", "isPreferred": true,
                          "edit": { "edits": [{ "textEdit": { "range": $range12, "text": "AB" } }] },
                          "command": { "id": "toy.after", "arguments": [1] } },
                        { "title": "Elsewhere", "edit": { "edits": [{ "resource": "file:///b", "textEdit": { "range": $range12, "text": "x" } }] } },
                        { "title": "Extract", "kind": "refactor.extract", "command": { "id": "toy.extract" } }
                    ] }"""
                },
                runCodeLensCommand = { id, args -> ran = id to args },
            ),
        )
        val document = CodeDocument("ab cd")
        service.diagnostics(document)
        val actions = service.codeActions(document, 0..1)

        assertEquals(listOf("Fix", "Extract"), actions.map { it.title })
        assertTrue("\"message\":\"bad\"" in markers, markers)
        val fix = actions.first()
        assertEquals(listOf(TextEdit(0..1, "AB")), fix.edits)
        assertTrue(fix.isPreferred)
        assertEquals(CodeActionKind.Refactor, actions[1].kind)
        fix.onApply!!.invoke()
        kotlinx.coroutines.delay(50)
        assertEquals("toy.after" to "[1]", ran)
    }

    @Test
    fun `rename is offered only with a provider, at what resolveRenameLocation says or the word`() = runTest {
        assertFalse(service("toy-no-rename", WebLanguageProviders()).supportsRename)
        val service = service(
            "toy-rename",
            WebLanguageProviders(
                rename = { _, _, _, newName -> """{ "edits": [{ "textEdit": { "range": $range12, "text": "$newName" } }] }""" },
            ),
        )
        assertTrue(service.supportsRename)
        assertEquals(3..4, service.prepareRename(CodeDocument("ab cd"), 4), "the word at the caret")
        assertEquals(listOf(TextEdit(0..1, "zz")), service.rename(CodeDocument("ab cd"), 0, "zz"))

        val resolving = service(
            "toy-rename-resolved",
            WebLanguageProviders(
                rename = { _, _, _, _ -> """{ "rejectReason": "no" }""" },
                resolveRename = { _, _, column ->
                    if (column ==
                        1
                    ) {
                        """{ "rejectReason": "keyword" }"""
                    } else {
                        """{ "range": $range12, "text": "ab" }"""
                    }
                },
            ),
        )
        assertNull(resolving.prepareRename(CodeDocument("ab cd"), 0))
        assertEquals(0..1, resolving.prepareRename(CodeDocument("ab cd"), 1))
        assertEquals(emptyList(), resolving.rename(CodeDocument("ab cd"), 1, "x"), "rejected")
    }

    @Test
    fun `a rename reaching another file renames nothing`() = runTest {
        val service = service(
            "toy-rename-elsewhere",
            WebLanguageProviders(
                rename = { _, _, _, _ ->
                    """{ "edits": [{ "textEdit": { "range": $range12, "text": "x" } }, { "resource": "file:///b", "textEdit": { "range": $range12, "text": "x" } }] }"""
                },
            ),
        )
        assertEquals(emptyList(), service.rename(CodeDocument("ab cd"), 0, "x"))
    }

    @Test
    fun `the page's folds replace the language's own, which stay while it answers nothing`() = runTest {
        var answer: String? = """[{ "start": 1, "end": 3, "kind": "region" }, { "start": 2, "end": 2 }, { "start": 2, "end": 99 }]"""
        val language = register("toy-folds", WebLanguageProviders(foldingRanges = { answer }))
        val document = CodeDocument("a\n  b\n  c\nd")
        assertEquals(listOf(FoldRange(0, 2, kind = FoldKind.Region)), language.foldingProvider.requestFoldableRanges(document))
        answer = null
        assertEquals(language.foldingProvider.foldableRanges(document), language.foldingProvider.requestFoldableRanges(document))
    }

    @Test
    fun `semantic tokens are read by the definition's legend, named as a server's are`() = runTest {
        val service = service(
            "toy-semantic",
            WebLanguageProviders(semanticTokens = { """{ "data": [0, 0, 2, 0, 0, 0, 3, 2, 1, 1, 1, 0, 1, 7, 0] }""" }),
            extra = """, "semanticTokensLegend": { "tokenTypes": ["class", "parameter"], "tokenModifiers": ["readonly"] }""",
        )
        assertEquals(
            listOf(Token(0, 2, NamedTokenType("type.class")), Token(3, 5, NamedTokenType("variable.parameter.readonly"))),
            service.semanticTokens(CodeDocument("ab cd\nx")),
            "a type outside the legend is left out",
        )
        assertEquals(
            emptyList(),
            service(
                "toy-semantic-no-legend",
                WebLanguageProviders(semanticTokens = {
                    "{\"data\":[0,0,1,0,0]}"
                }),
            ).semanticTokens(CodeDocument("a")),
        )
    }

    @Test
    fun `a completion is resolved from the item as the provider gave it`() = runTest {
        var given = ""
        val service = service(
            "toy-resolve",
            WebLanguageProviders(
                completions = { _, _, _ -> """[{ "label": "alpha", "data": 7 }]""" },
                resolveCompletionItem = { item ->
                    given = item
                    """{ "label": "alpha", "documentation": { "value": "**A**" }, "detail": "Int" }"""
                },
            ),
        )
        val item = service.completions(CodeDocument("al"), 2).single()
        val resolved = service.resolveCompletion(item)
        assertTrue("\"data\":7" in given, given)
        assertEquals("**A**", resolved.documentation)
        assertEquals("Int", resolved.detail)
    }

    @Test
    fun `a lens without a command is resolved, and left out if it stays without one`() = runTest {
        val service = service(
            "toy-resolve-lens",
            WebLanguageProviders(
                codeLenses = { """[{ "range": $range12 }, { "range": $range12, "id": "never" }]""" },
                resolveCodeLens = { _, lens ->
                    if ("never" in lens) null else """{ "range": $range12, "command": { "id": "c", "title": "3 references" } }"""
                },
            ),
        )
        assertEquals(listOf("3 references"), service.codeLenses(CodeDocument("ab")).map { it.title })
    }

    @Test
    fun `the dispatcher asks only for the providers the page has`() = runTest {
        val asked = ArrayList<String>()
        AardinkWeb.registerLanguage(
            """{ "id": "toy-provided", "grammar": { "tokenizer": { "root": [] } }, "provided": ["definition"] }""",
        ) { method, _, _ ->
            asked += method
            null
        }
        val service = AardinkWeb.registry.byId("toy-provided")!!.languageService!!
        assertFalse(service.supportsRename)
        service.definition(CodeDocument("a"), 0)
        service.references(CodeDocument("a"), 0)
        assertEquals(listOf("definition"), asked)

        AardinkWeb.registerLanguage("""{ "id": "toy-provided-all", "grammar": { "tokenizer": { "root": [] } } }""") { _, _, _ -> null }
        assertTrue(AardinkWeb.registry.byId("toy-provided-all")!!.languageService!!.supportsRename, "a page that does not say has them all")
    }

    @Test
    fun `a snippet inline completion shows as its text`() {
        assertEquals("for (i of items) {\n\t\n}", snippetText("for (\${1:i} of \${2:items}) {\n\t$0\n}"))
        assertEquals("let x = a;", snippetText("let \${1:x} = \${2|a,b|};"))
        assertEquals("f() \$ {}", snippetText("f(\$TM_SELECTED_TEXT) \\$ {\${3:\${4}}}"))
        assertEquals("nested default", snippetText("\${1:nested \${2:default}}"))
        assertEquals(
            listOf(InlineCompletion("a b")),
            parseInlineCompletions(CodeDocument(""), """{ "items": [{ "insertText": { "snippet": "a ${'$'}{1:b}" } }] }"""),
        )
    }
}
