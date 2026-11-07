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
import com.aardarch.aardink.core.text.DocumentChange
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A short label [LanguageService.inlayHints] shows among the characters: a parameter's name, an
 * inferred type. It is not in the document, so a caret, a selection, find and the clipboard pass
 * over it. It moves with the text around it until the next answer replaces it.
 *
 * @property offset The document offset it stands at, in front of the character there. A caret at
 *   this offset is in front of the label.
 * @property label What it shows, on one row: a line break in it shows as a space.
 */
@Immutable
data class InlayHint(val offset: Int, val label: String)

/**
 * In the context of a call to [LanguageService.inlayHints], told what the answer holds so far when
 * the rest may be long coming, or never come: [CompositeLanguageService] has its primary's hints
 * while a language server behind them starts up or sits on the request. What the call returns is
 * still the answer. Each [offer] stands for all the call has so far.
 */
internal class EarlyInlayHints(val offer: (List<InlayHint>) -> Unit) : AbstractCoroutineContextElement(EarlyInlayHints) {
    companion object Key : CoroutineContext.Key<EarlyInlayHints>
}

/**
 * The hints on show, moved with each edit until the next answer. A hint on text that went goes with
 * it: what it said no longer applies, and a block deleted would leave them all at one column.
 */
internal class InlayHintTracker : OffsetTracker<InlayHint>({ it.offset }, { hint, offset -> hint.copy(offset = offset) })

/** Items that each stand at an offset, moved with each edit. One on text that went goes with it. */
internal open class OffsetTracker<T>(private val offsetOf: (T) -> Int, withOffset: (T, Int) -> T) :
    TrackedRanges<T>(
        rangeOf = { offsetOf(it).let { offset -> offset until offset } },
        withRange = { item, range -> withOffset(item, range.first) },
        keepsOneCharacter = false,
    ) {
    override fun onDocumentChange(change: DocumentChange) {
        if (change.deletedLength > 0 && !change.isReset) {
            val inside = (change.offset + 1) until (change.offset + change.deletedLength)
            if (items.any { offsetOf(it) in inside }) replace(items.filterNot { offsetOf(it) in inside })
        }
        super.onDocumentChange(change)
    }
}
