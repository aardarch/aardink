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
package com.aardarch.aardink.core.edit

import com.aardarch.aardink.core.CodeDocument

/** Replace the characters in [start, end) of the document with [text]. */
internal data class TextChange(val start: Int, val end: Int, val text: String) {
    init {
        require(start in 0..end) { "invalid change $start..$end" }
    }

    val isNoOp: Boolean get() = start == end && text.isEmpty()
}

/**
 * A change as it was applied: at [start], [removed] was replaced by [inserted]. Enough to undo it
 * ([inverse]) and redo it, without the document it was applied to.
 */
internal data class AppliedChange(val start: Int, val removed: String, val inserted: String) {
    val inverse: AppliedChange get() = AppliedChange(start, inserted, removed)
}

/**
 * Applies [changes] (offsets into the document as it is now, not overlapping) as one edit and
 * returns them as applied, in the order they were applied: from the end of the document to the
 * start, so no change moves the text a later one addresses. Changes at the same offset keep their
 * order: inserting "a" then "b" at one offset reads "ab".
 */
internal fun CodeDocument.applyChanges(changes: List<TextChange>): List<AppliedChange> {
    val ordered = changes.withIndex()
        .filterNot { it.value.isNoOp }
        .sortedWith(
            compareByDescending<IndexedValue<TextChange>> {
                it.value.start
            }.thenByDescending { it.value.end }.thenByDescending { it.index },
        )
        .map { it.value }
    val applied = ArrayList<AppliedChange>(ordered.size)
    for (change in ordered) {
        val start = change.start.coerceIn(0, length)
        val end = change.end.coerceIn(start, length)
        val removed = if (end > start) subSequence(start, end).toString() else ""
        if (end > start) delete(start, end - start)
        if (change.text.isNotEmpty()) insert(start, change.text)
        applied.add(AppliedChange(start, removed, change.text))
    }
    return applied
}

/** Applies changes recorded by [applyChanges] again, in their order: a redo. */
internal fun CodeDocument.reapply(applied: List<AppliedChange>) {
    for (change in applied) replaceAt(change.start, change.removed.length, change.inserted)
}

/** Reverts changes recorded by [applyChanges]: their inverses, last applied first. An undo. */
internal fun CodeDocument.revert(applied: List<AppliedChange>) {
    for (change in applied.asReversed()) replaceAt(change.start, change.inserted.length, change.removed)
}

private fun CodeDocument.replaceAt(start: Int, removeLength: Int, text: String) {
    if (removeLength > 0) delete(start, removeLength)
    if (text.isNotEmpty()) insert(start, text)
}

/**
 * Where [offset] (into the text before [changes]) is after them. An offset after a change moves by
 * its size difference. One inside a replaced span goes to the end of the replacement; one exactly
 * at an insertion point ends up after the inserted text, or before it when [stickToEnd] is false.
 */
internal fun mapOffset(offset: Int, changes: List<TextChange>, stickToEnd: Boolean = true): Int {
    var shift = 0
    var snapped: Int? = null
    for (change in changes) {
        val insertedDelta = change.text.length - (change.end - change.start)
        when {
            change.end < offset -> shift += insertedDelta
            change.end == offset && (change.start < offset || stickToEnd) -> shift += insertedDelta
            change.start < offset -> snapped = change.start + change.text.length
        }
    }
    return (snapped ?: offset) + shift
}
