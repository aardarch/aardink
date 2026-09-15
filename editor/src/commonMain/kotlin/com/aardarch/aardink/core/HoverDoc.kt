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
 * Documentation shown in a hover popup when the cursor rests on a token.
 *
 * @param title Primary heading (e.g. element name, property name, transform name).
 * @param content Markdown-formatted body text.
 * @param example Optional code example shown below the content.
 * @param range Document range to highlight while the popup is visible. If null the cursor token's
 *   range is used.
 */
data class HoverDoc(val title: String, val content: String, val example: String? = null, val range: IntRange? = null)
