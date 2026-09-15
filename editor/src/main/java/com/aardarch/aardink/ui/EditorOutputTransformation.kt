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
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aardarch.aardink.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange

/**
 * Replaces the old `VisualTransformation`/[applyFolding] chain for the `TextFieldState`-based
 * [BasicTextField][androidx.compose.foundation.text.BasicTextField]: colors syntax tokens (via
 * [syntaxColoredText], the same [AnnotatedString] `CodeEditorLayout` used to build for the old
 * `VisualTransformation` — see its own staleness guard for why the fallback below is a uniform
 * color rather than a second, independent per-token pass), colors find-match backgrounds, and
 * finally collapses folded ranges into their placeholders — in that order, matching the layering
 * the old `syntaxTransformation` built.
 *
 * Styling is applied FIRST, while the buffer still holds the untransformed text (so
 * [syntaxColoredText]'s spans and [matches] are plain original-document offsets); folding's
 * `delete`/`insert` calls happen after, and rely on the framework adjusting already-added style
 * ranges as the buffer is edited — see [TextFieldBuffer.addStyle]'s documented behavior. Folding
 * itself still needs to track a running offset shift across multiple folds (mirroring
 * [originalToTransformedOffset]) because [document]'s line offsets are always in *original*
 * (unfolded) coordinates, while each fold after the first is edited into an already-shifted
 * buffer.
 */
internal class EditorOutputTransformation(
    private val syntaxColoredText: AnnotatedString?,
    private val textColor: Color,
    private val matches: List<IntRange>,
    private val currentMatchIndex: Int,
    private val matchHighlight: Color,
    private val currentMatchHighlight: Color,
    private val foldedRanges: List<FoldRange>,
    private val document: CodeDocument,
    private val placeholderStyle: SpanStyle,
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val originalLength = length

        // effectiveAnnotatedText already carries per-token color spans built for this exact
        // text; if it's stale (still describes an older edit — one tokenization tick behind),
        // fall back to a single uniform color rather than risk mis-colored spans, exactly like
        // the old VisualTransformation's `base` branch did.
        if (syntaxColoredText != null && syntaxColoredText.text == asCharSequence().toString()) {
            for (span in syntaxColoredText.spanStyles) {
                val start = span.start.coerceIn(0, originalLength)
                val end = span.end.coerceIn(start, originalLength)
                if (start < end) addStyle(span.item, start, end)
            }
        } else if (originalLength > 0) {
            addStyle(SpanStyle(color = textColor), 0, originalLength)
        }

        matches.forEachIndexed { idx, range ->
            val bg = if (idx == currentMatchIndex) currentMatchHighlight else matchHighlight
            val end = (range.last + 1).coerceAtMost(originalLength)
            val start = range.first.coerceAtLeast(0).coerceAtMost(end)
            if (start < end) addStyle(SpanStyle(background = bg), start, end)
        }

        if (foldedRanges.isEmpty()) return

        val sorted = foldedRanges.sortedBy { it.startLine }
        var shift = 0
        var prevHEnd = 0
        for (fold in sorted) {
            val hStart = document.lineEnd(fold.startLine).coerceIn(0, originalLength)
            val hEnd = document.lineEnd(fold.endLine).coerceIn(0, originalLength)
            if (hStart < prevHEnd || hStart >= hEnd) continue // nested inside an already-folded region, or stale

            val bufferStart = hStart - shift
            val bufferEnd = hEnd - shift
            delete(bufferStart, bufferEnd)
            val placeholder = " ${fold.placeholder}"
            insert(bufferStart, placeholder)
            addStyle(placeholderStyle, bufferStart, bufferStart + placeholder.length)

            shift += (hEnd - hStart) - placeholder.length
            prevHEnd = hEnd
        }
    }
}
