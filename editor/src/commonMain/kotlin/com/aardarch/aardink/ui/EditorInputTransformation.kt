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
import com.aardarch.aardink.core.edit.AppliedChange
import com.aardarch.aardink.core.edit.EditKind
import com.aardarch.aardink.core.edit.TextChange
import com.aardarch.aardink.core.edit.TypingRules
import com.aardarch.aardink.core.edit.applyChanges

/**
 * Mirrors every user keystroke into [CodeEditorState.document] as it happens, records it for undo,
 * then applies the typing rules ([TypingRules]: smart indent on Enter, auto-close of brackets and
 * quotes) directly to the buffer the framework is already editing.
 *
 * Deliberately does NOT call [CodeEditorState.applyEdit] or any other method that would push a
 * fresh copy of [CodeEditorState.document]'s text back into `textFieldState`: this transformation
 * runs *during* the field's own edit of that same state, and re-entering it here is undefined
 * behavior. It hands the applied changes to [CodeEditorState.recordFieldEdit] instead.
 *
 * A single-character insertion with no deletion — an ordinary keystroke, not a multi-character IME
 * commit, paste, or deletion — additionally triggers [onSingleCharacterInsert] so the composable can
 * run its own completion-request logic (which needs composition-scoped state this class does not
 * have access to). [onSingleCharacterInsert]'s `autoCloseLength` reports how many characters (0 if
 * none) were auto-closed right after the typed one, so the caller can re-address any in-flight
 * completion list past that insertion too.
 */
internal class EditorInputTransformation(
    private val state: CodeEditorState,
    private val languageService: () -> LanguageService?,
    private val onSingleCharacterInsert: (insertedAt: Int, typedChar: Char, autoCloseLength: Int) -> Unit,
    private val onOtherChange: () -> Unit,
) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        normalizeLineEndings()
        // A pass with no text change is not an edit. On wasmJs every programmatic update of the
        // field (loadText, undo, ...) is echoed back through here with an empty change list;
        // recording it reported each setValue to the host twice and tokenized twice.
        if (changes.changeCount == 0) {
            onOtherChange()
            return
        }

        // The field's changes, in the coordinates of the text before them, as the document is.
        val fieldChanges = ArrayList<TextChange>(changes.changeCount)
        changes.forEachChangeReversed { range, originalRange ->
            fieldChanges.add(TextChange(originalRange.min, originalRange.max, asCharSequence().substring(range.min, range.max)))
        }
        val kind = kindOf(fieldChanges, originalSelection)
        val applied: MutableList<AppliedChange> = state.document.applyChanges(fieldChanges).toMutableList()

        val single = fieldChanges.singleOrNull()
        val typedChar = single?.takeIf { it.start == it.end && it.text.length == 1 }?.text?.get(0)
        var autoCloseLength = 0
        if (typedChar != null) {
            val followUp = TypingRules.afterTyping(state.document, languageService(), single.start, typedChar)
            if (followUp != null) {
                insert(followUp.insertAt, followUp.text)
                applied += state.document.applyChanges(listOf(TextChange(followUp.insertAt, followUp.insertAt, followUp.text)))
                placeCursorBeforeCharAt(followUp.caret)
                if (typedChar != '\n') autoCloseLength = followUp.text.length
            }
        }

        state.recordFieldEdit(applied, kind, originalSelection, selection)

        if (typedChar != null) {
            onSingleCharacterInsert(single.start, typedChar, autoCloseLength)
        } else {
            onOtherChange()
        }
    }

    /** How the undo history groups this edit: typing, backspace, forward delete, or anything else. */
    private fun kindOf(fieldChanges: List<TextChange>, before: TextRange): EditKind {
        val change = fieldChanges.singleOrNull() ?: return EditKind.Other
        val deleted = change.end - change.start
        return when {
            change.text.isNotEmpty() -> TypingRules.kindOfTyping(change.text, deleted)
            before.collapsed && before.start == change.end -> EditKind.DeletingLeft
            before.collapsed && before.start == change.start -> EditKind.DeletingRight
            else -> EditKind.Other
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
            val normalized = TypingRules.normalizeLineEndings(text)
            if (normalized != text) replace(range.min, range.max, normalized)
        }
    }
}
