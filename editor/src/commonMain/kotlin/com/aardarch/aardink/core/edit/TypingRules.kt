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
import com.aardarch.aardink.core.LanguageService

/**
 * What typing does beyond inserting the character: line-ending normalisation, smart indent after
 * Enter, and auto-closing brackets and quotes. Shared by every input path, so the text field today
 * and the editor's own input later behave the same.
 */
internal object TypingRules {

    /** CR LF and lone CR as LF: what user input (a paste, the IME) is stored as. */
    fun normalizeLineEndings(text: String): String = if ('\r' in text) text.replace("\r\n", "\n").replace('\r', '\n') else text

    /** What to insert after a typed character, and where the caret goes. */
    class FollowUp(val insertAt: Int, val text: String, val caret: Int)

    /**
     * The follow-up to typing [typed] at [offset] (the document already contains it), or null.
     * Enter indents the new line as [service] says; other characters may be auto-closed, with the
     * caret left between the pair. Nothing while an IME composition is in progress: the composed
     * text is not final.
     */
    fun afterTyping(document: CodeDocument, service: LanguageService?, offset: Int, typed: Char, composing: Boolean = false): FollowUp? {
        if (service == null || composing) return null
        return if (typed == '\n') {
            val newLine = document.offsetToLineCol(offset + 1).first
            val spaces = service.smartIndent(document, newLine)
            if (spaces <= 0) null else FollowUp(offset + 1, " ".repeat(spaces), offset + 1 + spaces)
        } else {
            val closing = service.autoClose(document, offset, typed) ?: return null
            FollowUp(offset + 1, closing, offset + 1)
        }
    }

    /** The undo grouping of typing [text] over a selection of [replacedLength] characters. */
    fun kindOfTyping(text: String, replacedLength: Int): EditKind = when {
        replacedLength > 0 || text.length != 1 || text == "\n" -> EditKind.Other
        text[0].isWhitespace() -> EditKind.TypingSpace
        else -> EditKind.Typing
    }
}
