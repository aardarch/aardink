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

import com.aardarch.aardink.core.TokenType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A token type named by a grammar ([DeclarativeGrammar]): `keyword`, `tag`, `tag.aardflex`,
 * `attribute.name`. The name is dotted, most general first, as in Monaco, so a theme can colour
 * `tag.aardflex` on its own and anything without its own colour falls back to its prefix: `tag`.
 * On the web, `registerTheme` and the built-in themes resolve colours that way; a Kotlin host
 * gives its [com.aardarch.aardink.core.EditorTheme.tokenColors] entries keyed by `NamedTokenType`.
 *
 * Names under `comment` and `string` are not named types: a grammar's comments and strings get
 * [TokenType.Comment] and [TokenType.StringLiteral], so the editor treats them as it treats any
 * language's (no bracket colours or matching inside them).
 */
data class NamedTokenType(val name: String) : TokenType {
    override fun toString(): String = "NamedTokenType($name)"

    internal companion object {
        /** The type a grammar's token [name] gets. */
        fun of(name: String): TokenType {
            if (name.isEmpty()) return TokenType.Default
            return when (name.substringBefore('.')) {
                "comment" -> TokenType.Comment
                "string" -> TokenType.StringLiteral
                else -> NamedTokenType(name)
            }
        }
    }
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
 * - **Actions:** a token name; an array of token names, one per capture group; `{ "token",
 *   "next" }`; or `{ "cases": { guard: action } }`, where a guard is `@name` (the match is in the
 *   array `name`), `@default`, `@eos` (the match ends the line), or a regex the whole match must
 *   match. The token `@rematch` consumes nothing and matches again in the next state.
 * - **Next:** a state name to enter (pushed on the state stack), `@pop`, `@push` (the current
 *   state again) or `@popall`.
 * - **Top level:** `defaultToken` for text no rule matches, `ignoreCase`, `tokenPostfix` added to
 *   every token name, and any other string, which `@name` in a regex stands for, or string
 *   array, for the `@name` guards.
 *
 * **Not supported, and rejected by [parse]:** lookbehind (`(?<=`, `(?<!`), which Kotlin/wasm's
 * regex engine makes very slow.
 */
class DeclarativeGrammar internal constructor(
    internal val states: Map<String, List<Rule>>,
    internal val start: String,
    internal val defaultToken: TokenType,
    /** Every token name the grammar can produce, with its postfix: for giving each a colour. */
    val tokenNames: Set<String>,
) {
    internal sealed interface Action {
        data class Token(val type: TokenType, val next: Next?, val rematch: Boolean) : Action
        data class Groups(val types: List<TokenType>, val next: Next?) : Action
        data class Cases(val cases: List<Pair<Guard, Action>>) : Action
    }

    internal sealed interface Guard {
        data class InArray(val words: Set<String>, val ignoreCase: Boolean) : Guard
        data object Default : Guard
        data object EndOfLine : Guard
        data class Matches(val regex: Regex) : Guard
    }

    internal sealed interface Next {
        data class Enter(val state: String) : Next
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
    private val postfix = json.string("tokenPostfix") ?: ""
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

    fun read(): DeclarativeGrammar {
        if (tokenizer.isEmpty()) fail("tokenizer", "a grammar needs at least one state")
        val states = LinkedHashMap<String, List<DeclarativeGrammar.Rule>>()
        for (name in tokenizer.keys) states[name] = rulesOf(name, HashSet())
        val start = if ("root" in states) "root" else states.keys.first()
        val defaultToken = type(json.string("defaultToken") ?: "")
        // Every state a rule enters has to exist.
        for ((name, rules) in states) {
            rules.forEachIndexed { index, rule -> checkNext(rule.action, "tokenizer.$name[$index]", states.keys) }
        }
        return DeclarativeGrammar(states, start, defaultToken, names)
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
                val token = json.contentOrNull ?: fail(path, "an action is a token name, an array, or an object")
                if (token ==
                    "@rematch"
                ) {
                    DeclarativeGrammar.Action.Token(TokenType.Default, ruleNext, rematch = true)
                } else {
                    DeclarativeGrammar.Action.Token(type(token), ruleNext, rematch = false)
                }
            }

            is JsonArray -> {
                val groups = json.mapIndexed { index, element ->
                    (element as? JsonPrimitive)?.contentOrNull
                        ?: fail("$path[$index]", "a group's action is a token name")
                }
                DeclarativeGrammar.Action.Groups(groups.map(::type), ruleNext)
            }

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
                    val next = json["next"]?.let { next(it, "$path.next") } ?: ruleNext
                    val token = json.string("token") ?: ""
                    if (token ==
                        "@rematch"
                    ) {
                        DeclarativeGrammar.Action.Token(TokenType.Default, next, rematch = true)
                    } else {
                        DeclarativeGrammar.Action.Token(type(token), next, rematch = false)
                    }
                }
            }
        }

    private fun guard(key: String, path: String): DeclarativeGrammar.Guard = when {
        key == "@default" -> DeclarativeGrammar.Guard.Default

        key == "@eos" -> DeclarativeGrammar.Guard.EndOfLine

        key.startsWith("@") -> {
            val words = arrays[key.removePrefix("@")] ?: fail(path, "no array named ${key.removePrefix("@")}")
            DeclarativeGrammar.Guard.InArray(if (ignoreCase) words.map { it.lowercase() }.toSet() else words.toSet(), ignoreCase)
        }

        else -> DeclarativeGrammar.Guard.Matches(regex("^(?:$key)$", "$path.$key"))
    }

    private fun next(json: JsonElement, path: String): DeclarativeGrammar.Next {
        val value = (json as? JsonPrimitive)?.contentOrNull ?: fail(path, "next is a state name, @pop, @push or @popall")
        return when (value) {
            "@pop" -> DeclarativeGrammar.Next.Pop
            "@push" -> DeclarativeGrammar.Next.Push
            "@popall" -> DeclarativeGrammar.Next.PopAll
            else -> DeclarativeGrammar.Next.Enter(value.removePrefix("@"))
        }
    }

    private fun checkNext(action: DeclarativeGrammar.Action, path: String, states: Set<String>) {
        when (action) {
            is DeclarativeGrammar.Action.Token -> (action.next as? DeclarativeGrammar.Next.Enter)?.let {
                stateOf(it.state, states)
                    ?: fail(path, "enters an unknown state: ${it.state}")
            }

            is DeclarativeGrammar.Action.Groups -> (action.next as? DeclarativeGrammar.Next.Enter)?.let {
                stateOf(it.state, states)
                    ?: fail(path, "enters an unknown state: ${it.state}")
            }

            is DeclarativeGrammar.Action.Cases -> action.cases.forEach { checkNext(it.second, path, states) }
        }
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

    private fun type(token: String): TokenType {
        val name = if (token.isEmpty()) "" else token + postfix
        if (name.isNotEmpty()) names += name
        return NamedTokenType.of(name)
    }

    private companion object {
        val ATTRIBUTE = Regex("@(\\w+)")
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

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
