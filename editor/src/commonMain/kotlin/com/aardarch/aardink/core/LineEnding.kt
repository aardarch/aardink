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

/**
 * The characters that end a line: Monaco's `EndOfLineSequence`.
 *
 * A [CodeDocument] keeps its text exactly as given, so a file loaded with CR LF stays CR LF.
 * [CodeDocument.eol] is what it was loaded with, and Enter, typing and pasting insert that (or
 * [EditorOptions.eol][com.aardarch.aardink.ui.EditorOptions.eol] when set), so an edit never mixes
 * the two in one file.
 */
enum class LineEnding(val text: String) {
    /** `\n`, as on Linux, macOS and the web. */
    LF("\n"),

    /** `\r\n`, as on Windows. */
    CRLF("\r\n"),
    ;

    companion object {
        /** The line ending of [text]'s first line break; [LF] when it has none. */
        fun detect(text: CharSequence): LineEnding {
            val newline = text.indexOf('\n')
            return if (newline > 0 && text[newline - 1] == '\r') CRLF else LF
        }
    }
}
