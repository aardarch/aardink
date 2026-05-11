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
package com.aardarch.aardink.languages.internal.html

import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.languages.internal.xml.XmlTokenizer

/**
 * HTML tokenizer. v1 reuses the XML state machine — the rules are close enough that the visual
 * result is good. A future revision can layer CSS / JS highlighting inside `<style>` / `<script>`
 * blocks.
 */
object HtmlTokenizer : IncrementalTokenizer {

    override fun tokenizeFull(text: String): List<Token> = XmlTokenizer.tokenizeFull(text)

    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> =
        XmlTokenizer.tokenizeLines(text, dirtyRange, previousTokens)

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = XmlTokenizer.canSpanLines(lineIndex, tokens)

    override fun keyboardToolbarChars(): List<Char> = listOf('<', '>', '/', '=', '"', '&', ';', '#', '.')
}
