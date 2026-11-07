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
package com.aardarch.aardink.languages.lsp

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.platform.EditorDispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The integers a token takes up in `SemanticTokens.data`. */
private const val TOKEN_INTEGERS = 5

/** Integers between looks at the clock: a clock read per token would cost more than it saves. */
private const val INTEGERS_PER_CHECK = 1280

/**
 * What the numbers in a server's semantic tokens stand for: its `SemanticTokensLegend`.
 *
 * A token's type is a dotted name for the theme's lookup, whose first part is one a theme colours,
 * since a theme knows `type` and not `class`: `type.class`, `variable.parameter`, `function.method`.
 * Then come the token's modifiers in the legend's order, as Monaco's semantic tokens have them:
 * `function.method.static`. A type this client did not offer keeps the server's name, and is
 * coloured only by a theme that knows it.
 */
internal class LspSemanticLegend(private val tokenTypes: List<String>, private val tokenModifiers: List<String>) {
    private val named = HashMap<Long, NamedTokenType>()

    /** The type of a token with the legend's [type] and the [modifiers] bits, or null when [type] is outside it. */
    fun typeOf(type: Int, modifiers: Int): NamedTokenType? {
        val name = tokenTypes.getOrNull(type) ?: return null
        return named.getOrPut(type.toLong() shl Int.SIZE_BITS or (modifiers.toLong() and 0xFFFFFFFFL)) {
            NamedTokenType(
                buildString {
                    append(THEME_NAMES[name] ?: name)
                    for (bit in 0 until minOf(tokenModifiers.size, Int.SIZE_BITS)) {
                        if (modifiers ushr bit and 1 == 1) append('.').append(tokenModifiers[bit])
                    }
                },
            )
        }
    }

    companion object {
        /** The legend in [provider], a `SemanticTokensOptions`, or null if it has none or answers no `full` request. */
        fun from(provider: JsonObject): LspSemanticLegend? {
            val full = provider["full"]
            if (full !is JsonObject && (full as? JsonPrimitive)?.booleanOrNull != true) return null
            val legend = provider["legend"] as? JsonObject ?: return null
            val types = legend.strings("tokenTypes").takeIf { it.isNotEmpty() } ?: return null
            return LspSemanticLegend(types, legend.strings("tokenModifiers"))
        }

        private fun JsonObject.strings(name: String): List<String> =
            (this[name] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }

        /** The protocol's token types, as a theme finds their colours. */
        private val THEME_NAMES = mapOf(
            "namespace" to "type.namespace",
            "class" to "type.class",
            "enum" to "type.enum",
            "interface" to "type.interface",
            "struct" to "type.struct",
            "typeParameter" to "type.typeParameter",
            "parameter" to "variable.parameter",
            "property" to "variable.property",
            "enumMember" to "variable.enumMember",
            "event" to "variable.event",
            "method" to "function.method",
            "macro" to "function.macro",
            "modifier" to "keyword.modifier",
            "decorator" to "annotation.decorator",
        )
    }
}

/**
 * Pauses a decode for the browser: on wasmJs the compute dispatcher is the event loop that also
 * paints, and an answer can be as long as the document. With `delay`, as a coroutine that only
 * yields there resumes before any frame is drawn.
 */
internal class LspDecodePacer(private val slice: Duration = 8.milliseconds) {
    private var sliceStart = TimeSource.Monotonic.markNow()

    suspend fun check() {
        if (sliceStart.elapsedNow() < slice) return
        delay(1)
        sliceStart = TimeSource.Monotonic.markNow()
    }
}

/**
 * The tokens in [data], a `SemanticTokens.data`: five integers each, for the line and the start
 * relative to the token before, the length, the type and the modifiers. Columns are UTF-16, as
 * offsets in [document] are.
 *
 * What the document does not have is left out, not guessed at: a token on a line past its end or
 * starting past the end of its line, or of a type outside the legend. The ones after it still count
 * from it. One that runs past the end of its line goes on into the next, as
 * `multilineTokenSupport` lets it, and it stops at the end of the document. An answer that is not
 * unsigned integers is no answer, since nothing after a bad number can be placed.
 */
internal suspend fun decodeSemanticTokens(
    data: JsonArray,
    legend: LspSemanticLegend,
    document: CodeDocument,
    pacer: LspDecodePacer? = if (EditorDispatchers.computeIsMainThread) LspDecodePacer() else null,
): List<Token> {
    val tokens = ArrayList<Token>(data.size / TOKEN_INTEGERS)
    var line = 0L
    var column = 0L
    for (index in 0..data.size - TOKEN_INTEGERS step TOKEN_INTEGERS) {
        if (index % INTEGERS_PER_CHECK == 0) pacer?.check()
        val deltaLine = data.unsignedAt(index) ?: return emptyList()
        val deltaStart = data.unsignedAt(index + 1) ?: return emptyList()
        val length = data.unsignedAt(index + 2) ?: return emptyList()
        val type = data.unsignedAt(index + 3) ?: return emptyList()
        val modifiers = data.unsignedAt(index + 4) ?: return emptyList()
        line += deltaLine
        column = if (deltaLine == 0) column + deltaStart else deltaStart.toLong()
        // Lines only go forward, so nothing after this can be in the document either.
        if (line >= document.lineCount) break
        val start = document.lineStart(line.toInt()) + column
        if (start >= document.lineEnd(line.toInt())) continue
        val end = minOf(start + length, document.length.toLong())
        val tokenType = legend.typeOf(type, modifiers) ?: continue
        if (end > start) tokens += Token(start.toInt(), end.toInt(), tokenType)
    }
    return tokens
}

private fun JsonArray.unsignedAt(index: Int): Int? = (this[index] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
