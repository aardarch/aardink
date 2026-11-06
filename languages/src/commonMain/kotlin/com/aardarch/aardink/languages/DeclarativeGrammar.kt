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
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.concurrent.Volatile

/**
 * The type a grammar's token [name] (with its postfix) gets: a [NamedTokenType], so a theme colours
 * it by its name or the longest prefix of it with a colour. The bare names `comment` and `string`
 * are the editor's own [TokenType.Comment] and [TokenType.StringLiteral].
 */
internal fun grammarTokenType(name: String): TokenType = when (name) {
    "" -> TokenType.Default
    "comment" -> TokenType.Comment
    "string" -> TokenType.StringLiteral
    else -> NamedTokenType(name)
}

/**
 * A syntax grammar written as data, for [DeclarativeTokenizer]: a subset of Monaco's Monarch, so
 * that a grammar can come from JavaScript (the web's `registerLanguage`) or a file, with no code.
 *
 * ```json
 * {
 *   "defaultToken": "",
 *   "keywords": ["if", "else", "while"],
 *   "digits": "[0-9]+",
 *   "tokenizer": {
 *     "root": [
 *       ["[a-z_]\\w*", { "cases": { "@keywords": "keyword", "@default": "identifier" } }],
 *       ["@digits", "number"],
 *       ["\"", "string", "@string"],
 *       ["(\\w+)(=)", ["attribute.name", "delimiter"]],
 *       { "include": "@whitespace" }
 *     ],
 *     "string": [
 *       ["[^\"]+", "string"],
 *       ["\"", "string", "@pop"]
 *     ],
 *     "whitespace": [["\\s+", ""], ["#.*", "comment"]]
 *   }
 * }
 * ```
 *
 * - **States:** `tokenizer` maps state names to rules; `root` (or the first state) is where every
 *   document starts. A state named `a.b` that is not defined falls back to `a`.
 * - **Rules:** `[regex, action]` or `[regex, action, next]`, or `{ "regex", "action", "next" }`,
 *   or `{ "include": "@state" }` for another state's rules. The first rule whose regex matches at
 *   the current position wins. Regexes see one line at a time.
 * - **Actions:** a token name; `{ "token", "next", "switchTo", "goBack" }`;
 *   `{ "cases": { guard: action } }`; or an array with an action per capture group, any of those
 *   three, whose cases test the group's own text and whose `next`s apply in order.
 *   - `switchTo` replaces the current state instead of pushing one, and wins over `next`.
 *   - `goBack` hands that many characters of the match back to be read again, never more than
 *     the match itself. Not in a group.
 *   - The token `@rematch` (not in a group) consumes nothing and matches again in the next state.
 *   - The token `@brackets`, or `@brackets.suffix`, takes the token of the bracket the match is
 *     (see `brackets` below), with the suffix after it. Text that is no bracket gets the default
 *     token.
 *   - `log` is accepted and ignored: it is a grammar author's debugging aid, and a library has
 *     nowhere to print to on a path that runs for every token.
 * - **Guards:** `@default` (or `@`, or an empty key) always applies, and `@eos` when the match
 *   ends the line. Any other guard is a test, `[subject][operator]pattern`:
 *   - The subject is `$#`, the matched text (the default), or a part of the current state's name:
 *     `$S0` the whole name, `$S1` the part before its first dot, `$S2` the next part, and so on.
 *     In state `string.double`, `$S2` is `double`. A part the name does not have is empty.
 *   - The operator is `@name` (in the array `name`), `~` (the regex matches all of the subject),
 *     `==`, or their opposites `!@`, `!~` and `!=`. With none, a word means `==` and anything else
 *     `~`, and a subject with nothing after it (`$S2`) means it is not empty.
 *   - `==` and `~` patterns can use `$#` and `$Sn` too, so `$#==$S2` closes on the text that
 *     opened. In a regex they stand for their literal text.
 * - **Next:** a state name to enter (pushed on the state stack), `@pop`, `@push` (the current
 *   state again) or `@popall` (back to the bottom of the stack). A state name in `next` or
 *   `switchTo` can use `$#` and `$Sn`, as in `@string.$#`. [parse] cannot check a name made that
 *   way, so if the grammar has no such state the stack stays as it is.
 * - **Top level:** `defaultToken` for text no rule matches, `ignoreCase`, `tokenPostfix` added to
 *   every token name, `brackets` for `@brackets` (`{ "open", "close", "token" }` or
 *   `[open, close, token]` each; without it, `{}`, `[]`, `()` and `<>` as `delimiter.curly`,
 *   `delimiter.square`, `delimiter.parenthesis` and `delimiter.angle`), `comments` as in Monaco's
 *   language configuration (`{ "lineComment": "#", "blockComment": ["<!--", "-->"] }`, for toggling
 *   comments), and any other string, which `@name` in a regex stands for, or string array, for the
 *   `@name` guards.
 *
 * **Rejected by [parse]:** lookbehind (`(?<=`, `(?<!`), which Kotlin/wasm's regex engine makes very
 * slow, and `$` in a token name: names are resolved once, here, and [tokenNames] promises every
 * name a grammar can produce. Choose between fixed names with `cases` instead.
 *
 * **Not supported:** `$1`-style capture groups in guards and `next`, and `$Sn` in a rule's regex.
 * Both stay literal text there, so such a guard or rule never matches.
 */
class DeclarativeGrammar internal constructor(
    internal val states: Map<String, List<Rule>>,
    internal val start: String,
    internal val defaultToken: TokenType,
    /** Every token name the grammar can produce, with its postfix: for giving each a colour. */
    val tokenNames: Set<String>,
    internal val commentSyntax: CommentSyntax? = null,
) {
    internal sealed interface Action {
        /**
         * A token, of [type] unless [brackets] knows the text better. [goBack] characters of the
         * match are read again.
         */
        data class Token(val type: TokenType, val next: Next?, val rematch: Boolean, val goBack: Int = 0, val brackets: Brackets? = null) :
            Action {
            /** The type of this token on [matched]. */
            fun typeOf(matched: String): TokenType = brackets?.typeOf(matched) ?: type
        }

        /** One action per capture group, each a [Token] or [Cases]; their `next`s step in order, then [next]. */
        data class Groups(val actions: List<Action>, val next: Next?) : Action
        data class Cases(val cases: List<Pair<Guard, Action>>) : Action
    }

    /** The token `@brackets`: the type of each bracket's text, in lower case under [ignoreCase]. */
    internal data class Brackets(val types: Map<String, TokenType>, val ignoreCase: Boolean) {
        fun typeOf(text: String): TokenType? = types[if (ignoreCase) text.lowercase() else text]
    }

    internal sealed interface Guard {
        data object Default : Guard
        data object EndOfLine : Guard

        /** Whether [condition] holds for [subject], or with [negated], does not. */
        data class Test(val subject: Template.Part, val negated: Boolean, val condition: Condition) : Guard
    }

    internal sealed interface Condition {
        data class InArray(val words: Set<String>, val ignoreCase: Boolean) : Condition
        data class Equal(val text: Template, val ignoreCase: Boolean) : Condition
        data class Matches(val regex: Regex) : Condition

        /** A regex that depends on the match or the state, so it can only be made while tokenizing. */
        class MatchesTemplate(private val template: Template) : Condition {
            private class Compiled(val source: String, val regex: Regex?)

            // One tokenizer can run on two threads, and tokenizers share a grammar, so this is
            // replaced whole, never changed. A lost update only costs compiling the regex again.
            @Volatile
            private var last: Compiled? = null

            /**
             * The regex for this match, or null if the text filled in leaves no valid one. Kept
             * between matches, as a pattern on the state stays the same for the whole state.
             */
            fun regex(matched: String, state: String): Regex? {
                val source = template.fill(matched, state, Regex::escape)
                last?.takeIf { it.source == source }?.let { return it.regex }
                val regex = try {
                    if (template.ignoreCase) Regex(source, RegexOption.IGNORE_CASE) else Regex(source)
                } catch (_: IllegalArgumentException) {
                    null
                }
                last = Compiled(source, regex)
                return regex
            }
        }
    }

    /**
     * Text that can depend on the match: `$#` is the matched text, `$S0` the state's name, `$S1`,
     * `$S2` and so on its dotted parts, and `$$` a `$`. What is filled in is in lower case under
     * [ignoreCase], so that `@x.$#` finds state `x.end` for `END`, as in Monarch.
     */
    internal data class Template(val parts: List<Part>, val ignoreCase: Boolean) {
        sealed interface Part {
            data class Literal(val text: String) : Part
            data object Matched : Part
            data class StatePart(val index: Int) : Part

            /** This part's text for a match of [matched] in [state]. */
            fun textIn(matched: String, state: String): String = when (this) {
                is Literal -> text
                Matched -> matched
                is StatePart -> statePart(state, index)
            }
        }

        /** The text, when it does not depend on the match. */
        val literal: String? =
            if (parts.all { it is Part.Literal }) parts.joinToString("") { (it as Part.Literal).text } else null

        /** The text for a match of [matched] in [state], each filled-in value through [escape]. */
        fun fill(matched: String, state: String, escape: (String) -> String = { it }): String {
            literal?.let { return it }
            val text = StringBuilder()
            for (part in parts) {
                val value = part.textIn(matched, state)
                text.append(if (part is Part.Literal) value else escape(if (ignoreCase) value.lowercase() else value))
            }
            return text.toString()
        }
    }

    internal sealed interface Next {
        /** Pushes [state]. */
        data class Enter(val state: Template) : Next

        /** Replaces the current state with [state]. */
        data class Switch(val state: Template) : Next
        data object Pop : Next
        data object Push : Next
        data object PopAll : Next
    }

    internal class Rule(val regex: Regex, val action: Action)

    companion object {
        /**
         * Reads a grammar from [json]. Throws [IllegalArgumentException] for anything it cannot use,
         * naming where it is: `tokenizer.root[3]: lookbehind is not supported: (?<=a)b`.
         */
        fun parse(json: String): DeclarativeGrammar {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("not JSON: ${e.message}", e)
            }
            return GrammarReader(root as? JsonObject ?: fail("", "a grammar is a JSON object")).read()
        }
    }
}

private fun fail(path: String, message: String): Nothing =
    throw IllegalArgumentException(if (path.isEmpty()) message else "$path: $message")

/** Reads one grammar; keeps the names it saw for [DeclarativeGrammar.tokenNames]. */
private class GrammarReader(private val json: JsonObject) {
    private val ignoreCase = (json["ignoreCase"] as? JsonPrimitive)?.booleanOrNull ?: false
    private val postfix = tokenName(json.string("tokenPostfix") ?: "", "tokenPostfix")
    private val names = LinkedHashSet<String>()
    private val attributes: Map<String, String> = json.filterValues {
        it is JsonPrimitive && it.isString
    }.mapValues { (it.value as JsonPrimitive).content }
    private val arrays: Map<String, List<String>> = json.filterValues { value ->
        value is JsonArray &&
            value.all { it is JsonPrimitive && it.isString }
    }
        .mapValues { (_, value) -> (value as JsonArray).map { (it as JsonPrimitive).content } }
    private val tokenizer = json["tokenizer"] as? JsonObject ?: fail("tokenizer", "a grammar needs a tokenizer object of states")
    private val regexes = HashMap<String, Regex>()
    private val defaultToken = type(tokenName(json.string("defaultToken") ?: "", "defaultToken"))

    /** Each bracket's text to its token name, with the postfix. The first to claim a text keeps it. */
    private val brackets: Map<String, String> = bracketNames()

    fun read(): DeclarativeGrammar {
        if (tokenizer.isEmpty()) fail("tokenizer", "a grammar needs at least one state")
        val states = LinkedHashMap<String, List<DeclarativeGrammar.Rule>>()
        for (name in tokenizer.keys) states[name] = rulesOf(name, HashSet())
        val start = if ("root" in states) "root" else states.keys.first()
        // Every state a rule enters has to exist.
        for ((name, rules) in states) {
            rules.forEachIndexed { index, rule -> checkNext(rule.action, "tokenizer.$name[$index]", states.keys) }
        }
        return DeclarativeGrammar(states, start, defaultToken, names, comments())
    }

    private fun bracketNames(): Map<String, String> {
        val given = json["brackets"]?.takeIf { it !is JsonNull }
        val triples = when (given) {
            null -> DEFAULT_BRACKETS
            is JsonArray -> given.mapIndexed { index, entry -> bracket(entry, "brackets[$index]") }
            else -> fail("brackets", "brackets is an array of { open, close, token } or [open, close, token]")
        }
        val names = LinkedHashMap<String, String>()
        for ((open, close, token) in triples) {
            val name = if (token.isEmpty()) "" else token + postfix
            for (text in listOf(open, close)) names.getOrPut(if (ignoreCase) text.lowercase() else text) { name }
        }
        return names
    }

    private fun bracket(entry: JsonElement, path: String): Triple<String, String, String> {
        val parts = when (entry) {
            is JsonArray -> entry.takeIf { it.size == 3 }?.map { (it as? JsonPrimitive)?.takeIf { part -> part.isString }?.content }
            is JsonObject -> listOf(entry.string("open"), entry.string("close"), entry.string("token"))
            else -> null
        }
        if (parts == null || null in parts) fail(path, "a bracket is { open, close, token } or [open, close, token]")
        val (open, close, token) = parts.requireNoNulls()
        if (open == close) fail(path, "a bracket's open and close have to differ: $open")
        return Triple(open, close, tokenName(token, path))
    }

    private fun comments(): CommentSyntax? {
        val comments = json["comments"] as? JsonObject ?: return null
        val line = comments.string("lineComment")
        val block = (comments["blockComment"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (block != null && block.size != 2) fail("comments.blockComment", "a block comment is [start, end]")
        return CommentSyntax(line = line, blockStart = block?.get(0), blockEnd = block?.get(1)).takeIf { line != null || block != null }
    }

    private fun rulesOf(state: String, including: MutableSet<String>): List<DeclarativeGrammar.Rule> {
        if (!including.add(state)) fail("tokenizer.$state", "includes itself")
        val entries = tokenizer[state] as? JsonArray ?: fail("tokenizer.$state", "a state is an array of rules")
        val rules = ArrayList<DeclarativeGrammar.Rule>()
        entries.forEachIndexed { index, entry ->
            val path = "tokenizer.$state[$index]"
            val include = (entry as? JsonObject)?.string("include")
            if (include != null) {
                val target = include.removePrefix("@")
                if (target !in tokenizer) fail(path, "includes an unknown state: $include")
                rules += rulesOf(target, including)
            } else {
                rules += rule(entry, path)
            }
        }
        including.remove(state)
        return rules
    }

    private fun rule(entry: JsonElement, path: String): DeclarativeGrammar.Rule {
        val (regexJson, actionJson, nextJson) = when (entry) {
            is JsonArray -> {
                if (entry.size !in 2..3) fail(path, "a rule is [regex, action] or [regex, action, next]")
                Triple(entry[0], entry[1], entry.getOrNull(2))
            }

            is JsonObject -> Triple(
                entry["regex"] ?: fail(path, "a rule needs a regex"),
                entry["action"] ?: fail(path, "a rule needs an action"),
                entry["next"],
            )

            else -> fail(path, "a rule is an array or an object")
        }
        val source = (regexJson as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$path[0]", "the regex is a string")
        val regex = regex(source, "$path[0]")
        val next = nextJson?.let { next(it, "$path[2]") }
        return DeclarativeGrammar.Rule(regex, action(actionJson, "$path[1]", next, regex))
    }

    private fun action(json: JsonElement, path: String, ruleNext: DeclarativeGrammar.Next?, regex: Regex): DeclarativeGrammar.Action =
        when (json) {
            is JsonPrimitive -> {
                token(json.contentOrNull ?: fail(path, "an action is a token name, an array, or an object"), ruleNext, 0, path)
            }

            is JsonArray -> DeclarativeGrammar.Action.Groups(
                json.mapIndexed { index, element -> groupAction(element, "$path[$index]", regex) },
                ruleNext,
            )

            is JsonObject -> {
                val cases = json["cases"] as? JsonObject
                if (cases != null) {
                    DeclarativeGrammar.Action.Cases(
                        cases.entries.map { (guard, action) ->
                            guard(guard, "$path.cases") to
                                action(action, "$path.cases.$guard", ruleNext, regex)
                        },
                    )
                } else {
                    // `log` is read past on purpose. In Monarch it prints to the console on every
                    // match, a grammar author's debugging aid; a library has nowhere to print to,
                    // and this path runs for every token. Refusing it would only make a working
                    // Monaco grammar fail to load.
                    val next = switchTo(json, path) ?: json["next"]?.let { next(it, "$path.next") } ?: ruleNext
                    token(json.string("token") ?: "", next, goBack(json, path), path)
                }
            }
        }

    /** The action for the token [name]: `@rematch`, `@brackets` with any suffix, or a fixed name. */
    private fun token(name: String, next: DeclarativeGrammar.Next?, goBack: Int, path: String): DeclarativeGrammar.Action.Token {
        tokenName(name, path)
        return when {
            name == "@rematch" -> DeclarativeGrammar.Action.Token(TokenType.Default, next, rematch = true, goBack)

            name.startsWith(BRACKETS_TOKEN) -> {
                val suffix = name.removePrefix(BRACKETS_TOKEN)
                val types = brackets.mapValues { (_, bracket) -> typeNamed(bracket + suffix) }
                val table = DeclarativeGrammar.Brackets(types, ignoreCase)
                DeclarativeGrammar.Action.Token(defaultToken, next, rematch = false, goBack, table)
            }

            else -> DeclarativeGrammar.Action.Token(type(name), next, rematch = false, goBack)
        }
    }

    /** [name], which cannot hold a `$`: a name is resolved once, here, and [names] has to know it. */
    private fun tokenName(name: String, path: String): String {
        if ('$' in name) fail(path, "a token name cannot use \$, as names are fixed when a grammar is read: $name")
        return name
    }

    private fun switchTo(json: JsonObject, path: String): DeclarativeGrammar.Next? = json["switchTo"]?.let { value ->
        val state = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$path.switchTo", "switchTo is a state name")
        DeclarativeGrammar.Next.Switch(template(state.removePrefix("@")))
    }

    private fun goBack(json: JsonObject, path: String): Int = json["goBack"]?.let { value ->
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
            ?: fail("$path.goBack", "goBack is a number of characters, 0 or more")
    } ?: 0

    /**
     * A capture group's action: a token name, `{ "token", "next" }`, or `{ "cases" }` over the
     * group's own text. Not another array, not `@rematch` and no `goBack`, which would move the
     * groups after it (no language in Monaco uses one there).
     */
    private fun groupAction(json: JsonElement, path: String, regex: Regex): DeclarativeGrammar.Action =
        action(json, path, ruleNext = null, regex).also { checkGroupAction(it, path) }

    private fun checkGroupAction(action: DeclarativeGrammar.Action, path: String) {
        when (action) {
            is DeclarativeGrammar.Action.Token -> {
                if (action.rematch) fail(path, "a group's action cannot be @rematch")
                if (action.goBack != 0) fail(path, "a group's action cannot goBack")
            }

            is DeclarativeGrammar.Action.Groups -> fail(path, "a group's action is a token name, { token, next } or { cases }")

            is DeclarativeGrammar.Action.Cases -> action.cases.forEach { checkGroupAction(it.second, path) }
        }
    }

    private fun guard(key: String, path: String): DeclarativeGrammar.Guard {
        if (key == "@default" || key == "@" || key.isEmpty()) return DeclarativeGrammar.Guard.Default
        if (key == "@eos") return DeclarativeGrammar.Guard.EndOfLine
        val subject = SUBJECT.matchAt(key, 0)?.value
        val test = key.substring(subject?.length ?: 0)
        val operator = OPERATORS.firstOrNull { test.startsWith(it) }
        // Monarch's reading: nothing is "not empty", a plain word is "equals", else a regex.
        val (kind, pattern) = when {
            test.isEmpty() -> "!=" to ""
            WORD.matches(test) -> "==" to test
            operator != null -> operator to test.removePrefix(operator)
            else -> "~" to test
        }
        return DeclarativeGrammar.Guard.Test(
            subject = subject?.let(::part) ?: DeclarativeGrammar.Template.Part.Matched,
            negated = kind.startsWith("!"),
            condition = when (kind.removePrefix("!")) {
                "@" -> inArray(pattern, path)
                "~" -> matches(pattern, "$path.$key")
                else -> DeclarativeGrammar.Condition.Equal(template(pattern), ignoreCase)
            },
        )
    }

    private fun inArray(name: String, path: String): DeclarativeGrammar.Condition {
        val words = arrays[name] ?: fail(path, "no array named $name")
        return DeclarativeGrammar.Condition.InArray(if (ignoreCase) words.map { it.lowercase() }.toSet() else words.toSet(), ignoreCase)
    }

    /** A regex all of the subject has to match. One that depends on the match is checked with a stand-in for it. */
    private fun matches(pattern: String, path: String): DeclarativeGrammar.Condition {
        val source = "^(?:${expand(pattern, path, 0)})$"
        val filled = template(source)
        filled.literal?.let { return DeclarativeGrammar.Condition.Matches(regex(it, path)) }
        regex(filled.parts.joinToString("") { (it as? DeclarativeGrammar.Template.Part.Literal)?.text ?: Regex.escape("x") }, path)
        return DeclarativeGrammar.Condition.MatchesTemplate(filled)
    }

    private fun template(text: String): DeclarativeGrammar.Template {
        val parts = ArrayList<DeclarativeGrammar.Template.Part>()
        var at = 0
        for (found in SUBSTITUTION.findAll(text)) {
            if (found.range.first > at) parts += DeclarativeGrammar.Template.Part.Literal(text.substring(at, found.range.first))
            parts += part(found.value)
            at = found.range.last + 1
        }
        if (at < text.length) parts += DeclarativeGrammar.Template.Part.Literal(text.substring(at))
        return DeclarativeGrammar.Template(parts, ignoreCase)
    }

    /** The part `$$`, `$#` or `$Sn` stands for. */
    private fun part(substitution: String): DeclarativeGrammar.Template.Part = when (substitution) {
        "\$\$" -> DeclarativeGrammar.Template.Part.Literal("\$")
        "\$#" -> DeclarativeGrammar.Template.Part.Matched
        else -> DeclarativeGrammar.Template.Part.StatePart(substitution.drop(2).toInt())
    }

    private fun next(json: JsonElement, path: String): DeclarativeGrammar.Next {
        val value = (json as? JsonPrimitive)?.contentOrNull ?: fail(path, "next is a state name, @pop, @push or @popall")
        return when (value) {
            "@pop" -> DeclarativeGrammar.Next.Pop
            "@push" -> DeclarativeGrammar.Next.Push
            "@popall" -> DeclarativeGrammar.Next.PopAll
            else -> DeclarativeGrammar.Next.Enter(template(value.removePrefix("@")))
        }
    }

    private fun checkNext(action: DeclarativeGrammar.Action, path: String, states: Set<String>) {
        when (action) {
            is DeclarativeGrammar.Action.Token -> checkTarget(action.next, path, states)

            is DeclarativeGrammar.Action.Groups -> {
                action.actions.forEach { checkNext(it, path, states) }
                checkTarget(action.next, path, states)
            }

            is DeclarativeGrammar.Action.Cases -> action.cases.forEach { checkNext(it.second, path, states) }
        }
    }

    /** A state [next] names has to exist. One that depends on the match can only be known while tokenizing. */
    private fun checkTarget(next: DeclarativeGrammar.Next?, path: String, states: Set<String>) {
        val target = when (next) {
            is DeclarativeGrammar.Next.Enter -> next.state
            is DeclarativeGrammar.Next.Switch -> next.state
            else -> null
        }
        val state = target?.literal ?: return
        stateOf(state, states) ?: fail(path, "enters an unknown state: $state")
    }

    /** A regex, with `@name` standing for the top-level string `name` (as in Monarch). */
    private fun regex(source: String, path: String): Regex {
        val expanded = expand(source, path, 0)
        if ("(?<=" in expanded || "(?<!" in expanded) fail(path, "lookbehind is not supported: $source")
        return regexes.getOrPut(expanded) {
            try {
                if (ignoreCase) Regex(expanded, RegexOption.IGNORE_CASE) else Regex(expanded)
            } catch (e: Throwable) {
                fail(path, "not a valid regex: $source (${e.message})")
            }
        }
    }

    private fun expand(source: String, path: String, depth: Int): String {
        if (depth > 8) fail(path, "the @names in this regex refer to each other in a loop")
        return ATTRIBUTE.replace(source) { match ->
            val value = attributes[match.groupValues[1]] ?: return@replace Regex.escapeReplacement(match.value)
            Regex.escapeReplacement(expand(value, path, depth + 1))
        }
    }

    private fun type(token: String): TokenType = typeNamed(if (token.isEmpty()) "" else token + postfix)

    /** The type of the full token [name], with its postfix. */
    private fun typeNamed(name: String): TokenType {
        if (name.isNotEmpty()) names += name
        return grammarTokenType(name)
    }

    private companion object {
        val ATTRIBUTE = Regex("@(\\w+)")
        val SUBJECT = Regex("\\$(#|[sS]\\d\\d?)")
        val SUBSTITUTION = Regex("\\$(\\$|#|[sS]\\d\\d?)")
        val WORD = Regex("\\w+")
        val OPERATORS = listOf("!@", "!~", "!=", "==", "@", "~")
        const val BRACKETS_TOKEN = "@brackets"

        /** Monarch's brackets when a grammar has no `brackets` of its own. */
        val DEFAULT_BRACKETS = listOf(
            Triple("{", "}", "delimiter.curly"),
            Triple("[", "]", "delimiter.square"),
            Triple("(", ")", "delimiter.parenthesis"),
            Triple("<", ">", "delimiter.angle"),
        )
    }
}

/** The state [name] names, falling back along its dots (`a.b.c`, then `a.b`, then `a`), or null. */
internal fun stateOf(name: String, states: Set<String>): String? {
    var candidate = name
    while (true) {
        if (candidate in states) return candidate
        val dot = candidate.lastIndexOf('.')
        if (dot < 0) return null
        candidate = candidate.substring(0, dot)
    }
}

/** `$S`[index] of [state]: the whole name for 0, else its dotted part [index], or empty past the last. */
internal fun statePart(state: String, index: Int): String {
    if (index == 0) return state
    var start = 0
    repeat(index - 1) {
        val dot = state.indexOf('.', start)
        if (dot < 0) return ""
        start = dot + 1
    }
    val end = state.indexOf('.', start)
    return state.substring(start, if (end < 0) state.length else end)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
