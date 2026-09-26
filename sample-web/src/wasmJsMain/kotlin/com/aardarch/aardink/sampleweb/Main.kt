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
package com.aardarch.aardink.sampleweb

import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.web.AardinkEditorHandle
import com.aardarch.aardink.web.AardinkWeb
import com.aardarch.aardink.web.WebEditorOptions
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Browser harness. The page's controls are plain HTML driving [AardinkWeb] from outside Compose,
 * which is how a real web host uses it. The `@JsExport` surface in Exports.kt is the running
 * copy of the template in `editor-web/src/wasmJsTest/.../ExportsTemplate.kt`.
 *
 * Only runs on the harness page (`<body id="aardink-harness">`). The same executable backs the
 * npm package, whose hosts call the exports instead, and importing it must not take over any
 * element of theirs.
 */
fun main() {
    if (document.getElementById("aardink-harness") == null) return
    Harness().start()
}

private class Harness {
    private val registry = LanguageRegistry.withBuiltIns()
    private val language = element<HTMLSelectElement>("language")
    private val theme = element<HTMLSelectElement>("theme")
    private val wordWrap = element<HTMLInputElement>("wordWrap")
    private val showGutter = element<HTMLInputElement>("showGutter")
    private val readOnly = element<HTMLInputElement>("readOnly")
    private val stats = element<HTMLElement>("stats")
    private val caret = element<HTMLElement>("caret")
    private val status = element<HTMLElement>("status")

    private lateinit var editor: AardinkEditorHandle

    fun start() {
        registry.all.forEach { language.add(option(it.id, it.displayName)) }
        AardinkWeb.builtInThemes.keys.forEach { theme.add(option(it, it)) }
        language.value = "kotlin"

        editor = AardinkWeb.mount("editor", SAMPLE_KOTLIN, currentOptions(), registry)
        AardinkWeb.onChange(editor) { text -> showStats(text) }
        AardinkWeb.onCursorChange(editor) { line, column -> caret.textContent = "Ln $line, Col $column" }
        showStats(SAMPLE_KOTLIN)

        listOf(language, theme, wordWrap, showGutter, readOnly).forEach { control ->
            control.addEventListener("change", { AardinkWeb.updateOptions(editor, currentOptions()) })
        }
        element<HTMLButtonElement>("load-large").addEventListener("click", { loadLargeFile() })
        element<HTMLButtonElement>("stress").addEventListener("click", { mountAndDispose(times = 50) })
    }

    private fun currentOptions() = WebEditorOptions(
        language = language.value,
        theme = theme.value.ifEmpty { "vscode-dark" },
        wordWrap = wordWrap.checked,
        showGutter = showGutter.checked,
        readOnly = readOnly.checked,
    )

    private fun showStats(text: String) {
        stats.textContent = "${text.count { it == '\n' } + 1} lines · ${text.length} chars"
    }

    /** W-10: typing latency with a document large enough for the cooperative tokenizer path. */
    private fun loadLargeFile() {
        val started = window.performance.now()
        val text = buildString {
            var line = 0
            while (line < 5_000) {
                append(SAMPLE_KOTLIN).append('\n')
                line += SAMPLE_KOTLIN.count { it == '\n' } + 1
            }
        }
        AardinkWeb.setValue(editor, text)
        status.textContent =
            "Loaded ${text.length / 1024} KB in ${(window.performance.now() - started).toInt()} ms (tokenizing continues in chunks)"
    }

    /** W-1: mounts and disposes editors in an off-screen box and reports the JS heap before and after. */
    private fun mountAndDispose(times: Int) {
        val before = usedHeapMb()
        repeat(times) {
            val box = document.createElement("div") as HTMLElement
            box.id = "stress-$it"
            box.style.width = "400px"
            box.style.height = "300px"
            element<HTMLElement>("stress-box").appendChild(box)
            val handle = AardinkWeb.mount(box.id, SAMPLE_KOTLIN, WebEditorOptions(language = "kotlin"), registry)
            AardinkWeb.dispose(handle)
            box.remove()
        }
        status.textContent = "Mounted and disposed $times editors. JS heap: $before MB → ${usedHeapMb()} MB " +
            "(Chrome only; force GC in DevTools and re-run to separate garbage from leaks)"
    }

    private fun option(value: String, label: String): HTMLOptionElement = (document.createElement("option") as HTMLOptionElement).also {
        it.value = value
        it.text = label
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : HTMLElement> element(id: String): T = requireNotNull(document.getElementById(id)) { "harness page has no #$id" } as T

private val SAMPLE_KOTLIN = """
    package demo

    /** A small sample to highlight, fold and edit. */
    data class Greeting(val name: String, val times: Int = 3)

    fun main() {
        val greeting = Greeting("Aardink")
        repeat(greeting.times) { index ->
            println("Hello, ${'$'}{greeting.name} (${'$'}index)")
        }
    }
""".trimIndent()

private fun usedHeapMb(): String {
    val bytes = usedJsHeapBytes()
    return if (bytes < 0) "n/a" else (bytes / (1024 * 1024)).toInt().toString()
}

// performance.memory is a non-standard Chrome extension, so it is not in the DOM bindings.
private fun usedJsHeapBytes(): Double = js("(performance.memory ? performance.memory.usedJSHeapSize : -1)")
