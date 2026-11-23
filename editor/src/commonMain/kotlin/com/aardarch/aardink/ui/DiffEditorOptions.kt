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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How [CodeDiffEditorLayout] shows the difference between two texts, with the names and defaults of
 * Monaco's diff editor options. What each side's editor looks like comes from the `editorOptions` of
 * the layout; change one with `copy`.
 *
 * @property renderSideBySide The two texts side by side, the original on the left, with blank rows
 *   that keep them aligned. False shows the modified text alone, with the original's deleted lines as
 *   rows above the lines that replaced them.
 * @property renderSideBySideInlineBreakpoint A layout narrower than this shows the inline view even
 *   with [renderSideBySide], so that a phone gets it. 900 dp, as Monaco's 900 pixels.
 * @property ignoreTrimWhitespace Leading and trailing whitespace on a line does not make it differ.
 * @property maxComputationTimeMs How long the diff may take. When it runs out, the lines it has not
 *   settled show as one change per region, and `DiffEditorState.hitTimeout` says so. 5 seconds, as
 *   Monaco's.
 * @property originalEditable The original text can be edited, in the side-by-side view, and a change
 *   can be accepted into it. Off by default: the original is read-only. The inline view never edits
 *   the original.
 * @property hideUnchangedRegions Runs of unchanged lines longer than twice [unchangedContextLines] are
 *   folded, on both sides at once, leaving that many lines either side of a change. The fold says how
 *   many lines it hides, and opening it on one side opens it on the other.
 * @property unchangedContextLines The lines of context [hideUnchangedRegions] leaves next to a
 *   change: 3, as Monaco's, and at least 1.
 * @property renderIndicators The gutter's diff lane: the added, modified and deleted lines on the
 *   modified side and the removed lines on the original's, with an arrow on a change's first line to
 *   revert it (modified side) or accept it (original side, with [originalEditable]).
 * @property enableSplitViewResizing The divider between the two sides can be dragged.
 * @property splitRatio How much of the width the original takes when the layout first shows the two
 *   sides: 0.5 is half. A changed value moves the divider.
 */
@Immutable
data class DiffEditorOptions(
    val renderSideBySide: Boolean = true,
    val renderSideBySideInlineBreakpoint: Dp = 900.dp,
    val ignoreTrimWhitespace: Boolean = true,
    val maxComputationTimeMs: Long = 5_000,
    val originalEditable: Boolean = false,
    val hideUnchangedRegions: Boolean = false,
    val unchangedContextLines: Int = 3,
    val renderIndicators: Boolean = true,
    val enableSplitViewResizing: Boolean = true,
    val splitRatio: Float = 0.5f,
)
