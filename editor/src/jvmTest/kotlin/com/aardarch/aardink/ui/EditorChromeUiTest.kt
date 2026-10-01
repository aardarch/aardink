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
package com.aardarch.aardink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.FindReplaceState
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The popups and panels the editor draws take their colours from the editor theme, not the host's
 * Material theme: each one is shown under a light host theme with a dark editor theme, and the
 * other way round, and its background is sampled.
 */
@OptIn(ExperimentalTestApi::class)
class EditorChromeUiTest {

    private fun ComposeUiTest.show(lightHost: Boolean, theme: EditorTheme, content: @Composable () -> Unit) {
        setContent {
            MaterialTheme(colorScheme = if (lightHost) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalEditorTheme provides theme, content = content)
            }
        }
        waitForIdle()
    }

    /** The colour near the right edge, halfway down: background, clear of rounded corners and text. */
    private fun ComposeUiTest.backgroundOf(tag: String): Color {
        val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width - 3, pixels.height / 2]
    }

    private fun ComposeUiTest.assertDark(tag: String) {
        val color = backgroundOf(tag)
        assertTrue(color.luminance() < 0.1f, "$tag is dark under a dark editor theme, was $color")
    }

    private fun ComposeUiTest.assertLight(tag: String) {
        val color = backgroundOf(tag)
        assertTrue(color.luminance() > 0.6f, "$tag is light under a light editor theme, was $color")
    }

    private val doc = HoverDoc("title", "content", example = "val x = 1")
    private val help = SignatureHelp(
        listOf(SignatureInformation("foo(a: Int)", parameters = listOf(ParameterInformation("a: Int")))),
    )
    private val items = listOf(CompletionItem("println", CompletionKind.Snippet, "println()"))

    @Test
    fun `the hover card follows a dark editor theme under a light host`() = runComposeUiTest {
        show(lightHost = true, EditorThemes.VsCodeDark) { HoverDocCard(doc, Modifier.testTag("hover")) }
        assertDark("hover")
    }

    @Test
    fun `the signature card follows a dark editor theme under a light host`() = runComposeUiTest {
        show(lightHost = true, EditorThemes.VsCodeDark) { SignatureHelpCard(help, Modifier.testTag("signature")) }
        assertDark("signature")
    }

    @Test
    fun `the completion list follows a dark editor theme under a light host`() = runComposeUiTest {
        show(lightHost = true, EditorThemes.VsCodeDark) { CompletionList(items, selected = -1, onAccept = {}) }
        assertDark(EditorTestTags.COMPLETION_LIST)
    }

    @Test
    fun `the references list follows a dark editor theme under a light host`() = runComposeUiTest {
        show(lightHost = true, EditorThemes.MidnightOcean) { ReferencesPeekCard(emptyList(), selected = -1, onOpen = {}) }
        assertDark(EditorTestTags.REFERENCES)
    }

    @Test
    fun `the find panel follows a dark editor theme under a light host`() = runComposeUiTest {
        val state = FindReplaceState().apply { visible = true }
        show(lightHost = true, EditorThemes.SolarizedDark) {
            FindReplacePanel(state, onNext = {}, onPrev = {}, onReplace = {}, onReplaceAll = {}, onClose = {})
        }
        assertDark(EditorTestTags.FIND_PANEL)
    }

    @Test
    fun `the diagnostic banner follows a dark editor theme under a light host`() = runComposeUiTest {
        show(lightHost = true, EditorThemes.VsCodeDark) {
            AnnotationTooltip("message", DiagnosticSeverity.Error, onDismiss = {}, modifier = Modifier.testTag("banner"))
        }
        assertDark("banner")
    }

    @Test
    fun `the code action card follows a light editor theme under a dark host`() = runComposeUiTest {
        show(lightHost = false, EditorThemes.VsCodeLight) {
            CodeActionCard(emptyList(), onSelectAction = {}, onDismiss = {}, modifier = Modifier.testTag("actions"))
        }
        assertLight("actions")
    }

    @Test
    fun `the find panel follows a light editor theme under a dark host`() = runComposeUiTest {
        val state = FindReplaceState().apply { visible = true }
        show(lightHost = false, EditorThemes.MaterialLight) {
            FindReplacePanel(state, onNext = {}, onPrev = {}, onReplace = {}, onReplaceAll = {}, onClose = {})
        }
        assertLight(EditorTestTags.FIND_PANEL)
    }
}
