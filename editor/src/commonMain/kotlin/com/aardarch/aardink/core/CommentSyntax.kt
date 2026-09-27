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
 * How a language writes comments, for the editor's toggle-comment command (Ctrl+/).
 *
 * The command comments each selected line with [line] when the language has one, and otherwise
 * wraps the selected lines in [blockStart] and [blockEnd]. Return it from
 * [IncrementalTokenizer.commentSyntax]; `null` there disables the command.
 *
 * @property line The line-comment prefix, such as `//` or `#`; null if the language has none.
 * @property blockStart The block-comment opener, such as `/*` or `<!--`; null if none.
 * @property blockEnd The block-comment closer, such as `*/` or `-->`; null if none.
 */
data class CommentSyntax(val line: String? = null, val blockStart: String? = null, val blockEnd: String? = null)
