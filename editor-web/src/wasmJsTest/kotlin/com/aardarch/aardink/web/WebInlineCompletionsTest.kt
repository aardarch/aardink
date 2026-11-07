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

import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.InlineCompletion
import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `registerInlineCompletionsProvider` and the `editor.action.inlineSuggest` actions, in a mounted editor. */
class WebInlineCompletionsTest {

    private val mounted = mutableListOf<AardinkEditorHandle>()
    private val containers = mutableListOf<HTMLElement>()
    private val registered = mutableListOf<kotlinx.coroutines.DisposableHandle>()

    private val commit = "editor.action.inlineSuggest.commit"
    private val showNext = "editor.action.inlineSuggest.showNext"
    private val showPrevious = "editor.action.inlineSuggest.showPrevious"
    private val hide = "editor.action.inlineSuggest.hide"

    @AfterTest
    fun tearDown() {
        registered.forEach { it.dispose() }
        mounted.forEach(AardinkWeb::dispose)
        containers.forEach { it.remove() }
    }

    private fun mount(text: String, language: String): AardinkEditorHandle {
        val div = document.createElement("div") as HTMLElement
        div.id = "aardink-inline-test-${containers.size}"
        div.style.width = "600px"
        div.style.height = "400px"
        document.body!!.appendChild(div)
        containers += div
        return AardinkWeb.mount(div.id, text, WebEditorOptions(language = language)).also { mounted += it }
    }

    private fun register(language: String, provider: suspend (String, Int, Int) -> String?) {
        registered += AardinkWeb.registerInlineCompletionsProvider(language, provider)
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(16)
    }

    private fun onPlace(text: String, at: Int, handle: AardinkEditorHandle) {
        assertEquals(text, AardinkWeb.getValue(handle))
        handle.state.value.selection = TextRange(at)
    }

    @Test
    fun `an answer in Monaco's shape shows through the actions, and commit puts it in, as one undo step`() = runTest {
        val asked = mutableListOf<String>()
        register("xml") { text, line, column ->
            asked += "$text $line $column"
            """{ "items": [{ "insertText": "bar)" }, { "insertText": "other)" }] }"""
        }
        val handle = mount("one\nfoo()", "xml")
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            onPlace("one\nfoo()", 8, handle)
            assertFalse(AardinkWeb.canTrigger(handle, commit), "nothing shows yet")

            assertTrue(AardinkWeb.trigger(handle, showNext))
            until { AardinkWeb.canTrigger(handle, commit) }
            assertEquals(listOf("one\nfoo() 2 5"), asked, "the text and a 1-based place")
            assertEquals("one\nfoo()", AardinkWeb.getValue(handle), "shown, and not in the document")

            assertTrue(AardinkWeb.trigger(handle, showNext))
            assertTrue(AardinkWeb.trigger(handle, showPrevious))
            assertTrue(AardinkWeb.trigger(handle, showPrevious))
            assertTrue(AardinkWeb.trigger(handle, commit))
            assertEquals("one\nfoo(other)", AardinkWeb.getValue(handle), "the bracket it repeats is not doubled")
            assertEquals(TextRange("one\nfoo(other".length), handle.state.value.selection)
            handle.state.value.undo()
            assertEquals("one\nfoo()", AardinkWeb.getValue(handle))
        }
    }

    @Test
    fun `hide can run exactly while something shows, and what it hides stays gone until an edit asks`() = runTest {
        var asked = 0
        register("xml") { _, _, _ ->
            asked++
            """[{ "insertText": "bar" }]"""
        }
        val handle = mount("foo", "xml")
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            onPlace("foo", 3, handle)
            assertFalse(AardinkWeb.canTrigger(handle, hide), "nothing shows yet")
            assertFalse(AardinkWeb.trigger(handle, hide))

            assertTrue(AardinkWeb.trigger(handle, showNext))
            until { AardinkWeb.canTrigger(handle, hide) }
            assertTrue(AardinkWeb.trigger(handle, hide))
            assertFalse(AardinkWeb.canTrigger(handle, hide))
            assertFalse(AardinkWeb.canTrigger(handle, commit))
            assertEquals("foo", AardinkWeb.getValue(handle))

            delay(600)
            assertEquals(1, asked, "nothing asks until an edit does")
            handle.state.value.indentSelection()
            until { AardinkWeb.canTrigger(handle, hide) }
            assertEquals(2, asked)
        }
    }

    /** A key going down in the editor, as the page gets it. */
    private fun press(handle: AardinkEditorHandle, code: String, alt: Boolean = true): KeyboardEvent {
        val init = KeyboardEventInit(code = code, altKey = alt, bubbles = true, cancelable = true, composed = true)
        return KeyboardEvent("keydown", init).also { handle.viewportElement!!.dispatchEvent(it) }
    }

    @Test
    fun `Alt with a bracket reaches the commands from the page, as Compose keeps it for typing`() = runTest {
        register("sql") { _, _, _ -> """[{ "insertText": "one" }, { "insertText": "two" }]""" }
        val handle = mount("a", "sql")
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            onPlace("a", 1, handle)
            val asks = press(handle, "BracketRight")
            assertTrue(asks.defaultPrevented, "it does not go on to type")
            until { AardinkWeb.canTrigger(handle, commit) }
            press(handle, "BracketRight")
            press(handle, "BracketRight")
            press(handle, "BracketLeft")
            assertTrue(AardinkWeb.trigger(handle, commit))
            assertEquals("atwo", AardinkWeb.getValue(handle), "next, next, previous")
        }
    }

    @Test
    fun `a bracket with no Alt, or with nothing to run, is left to type`() = runTest {
        val handle = mount("a", "yaml")
        withContext(Dispatchers.Main) {
            delay(100)
            assertFalse(press(handle, "BracketRight").defaultPrevented, "no provider")
        }
        register("yaml") { _, _, _ -> null }
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            assertFalse(press(handle, "BracketRight", alt = false).defaultPrevented, "no Alt")
            assertFalse(press(handle, "KeyK").defaultPrevented, "another key")
        }
    }

    @Test
    fun `a host's own Alt with a bracket comes first`() = runTest {
        var asked = 0
        register("ini") { _, _, _ ->
            asked++
            null
        }
        val handle = mount("a", "ini")
        var ran = 0
        AardinkWeb.addCommand(handle, "Alt+]") { ran++ }
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            assertTrue(press(handle, "BracketRight").defaultPrevented)
            delay(100)
            assertEquals(1, ran)
            assertEquals(0, asked)
        }
    }

    @Test
    fun `the actions have Monaco's ids and labels`() {
        assertEquals("Accept Inline Suggestion", MonacoActions.label(commit))
        assertEquals("Show Next Inline Suggestion", MonacoActions.label(showNext))
        assertEquals("Show Previous Inline Suggestion", MonacoActions.label(showPrevious))
        assertEquals("Hide Inline Suggestion", MonacoActions.label(hide))
    }

    @Test
    fun `with no provider for the language the actions cannot run`() = runTest {
        val handle = mount("text", "json")
        withContext(Dispatchers.Main) {
            delay(100)
            assertFalse(AardinkWeb.canTrigger(handle, showNext))
            assertFalse(AardinkWeb.trigger(handle, commit))
        }
    }

    @Test
    fun `a provider registered after the editor opened reaches it, and disposing ends it`() = runTest {
        val handle = mount("text", "toml")
        withContext(Dispatchers.Main) {
            delay(100)
            assertFalse(AardinkWeb.canTrigger(handle, showNext))
            val registration = AardinkWeb.registerInlineCompletionsProvider("toml") { _, _, _ -> """[{ "insertText": "!" }]""" }
            until { AardinkWeb.canTrigger(handle, showNext) }
            registration.dispose()
            until { !AardinkWeb.canTrigger(handle, showNext) }
        }
    }

    @Test
    fun `a read-only editor is not asked`() = runTest {
        var asked = 0
        register("css") { _, _, _ ->
            asked++
            null
        }
        val handle = mount("a", "css")
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            AardinkWeb.patchOptions(handle, """{"readOnly": true}""")
            until { !AardinkWeb.canTrigger(handle, showNext) }
            assertFalse(AardinkWeb.trigger(handle, showNext))
            delay(400)
            assertEquals(0, asked)
        }
    }

    @Test
    fun `items come as Monaco's list or a bare array, with Monaco's ranges`() {
        val document = CodeDocument("one\ntwo")
        val range = """{ "startLineNumber": 2, "startColumn": 1, "endLineNumber": 2, "endColumn": 4 }"""
        assertEquals(
            listOf(InlineCompletion("a"), InlineCompletion("b", 4 until 7)),
            parseInlineCompletions(document, """{ "items": [{ "insertText": "a" }, { "insertText": "b", "range": $range }] }"""),
        )
        assertEquals(listOf(InlineCompletion("c")), parseInlineCompletions(document, """[{ "insertText": "c" }]"""))
    }

    @Test
    fun `an item whose range is not one in the text is left out, and a null range is none given`() {
        val document = CodeDocument("one\ntwo")
        val past = """{ "startLineNumber": 9, "startColumn": 1, "endLineNumber": 9, "endColumn": 1 }"""
        val reversed = """{ "startLineNumber": 2, "startColumn": 1, "endLineNumber": 1, "endColumn": 1 }"""
        for (range in listOf(past, reversed, """{ "startLineNumber": 1 }""", "3")) {
            val items = """[{ "insertText": "x", "range": $range }, { "insertText": "y" }]"""
            assertEquals(listOf(InlineCompletion("y")), parseInlineCompletions(document, items), range)
        }
        assertEquals(listOf(InlineCompletion("x")), parseInlineCompletions(document, """[{ "insertText": "x", "range": null }]"""))
    }

    @Test
    fun `commit from a host's button shows the caret, as Tab does`() = runTest {
        val lines = (1..100).joinToString("\\n") { "line" }
        register("xml") { text, _, _ -> if (text == "a") """[{ "insertText": "$lines" }]""" else null }
        val handle = mount("a", "xml")
        withContext(Dispatchers.Main) {
            until { AardinkWeb.canTrigger(handle, showNext) }
            onPlace("a", 1, handle)
            assertTrue(AardinkWeb.trigger(handle, showNext))
            until { AardinkWeb.canTrigger(handle, commit) }
            assertTrue(AardinkWeb.trigger(handle, commit))
            until { handle.state.value.viewport.let { 99 in it.firstVisibleLine..it.lastVisibleLine } }
        }
    }

    @Test
    fun `an empty range is kept as a plain insertion`() {
        val range = """{ "startLineNumber": 1, "startColumn": 3, "endLineNumber": 1, "endColumn": 3 }"""
        val item = parseInlineCompletions(CodeDocument("abc"), """[{ "insertText": "x", "range": $range }]""").single()
        assertEquals(2 until 2, item.range)
    }

    @Test
    fun `what is not a text to show is left out`() {
        val document = CodeDocument("a")
        assertEquals(
            emptyList(),
            parseInlineCompletions(document, """[{ "insertText": { "snippet": "${'$'}1" } }, { "range": null }, 7]"""),
        )
        assertEquals(emptyList(), parseInlineCompletions(document, "not json"))
        assertEquals(emptyList(), parseInlineCompletions(document, "{}"))
    }

    @Test
    fun `every provider of a language answers, oldest first, and one failing costs only its answer`() = runTest {
        val provider = WebInlineCompletions(
            listOf(
                { _, _, _ -> """[{ "insertText": "first" }]""" },
                { _, _, _ -> error("no") },
                { _, _, _ -> null },
                { _, _, _ -> """[{ "insertText": "last" }]""" },
            ),
        )
        assertEquals(listOf("first", "last"), provider.provideInlineCompletions(CodeDocument("a"), 0).map { it.text })
    }

    @Test
    fun `through the exports a provider gets the text and a place, and answers with a Promise`() = runTest {
        val asked = mutableListOf<String>()
        val id = aardinkRegisterInlineCompletionsProvider("exported-inline") { text, line, column ->
            asked += "$text $line $column"
            Promise.resolve<JsString?>("""[{ "insertText": "x" }]""".toJsString())
        }
        val sources = assertNotNull(AardinkWeb.inlineCompletionSources("exported-inline"))
        val answer = withContext(Dispatchers.Main) { WebInlineCompletions(sources).provideInlineCompletions(CodeDocument("ab\ncd"), 4) }
        assertEquals(listOf(InlineCompletion("x")), answer)
        assertEquals(listOf("ab\ncd 2 2"), asked)
        aardinkDisposeInlineCompletionsProvider(id)
        assertNull(AardinkWeb.inlineCompletionSources("exported-inline"))
        aardinkDisposeInlineCompletionsProvider(id)
    }
}
