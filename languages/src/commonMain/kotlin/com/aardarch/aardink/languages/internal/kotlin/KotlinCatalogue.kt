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
package com.aardarch.aardink.languages.internal.kotlin

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation

/**
 * What the Kotlin service knows of the standard library without reading any code: some functions
 * with their signatures, the types it completes and the annotations it offers after `@`. Hover and
 * signature help come from here, by name alone: a function of the same name declared in the
 * document is not told apart, and a name in a comment or a string is hovered as any other.
 */
internal object KotlinCatalogue {

    /** One overload: its declaration as Kotlin writes it, and what each parameter is for. */
    class Overload(val declaration: String, val parameters: List<Pair<String, String>> = emptyList())

    class Function(val name: String, val documentation: String, val overloads: List<Overload>)

    val functions: Map<String, Function> = listOf(
        Function(
            "map",
            "A list of what [transform] gives for each element, in order.",
            listOf(
                Overload(
                    "fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R>",
                    listOf("transform: (T) -> R" to "Applied to each element."),
                ),
            ),
        ),
        Function(
            "filter",
            "A list of the elements for which [predicate] is true.",
            listOf(
                Overload(
                    "fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T>",
                    listOf("predicate: (T) -> Boolean" to "Whether to keep an element."),
                ),
            ),
        ),
        Function(
            "forEach",
            "Runs [action] on each element.",
            listOf(Overload("fun <T> Iterable<T>.forEach(action: (T) -> Unit)", listOf("action: (T) -> Unit" to "Run on each element."))),
        ),
        Function(
            "let",
            "Calls [block] with this value as its argument (`it`), and returns its result.",
            listOf(Overload("fun <T, R> T.let(block: (T) -> R): R", listOf("block: (T) -> R" to "Gets the value as `it`."))),
        ),
        Function(
            "apply",
            "Calls [block] with this value as its receiver (`this`), and returns the value.",
            listOf(Overload("fun <T> T.apply(block: T.() -> Unit): T", listOf("block: T.() -> Unit" to "Gets the value as `this`."))),
        ),
        Function(
            "also",
            "Calls [block] with this value as its argument (`it`), and returns the value.",
            listOf(Overload("fun <T> T.also(block: (T) -> Unit): T", listOf("block: (T) -> Unit" to "Gets the value as `it`."))),
        ),
        Function(
            "run",
            "Calls [block] with this value as its receiver (`this`), and returns its result.",
            listOf(Overload("fun <T, R> T.run(block: T.() -> R): R", listOf("block: T.() -> R" to "Gets the value as `this`."))),
        ),
        Function(
            "firstOrNull",
            "The first element, or the first that matches [predicate]; null if there is none.",
            listOf(
                Overload("fun <T> Iterable<T>.firstOrNull(): T?"),
                Overload(
                    "fun <T> Iterable<T>.firstOrNull(predicate: (T) -> Boolean): T?",
                    listOf("predicate: (T) -> Boolean" to "Whether an element is the one."),
                ),
            ),
        ),
        Function(
            "count",
            "How many elements there are, or how many match [predicate].",
            listOf(
                Overload("fun <T> Iterable<T>.count(): Int"),
                Overload(
                    "fun <T> Iterable<T>.count(predicate: (T) -> Boolean): Int",
                    listOf("predicate: (T) -> Boolean" to "Whether to count an element."),
                ),
            ),
        ),
        Function("isEmpty", "True when there are no elements.", listOf(Overload("fun isEmpty(): Boolean"))),
        Function("isNotEmpty", "True when there is at least one element.", listOf(Overload("fun <T> Collection<T>.isNotEmpty(): Boolean"))),
        Function(
            "println",
            "Prints [message] and a line break to the standard output.",
            listOf(
                Overload("fun println()"),
                Overload("fun println(message: Any?)", listOf("message: Any?" to "Printed as its `toString()`.")),
            ),
        ),
        Function(
            "listOf",
            "A read-only list of [elements].",
            listOf(
                Overload("fun <T> listOf(vararg elements: T): List<T>", listOf("vararg elements: T" to "The list's elements, in order.")),
            ),
        ),
        Function(
            "mutableListOf",
            "A list of [elements] that can be changed.",
            listOf(
                Overload(
                    "fun <T> mutableListOf(vararg elements: T): MutableList<T>",
                    listOf("vararg elements: T" to "The list's first elements, in order."),
                ),
            ),
        ),
        Function(
            "setOf",
            "A read-only set of [elements], in the order they come first.",
            listOf(Overload("fun <T> setOf(vararg elements: T): Set<T>", listOf("vararg elements: T" to "The set's elements."))),
        ),
        Function(
            "mapOf",
            "A read-only map of [pairs], each a key `to` its value.",
            listOf(
                Overload(
                    "fun <K, V> mapOf(vararg pairs: Pair<K, V>): Map<K, V>",
                    listOf("vararg pairs: Pair<K, V>" to "Keys and values, as `key to value`."),
                ),
            ),
        ),
        Function(
            "require",
            "Throws `IllegalArgumentException` when [value] is false: for checking arguments.",
            listOf(
                Overload("fun require(value: Boolean)", listOf("value: Boolean" to "What must hold.")),
                Overload(
                    "fun require(value: Boolean, lazyMessage: () -> Any)",
                    listOf(
                        "value: Boolean" to "What must hold.",
                        "lazyMessage: () -> Any" to "The exception's message, made only when it is thrown.",
                    ),
                ),
            ),
        ),
        Function(
            "check",
            "Throws `IllegalStateException` when [value] is false: for checking state.",
            listOf(
                Overload("fun check(value: Boolean)", listOf("value: Boolean" to "What must hold.")),
                Overload(
                    "fun check(value: Boolean, lazyMessage: () -> Any)",
                    listOf(
                        "value: Boolean" to "What must hold.",
                        "lazyMessage: () -> Any" to "The exception's message, made only when it is thrown.",
                    ),
                ),
            ),
        ),
        Function(
            "error",
            "Throws `IllegalStateException` with [message].",
            listOf(Overload("fun error(message: Any): Nothing", listOf("message: Any" to "The exception's message."))),
        ),
        Function(
            "repeat",
            "Runs [action] [times] times, with the index from zero.",
            listOf(
                Overload(
                    "fun repeat(times: Int, action: (Int) -> Unit)",
                    listOf(
                        "times: Int" to "How many times.",
                        "action: (Int) -> Unit" to "Gets the index as `it`.",
                    ),
                ),
            ),
        ),
        Function(
            "maxOf",
            "The greater of [a] and [b].",
            listOf(Overload("fun <T : Comparable<T>> maxOf(a: T, b: T): T", listOf("a: T" to "", "b: T" to ""))),
        ),
        Function(
            "minOf",
            "The lesser of [a] and [b].",
            listOf(Overload("fun <T : Comparable<T>> minOf(a: T, b: T): T", listOf("a: T" to "", "b: T" to ""))),
        ),
        Function(
            "buildString",
            "A string built by [builderAction] on a new `StringBuilder`.",
            listOf(
                Overload(
                    "fun buildString(builderAction: StringBuilder.() -> Unit): String",
                    listOf("builderAction: StringBuilder.() -> Unit" to "Appends to the builder (`this`)."),
                ),
            ),
        ),
        Function(
            "lazy",
            "A value made by [initializer] the first time it is read, and kept.",
            listOf(Overload("fun <T> lazy(initializer: () -> T): Lazy<T>", listOf("initializer: () -> T" to "Makes the value, once."))),
        ),
    ).associateBy { it.name }

    val types: Map<String, String> = mapOf(
        "String" to "A sequence of characters, as UTF-16. Strings cannot be changed; operations on one return a new one.",
        "Int" to "A 32-bit signed integer, from -2 147 483 648 to 2 147 483 647.",
        "Boolean" to "`true` or `false`.",
        "Long" to "A 64-bit signed integer. A literal of one ends in `L`.",
        "Float" to "A 32-bit floating-point number. A literal of one ends in `f`.",
        "Double" to "A 64-bit floating-point number: what a literal with a decimal point is.",
        "List" to "A read-only, ordered collection, which may hold an element more than once.",
        "Map" to "A read-only collection of keys, each with one value.",
        "Set" to "A read-only collection that holds each element once.",
        "Unit" to "The type of a function that returns nothing of use; it has one value, `Unit`.",
        "Any" to "The root of Kotlin's class hierarchy: every class has `Any` as a superclass.",
    )

    val annotations: Map<String, String> = mapOf(
        "Composable" to "A Compose function: it can call other composables, and describes UI rather than returning it.",
        "OptIn" to "Allows the use of an API that asks to be opted into, such as an experimental one.",
        "Stable" to "Compose may trust that equal values stay equal, and that it is told when a public property changes.",
        "Immutable" to "Compose may trust that no public property changes once the value is made.",
        "Test" to "A test method, run by JUnit or `kotlin.test`.",
        "Preview" to "Shows this composable in Android Studio's preview.",
        "Keep" to "Keeps this from being removed or renamed when the code is shrunk.",
        "Deprecated" to "Marks this as no longer to be used: a use gets a warning or an error, with the message given.",
    )

    fun hover(document: CodeDocument, offset: Int): HoverDoc? {
        val text = document.text
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && text[start - 1].isIdentifierPart()) start--
        while (end < text.length && text[end].isIdentifierPart()) end++
        if (start == end) return null
        val name = text.substring(start, end)
        val range = start until end
        if (start > 0 && text[start - 1] == '@') {
            return annotations[name]?.let { HoverDoc(title = "@$name", content = it, range = (start - 1) until end) }
        }
        // A function only where it is called (`name(`, `name {`, `name<T>(`), so `val map` is not `map`.
        functions[name]?.takeIf { isCalled(text, end) }?.let { function ->
            val code = function.overloads.joinToString("\n") { it.declaration }
            return HoverDoc(title = name, content = "```kotlin\n$code\n```\n\n${function.documentation}", range = range)
        }
        return types[name]?.let { HoverDoc(title = name, content = it, range = range) }
    }

    /**
     * Help for the call whose argument list [offset] is in, when the function's name is in the
     * catalogue: its overloads, the first with enough parameters for the arguments so far active.
     */
    fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? {
        val call = callAt(document.text, offset.coerceIn(0, document.length)) ?: return null
        val function = functions[call.name] ?: return null
        val signatures = function.overloads.map { overload ->
            SignatureInformation(
                label = overload.declaration,
                documentation = function.documentation,
                parameters = overload.parameters.map { (label, doc) ->
                    val at = overload.declaration.indexOf(label, overload.declaration.indexOf('('))
                    ParameterInformation(label, doc.ifEmpty { null }, if (at < 0) null else at until at + label.length)
                },
            )
        }
        val fits = function.overloads.indexOfFirst { overload ->
            overload.parameters.size > call.commas || overload.parameters.lastOrNull()?.first?.startsWith("vararg ") == true
        }
        val active = if (fits < 0) function.overloads.lastIndex else fits
        val parameters = function.overloads[active].parameters
        val vararg = parameters.lastOrNull()?.first?.startsWith("vararg ") == true
        val parameter = if (vararg) call.commas.coerceAtMost(parameters.lastIndex) else call.commas
        return SignatureHelp(signatures, activeSignature = active, activeParameter = parameter)
    }

    private fun isCalled(text: String, nameEnd: Int): Boolean {
        var i = nameEnd
        while (i < text.length && text[i] == ' ') i++
        return i < text.length && text[i] in "({<"
    }

    private class Call(val name: String, val commas: Int)

    /** The innermost parenthesised call [offset] is directly in (not inside a lambda or brackets within it). */
    private fun callAt(text: String, offset: Int): Call? {
        // Opened brackets, each with the commas seen at its own level.
        val opens = ArrayList<Int>()
        val commas = ArrayList<Int>()
        var i = (offset - SCAN_LIMIT).coerceAtLeast(0)
        while (i < offset) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) offset else it }

                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) offset else it + 2 }

                text.startsWith("\"\"\"", i) -> i = text.indexOf("\"\"\"", i + 3).let { if (it < 0) offset else it + 3 }

                c == '"' || c == '\'' -> i = quotedEnd(text, i, offset)

                c == '(' || c == '{' || c == '[' -> {
                    opens += i
                    commas += 0
                    i++
                }

                c == ')' || c == '}' || c == ']' -> {
                    if (opens.isNotEmpty()) {
                        opens.removeAt(opens.size - 1)
                        commas.removeAt(commas.size - 1)
                    }
                    i++
                }

                c == ',' -> {
                    if (commas.isNotEmpty()) commas[commas.size - 1]++
                    i++
                }

                else -> i++
            }
        }
        val open = opens.lastOrNull() ?: return null
        if (text[open] != '(') return null
        var nameEnd = open
        while (nameEnd > 0 && text[nameEnd - 1] == ' ') nameEnd--
        // Type arguments: `listOf<String>(`.
        if (nameEnd > 0 && text[nameEnd - 1] == '>') {
            var depth = 0
            var j = nameEnd - 1
            while (j >= 0) {
                if (text[j] == '>') {
                    depth++
                } else if (text[j] == '<' && --depth == 0) {
                    break
                }
                j--
            }
            if (j < 0) return null
            nameEnd = j
        }
        var nameStart = nameEnd
        while (nameStart > 0 && text[nameStart - 1].isIdentifierPart()) nameStart--
        if (nameStart == nameEnd) return null
        return Call(text.substring(nameStart, nameEnd), commas.last())
    }

    /** Just past the string or character opened at [start], or the end of its line, before [limit]. */
    private fun quotedEnd(text: String, start: Int, limit: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < limit) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return limit
    }

    private fun Char.isIdentifierPart() = isLetterOrDigit() || this == '_'

    private const val SCAN_LIMIT = 4_000
}
