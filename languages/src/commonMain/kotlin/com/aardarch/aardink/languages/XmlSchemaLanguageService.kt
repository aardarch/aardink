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

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.CompositeLanguageService
import com.aardarch.aardink.core.Diagnostic
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.languages.internal.diagnosticsPacer
import com.aardarch.aardink.languages.internal.xml.XmlAttr
import com.aardarch.aardink.languages.internal.xml.XmlScan
import com.aardarch.aardink.languages.internal.xml.XmlTag

/**
 * An XML dialect's language service from its [schema]: completions of the elements the element
 * at the caret may hold (with their required attributes and tab stops), of attributes and their
 * values; hover documentation for elements, attributes and values; and diagnostics for unknown
 * elements and attributes, elements out of place, values not among an attribute's and required
 * attributes left out. [base], the built-in XML language's service by default, adds everything
 * else, by [CompositeLanguageService]'s rules: well-formedness errors, closing tags on `>`,
 * indentation, formatting. Its own completions are left out; the schema's replace them.
 *
 * Schema completions are complete for the word being typed
 * ([completionsAreIncomplete][LanguageService.completionsAreIncomplete] false): the editor asks
 * once per word and narrows the list as it grows.
 */
class XmlSchemaLanguageService(val schema: XmlSchema, base: LanguageService? = BuiltInLanguages.Xml.languageService) :
    CompositeLanguageService(
        primary = SchemaService(schema, base?.triggerCharacters.orEmpty()),
        fallback = base ?: NoXmlService,
        inheritCompletions = false,
    )

/** A fallback with nothing to add. */
private object NoXmlService : LanguageService {
    override val triggerCharacters: Set<Char> get() = emptySet()

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> = emptyList()

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> = emptyList()

    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? = null

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
}

/** What the schema alone answers. */
private class SchemaService(private val schema: XmlSchema, baseTriggers: Set<Char>) : LanguageService {

    override val triggerCharacters: Set<Char> = baseTriggers + setOf('<', ' ', '"', '\'', '=')

    override val completionsAreIncomplete: Boolean get() = false

    // ── Completions ─────────────────────────────────────────────────────────

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val text = document.text
        val offset = cursorOffset.coerceIn(0, text.length)
        val tag = XmlScan.tagAt(XmlScan.tags(text, until = offset), offset) ?: return emptyList()
        if (tag.closing) return emptyList()
        if (offset in tag.nameStart..tag.nameEnd) return elementCompletions(text, tag, offset)
        val element = schema.elements[tag.name] ?: return emptyList()
        tag.attributes.firstOrNull { it.valueStart >= 0 && offset in it.valueStart..it.valueEnd }?.let { attr ->
            val info = element.attributes[attr.name] ?: return emptyList()
            return valueCompletions(info, offset, attr.valueStart until offset, quoted = false)
        }
        // `name=` with no quote yet: the values, in quotes.
        tag.attributes.firstOrNull {
            it.valueStart < 0 && it.nameEnd <= offset && text.substring(it.nameEnd, offset).trim() == "="
        }?.let { attr ->
            val info = element.attributes[attr.name] ?: return emptyList()
            return valueCompletions(info, offset, offset until offset, quoted = true)
        }
        val editing = tag.attributes.firstOrNull { offset in it.nameStart..it.nameEnd }
        if (editing == null && offset > 0 && !text[offset - 1].isWhitespace()) return emptyList()
        return attributeCompletions(element, tag, editing, offset)
    }

    private fun elementCompletions(text: String, tag: XmlTag, offset: Int): List<CompletionItem> {
        val allowed = when (val parent = tag.parent) {
            null -> schema.roots.ifEmpty { schema.elements.keys.toList() }
            else -> schema.elements[parent]?.children ?: schema.elements.keys.toList()
        }
        // A tag being written gets the whole element; renaming one that has more gets the name.
        val fresh = tag.attributes.isEmpty() && !tag.terminated && text.substring(tag.nameEnd, tag.end).isBlank()
        return allowed.mapNotNull { name -> schema.elements[name] }.map { element ->
            CompletionItem(
                label = element.name,
                kind = CompletionKind.Element,
                insertText = if (fresh) elementSnippet(element) else element.name,
                documentation = element.documentation,
                replaceRange = tag.nameStart until if (fresh) offset else tag.nameEnd,
                isSnippet = fresh,
            )
        }
    }

    private fun attributeCompletions(element: XmlSchema.Element, tag: XmlTag, editing: XmlAttr?, offset: Int): List<CompletionItem> {
        val present = tag.attributes.filter { it !== editing }.mapTo(HashSet()) { it.name }
        return element.attributes.values.filter { it.name !in present }.map { attr ->
            val withValue = editing != null && editing.valueStart >= 0
            CompletionItem(
                label = attr.name,
                kind = CompletionKind.Attribute,
                insertText = if (withValue) attr.name else "${attr.name}=\"${placeholder(attr, 1)}\"",
                documentation = attr.documentation,
                replaceRange = if (editing !=
                    null
                ) {
                    editing.nameStart until if (withValue) editing.nameEnd else offset
                } else {
                    offset until offset
                },
                isSnippet = !withValue,
                sortPriority = if (attr.required) 0 else 1,
                detail = if (attr.required) "required" else null,
            )
        }
    }

    private fun valueCompletions(attr: XmlSchema.Attribute, offset: Int, range: IntRange, quoted: Boolean): List<CompletionItem> =
        attr.values.map { value ->
            CompletionItem(
                label = value.value,
                kind = CompletionKind.Value,
                insertText = if (quoted) "\"${value.value}\"" else value.value,
                documentation = value.documentation,
                replaceRange = range,
            )
        }

    /** An element as completing it inserts it: its required attributes with tab stops, then its end. */
    private fun elementSnippet(element: XmlSchema.Element): String {
        element.snippet?.let { return it }
        val required = element.attributes.values.filter { it.required }
        val attributes = required.withIndex().joinToString("") { (i, attr) -> " ${attr.name}=\"${placeholder(attr, i + 1)}\"" }
        return if (element.children?.isEmpty() ==
            true
        ) {
            "${element.name}$attributes/>\$0"
        } else {
            "${element.name}$attributes>\$0</${element.name}>"
        }
    }

    /** A tab stop for [attr]'s value: a choice of its values when it has some. */
    private fun placeholder(attr: XmlSchema.Attribute, stop: Int): String =
        if (attr.values.isEmpty()) "\$$stop" else "\${$stop|${attr.values.joinToString(",") { escapeChoice(it.value) }}|}"

    private fun escapeChoice(value: String): String = buildString {
        for (c in value) {
            if (c == '\\' || c == ',' || c == '|' || c == '$' || c == '}') append('\\')
            append(c)
        }
    }

    // ── Hover ───────────────────────────────────────────────────────────────

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? {
        val tag = XmlScan.tagAt(XmlScan.tags(document.text, until = offset), offset) ?: return null
        val element = schema.elements[tag.name] ?: return null
        if (offset in tag.nameStart..tag.nameEnd) {
            val doc = element.documentation ?: return null
            return HoverDoc(title = "<${element.name}>", content = doc, range = tag.nameStart until tag.nameEnd)
        }
        val attr =
            tag.attributes.firstOrNull {
                offset in it.nameStart..it.nameEnd || (it.valueStart >= 0 && offset in it.valueStart..it.valueEnd)
            }
                ?: return null
        val info = element.attributes[attr.name] ?: return null
        if (attr.valueStart >= 0 && offset >= attr.valueStart) {
            val value = info.values.firstOrNull { it.value == attr.decodedValue }
            val doc = value?.documentation ?: return null
            return HoverDoc(title = "${attr.name}=\"${value.value}\"", content = doc, range = attr.valueStart until attr.valueEnd)
        }
        val values = info.values.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "\n\nValues: ") { it.value }.orEmpty()
        val content = (info.documentation.orEmpty() + values).trim()
        if (content.isEmpty()) return null
        return HoverDoc(title = info.name, content = content, range = attr.nameStart until attr.nameEnd)
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    override suspend fun diagnostics(document: CodeDocument): List<Diagnostic> {
        val problems = ArrayList<Diagnostic>()
        for (tag in XmlScan.tags(document.text, pacer = diagnosticsPacer())) {
            if (tag.closing || tag.name.isEmpty()) continue
            val nameRange = tag.nameStart until tag.nameEnd
            val element = schema.elements[tag.name]
            if (element == null) {
                if (schema.strict) problems += problem(nameRange, "Unknown element <${tag.name}>")
                continue
            }
            val parent = tag.parent
            if (parent == null && schema.roots.isNotEmpty() && tag.name !in schema.roots) {
                problems +=
                    problem(nameRange, "<${tag.name}> cannot be the root element; expected ${schema.roots.joinToString(", ") { "<$it>" }}")
            }
            val allowed = parent?.let { schema.elements[it]?.children }
            if (allowed != null && tag.name !in allowed) {
                problems +=
                    problem(
                        nameRange,
                        if (allowed.isEmpty()) "<$parent> holds no elements" else "<${tag.name}> is not allowed in <$parent>",
                    )
            }
            for (attr in tag.attributes) {
                val info = element.attributes[attr.name]
                if (info == null) {
                    if (schema.strict && element.strictAttributes) {
                        problems += problem(attr.nameStart until attr.nameEnd, "<${tag.name}> has no attribute '${attr.name}'")
                    }
                    continue
                }
                val value = attr.value ?: continue
                val meaning = attr.decodedValue ?: continue
                if (info.values.isEmpty() || info.values.any { it.value == meaning }) continue
                if (schema.dynamicValue?.containsMatchIn(meaning) == true) continue
                val range = if (value.isEmpty()) (attr.valueStart - 1)..attr.valueStart else attr.valueStart until attr.valueEnd
                problems +=
                    problem(range, "'$value' is not a value of ${attr.name}; expected ${info.values.joinToString(", ") { it.value }}")
            }
            if (tag.terminated) {
                val present = tag.attributes.mapTo(HashSet()) { it.name }
                for (missing in element.attributes.values.filter { it.required && it.name !in present }) {
                    problems += problem(nameRange, "<${tag.name}> needs the attribute '${missing.name}'")
                }
            }
        }
        return problems
    }

    private fun problem(range: IntRange, message: String) = Diagnostic(range, message, DiagnosticSeverity.Warning, source = "schema")

    // What the base answers.
    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String = ""

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? = null

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text
}
