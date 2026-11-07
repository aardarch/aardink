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

package com.aardarch.aardink.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.ui.EditorTestTags
import com.aardarch.aardink.ui.view.InlineFixture.LINE_HEIGHT
import com.aardarch.aardink.ui.view.InlineFixture.PADDING
import com.aardarch.aardink.ui.view.InlineFixture.lines
import com.aardarch.aardink.ui.view.InlineFixture.shown
import com.aardarch.aardink.ui.view.InlineFixture.viewOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The spike's steps S7 and S8 where they need a composition: the gutter, and the input node's semantics. */
class InlineUiTest {

    private val lens = Injection.rowAbove("3 references")

    private fun content() = GutterContent(
        annotations = emptyMap(),
        foldableLines = emptySet(),
        reserveFoldLane = false,
        diffAnnotations = emptyMap(),
        reserveDiffLane = false,
        reserveAnnotationLane = false,
        showLineNumbers = true,
        onToggleFold = {},
        onAnnotationTap = {},
    )

    private fun PixelMap.band(row: Int): List<Color> {
        val top = (PADDING + row * LINE_HEIGHT).toInt()
        return (top until top + LINE_HEIGHT.toInt()).flatMap { y -> (0 until width).map { x -> this[x, y] } }
    }

    @Test
    fun `a line's number is on its text row, and a lens row has none`() = runComposeUiTest {
        val text = "first\nsecond\nthird"
        val plain = viewOf(text)
        val withLens = viewOf(text, injections = lines(1 to LineInjections.of(lens)))
        setContent {
            Row {
                for (view in listOf(plain, withLens)) {
                    EditorGutterView(
                        view = view,
                        lineCount = 3,
                        background = Color.White,
                        foreground = Color.Black,
                        severityColor = { Color.Red },
                        content = content(),
                        modifier = Modifier.width(60.dp).height(200.dp),
                    )
                }
            }
        }
        waitForIdle()
        val gutters = onAllNodesWithTag(EditorTestTags.GUTTER)
        val without = gutters[0].captureToImage().toPixelMap()
        val with = gutters[1].captureToImage().toPixelMap()
        assertTrue(without.band(1).any { it != Color.White }, "the number 2 has ink")
        assertTrue(with.band(1).all { it == Color.White }, "the lens row has no number")
        assertEquals(without.band(0), with.band(0), "number 1 is where it was")
        assertEquals(without.band(1), with.band(2), "number 2 is on its text row")
        assertEquals(without.band(2), with.band(3), "number 3 moved down with its line")
    }

    private fun ComposeUiTest.showInput(view: EditorView, controller: EditorController): SemanticsNodeInteraction {
        val focus = FocusRequester()
        controller.requestFocus = { focus.requestFocus() }
        setContent {
            Box(
                Modifier
                    .size(600.dp, 400.dp)
                    .onGloballyPositioned { controller.coordinates = it }
                    .then(EditorInputElement(controller))
                    .focusRequester(focus)
                    .focusTarget(),
            )
        }
        waitForIdle()
        return onNode(hasSetTextAction())
    }

    @Test
    fun `semantics hold the document's text, and text typed through them lands once`() = runComposeUiTest {
        val text = "val x = 1\nsecond"
        val view = viewOf(
            text,
            injections = lines(
                0 to LineInjections.of(lens, Injection(5, ": Int")),
                1 to LineInjections.of(Injection(6, "ghost\nrow")),
            ),
        )
        val node = showInput(view, EditorController(view.state, view))
        assertEquals(text, node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        node.requestFocus()
        waitForIdle()
        view.state.selection = TextRange(5)
        waitForIdle()
        node.performTextInput("y")
        waitForIdle()
        assertEquals("val xy = 1\nsecond", view.state.document.text)
        val shown = node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("val xy = 1\nsecond", shown)
        assertFalse("ghost" in shown || "Int" in shown || "references" in shown)
    }

    @Test
    fun `an input method's character bounds are on the character, past what is injected`() = runComposeUiTest {
        val view = viewOf("val x = 1", injections = lines(0 to LineInjections.of(lens, Injection(5, ": Int"))))
        val controller = EditorController(view.state, view)
        showInput(view, controller)
        val result = view.shown(0).layout.result
        val shift = "3 references\n".length
        for (column in listOf(0, 4)) {
            val raw = result.getBoundingBox(column + shift).translate(Offset(PADDING, PADDING))
            assertEquals(raw.left, assertNotNull(controller.characterBoundsInRoot(column)).left, 0.5f, "column $column")
        }
        for (column in listOf(5, 8)) {
            val raw = result.getBoundingBox(column + shift + ": Int".length).translate(Offset(PADDING, PADDING))
            assertEquals(raw.left, assertNotNull(controller.characterBoundsInRoot(column)).left, 0.5f, "column $column")
        }
    }
}
