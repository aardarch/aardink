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

import com.aardarch.aardink.core.DocumentLink
import com.aardarch.aardink.core.DocumentSymbol
import com.aardarch.aardink.core.SymbolKind
import com.aardarch.aardink.languages.internal.CooperativePacer

/**
 * The elements of an XML or HTML document as [DocumentSymbol]s: one per element, from its `<` to
 * the `>` of its closing tag (or of itself when it closes itself), named by its tag, with its
 * `id`, `name` or `android:id` as the detail, and the elements inside it as children.
 *
 * Built on [XmlScan]'s forgiving scan, so a document being typed still has a structure: an element
 * never closed ends where its parent does, or at the end of the text; a closing tag with no open
 * element of its name is ignored. In [htmlMode] names match without case and the [voidElements]
 * (`br`, `img`, …) have no content.
 */
internal object XmlSymbols {

    private class Open(val tag: XmlTag) {
        val children = ArrayList<DocumentSymbol>()
    }

    suspend fun of(
        text: CharSequence,
        htmlMode: Boolean,
        voidElements: Set<String> = emptySet(),
        pacer: CooperativePacer? = null,
    ): List<DocumentSymbol> {
        if (text.isEmpty()) return emptyList()
        val tags = XmlScan.tags(text, pacer = pacer)
        val top = ArrayList<DocumentSymbol>()
        val open = ArrayList<Open>()

        fun add(symbol: DocumentSymbol) {
            (open.lastOrNull()?.children ?: top) += symbol
        }

        fun close(element: Open, last: Int) {
            add(symbolFor(element.tag, last, element.children))
        }

        for (tag in tags) {
            if (tag.name.isEmpty()) continue
            val isVoid = htmlMode && tag.name.lowercase() in voidElements
            when {
                tag.closing -> {
                    val at = open.indexOfLast { it.tag.name.equals(tag.name, ignoreCase = htmlMode) }
                    if (at < 0) continue
                    // Elements left open inside it end where it does.
                    while (open.size > at + 1) {
                        val inner = open.removeAt(open.size - 1)
                        close(inner, tag.start - 1)
                    }
                    close(open.removeAt(open.size - 1), tag.end - 1)
                }

                tag.selfClosing || isVoid || !tag.terminated -> add(symbolFor(tag, tag.end - 1, emptyList()))

                else -> open += Open(tag)
            }
        }
        while (open.isNotEmpty()) close(open.removeAt(open.size - 1), text.length - 1)
        return top
    }

    private fun symbolFor(tag: XmlTag, last: Int, children: List<DocumentSymbol>): DocumentSymbol {
        val detail = tag.attributes.firstOrNull { it.name in NAMING_ATTRIBUTES && it.value != null }?.decodedValue
        return DocumentSymbol(
            name = tag.name,
            kind = SymbolKind.Object,
            range = tag.start..maxOf(last, tag.start),
            selectionRange = tag.nameStart until tag.nameEnd,
            detail = detail,
            children = children,
        )
    }

    /** Attributes whose value names an element, shown beside its tag. */
    private val NAMING_ATTRIBUTES = setOf("id", "name", "android:id", "key")

    /**
     * Which tag closes which in [tags], as indices both ways: an opening tag and the closing tag
     * that closes it. A closing tag closes the innermost open element of its name (without case in
     * [htmlMode]); self-closing tags, the [voidElements] and unfinished tags have no partner.
     */
    fun partners(tags: List<XmlTag>, htmlMode: Boolean, voidElements: Set<String> = emptySet()): Map<Int, Int> {
        val pairs = HashMap<Int, Int>()
        val open = ArrayList<Int>()
        for ((index, tag) in tags.withIndex()) {
            if (tag.name.isEmpty()) continue
            if (tag.closing) {
                val at = open.indexOfLast { tags[it].name.equals(tag.name, ignoreCase = htmlMode) }
                if (at < 0) continue
                val opener = open[at]
                while (open.size > at) open.removeAt(open.size - 1)
                pairs[opener] = index
                pairs[index] = opener
            } else if (tag.terminated && !tag.selfClosing && !(htmlMode && tag.name.lowercase() in voidElements)) {
                open += index
            }
        }
        return pairs
    }

    /**
     * The tag names linked to the one at [offset]: the name in an element's opening tag and in its
     * closing tag, each `start until end`, when [offset] is in or just after one of them.
     */
    suspend fun linkedNames(text: CharSequence, offset: Int, htmlMode: Boolean, voidElements: Set<String>): List<IntRange>? {
        val tags = XmlScan.tags(text)
        val index = tags.indexOfFirst { it.name.isNotEmpty() && offset >= it.nameStart && offset <= it.nameEnd }
        if (index < 0) return null
        val partner = partners(tags, htmlMode, voidElements)[index] ?: return null
        val a = tags[index]
        val b = tags[partner]
        return listOf(a.nameStart until a.nameEnd, b.nameStart until b.nameEnd).sortedBy { it.first }
    }

    /**
     * The links in the attributes of [text]: `href` and `src` values, and the `component` of an
     * `include`, each as its value's range and the value as the target.
     */
    suspend fun links(text: CharSequence, pacer: CooperativePacer? = null): List<DocumentLink> {
        val links = ArrayList<DocumentLink>()
        for (tag in XmlScan.tags(text, pacer = pacer)) {
            if (tag.closing) continue
            for (attribute in tag.attributes) {
                val value = attribute.decodedValue ?: continue
                if (value.isBlank() || attribute.valueEnd <= attribute.valueStart) continue
                val local = attribute.name.substringAfterLast(':')
                val isLink = local == "href" || local == "src" || (local == "component" && tag.name.substringAfterLast(':') == "include")
                if (isLink) links += DocumentLink(attribute.valueStart until attribute.valueEnd, value)
            }
        }
        return links
    }
}
