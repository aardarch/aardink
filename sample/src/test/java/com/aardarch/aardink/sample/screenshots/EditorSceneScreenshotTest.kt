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
package com.aardarch.aardink.sample.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.FoldState
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import com.aardarch.aardink.ui.LocalEditorTheme
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's rendering of the things a sample screen does not show: soft wrap, a closed fold,
 * squiggles, and a selection with find matches, under `screenshots/scene-<scene>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h480dp-xxhdpi")
class EditorSceneScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val kotlin = LanguageRegistry.withBuiltIns().all.first { it.id == "kotlin" }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = 0.04f)),
    )

    private val code = """
        |package demo
        |
        |/** Greets everyone on the list, one line each, and says how many there were. */
        |class Greeter(private val names: List<String>) {
        |    fun greetAll(prefix: String = "Hello"): Int {
        |        for (name in names) {
        |            println("${'$'}prefix, ${'$'}name! This line is long enough to need wrapping on a phone screen.")
        |        }
        |        return names.size
        |    }
        |}
        |
        |fun main() {
        |    val count = Greeter(listOf("Ada", "Grace", "Linus")).greetAll()
        |    println(count)
        |}
    """.trimMargin()

    private fun stateOf(text: String) = CodeEditorState(initialText = text, tokenizer = kotlin.tokenizer, tokenizeDebounceMs = 0).apply {
        computeDispatcher = Dispatchers.Unconfined
    }

    private fun capture(
        scene: String,
        state: CodeEditorState,
        foldState: FoldState? = null,
        findReplaceState: FindReplaceState? = null,
        diagnostics: List<Diagnostic> = emptyList(),
        softWrap: Boolean = false,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark) {
                    CodeEditorLayout(
                        state = state,
                        foldState = foldState,
                        foldingProvider = kotlin.foldingProvider,
                        findReplaceState = findReplaceState,
                        diagnostics = diagnostics,
                        options = EditorOptions(softWrap = softWrap),
                        keyboardToolbarPlacement = KeyboardToolbarPlacement.Hidden,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Past the find and folding debounces, which run on the test clock.
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("../screenshots/scene-$scene.png", roborazziOptions = options)
    }

    @Test
    fun wrap() = capture("wrap", stateOf(code), softWrap = true)

    @Test
    fun fold() {
        val state = stateOf(code)
        val foldState = FoldState().apply {
            updateFoldableRanges(kotlin.foldingProvider.foldableRanges(state.document))
            toggle(5)
        }
        capture("fold", state, foldState = foldState)
    }

    @Test
    fun squiggles() {
        val state = stateOf(code)
        val document = state.document
        val names = document.text.indexOf("names.size")
        val count = document.text.indexOf("count)")
        capture(
            "squiggles",
            state,
            diagnostics = listOf(
                Diagnostic(
                    range = names until names + 10,
                    lineNumber = document.offsetToLineCol(names).first,
                    message = "Unresolved reference",
                    severity = DiagnosticSeverity.Error,
                ),
                Diagnostic(
                    range = count until count + 5,
                    lineNumber = document.offsetToLineCol(count).first,
                    message = "Name shadowed",
                    severity = DiagnosticSeverity.Warning,
                ),
            ),
        )
    }

    @Test
    fun selection() {
        val state = stateOf(code)
        val start = state.document.lineStart(5)
        state.selection = TextRange(start + 8, state.document.lineStart(7) + 12)
        val find = FindReplaceState().apply {
            show()
            query = "name"
        }
        capture("selection", state, findReplaceState = find)
    }
}
