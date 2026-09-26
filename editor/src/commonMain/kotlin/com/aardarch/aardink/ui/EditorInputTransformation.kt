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

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.forEachChangeReversed
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.LanguageService

/**
 * Mirrors every user keystroke into [CodeEditorState.document] and [CodeEditorState.undoManager]
 * as it happens, then applies the same-language "typing conveniences" the old `handleTextChange`
 * applied — smart indent on `Enter` and auto-close of brackets/quotes — directly to the buffer the
 * framework is already editing.
 *
 * Deliberately does NOT call [CodeEditorState.applyEdit] or any other method that would push a
 * fresh copy of [CodeEditorState.document]'s text back into `textFieldState`: this transformation
 * runs *during* the field's own edit of that same state, and re-entering it here is undefined
 * behavior. It calls [CodeEditorState.bumpTextVersionAndScheduleTokenization] once the mirroring is
 * done instead.
 *
 * A single-character insertion with no deletion — an ordinary keystroke, not a multi-character IME
 * commit, paste, or deletion — additionally triggers [onSingleCharacterInsert] so the composable can
 * run its own completion-request logic (which needs composition-scoped state this class does not
 * have access to). [onSingleCharacterInsert]'s `autoCloseLength` reports how many characters (0 if
 * none) were auto-closed right after the typed one, so the caller can re-address any in-flight
 * completion list past that insertion too — matching the old `handleTextChange`'s
 * `carried.map { it.shiftedForInsert(finalSelection.start, closing.length, absorbing = false) }`.
 */
internal class EditorInputTransformation(
    private val state: CodeEditorState,
    private val languageService: () -> LanguageService?,
    private val onSingleCharacterInsert: (insertedAt: Int, typedChar: Char, autoCloseLength: Int) -> Unit,
    private val onOtherChange: () -> Unit,
) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        normalizeLineEndings()

        var singleCharInsertAt = -1
        var singleCharTyped: Char? = null
        val isSingleChange = changes.changeCount == 1

        changes.forEachChangeReversed { range, originalRange ->
            val deleteLen = originalRange.length
            if (deleteLen > 0) {
                val deletedText = state.document.text.substring(originalRange.min, originalRange.max)
                state.document.delete(originalRange.min, deleteLen)
                state.undoManager.recordDelete(originalRange.min, deleteLen, deletedText)
            }
            val insertLen = range.length
            if (insertLen > 0) {
                val insertedText = asCharSequence().substring(range.min, range.max)
                state.document.insert(originalRange.min, insertedText)
                state.undoManager.recordInsert(originalRange.min, insertedText)
                if (isSingleChange && deleteLen == 0 && insertLen == 1) {
                    singleCharInsertAt = originalRange.min
                    singleCharTyped = insertedText[0]
                }
            }
        }

        val typedChar = singleCharTyped
        var autoCloseLength = 0
        if (typedChar != null) {
            val service = languageService()
            if (service != null) {
                if (typedChar == '\n') {
                    val (newLine, _) = state.document.offsetToLineCol(singleCharInsertAt + 1)
                    val spaces = service.smartIndent(state.document, newLine)
                    if (spaces > 0) {
                        val indentAt = singleCharInsertAt + 1
                        val indent = " ".repeat(spaces)
                        insert(indentAt, indent)
                        state.document.insert(indentAt, indent)
                        state.undoManager.recordInsert(indentAt, indent)
                        placeCursorBeforeCharAt(indentAt + spaces)
                    }
                } else {
                    val closing = service.autoClose(state.document, singleCharInsertAt, typedChar)
                    if (closing != null) {
                        val insertAt = selection.start
                        insert(insertAt, closing)
                        state.document.insert(insertAt, closing)
                        state.undoManager.recordInsert(insertAt, closing)
                        placeCursorBeforeCharAt(insertAt)
                        autoCloseLength = closing.length
                    }
                }
            }
        }

        // A pass with no text change is not an edit. On wasmJs every programmatic update of the
        // field (loadText, undo, ...) is echoed back through here with an empty change list;
        // bumping for it reported each setValue to the host twice and tokenized twice.
        if (changes.changeCount > 0) state.bumpTextVersionAndScheduleTokenization()

        if (typedChar != null) {
            onSingleCharacterInsert(singleCharInsertAt, typedChar, autoCloseLength)
        } else {
            onOtherChange()
        }
    }

    /**
     * Rewrites CR LF (and a lone CR) in the text just inserted as LF, in the buffer itself, so
     * the field shows and [CodeEditorState.document] receives the same text. A Windows clipboard
     * or a browser paste delivers CR LF; left in, the carriage return is an invisible character
     * a caret can stop on, and a paste into an LF document leaves mixed line endings.
     *
     * Only user input is normalised: [CodeEditorState.loadText] keeps a host's text verbatim, so a
     * CR LF file round-trips unchanged.
     */
    private fun TextFieldBuffer.normalizeLineEndings() {
        // Collected first, then rewritten from the end: editing while iterating the change list
        // would shift the ranges still to come.
        val inserted = ArrayList<TextRange>(changes.changeCount)
        changes.forEachChangeReversed { range, _ -> if (range.length > 0) inserted += range }
        for (range in inserted) {
            val text = asCharSequence().substring(range.min, range.max)
            if ('\r' in text) replace(range.min, range.max, text.replace("\r\n", "\n").replace('\r', '\n'))
        }
    }
}
