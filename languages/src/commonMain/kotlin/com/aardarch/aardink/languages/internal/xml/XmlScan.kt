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
package com.aardarch.aardink.languages.internal.xml

import com.aardarch.aardink.languages.internal.CooperativePacer

/**
 * An attribute of an [XmlTag]: its name at [nameStart], and its value from [valueStart] to [valueEnd]
 * when it has one. [value] is the source text; [decodedValue] is what it means, with its entity and
 * character references replaced.
 */
internal class XmlAttr(val name: String, val nameStart: Int, val valueStart: Int, val valueEnd: Int, val value: String?) {
    val nameEnd: Int get() = nameStart + name.length

    val decodedValue: String? by lazy { value?.let(XmlScan::decodeReferences) }
}

/**
 * A tag as [XmlScan] found it: `<name …>`, `</name>` ([closing]) or `<name …/>` ([selfClosing]),
 * from [start] (its `<`) to [end] (after its `>`, or where it broke off when not [terminated]).
 * [parent] is the element it is inside, by name; null at the top level.
 */
internal class XmlTag(
    val start: Int,
    val end: Int,
    val name: String,
    val nameStart: Int,
    val closing: Boolean,
    val selfClosing: Boolean,
    val terminated: Boolean,
    val attributes: List<XmlAttr>,
    val parent: String?,
) {
    val nameEnd: Int get() = nameStart + name.length
}

/**
 * A forgiving scan of XML into tags, for completions and checks while the text is being typed:
 * comments, CDATA, declarations and processing instructions are skipped; a tag not finished by
 * `>` ends at the next `<` (a tag being typed); an attribute value not closed by its quote ends at
 * the next `<`. Which element each tag is in follows the opening and closing tags, a closing tag
 * closing the innermost open element of its name.
 */
internal object XmlScan {

    /**
     * The tags of [text] that start before [until]; a request about one offset needs no more than
     * the tags up to it. A whole-document pass gives a [pacer] to pause for the browser. The
     * content of an element named in [textOnly] (lower case; HTML's `script`, `title`, …) is text,
     * not markup, and is skipped to its closing tag.
     */
    suspend fun tags(
        text: CharSequence,
        until: Int = text.length,
        pacer: CooperativePacer? = null,
        textOnly: Set<String> = emptySet(),
    ): List<XmlTag> {
        val tags = ArrayList<XmlTag>()
        val open = ArrayList<String>()
        val n = text.length
        var i = 0
        while (i < n) {
            pacer?.onProgress(i)
            val lt = indexOf(text, '<', i)
            if (lt < 0 || lt >= until) break
            when {
                startsWith(text, "<!--", lt) -> {
                    i = endOf(text, "-->", lt + 4)
                    continue
                }

                startsWith(text, "<![CDATA[", lt) -> {
                    i = endOf(text, "]]>", lt + 9)
                    continue
                }

                lt + 1 < n && text[lt + 1] == '?' -> {
                    i = endOf(text, "?>", lt + 2)
                    continue
                }

                lt + 1 < n && text[lt + 1] == '!' -> {
                    i = endOfDeclaration(text, lt + 2)
                    continue
                }
            }
            var j = lt + 1
            val closing = j < n && text[j] == '/'
            if (closing) j++
            val nameStart = j
            while (j < n && isNameChar(text[j])) j++
            val name = text.subSequence(nameStart, j).toString()
            val attributes = ArrayList<XmlAttr>()
            var terminated = false
            var selfClosing = false
            while (j < n) {
                val c = text[j]
                if (c == '>') {
                    terminated = true
                    j++
                    break
                }
                if (c == '/' && j + 1 < n && text[j + 1] == '>') {
                    selfClosing = true
                    terminated = true
                    j += 2
                    break
                }
                if (c == '<') break
                if (!isNameStartChar(c)) {
                    j++
                    continue
                }
                val attrStart = j
                while (j < n && isNameChar(text[j])) j++
                val attrName = text.subSequence(attrStart, j).toString()
                var k = j
                while (k < n && text[k].isWhitespace()) k++
                if (k < n && text[k] == '=') {
                    k++
                    while (k < n && text[k].isWhitespace()) k++
                    if (k < n && (text[k] == '"' || text[k] == '\'')) {
                        val quote = text[k]
                        val valueStart = k + 1
                        var e = valueStart
                        while (e < n && text[e] != quote && text[e] != '<') e++
                        attributes += XmlAttr(attrName, attrStart, valueStart, e, text.subSequence(valueStart, e).toString())
                        j = if (e < n && text[e] == quote) e + 1 else e
                    } else {
                        attributes += XmlAttr(attrName, attrStart, -1, -1, null)
                        j = k
                    }
                } else {
                    attributes += XmlAttr(attrName, attrStart, -1, -1, null)
                }
            }
            val parent = if (closing) parentOfClosing(open, name) else open.lastOrNull()
            tags += XmlTag(lt, j, name, nameStart, closing, selfClosing, terminated, attributes, parent)
            if (closing) {
                val at = open.lastIndexOf(name)
                if (at >= 0) while (open.size > at) open.removeAt(open.size - 1)
            } else if (terminated && !selfClosing && name.isNotEmpty()) {
                open += name
            }
            i = j
            if (!closing && terminated && !selfClosing && textOnly.isNotEmpty() && name.lowercase() in textOnly) {
                val close = text.indexOf("</$name", j, ignoreCase = true)
                if (close >= 0) i = close
            }
        }
        return tags
    }

    /** The element a closing tag of [name] closes is inside: the one around its innermost open [name]. */
    private fun parentOfClosing(open: List<String>, name: String): String? {
        val at = open.lastIndexOf(name)
        return if (at > 0) {
            open[at - 1]
        } else if (at == 0) {
            null
        } else {
            open.lastOrNull()
        }
    }

    /** The tag [offset] is in: after its `<` and before its `>` (or anywhere up to where an unfinished one broke off). */
    fun tagAt(tags: List<XmlTag>, offset: Int): XmlTag? {
        var lo = 0
        var hi = tags.size - 1
        var found: XmlTag? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val tag = tags[mid]
            if (tag.start < offset) {
                found = tag
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val tag = found ?: return null
        val inside = if (tag.terminated) offset < tag.end else offset <= tag.end
        return tag.takeIf { inside }
    }

    fun isNameStartChar(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

    fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '.' || c == '-'

    private fun indexOf(text: CharSequence, c: Char, from: Int): Int {
        for (i in from until text.length) if (text[i] == c) return i
        return -1
    }

    private fun startsWith(text: CharSequence, prefix: String, at: Int): Boolean =
        at + prefix.length <= text.length && (prefix.indices).all { text[at + it] == prefix[it] }

    /**
     * Just after the `>` ending a `<!…` declaration whose body starts at [from], or the end of
     * [text]: not one inside quotes or inside a DOCTYPE's `[…]` internal subset, whose comments and
     * processing instructions are skipped whole.
     */
    private fun endOfDeclaration(text: CharSequence, from: Int): Int {
        var quote: Char? = null
        var depth = 0
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null

                c == '"' || c == '\'' -> quote = c

                depth > 0 && startsWith(text, "<!--", i) -> {
                    i = endOf(text, "-->", i + 4)
                    continue
                }

                depth > 0 && startsWith(text, "<?", i) -> {
                    i = endOf(text, "?>", i + 2)
                    continue
                }

                c == '[' -> depth++

                c == ']' -> if (depth > 0) depth--

                c == '>' && depth == 0 -> return i + 1
            }
            i++
        }
        return text.length
    }

    /**
     * [value] with its references replaced: the five predefined entities and decimal and
     * hexadecimal character references. One it cannot resolve stays as written.
     */
    fun decodeReferences(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val semi = if (value[i] == '&') value.indexOf(';', i + 1) else -1
            val decoded = if (semi > i + 1) referenceText(value.substring(i + 1, semi)) else null
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
            } else {
                out.append(value[i])
                i++
            }
        }
        return out.toString()
    }

    private fun referenceText(name: String): String? = when {
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "amp" -> "&"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name.startsWith("#x") -> codePointText(name.substring(2).toIntOrNull(16))
        name.startsWith("#") -> codePointText(name.substring(1).toIntOrNull())
        else -> null
    }

    private fun codePointText(codePoint: Int?): String? = when {
        codePoint == null || codePoint <= 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF -> null

        codePoint < 0x10000 -> codePoint.toChar().toString()

        else -> {
            val v = codePoint - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }

    /** Just after the first [marker] at or after [from], or the end of [text]. */
    private fun endOf(text: CharSequence, marker: String, from: Int): Int {
        var i = from
        while (i + marker.length <= text.length) {
            if (startsWith(text, marker, i)) return i + marker.length
            i++
        }
        return text.length
    }
}
