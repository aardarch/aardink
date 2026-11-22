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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.DiffEditorState
import com.aardarch.aardink.core.LineChange
import com.aardarch.aardink.ui.CodeDiffEditorLayout
import com.aardarch.aardink.ui.DiffEditorOptions
import com.aardarch.aardink.ui.LocalCodeFenceTokenizers
import com.aardarch.aardink.ui.LocalEditorTheme
import com.aardarch.aardink.ui.LocalEditorTypography
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One diff editor mounted by [AardinkWeb.mountDiff]. Opaque to hosts, as [AardinkEditorHandle] is:
 * every operation goes through [AardinkWeb]. Its two sides are editors of their own, [original] and
 * [modified], which every per-editor function of [AardinkWeb] takes.
 *
 * @property original The handle of the left side, or of the one above the other when inline.
 * @property modified The handle of the side that is edited. It owns the viewport, so the editor
 *   options that both sides share (theme, font, wrapping and the rest) are this handle's.
 */
class AardinkDiffHandle internal constructor(
    val original: AardinkEditorHandle,
    val modified: AardinkEditorHandle,
    internal val state: DiffEditorState,
    initialOptions: WebDiffOptions,
) {
    internal val options: MutableState<WebDiffOptions> = mutableStateOf(initialOptions)

    /** The changes of the last diff as Monaco's `ILineChange` array, worked out when that diff landed. */
    internal var lineChangesJson: String = "[]"

    internal var onDidUpdateDiff: (() -> Unit)? = null
    internal var diffListener: ((com.aardarch.aardink.core.TextDiffResult) -> Unit)? = null
    internal var disposed: Boolean = false

    /** The side holding the keyboard focus, the modified one when neither does. */
    internal fun focusedSide(): AardinkEditorHandle = if (original.state.value.isFocused) original else modified
}

/**
 * The diff editor's own options, Monaco's names, read from the same JSON as the editor options both
 * sides share. Internal: the JavaScript side passes JSON.
 */
@Serializable
internal data class WebDiffOptions(
    val renderSideBySide: Boolean = true,
    val renderSideBySideInlineBreakpoint: Int = 900,
    val ignoreTrimWhitespace: Boolean = true,
    val maxComputationTime: Long = 5_000,
    val originalEditable: Boolean = false,
    val hideUnchangedRegions: WebHideUnchangedRegions = WebHideUnchangedRegions(),
    val renderIndicators: Boolean = true,
    val enableSplitViewResizing: Boolean = true,
)

/** Monaco's `hideUnchangedRegions`: off by default, with 3 lines of context. */
@Serializable
internal data class WebHideUnchangedRegions(val enabled: Boolean = false, val contextLineCount: Int = 3)

private fun WebDiffOptions.toDiffEditorOptions() = DiffEditorOptions(
    renderSideBySide = renderSideBySide,
    renderSideBySideInlineBreakpoint = renderSideBySideInlineBreakpoint.dp,
    ignoreTrimWhitespace = ignoreTrimWhitespace,
    maxComputationTimeMs = maxComputationTime,
    originalEditable = originalEditable,
    hideUnchangedRegions = hideUnchangedRegions.enabled,
    unchangedContextLines = hideUnchangedRegions.contextLineCount.coerceAtLeast(1),
    renderIndicators = renderIndicators,
    enableSplitViewResizing = enableSplitViewResizing,
)

/** What a diff editor shows: the layout, with each side's listeners, as [EditorContent] has them for one editor. */
@Composable
internal fun DiffContent(diff: AardinkDiffHandle) {
    val original = diff.original
    val modified = diff.modified
    EditorEffects(original, original.state.value, original.languageService)
    EditorEffects(modified, modified.state.value, modified.languageService)
    // Both sides share the modified side's editor options, font and theme.
    val options = modified.options.value
    val look = rememberEditorLook(modified, options)

    // A focus() made before the editor is on screen is kept until it is, for either side.
    val focusRequester = remember { FocusRequester() }
    val shown = look.fontFamily != null
    for (side in listOf(original, modified)) {
        val requests = side.focusRequests.intValue
        LaunchedEffect(requests, shown) {
            if (!shown || requests <= side.focusesDone) return@LaunchedEffect
            withFrameNanos { }
            if (runCatching { focusRequester.requestFocus() }.isSuccess) side.focusesDone = requests
        }
    }

    if (look.fontFamily != null) {
        CompositionLocalProvider(
            LocalEditorTheme provides look.theme,
            LocalEditorTypography provides look.typography,
            LocalCodeFenceTokenizers provides remember(modified.registry) { codeFenceTokenizers(modified.registry) },
        ) {
            CodeDiffEditorLayout(
                state = diff.state,
                // The host's keys come first, as on one editor: those of the side that has the focus.
                modifier = Modifier.fillMaxSize().focusRequester(focusRequester).onPreviewKeyEvent { event ->
                    hostKey(diff.focusedSide(), event)
                },
                options = diff.options.value.toDiffEditorOptions(),
                editorOptions = editorOptionsOf(options),
                languageService = modified.languageService,
                originalLanguageService = original.languageService,
            )
        }
    }
}

/**
 * [changes] as Monaco's `ILineChange` array. Lines are 1-based and inclusive on both ends; a side
 * with no lines (an insertion has none in the original, a deletion none in the modified text) ends at
 * 0 and starts at the line before the change, as Monaco's does. `charChanges` are Monaco's `ICharChange`:
 * 1-based line and column positions, the end exclusive, and absent when they were not worked out.
 */
internal fun lineChangesJson(original: CodeDocument, modified: CodeDocument, changes: List<LineChange>): String = buildJsonArray {
    for (change in changes) {
        addJsonObject {
            lineRange("original", change.originalLines)
            lineRange("modified", change.modifiedLines)
            change.charChanges?.let { chars ->
                putJsonArray("charChanges") {
                    for (char in chars) {
                        addJsonObject {
                            position("originalStart", original, char.original.first)
                            position("originalEnd", original, char.original.last + 1)
                            position("modifiedStart", modified, char.modified.first)
                            position("modifiedEnd", modified, char.modified.last + 1)
                        }
                    }
                }
            }
        }
    }
}.toString()

private fun kotlinx.serialization.json.JsonObjectBuilder.lineRange(side: String, lines: IntRange) {
    // Monaco's legacy shape: the first line, or for no lines the one before the place they would be, and 0 for the end.
    put("${side}StartLineNumber", if (lines.isEmpty()) lines.first else lines.first + 1)
    put("${side}EndLineNumber", if (lines.isEmpty()) 0 else lines.last + 1)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.position(name: String, document: CodeDocument, offset: Int) {
    val (line, column) = document.offsetToLineCol(offset.coerceIn(0, document.length))
    put("${name}LineNumber", line + 1)
    put("${name}Column", column + 1)
}
