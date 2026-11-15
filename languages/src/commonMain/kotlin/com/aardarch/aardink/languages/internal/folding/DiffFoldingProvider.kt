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
package com.aardarch.aardink.languages.internal.folding

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FoldingProvider

/**
 * Folds a unified diff: each file from its first header line to its last line, and each hunk from
 * its `@@` line to its last line. A hunk's length is read from its header's line counts, so a
 * removed line that reads `--- x` stays in its hunk. A file starts at a `diff` line, or at a `---`
 * line followed by `+++` when it has none.
 */
internal object DiffFoldingProvider : FoldingProvider {

    private val HUNK = Regex("""^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@""")

    override fun foldableRanges(document: CodeDocument): List<FoldRange> {
        val ranges = ArrayList<FoldRange>()
        val lineCount = document.lineCount
        var file = -1
        var fileHasHunk = false
        var lastNonBlank = -1
        var hunk = -1
        var oldLeft = 0
        var newLeft = 0

        fun closeFile(before: Int) {
            if (file >= 0 && lastNonBlank > file && lastNonBlank < before) ranges += FoldRange(file, lastNonBlank)
        }

        for (line in 0 until lineCount) {
            val text = document.lineText(line)
            if (hunk >= 0) {
                when {
                    text.startsWith('-') -> oldLeft--

                    text.startsWith('+') -> newLeft--

                    text.startsWith('\\') -> Unit

                    else -> {
                        oldLeft--
                        newLeft--
                    }
                }
                if (text.isNotBlank()) lastNonBlank = line
                if (oldLeft <= 0 && newLeft <= 0) {
                    if (line > hunk) ranges += FoldRange(hunk, line)
                    hunk = -1
                }
                continue
            }
            val header = HUNK.find(text)
            when {
                text.startsWith("diff ") -> {
                    closeFile(line)
                    file = line
                    fileHasHunk = false
                }

                text.startsWith("--- ") && line + 1 < lineCount && document.lineText(line + 1).startsWith("+++ ") &&
                    (file < 0 || fileHasHunk) -> {
                    closeFile(line)
                    file = line
                    fileHasHunk = false
                }

                header != null -> {
                    fileHasHunk = true
                    hunk = line
                    oldLeft = header.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: 0
                    newLeft = header.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: 0
                    if (oldLeft <= 0 && newLeft <= 0) hunk = -1
                }
            }
            if (text.isNotBlank()) lastNonBlank = line
        }
        if (hunk >= 0 && lastNonBlank > hunk) ranges += FoldRange(hunk, lastNonBlank)
        closeFile(lineCount)
        return ranges.sortedBy { it.startLine }
    }
}
