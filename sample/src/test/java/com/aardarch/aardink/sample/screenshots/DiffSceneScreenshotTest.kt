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
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.sample.ui.DiffSampleEditor
import com.aardarch.aardink.ui.CodeEditorLayout
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
 * The diff editor's rendering, side by side, inline, with filler rows and with the unchanged regions
 * folded, and the plain editor's gutter marks for text changed since it was saved, under
 * `screenshots/diff-<scene>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w720dp-h560dp-xxhdpi")
class DiffSceneScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val kotlin = LanguageRegistry.withBuiltIns().all.first { it.id == "kotlin" }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = 0.04f)),
    )

    /** Four lines on the original's side that the modified one does not have, and four the other way round. */
    private val fillersOriginal = """
        |fun main() {
        |    val names = listOf("Ada", "Grace", "Linus")
        |    println("start")
        |    val legacy = names.size
        |    val old = legacy * 2
        |    println(old)
        |    println(legacy)
        |    println("middle")
        |    for (name in names) {
        |        println(name)
        |    }
        |    println("end")
        |}
    """.trimMargin()

    private val fillersModified = """
        |fun main() {
        |    val names = listOf("Ada", "Grace", "Linus")
        |    println("starting up")
        |    println("middle")
        |    for (name in names) {
        |        val greeting = "Hello, " + name
        |        val shout = greeting.uppercase()
        |        println(greeting)
        |        println(shout)
        |    }
        |    println("end")
        |}
    """.trimMargin()

    private fun diff(
        scene: String,
        sideBySide: Boolean,
        hideUnchanged: Boolean = false,
        original: String? = null,
        modified: String? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark) {
                    if (original != null && modified != null) {
                        DiffSampleEditor(kotlin, sideBySide, hideUnchanged, Modifier.fillMaxSize(), 0L, original, modified)
                    } else {
                        DiffSampleEditor(kotlin, sideBySide, hideUnchanged, Modifier.fillMaxSize(), 0L)
                    }
                }
            }
        }
        // Past the diff's debounce and the first diff, which run on the test clock.
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("../screenshots/diff-$scene.png", roborazziOptions = options)
    }

    @Test
    fun sideBySide() = diff("side-by-side", sideBySide = true)

    @Test
    fun inline() = diff("inline", sideBySide = false, original = fillersOriginal, modified = fillersModified)

    @Test
    fun fillers() = diff("fillers", sideBySide = true, original = fillersOriginal, modified = fillersModified)

    @Test
    fun hiddenRegions() = diff("hidden-regions", sideBySide = true, hideUnchanged = true)

    @Test
    fun gutter() {
        val saved = """
            |fun main() {
            |    val names = listOf("Ada", "Grace", "Linus")
            |    println("start")
            |    for (name in names) {
            |        println(name)
            |    }
            |    println("cleanup")
            |    println("end")
            |}
        """.trimMargin()
        val current = """
            |fun main() {
            |    val names = listOf("Ada", "Grace", "Linus", "Alan")
            |    println("start")
            |    for (name in names) {
            |        println("Hello, " + name)
            |        println(name.length)
            |    }
            |    println("end")
            |}
        """.trimMargin()
        val state = CodeEditorState(initialText = current, tokenizer = kotlin.tokenizer, tokenizeDebounceMs = 0).apply {
            computeDispatcher = Dispatchers.Unconfined
        }
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark as EditorTheme) {
                    CodeEditorLayout(
                        state = state,
                        savedText = saved,
                        keyboardToolbarPlacement = KeyboardToolbarPlacement.Hidden,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("../screenshots/diff-gutter.png", roborazziOptions = options)
    }
}
