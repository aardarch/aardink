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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.sample.SampleAppContent
import com.aardarch.aardink.sample.themeChoices
import com.aardarch.aardink.sample.ui.SampleThemeChoice
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the sample app's start screen and every language sample screen, in every bundled
 * editor theme, to PNG via Roborazzi — driven by `scripts/capture-screenshots.ps1`.
 *
 * The CSS sample normally talks to an in-process demo language server
 * ([com.aardarch.aardink.sample.lsp.createDemoCssLspConnection]); that server is wired up through
 * [com.aardarch.aardink.sample.rememberSampleRegistry] inside [MainActivity][com.aardarch.aardink.sample.MainActivity]
 * only, so here we use the plain built-in registry instead — the CSS screenshot shows syntax
 * highlighting without live diagnostics, which is a fine trade-off for a static screenshot and
 * avoids depending on LSP round-trip timing inside a Robolectric test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SampleScreenshotTest {

    companion object {
        private const val OUTPUT_DIR = "build/outputs/screenshots"

        /** Page id `null` renders the start screen; every other id renders that sample's editor. */
        private val pageIds: List<String?> = listOf(null) + LanguageRegistry.withBuiltIns().all.map { it.id }
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private val registry = LanguageRegistry.withBuiltIns()

    @Before
    fun setup() {
        File(OUTPUT_DIR).mkdirs()
    }

    @Test
    fun midnightOcean() = captureTheme(themeChoices[0])

    @Test
    fun vsCodeDark() = captureTheme(themeChoices[1])

    @Test
    fun vsCodeLight() = captureTheme(themeChoices[2])

    @Test
    fun materialDark() = captureTheme(themeChoices[3])

    @Test
    fun materialLight() = captureTheme(themeChoices[4])

    @Test
    fun solarizedDark() = captureTheme(themeChoices[5])

    /**
     * Captures every page for [themeChoice]. `setContent` can only be called once per test, so
     * pages are driven by swapping the `selectedId` state and letting Compose recompose —
     * the same pattern aardflex's PromoScreenshotTest uses to cycle wallpaper pages.
     */
    private fun captureTheme(themeChoice: SampleThemeChoice) {
        File(OUTPUT_DIR).listFiles { f -> f.name.startsWith("${themeChoice.id}-") }
            ?.forEach { it.delete() }

        var selectedId by mutableStateOf<String?>(null)

        composeTestRule.setContent {
            SampleAppContent(
                registry = registry,
                themeChoice = themeChoice,
                selectedId = selectedId,
                onThemeChange = {},
                onSelect = {},
                onBack = { selectedId = null },
                // Production debounces tokenization by 150ms on a real coroutine delay, which
                // Robolectric's paused main looper never fires under Compose's virtual test
                // clock — every sample would render with syntax highlighting missing. 0 makes
                // CodeEditorState tokenize synchronously instead (see EditorScreen).
                tokenizeDebounceMs = 0L,
            )
        }

        pageIds.forEach { pageId ->
            selectedId = pageId
            composeTestRule.waitForIdle()

            val pageName = pageId ?: "main"
            composeTestRule.onRoot()
                .captureRoboImage(filePath = "$OUTPUT_DIR/${themeChoice.id}-$pageName.png")
        }
    }
}
