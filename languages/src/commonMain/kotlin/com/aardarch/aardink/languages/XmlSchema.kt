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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The vocabulary of an XML dialect, as data: which elements there are, which attributes each
 * takes and what values, which elements may hold which, and what each is for.
 * [XmlSchemaLanguageService] turns it into completions, hover documentation and diagnostics, so a
 * product describes its markup once (generated from its own model, say) and every editor that
 * shows it, Kotlin or web, offers the same help.
 *
 * @property elements Every element, by name.
 * @property roots The elements a document may start with; empty for any.
 * @property strict An element the schema does not have is reported. Off for a schema that only
 *   suggests, such as the built-in XML language's.
 * @property dynamicValue Attribute values matching it are never checked against an attribute's
 *   [Attribute.values]: placeholders, expressions (`{clock:hour}`) and the like.
 */
data class XmlSchema(
    val elements: Map<String, Element>,
    val roots: List<String> = emptyList(),
    val strict: Boolean = true,
    val dynamicValue: Regex? = null,
) {
    /**
     * An element.
     *
     * @property documentation Shown on hover and with its completion.
     * @property attributes Its attributes, by name.
     * @property children The elements it may hold; null for any, empty for none (a completion then
     *   inserts it self-closing).
     * @property snippet What completing it inserts after `<`, in VS Code's snippet syntax, in place
     *   of the default: its name, its required attributes with tab stops, and its closing tag.
     * @property strictAttributes An attribute it does not have is reported (with [XmlSchema.strict]).
     */
    data class Element(
        val name: String,
        val documentation: String? = null,
        val attributes: Map<String, Attribute> = emptyMap(),
        val children: List<String>? = null,
        val snippet: String? = null,
        val strictAttributes: Boolean = true,
    )

    /**
     * An attribute.
     *
     * @property values The values it takes; empty for any. A value not among them is reported,
     *   unless it matches [XmlSchema.dynamicValue].
     * @property required Leaving it out is reported, and completing the element inserts it.
     * @property type What kind of value it holds, for features that care (`"color"`, `"number"`).
     */
    data class Attribute(
        val name: String,
        val documentation: String? = null,
        val values: List<Value> = emptyList(),
        val required: Boolean = false,
        val type: String? = null,
    )

    /** One of an [Attribute]'s values, with what it means. */
    data class Value(val value: String, val documentation: String? = null)

    companion object {
        /**
         * Reads a schema from JSON:
         *
         * ```json
         * {
         *   "roots": ["page"],
         *   "strict": true,
         *   "dynamicValue": "^\\{.*\\}$",
         *   "elements": {
         *     "page": { "doc": "A page.", "children": ["text"] },
         *     "text": {
         *       "doc": "A line of text.",
         *       "children": [],
         *       "attributes": {
         *         "value": { "doc": "What it says.", "required": true },
         *         "align": { "values": ["start", "center", { "value": "end", "doc": "Right, in left-to-right text." }] }
         *       }
         *     }
         *   }
         * }
         * ```
         *
         * Every key but `elements` is optional; an element's and an attribute's too. An element
         * also takes `snippet` and `strictAttributes`, an attribute `type`. Throws
         * [IllegalArgumentException] saying where for JSON it cannot use.
         */
        fun parse(json: String): XmlSchema {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Not JSON: ${e.message}", e)
            } as? JsonObject ?: throw IllegalArgumentException("A schema is a JSON object")
            val elements = root["elements"] as? JsonObject ?: throw IllegalArgumentException("A schema needs an \"elements\" object")
            return XmlSchema(
                elements = elements.mapValues { (name, value) -> element(name, value) },
                roots = strings(root["roots"], "roots"),
                strict = (root["strict"] as? JsonPrimitive)?.booleanOrNull ?: true,
                dynamicValue = (root["dynamicValue"] as? JsonPrimitive)?.contentOrNull?.let {
                    try {
                        Regex(it)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("dynamicValue: not a regex: ${e.message}", e)
                    }
                },
            )
        }

        private fun element(name: String, value: JsonElement): Element {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("elements.$name: not an object")
            val attributes = obj["attributes"]?.let {
                it as? JsonObject
                    ?: throw IllegalArgumentException("elements.$name.attributes: not an object")
            }
            return Element(
                name = name,
                documentation = text(obj["doc"]),
                attributes = attributes.orEmpty().mapValues { (attr, v) -> attribute("elements.$name.attributes.$attr", attr, v) },
                children = obj["children"]?.let { strings(it, "elements.$name.children") },
                snippet = text(obj["snippet"]),
                strictAttributes = (obj["strictAttributes"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }

        private fun attribute(path: String, name: String, value: JsonElement): Attribute {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("$path: not an object")
            val values = (obj["values"] as? JsonArray).orEmpty().map { v ->
                when (v) {
                    is JsonPrimitive -> Value(v.content)

                    is JsonObject -> Value(
                        text(v["value"]) ?: throw IllegalArgumentException("$path.values: a value object needs \"value\""),
                        text(v["doc"]),
                    )

                    else -> throw IllegalArgumentException("$path.values: a value is a string or { value, doc }")
                }
            }
            return Attribute(
                name = name,
                documentation = text(obj["doc"]),
                values = values,
                required = (obj["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
                type = text(obj["type"]),
            )
        }

        private fun strings(value: JsonElement?, path: String): List<String> = when (value) {
            null -> emptyList()

            is JsonArray -> value.map {
                (it as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("$path: not a list of names")
            }

            else -> throw IllegalArgumentException("$path: not a list of names")
        }

        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
