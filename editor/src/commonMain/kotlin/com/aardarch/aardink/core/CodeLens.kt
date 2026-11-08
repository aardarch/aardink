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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What [LanguageService.codeLenses] shows in a row above a line: a reference count, a way to run a
 * test. It is not in the document, so a caret, a selection, find and the clipboard pass over it. It
 * moves with the text around it until the next answer replaces it.
 *
 * @property offset A document offset on the line it is above.
 * @property title What it shows, on one row: a line break in it shows as a space. Blank means not
 *   known yet: the lens keeps its row, so that the text does not move when the title comes, and the
 *   editor asks [LanguageService.codeLenses] again when it is on screen. It cannot be clicked.
 * @property onClick Runs when it is clicked or tapped, on the thread the editor's input runs on, so
 *   it should return at once. The caret stays where it was.
 */
@Immutable
data class CodeLens(val offset: Int, val title: String, val onClick: () -> Unit = {})

/**
 * In the context of a call to [LanguageService.codeLenses], told what the answer holds so far, as
 * [EarlyInlayHints] is for hints. What the call returns is still the answer.
 */
internal class EarlyCodeLenses(val offer: (List<CodeLens>) -> Unit) : AbstractCoroutineContextElement(EarlyCodeLenses) {
    companion object Key : CoroutineContext.Key<EarlyCodeLenses>
}
