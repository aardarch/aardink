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
@file:OptIn(ExperimentalTestApi::class)

package com.aardarch.aardink.perf

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.FindEngine
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorTestTags
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * The JVM performance gate (docs/AARDINK_ROADMAP.md §8.4), beside the web one in
 * `tools/vite-smoke/perf.mjs`: the same 5,000-line reference document, the same measurements where
 * the desktop test host can make them, against limits of their own. Fails when one misses its
 * gate, which is looser than its target to absorb a shared runner's noise.
 *
 * Tagged `perf`, so `jvmTest` leaves it out and `./gradlew :editor:jvmPerfTest` runs it alone.
 * Writes `build/perf-jvm.json`.
 */
@Tag("perf")
class EditorPerfTest {

    private class Limit(val target: Double, val gate: Double?)

    private val limits = linkedMapOf(
        "mountToIdleMs" to Limit(target = 200.0, gate = 400.0),
        "keyToIdleP50Ms" to Limit(target = 16.0, gate = null),
        "keyToIdleP95Ms" to Limit(target = 33.0, gate = 50.0),
        "setValueToIdleMs" to Limit(target = 150.0, gate = null),
        "findAllMs" to Limit(target = 20.0, gate = 50.0),
        "tokenizeFullMs" to Limit(target = 100.0, gate = 300.0),
    )

    @Test
    fun `the editor keeps within its JVM gates on the reference document`() = runComposeUiTest {
        val text = largeKotlin(LINES)
        val results = LinkedHashMap<String, Double>()
        var state by mutableStateOf(stateOf(text))
        var round by mutableStateOf(0)
        setContent {
            key(round) {
                CodeEditorLayout(state = state, modifier = Modifier.size(1000.dp, 600.dp))
            }
        }
        waitForIdle()
        // The first mount and keys load and compile the code; the second are what is measured.
        typeKeys(state, WARM_UP_KEYS)

        state = stateOf(text)
        val mount = TimeSource.Monotonic.markNow()
        round++
        waitForIdle()
        results["mountToIdleMs"] = mount.elapsedNow().inWholeMicroseconds / 1000.0

        val keys = typeKeys(state, KEYS).sorted()
        results["keyToIdleP50Ms"] = percentile(keys, 50)
        results["keyToIdleP95Ms"] = percentile(keys, 95)

        val setValue = TimeSource.Monotonic.markNow()
        state.replaceText(largeKotlin(LINES + 1))
        waitForIdle()
        results["setValueToIdleMs"] = setValue.elapsedNow().inWholeMicroseconds / 1000.0

        results["findAllMs"] = medianMs(5) { FindEngine.findAll(text, "Point") }
        val tokenizer = BuiltInLanguages.Kotlin.tokenizer
        results["tokenizeFullMs"] = medianMs(3) { tokenizer.tokenizeFull(text) }

        report(results)
    }

    private fun stateOf(text: String) = CodeEditorState(text, tokenizer = BuiltInLanguages.Kotlin.tokenizer).apply {
        selection = TextRange(document.lineStart(document.lineCount / 2))
    }

    /** Types [count] characters in the middle of the document, each timed until the editor is idle again. */
    private fun androidx.compose.ui.test.ComposeUiTest.typeKeys(state: CodeEditorState, count: Int): List<Double> {
        val editor = onNodeWithTag(EditorTestTags.EDITOR)
        editor.requestFocus()
        waitForIdle()
        return List(count) {
            val key = TimeSource.Monotonic.markNow()
            editor.performTextInput("x")
            waitForIdle()
            key.elapsedNow().inWholeMicroseconds / 1000.0
        }.also { check(state.document.length > 0) }
    }

    private fun medianMs(runs: Int, block: () -> Any): Double {
        block()
        return List(runs) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow().inWholeMicroseconds / 1000.0
        }.sorted()[runs / 2]
    }

    private fun percentile(sorted: List<Double>, p: Int): Double = sorted[((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1]

    private fun report(results: Map<String, Double>) {
        val failures = ArrayList<String>()
        println(
            "JVM performance, $LINES-line Kotlin, $KEYS keys (${System.getProperty("java.vm.name")} ${System.getProperty("java.version")})",
        )
        for ((name, limit) in limits) {
            val value = results.getValue(name)
            val over = limit.gate != null && value > limit.gate
            if (over) failures += name
            val verdict = when {
                value <= limit.target -> "meets target"
                over -> "OVER GATE"
                else -> "over target"
            }
            println(
                "  ${name.padEnd(20)} ${"%8.1f".format(value)} ms  target ${limit.target}${limit.gate?.let {
                    ", gate $it"
                } ?: ""}   $verdict",
            )
        }
        val out = File(System.getProperty("aardink.perfOut") ?: "build/perf-jvm.json")
        out.parentFile?.mkdirs()
        out.writeText(
            buildString {
                append("{\n")
                results.entries.forEach { (name, value) -> append("  \"$name\": ${"%.2f".format(java.util.Locale.ROOT, value)},\n") }
                append("  \"limits\": {")
                append(
                    limits.entries.joinToString(", ") { (name, limit) ->
                        "\"$name\": { \"target\": ${limit.target}, \"gate\": ${limit.gate} }"
                    },
                )
                append("},\n  \"date\": \"${java.time.Instant.now()}\"\n}\n")
            },
        )
        println("Wrote ${out.path}")
        if (failures.isNotEmpty()) fail("Performance gate failed: ${failures.joinToString()}")
    }

    private companion object {
        const val LINES = 5_000
        const val KEYS = 30
        const val WARM_UP_KEYS = 20

        val KOTLIN_LINES = listOf(
            "package demo",
            "",
            "/** A sample block, repeated to build a large document. */",
            "data class Point(val x: Int, val y: Int) {",
            "    fun plus(other: Point): Point = Point(x + other.x, y + other.y)",
            "    // a comment with \"quotes\" and 0x1F numbers",
            "}",
        )

        /** The web gate's reference document, `largeKotlin` in `tools/vite-smoke/harness.mjs`. */
        fun largeKotlin(lines: Int): String = (0 until lines).joinToString("\n") { KOTLIN_LINES[it % KOTLIN_LINES.size] }
    }
}
