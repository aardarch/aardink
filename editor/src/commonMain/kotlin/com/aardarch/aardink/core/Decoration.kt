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
package com.aardarch.aardink.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Something a host draws on the text, as Monaco's decorations: a highlight on what an assistant
 * changed, on the element under the mouse in a preview, on a search result. Set them with
 * [CodeEditorState.setDecorations]; their ranges follow the edits made after.
 *
 * @property range The characters it covers, as document offsets, first to last (inclusive). It may
 *   be empty (`at until at`) for a decoration of a whole line, the gutter or the rulers only; an
 *   edit that deletes all its text leaves it empty there.
 * @property style How it is drawn.
 */
@Immutable
data class Decoration(val range: IntRange, val style: DecorationStyle)

/**
 * How a [Decoration] is drawn. Every part is optional: [Color.Unspecified] (or null) draws
 * nothing of that part. None of them changes the text's layout, so a decoration costs no
 * relayout; text before or after a range (Monaco's `before` and `after`) is not here for that
 * reason.
 *
 * @property background Behind the characters, as a selection is drawn; behind the whole width of
 *   the lines with [wholeLine].
 * @property underline A line under the characters.
 * @property border A box around the characters, row by row.
 * @property wholeLine [background] fills each line the range touches, edge to edge, as Monaco's
 *   `isWholeLine`.
 * @property gutterColor A bar at the gutter's text edge on each line the range touches.
 * @property minimapColor A mark at the minimap's edge on those lines.
 * @property overviewRulerColor A mark in the overview ruler beside the scrollbar, where its lines are.
 * @property hoverMessage Shown when the mouse rests on the characters, above any diagnostics and
 *   documentation.
 */
@Immutable
data class DecorationStyle(
    val background: Color = Color.Unspecified,
    val underline: Color = Color.Unspecified,
    val border: Color = Color.Unspecified,
    val wholeLine: Boolean = false,
    val gutterColor: Color = Color.Unspecified,
    val minimapColor: Color = Color.Unspecified,
    val overviewRulerColor: Color = Color.Unspecified,
    val hoverMessage: String? = null,
)

/** One owner's decorations, moved along with each edit; a deleted range collapses where it was. */
internal class DecorationTracker :
    TrackedRanges<Decoration>(
        rangeOf = { it.range },
        withRange = { decoration, range -> decoration.copy(range = range) },
        keepsOneCharacter = false,
    )

/** A line the decorations mark in the gutter, the minimap or the overview ruler, from [firstLine] to [lastLine]. */
@Immutable
internal class LineMark(val firstLine: Int, val lastLine: Int, val color: Color)

/** The lines [decorations] mark with the colour [colorOf] picks, in [document]; none for an unspecified colour. */
internal fun lineMarks(decorations: List<Decoration>, document: CodeDocument, colorOf: (DecorationStyle) -> Color): List<LineMark> {
    if (decorations.isEmpty()) return emptyList()
    val length = document.length
    val marks = ArrayList<LineMark>()
    for (decoration in decorations) {
        val color = colorOf(decoration.style)
        if (color == Color.Unspecified) continue
        val first = document.offsetToLineCol(decoration.range.first.coerceIn(0, length)).first
        val last = if (decoration.range.isEmpty()) {
            first
        } else {
            document.offsetToLineCol(decoration.range.last.coerceIn(0, length)).first
        }
        marks += LineMark(first, maxOf(first, last), color)
    }
    return marks
}
