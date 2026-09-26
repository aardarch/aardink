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
package com.aardarch.aardink.core.text

/**
 * One edit to a [CodeDocument][com.aardarch.aardink.core.CodeDocument], as its listeners see it:
 * the characters in [offset, offset + deletedLength) were replaced by [insertedLength] characters.
 * The document reports an insert and a delete as separate changes, so one of the two lengths is 0
 * unless [isReset].
 *
 * In line terms the edit began at ([startLine], [startColumn]). [removedLines] lines after it were
 * merged into it and [addedLines] new lines follow it, so everything below the edit keeps its text
 * and moves by [lineDelta] lines. The text that followed the edit on its last line started at
 * column [oldTailColumn] of line `startLine + removedLines` and now starts at column [tailColumn]
 * of line `startLine + addedLines`.
 *
 * A full replacement ([isReset]) says nothing reliable about lines or columns.
 */
internal data class DocumentChange(
    val offset: Int,
    val deletedLength: Int,
    val insertedLength: Int,
    val startLine: Int,
    val startColumn: Int,
    val removedLines: Int,
    val addedLines: Int,
    val oldTailColumn: Int,
    val tailColumn: Int,
    val version: Long,
    val isReset: Boolean = false,
) {
    val lineDelta: Int get() = addedLines - removedLines
}

internal fun interface DocumentChangeListener {
    fun onDocumentChange(change: DocumentChange)
}
