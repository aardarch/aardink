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
package com.aardarch.aardink.languages

import com.aardarch.aardink.core.CommentSyntax
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What a language says about typing in it, as VS Code's `language-configuration.json`: brackets
 * and quotes that close themselves or surround a selection, what Enter does, comments and fold
 * markers. [LanguageDefinition.withConfiguration] applies it, giving a language with only a grammar
 * all of that as data.
 *
 * Its regexes run on one line at a time, on the main thread at each keystroke, so write none with a
 * lookbehind: Kotlin/wasm's regex engine makes one very slow, and [parse] refuses them.
 *
 * @property comments What toggling a comment writes; null keeps the tokenizer's own.
 * @property brackets Pairs the language nests. Enter indents after an opening one, and leaves a
 *   closing one at the opener's indentation. Matching and colouring brackets stay `()[]{}`.
 * @property autoClosingPairs Pairs whose closing text is inserted when the opening one is typed.
 * @property surroundingPairs Pairs that wrap a selection when the opening one, a single character,
 *   is typed over it. One with an empty [CharacterPair.close], as in VS Code's `["$", ""]`, wraps nothing.
 * @property onEnterRules What Enter does, tried in order before anything else here.
 * @property indentationRules What Enter does when no rule and no bracket decides.
 * @property foldingMarkers Lines that open and close a fold region.
 */
data class LanguageConfiguration(
    val comments: CommentSyntax? = null,
    val brackets: List<CharacterPair> = emptyList(),
    val autoClosingPairs: List<AutoClosingPair> = emptyList(),
    val surroundingPairs: List<CharacterPair> = emptyList(),
    val onEnterRules: List<OnEnterRule> = emptyList(),
    val indentationRules: IndentationRules? = null,
    val foldingMarkers: FoldingRegionMarkers? = null,
) {
    /** An opening and a closing text, such as `(` and `)`, or `<!--` and `-->`. */
    data class CharacterPair(val open: String, val close: String)

    /**
     * [close] goes after the caret when the last character of [open] is typed and the line then ends
     * in [open]. Of several that fit, the longest [open] wins. As with VS Code's default
     * `autoCloseBefore`, it closes at the line's end or before blanks and what ends an expression,
     * not before a word; a quote does not close right after a word, as in `don't`.
     *
     * Whether the caret is in a string or a comment is worked out from its own line alone: strings
     * are delimited by the pairs with the same one-character opening and closing, and a block comment
     * opened on an earlier line is not seen.
     *
     * @property notInString Not closed in a string: VS Code's `"notIn": ["string"]`.
     * @property notInComment Not closed in a comment: VS Code's `"notIn": ["comment"]`.
     */
    data class AutoClosingPair(val open: String, val close: String, val notInString: Boolean = false, val notInComment: Boolean = false)

    /**
     * Applies [action] when Enter is pressed and every regex given finds a match.
     *
     * @property beforeText Against the text before the caret.
     * @property afterText Against the text after the caret.
     * @property previousLineText Against the line above the one Enter was pressed on.
     */
    data class OnEnterRule(
        val action: EnterAction,
        val beforeText: Regex? = null,
        val afterText: Regex? = null,
        val previousLineText: Regex? = null,
    )

    /**
     * What Enter does to the new line.
     *
     * @property indent How its indentation relates to the line above's.
     * @property appendText Text the new line starts with, after its indentation. Not used with
     *   [IndentAction.IndentOutdent], whose line in between does not exist here.
     * @property removeText Characters taken off the end of the indentation before [indent] applies.
     */
    data class EnterAction(val indent: IndentAction = IndentAction.None, val appendText: String = "", val removeText: Int = 0)

    /** How Enter indents the new line. */
    enum class IndentAction {
        /** As the line above. */
        None,

        /** One level further than the line above. */
        Indent,

        /** One level less than the line above. */
        Outdent,

        /**
         * For the caret between a pair: as the line above, so the closer stays at the opener's
         * indentation with the caret before it. VS Code adds a line in between; this does not.
         */
        IndentOutdent,
    }

    /**
     * How a line's text decides the next line's indentation, when no [OnEnterRule] or bracket does.
     *
     * @property increase The lines after a match are indented one level further.
     * @property decrease A match is one level less than the lines before it: the text the caret
     *   leaves on the new line, if it matches, starts one level back.
     * @property indentNextLine Only the one line after a match is indented further.
     * @property unIndentedLine A match says nothing about the next line, which looks further up.
     */
    data class IndentationRules(
        val increase: Regex? = null,
        val decrease: Regex? = null,
        val indentNextLine: Regex? = null,
        val unIndentedLine: Regex? = null,
    )

    companion object {
        /**
         * A configuration from the text of a `language-configuration.json`, which may have comments
         * and trailing commas:
         *
         * ```json
         * {
         *   "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
         *   "brackets": [["{", "}"], ["(", ")"]],
         *   "autoClosingPairs": [["{", "}"], { "open": "'", "close": "'", "notIn": ["string", "comment"] }],
         *   "surroundingPairs": [["{", "}"], ["'", "'"]],
         *   "folding": { "markers": { "start": "^#region\\b", "end": "^#endregion\\b" } },
         *   "indentationRules": { "increaseIndentPattern": "\\{\\s*$", "decreaseIndentPattern": "^\\s*\\}" },
         *   "onEnterRules": [{ "beforeText": "^\\s*\\* ", "action": { "indent": "none", "appendText": "* " } }]
         * }
         * ```
         *
         * Every key is optional. As in VS Code, without `autoClosingPairs` the brackets close
         * themselves, and without `surroundingPairs` those pairs surround. A pair is `[open, close]`
         * or `{ "open", "close" }`, and only a surrounding pair's close may be empty; a regex is a
         * JavaScript regex as a string or `{ "pattern", "flags" }`, of whose flags only `i` matters
         * on one line; `indent` is `none`, `indent`, `outdent` or `indentOutdent`. An empty
         * `lineComment` is none.
         *
         * `wordPattern`, `autoCloseBefore` and `folding.offSide` are not read, and neither is
         * anything else VS Code knows that is not above. `notIn` knows `string` and `comment`.
         *
         * Throws [IllegalArgumentException] saying where for JSON it cannot use, such as a regex
         * with a lookbehind: `onEnterRules[0].beforeText: lookbehind is not supported: (?<=a)b`.
         */
        fun parse(json: String): LanguageConfiguration {
            val root = try {
                Jsonc.parseToJsonElement(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Not JSON: ${e.message}", e)
            } as? JsonObject ?: throw IllegalArgumentException("A language configuration is a JSON object")
            val brackets = root.given("brackets")?.let { pairs(it, "brackets") }.orEmpty()
            val autoClosing = root.given("autoClosingPairs")?.let { autoClosingPairs(it, "autoClosingPairs") }
                ?: brackets.map { AutoClosingPair(it.open, it.close) }
            return LanguageConfiguration(
                comments = root.given("comments")?.let { comments(it, "comments") },
                brackets = brackets,
                autoClosingPairs = autoClosing,
                surroundingPairs = root.given("surroundingPairs")?.let { pairs(it, "surroundingPairs", closeMayBeEmpty = true) }
                    ?: autoClosing.map { CharacterPair(it.open, it.close) },
                onEnterRules = root.given("onEnterRules")?.let { onEnterRules(it, "onEnterRules") }.orEmpty(),
                indentationRules = root.given("indentationRules")?.let { indentationRules(it, "indentationRules") },
                foldingMarkers = root.given("folding")?.let { foldingMarkers(it, "folding") },
            )
        }

        private fun comments(value: JsonElement, path: String): CommentSyntax? {
            val obj = value.obj(path)
            // VS Code also takes `{ "comment": "//", "noIndent": true }`; the prefix is what counts.
            val line = when (val given = obj.given("lineComment")) {
                null -> null
                is JsonObject -> given.requiredText("comment", "$path.lineComment")
                else -> text(given, "$path.lineComment")
            }?.takeIf { it.isNotEmpty() } // VS Code's XSL says `""` for a language with block comments only.
            val block = obj.given("blockComment")?.let { pair(it, "$path.blockComment") }
            return CommentSyntax(line = line, blockStart = block?.open, blockEnd = block?.close).takeIf { line != null || block != null }
        }

        private fun pairs(value: JsonElement, path: String, closeMayBeEmpty: Boolean = false): List<CharacterPair> =
            value.array(path).mapIndexed { index, pair -> pair(pair, "$path[$index]", closeMayBeEmpty) }

        private fun autoClosingPairs(value: JsonElement, path: String): List<AutoClosingPair> =
            value.array(path).mapIndexed { index, element ->
                val pair = pair(element, "$path[$index]")
                val notIn = (element as? JsonObject)?.given("notIn")?.array("$path[$index].notIn")
                    ?.mapIndexed { place, name -> text(name, "$path[$index].notIn[$place]") }.orEmpty()
                AutoClosingPair(pair.open, pair.close, notInString = "string" in notIn, notInComment = "comment" in notIn)
            }

        private fun pair(value: JsonElement, path: String, closeMayBeEmpty: Boolean = false): CharacterPair {
            val (open, close) = when (value) {
                is JsonArray -> {
                    if (value.size != 2) throw IllegalArgumentException("$path: a pair is [open, close]")
                    text(value[0], "$path[0]") to text(value[1], "$path[1]")
                }

                is JsonObject -> value.requiredText("open", path) to value.requiredText("close", path)

                else -> throw IllegalArgumentException("$path: a pair is [open, close] or { open, close }")
            }
            if (open.isEmpty() || (close.isEmpty() && !closeMayBeEmpty)) {
                throw IllegalArgumentException("$path: a pair's open and close are not empty")
            }
            return CharacterPair(open, close)
        }

        private fun onEnterRules(value: JsonElement, path: String): List<OnEnterRule> = value.array(path).mapIndexed { index, element ->
            val rulePath = "$path[$index]"
            val rule = element.obj(rulePath)
            OnEnterRule(
                action = enterAction(
                    rule.given("action") ?: throw IllegalArgumentException("$rulePath: a rule needs an action"),
                    "$rulePath.action",
                ),
                beforeText = regexAt(rule, "beforeText", rulePath),
                afterText = regexAt(rule, "afterText", rulePath),
                previousLineText = regexAt(rule, "previousLineText", rulePath),
            )
        }

        private fun enterAction(value: JsonElement, path: String): EnterAction {
            val obj = value.obj(path)
            val indent = when (val name = obj.requiredText("indent", path)) {
                "none" -> IndentAction.None
                "indent" -> IndentAction.Indent
                "outdent" -> IndentAction.Outdent
                "indentOutdent" -> IndentAction.IndentOutdent
                else -> throw IllegalArgumentException("$path.indent: none, indent, outdent or indentOutdent, not '$name'")
            }
            val removeText = obj.given("removeText")?.let {
                (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull?.takeIf { n -> n >= 0 }
                    ?: throw IllegalArgumentException("$path.removeText: a number of characters")
            } ?: 0
            return EnterAction(
                indent,
                appendText = obj.given("appendText")?.let {
                    text(it, "$path.appendText")
                }.orEmpty(),
                removeText = removeText,
            )
        }

        private fun indentationRules(value: JsonElement, path: String): IndentationRules {
            val obj = value.obj(path)
            return IndentationRules(
                increase = regexAt(obj, "increaseIndentPattern", path),
                decrease = regexAt(obj, "decreaseIndentPattern", path),
                indentNextLine = regexAt(obj, "indentNextLinePattern", path),
                unIndentedLine = regexAt(obj, "unIndentedLinePattern", path),
            )
        }

        private fun foldingMarkers(value: JsonElement, path: String): FoldingRegionMarkers? {
            val markers = value.obj(path).given("markers")?.obj("$path.markers") ?: return null
            return FoldingRegionMarkers(
                start = regexAt(markers, "start", "$path.markers") ?: throw IllegalArgumentException("$path.markers: markers need a start"),
                end = regexAt(markers, "end", "$path.markers") ?: throw IllegalArgumentException("$path.markers: markers need an end"),
            )
        }

        private fun regexAt(obj: JsonObject, key: String, path: String): Regex? = obj.given(key)?.let { regex(it, "$path.$key") }

        /** A regex as VS Code writes one: its source, or `{ "pattern", "flags" }`. */
        private fun regex(value: JsonElement, path: String): Regex {
            val source: String
            val flags: String
            if (value is JsonObject) {
                source = value.requiredText("pattern", path)
                flags = value.given("flags")?.let { text(it, "$path.flags") }.orEmpty()
            } else {
                source = text(value, path)
                flags = ""
            }
            if ("(?<=" in source || "(?<!" in source) throw IllegalArgumentException("$path: lookbehind is not supported: $source")
            return try {
                val pattern = fromJavaScript(source)
                if ('i' in flags) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$path: not a regex: ${e.message}", e)
            }
        }

        /**
         * JavaScript regex [source] for [Regex], whose engines refuse two things JavaScript reads as
         * themselves, and VS Code's own files lean on: a `{` that starts no quantifier, and a `[` in
         * a character class. The brace of a `\p{...}` is left as it is.
         */
        private fun fromJavaScript(source: String): String {
            val pattern = StringBuilder(source.length + 8)
            var inClass = false
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '\\' -> {
                        val end = escapeEnd(source, i)
                        pattern.append(source, i, end)
                        i = end - 1
                    }

                    inClass && c == '[' -> pattern.append("\\[")

                    inClass -> {
                        pattern.append(c)
                        inClass = c != ']'
                    }

                    c == '[' -> {
                        pattern.append(c)
                        inClass = true
                    }

                    c == '{' && Quantifier.matchAt(source, i) == null -> pattern.append("\\{")

                    else -> pattern.append(c)
                }
                i++
            }
            return pattern.toString()
        }

        /** Where the escape at [start] of [source] ends: after the character it escapes, or a `\p{...}`'s brace. */
        private fun escapeEnd(source: String, start: Int): Int {
            val end = (start + 2).coerceAtMost(source.length)
            val property = source.getOrNull(start + 1).let { it == 'p' || it == 'P' } && source.getOrNull(end) == '{'
            val brace = if (property) source.indexOf('}', end) else -1
            return if (brace < 0) end else brace + 1
        }

        private fun text(value: JsonElement, path: String): String =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$path: not a string")

        private fun JsonElement.array(path: String): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("$path: not an array")

        private fun JsonElement.obj(path: String): JsonObject =
            this as? JsonObject ?: throw IllegalArgumentException("$path: not an object")

        private fun JsonObject.requiredText(key: String, path: String): String =
            text(given(key) ?: throw IllegalArgumentException("$path: needs \"$key\""), "$path.$key")

        /** The value at [key], unless it is absent or `null`. */
        private fun JsonObject.given(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }
    }
}

/** What a `{` in a regex must start to be more than itself. */
private val Quantifier = Regex("""\{\d+(,\d*)?}""")

/** JSON as `language-configuration.json` files write it, with comments and trailing commas. */
private val Jsonc = Json {
    allowComments = true
    allowTrailingComma = true
}
