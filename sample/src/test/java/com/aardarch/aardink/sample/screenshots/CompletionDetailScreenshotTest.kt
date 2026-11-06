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
// The pane is internal and belongs to desktop and the web, so Android's own CodeEditorLayout never
// shows it: reaching CompletionPopup directly is how it gets under this repo's one screenshot gate.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.aardarch.aardink.sample.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.CompletionPopup
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.KeyboardToolbarPlacement
import com.aardarch.aardink.ui.LocalCodeFenceTokenizers
import com.aardarch.aardink.ui.LocalEditorTheme
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FENCE = """Greeter(listOf("Ada")).greetAll()"""

/**
 * The completion list with the pane beside it, in a window the size of a small desktop one, under
 * `screenshots/scene-completion-detail.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h480dp-xhdpi")
class CompletionDetailScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val registry = LanguageRegistry.withBuiltIns()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = 0.04f)),
    )

    private val code = """
        |class Greeter(private val names: List<String>) {
        |    fun greetAll(prefix: String = "Hello"): Int {
        |        return names.size
        |    }
        |}
        |
        |fun main() {
        |    val greeter = Greeter(listOf("Ada", "Grace"))
        |    greeter.gre
        |}
    """.trimMargin()

    private val items = listOf(
        CompletionItem(
            label = "greetAll",
            kind = CompletionKind.Method,
            insertText = "greetAll()",
            detail = "fun greetAll(prefix: String = \"Hello\"): Int",
            documentation = """
                |Greets *everyone* on the list, **one line each**, and says how many there were.
                |
                |- Takes the greeting as `prefix`
                |- See [the guide](https://example.invalid/guide)
                |
                |```kotlin
                |$FENCE
                |```
            """.trimMargin(),
        ),
        CompletionItem("greeted", CompletionKind.Property, "greeted", detail = "val greeted: Int"),
        CompletionItem("greetOne", CompletionKind.Method, "greetOne()", documentation = "Greets the first name only."),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun completionDetail() {
        val kotlin = registry.byId("kotlin")!!
        val state = CodeEditorState(initialText = code, tokenizer = kotlin.tokenizer, tokenizeDebounceMs = 0).apply {
            computeDispatcher = Dispatchers.Unconfined
        }
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalEditorTheme provides EditorThemes.VsCodeDark,
                    LocalCodeFenceTokenizers provides { registry.byId(it)?.tokenizer },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        CodeEditorLayout(
                            state = state,
                            keyboardToolbarPlacement = KeyboardToolbarPlacement.Hidden,
                            modifier = Modifier.fillMaxSize(),
                        )
                        val caret = with(LocalDensity.current) { Rect(168.dp.toPx(), 170.dp.toPx(), 170.dp.toPx(), 190.dp.toPx()) }
                        CompletionPopup(
                            anchor = caret,
                            items = items,
                            selected = 0,
                            onAccept = {},
                            onDismiss = {},
                            resolve = { it },
                            // A link only looks like one with somewhere to go.
                            onOpenLink = {},
                        )
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        // The fence is coloured off the main thread, which the test clock does not wait for.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(hasText(FENCE)).fetchSemanticsNodes().any { node ->
                node.config[SemanticsProperties.Text].single().spanStyles.isNotEmpty()
            }
        }
        composeTestRule.waitForIdle()
        captureScreenRoboImage("../screenshots/scene-completion-detail.png", roborazziOptions = options)
    }
}
