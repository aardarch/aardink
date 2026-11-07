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
 * nothing of that part.
 *
 * Most parts are drawn over the laid-out text, so setting them lays nothing out again. [before],
 * [after] and [inlineColor] are part of a line's layout: the lines they touch are laid out again
 * when they change, and only those. Text from [before] and [after] is shown among the characters
 * and is not in the document, so a caret, a selection, find and the clipboard pass over it. It
 * stays with the range as the text is edited, and goes with it.
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
 * @property before Text shown in front of the range's first character, as Monaco's `before`, on one
 *   row: a line break in it shows as a space. A caret at the range's start is in front of it, where
 *   typing goes, as text typed there moves the range along.
 * @property after Text shown behind the range's last character, as Monaco's `after`, on one row. A
 *   caret at the range's end is behind it, where typing goes, as text typed there stays out of the
 *   range. An empty range shows [before] then [after] at its place, with a caret in front of both.
 * @property inlineColor The colour of the range's characters, over their syntax colour, and of
 *   [before] and [after], which are faint without it.
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
    val before: String? = null,
    val after: String? = null,
    val inlineColor: Color = Color.Unspecified,
)

/** Whether this is part of a line's layout, not only drawn over it. */
internal val DecorationStyle.isInline: Boolean
    get() = !before.isNullOrEmpty() || !after.isNullOrEmpty() || inlineColor != Color.Unspecified

/** One owner's decorations, moved along with each edit; a deleted range collapses where it was. */
internal class DecorationTracker :
    TrackedRanges<Decoration>(
        rangeOf = { it.range },
        withRange = { decoration, range -> decoration.copy(range = range) },
        keepsOneCharacter = false,
    ) {
    /** Whether any of them is part of a line's layout; see [isInline]. */
    var hasInline = false
}

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
