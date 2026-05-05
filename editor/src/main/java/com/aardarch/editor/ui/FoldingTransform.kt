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
package com.aardarch.editor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import com.aardarch.editor.core.CodeDocument
import com.aardarch.editor.core.FoldRange

/**
 * Collapses folded regions in [text] for display in a [BasicTextField].
 *
 * Each folded range hides its interior lines (startLine+1 through endLine) and replaces them with
 * a styled placeholder appended to the start line. The returned [TransformedText] includes an
 * [OffsetMapping] that lets Compose translate between original document offsets and the collapsed
 * display offsets in both directions, so cursor placement and selection remain correct.
 *
 * Nested folds (inner ranges whose hidden region falls inside an outer fold's hidden region) are
 * skipped — the outer fold already hides them.
 */
fun applyFolding(
    text: AnnotatedString,
    foldedRanges: List<FoldRange>,
    document: CodeDocument,
    placeholderStyle: SpanStyle,
): TransformedText {
    if (foldedRanges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

    val origLen = text.length
    val sorted = foldedRanges.sortedBy { it.startLine }

    // origToTrans[i] = where original offset i appears in the transformed string.
    // transToOrig[j] = which original offset corresponds to transformed offset j.
    val origToTrans = IntArray(origLen + 1)
    val transToOrigList = mutableListOf<Int>()
    val builder = AnnotatedString.Builder()

    var origPos = 0
    for (fold in sorted) {
        val hStart = document.lineEnd(fold.startLine).coerceIn(0, origLen)
        val hEnd = if (fold.endLine + 1 < document.lineCount) {
            document.lineStart(fold.endLine + 1)
        } else {
            document.length
        }.coerceIn(0, origLen)

        if (hStart < origPos) continue // nested inside an already-folded region
        if (hStart >= hEnd) continue // degenerate / stale range

        // Visible segment before this fold
        if (origPos < hStart) {
            for (i in origPos until hStart) {
                origToTrans[i] = transToOrigList.size
                transToOrigList.add(i)
            }
            builder.append(text.subSequence(origPos, hStart))
        }

        // Hidden region: all original offsets map to the placeholder's start in transformed space
        val plTransStart = transToOrigList.size
        for (i in hStart until hEnd) {
            origToTrans[i] = plTransStart
        }

        // Placeholder: all transformed offsets inside it map back to hStart in original space
        val placeholder = " ${fold.placeholder}"
        builder.pushStyle(placeholderStyle)
        builder.append(placeholder)
        builder.pop()
        repeat(placeholder.length) { transToOrigList.add(hStart) }

        origPos = hEnd
    }

    // Remaining visible text after all folds
    if (origPos < origLen) {
        for (i in origPos until origLen) {
            origToTrans[i] = transToOrigList.size
            transToOrigList.add(i)
        }
        builder.append(text.subSequence(origPos, origLen))
    }

    // Sentinel for end-of-text positions
    origToTrans[origLen] = transToOrigList.size
    transToOrigList.add(origLen)

    val transToOrig = transToOrigList.toIntArray()

    return TransformedText(
        text = builder.toAnnotatedString(),
        offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = origToTrans[offset.coerceIn(0, origLen)]

            override fun transformedToOriginal(offset: Int): Int = transToOrig[offset.coerceIn(0, transToOrig.size - 1)]
        },
    )
}
