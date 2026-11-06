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
package com.aardarch.aardink.ui

import com.aardarch.aardink.ui.MarkdownBlock.Code
import com.aardarch.aardink.ui.MarkdownBlock.Heading
import com.aardarch.aardink.ui.MarkdownBlock.Items
import com.aardarch.aardink.ui.MarkdownBlock.Paragraph
import com.aardarch.aardink.ui.MarkdownBlock.Rule
import com.aardarch.aardink.ui.MarkdownInline.Break
import com.aardarch.aardink.ui.MarkdownInline.Emphasis
import com.aardarch.aardink.ui.MarkdownInline.Link
import com.aardarch.aardink.ui.MarkdownInline.Plain
import com.aardarch.aardink.ui.MarkdownInline.Strong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownParserTest {

    private fun paragraph(text: String) = Paragraph(listOf(Plain(text)))

    /** [text] as inlines, for the cases where it is a single paragraph. */
    private fun inlines(text: String) = parseInlines(text)

    private fun literal(text: String) = assertEquals(listOf(Plain(text)), inlines(text), "$text stays as written")

    // ── Blocks ───────────────────────────────────────────────────────────────

    @Test
    fun `text with no markup is one paragraph`() {
        assertEquals(listOf(paragraph("What it does.")), parseMarkdown("What it does."))
    }

    @Test
    fun `nothing is no blocks`() {
        assertEquals(emptyList(), parseMarkdown(""))
        assertEquals(emptyList(), parseMarkdown("  \n\n "))
    }

    @Test
    fun `a blank line separates paragraphs`() {
        assertEquals(listOf(paragraph("one"), paragraph("two")), parseMarkdown("one\n\n\ntwo"))
    }

    @Test
    fun `Windows line ends are line ends`() {
        assertEquals(listOf(paragraph("one"), paragraph("two")), parseMarkdown("one\r\n\r\ntwo"))
    }

    @Test
    fun `hashes make a heading of that level, with a closing run dropped`() {
        assertEquals(listOf(Heading(1, listOf(Plain("One")))), parseMarkdown("# One"))
        assertEquals(listOf(Heading(3, listOf(Plain("Three")))), parseMarkdown("### Three ###"))
        assertEquals(listOf(Heading(6, listOf(Plain("Six")))), parseMarkdown("###### Six"))
    }

    @Test
    fun `hashes with no space, or too many, are text`() {
        assertEquals(listOf(paragraph("#define X")), parseMarkdown("#define X"))
        assertEquals(listOf(paragraph("####### seven")), parseMarkdown("####### seven"))
    }

    @Test
    fun `a heading keeps a hash that is part of its text`() {
        assertEquals(listOf(Heading(2, listOf(Plain("C#")))), parseMarkdown("## C#"))
    }

    @Test
    fun `a heading's text has inline markup`() {
        assertEquals(listOf(Heading(1, listOf(Plain("A "), Emphasis(listOf(Plain("b")))))), parseMarkdown("# A *b*"))
    }

    @Test
    fun `an underline makes a setext heading`() {
        assertEquals(listOf(Heading(1, listOf(Plain("One")))), parseMarkdown("One\n==="))
        assertEquals(listOf(Heading(2, listOf(Plain("Two")))), parseMarkdown("Two\n---"))
    }

    @Test
    fun `a fence keeps its text verbatim and names its language`() {
        val blocks = parseMarkdown("```kotlin\nval *a* = `b`\n\n# not a heading\n```")
        assertEquals(listOf(Code("kotlin", "val *a* = `b`\n\n# not a heading")), blocks)
    }

    @Test
    fun `a fence's language is the first word of its info`() {
        assertEquals(listOf(Code("kotlin", "x")), parseMarkdown("``` kotlin title=a\nx\n```"))
    }

    @Test
    fun `tildes fence too, and only a matching run closes`() {
        assertEquals(listOf(Code(null, "```\nx")), parseMarkdown("~~~\n```\nx\n~~~"))
        assertEquals(listOf(Code(null, "```\nx")), parseMarkdown("````\n```\nx\n````"))
    }

    @Test
    fun `an unclosed fence runs to the end`() {
        assertEquals(listOf(Code(null, "x\ny")), parseMarkdown("```\nx\ny"))
    }

    @Test
    fun `a fence ends a paragraph`() {
        assertEquals(listOf(paragraph("text"), Code(null, "x")), parseMarkdown("text\n```\nx\n```"))
    }

    @Test
    fun `an indented fence loses that indentation from its text`() {
        assertEquals(listOf(Code(null, "a\n  b")), parseMarkdown("  ```\n  a\n    b\n  ```"))
    }

    @Test
    fun `four spaces after a blank line are code, inside a paragraph they are not`() {
        assertEquals(listOf(paragraph("text"), Code(null, "code\n\n  more")), parseMarkdown("text\n\n    code\n\n      more\n"))
        assertEquals(listOf(paragraph("text more")), parseMarkdown("text\n    more"))
    }

    @Test
    fun `a leading tab indents as four spaces`() {
        assertEquals(listOf(Code(null, "code")), parseMarkdown("\tcode"))
    }

    @Test
    fun `a rule sets text apart, as language servers do`() {
        assertEquals(listOf(Code("rust", "fn a()"), Rule, paragraph("Docs.")), parseMarkdown("```rust\nfn a()\n```\n\n---\n\nDocs."))
        assertEquals(listOf(Rule), parseMarkdown("***"))
        assertEquals(listOf(Rule), parseMarkdown("_ _ _"))
    }

    @Test
    fun `bullets make a list, with any of the three marks`() {
        val items = listOf(listOf(paragraph("a")), listOf(paragraph("b")))
        assertEquals(listOf(Items(false, 1, items)), parseMarkdown("- a\n- b"))
        assertEquals(listOf(Items(false, 1, items)), parseMarkdown("* a\n* b"))
        assertEquals(listOf(Items(false, 1, items)), parseMarkdown("+ a\n+ b"))
    }

    @Test
    fun `changing the mark starts a new list`() {
        val blocks = parseMarkdown("- a\n* b")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is Items })
    }

    @Test
    fun `numbers make an ordered list that remembers its start`() {
        val items = listOf(listOf(paragraph("a")), listOf(paragraph("b")))
        assertEquals(listOf(Items(true, 3, items)), parseMarkdown("3. a\n4. b"))
        assertEquals(listOf(Items(true, 1, items)), parseMarkdown("1) a\n2) b"))
    }

    @Test
    fun `an indented list nests inside its item`() {
        val inner = Items(false, 1, listOf(listOf(paragraph("b"))))
        assertEquals(listOf(Items(false, 1, listOf(listOf(paragraph("a"), inner)))), parseMarkdown("- a\n  - b"))
    }

    @Test
    fun `an item carries on over an indented line and a lazy one`() {
        assertEquals(listOf(Items(false, 1, listOf(listOf(paragraph("a b"))))), parseMarkdown("- a\n  b"))
        assertEquals(listOf(Items(false, 1, listOf(listOf(paragraph("a b"))))), parseMarkdown("- a\nb"))
    }

    @Test
    fun `an item holds a second paragraph and a fence when they are indented`() {
        val item = listOf(paragraph("a"), paragraph("b"), Code(null, "c"))
        assertEquals(listOf(Items(false, 1, listOf(item))), parseMarkdown("- a\n\n  b\n\n  ```\n  c\n  ```"))
    }

    @Test
    fun `a blank line between items keeps one list, and unindented text ends it`() {
        val items = listOf(listOf(paragraph("a")), listOf(paragraph("b")))
        assertEquals(listOf(Items(false, 1, items), paragraph("c")), parseMarkdown("- a\n\n- b\n\nc"))
    }

    @Test
    fun `a list breaks into a paragraph, but only from 1 and not when empty`() {
        val bullets = Items(false, 1, listOf(listOf(paragraph("b"))))
        assertEquals(listOf(paragraph("a"), bullets), parseMarkdown("a\n- b"))
        assertEquals(listOf(paragraph("In 1986. A great year")), parseMarkdown("In\n1986. A great year"))
        assertEquals(listOf(paragraph("a *")), parseMarkdown("a\n*"))
    }

    @Test
    fun `a dash with no space is text`() {
        assertEquals(listOf(paragraph("-a")), parseMarkdown("-a"))
    }

    @Test
    fun `block quotes and tables stay as written`() {
        assertEquals(listOf(paragraph("> quoted")), parseMarkdown("> quoted"))
        assertEquals(listOf(paragraph("| a | b |")), parseMarkdown("| a | b |"))
    }

    // ── Emphasis ─────────────────────────────────────────────────────────────

    @Test
    fun `one mark is emphasis and two are strong`() {
        assertEquals(listOf(Emphasis(listOf(Plain("a")))), inlines("*a*"))
        assertEquals(listOf(Emphasis(listOf(Plain("a")))), inlines("_a_"))
        assertEquals(listOf(Strong(listOf(Plain("a")))), inlines("**a**"))
        assertEquals(listOf(Strong(listOf(Plain("a")))), inlines("__a__"))
    }

    @Test
    fun `emphasis nests`() {
        assertEquals(listOf(Emphasis(listOf(Strong(listOf(Plain("a")))))), inlines("***a***"))
        assertEquals(
            listOf(Strong(listOf(Plain("a "), Emphasis(listOf(Plain("b"))), Plain(" c")))),
            inlines("**a *b* c**"),
        )
    }

    @Test
    fun `text a plain-text author wrote stays as written`() {
        literal("snake_case_name")
        literal("wrap_content")
        literal("2 * 3 * 4")
        literal("a ** b")
        literal("*")
        literal("_")
        literal("**kwargs")
        literal("_not closed")
        literal("Reference to [versions] entry")
    }

    @Test
    fun `a name a Markdown reader would mark up is marked up`() {
        assertEquals(listOf(Strong(listOf(Plain("init"))), Plain(".py")), inlines("__init__.py"))
        assertEquals(listOf(Emphasis(listOf(Plain("private"))), Plain(" field")), inlines("_private_ field"))
    }

    @Test
    fun `an underscore inside a word never emphasises, a star does`() {
        literal("foo_bar_baz")
        assertEquals(listOf(Plain("a"), Emphasis(listOf(Plain("b"))), Plain("c")), inlines("a*b*c"))
    }

    @Test
    fun `an unmatched mark stays beside the emphasis that matched`() {
        assertEquals(listOf(Plain("*"), Emphasis(listOf(Plain("a")))), inlines("**a*"))
        assertEquals(listOf(Emphasis(listOf(Plain("a"))), Plain("*")), inlines("*a**"))
    }

    // ── Code ─────────────────────────────────────────────────────────────────

    @Test
    fun `a code span is literal`() {
        assertEquals(listOf(Plain("use "), MarkdownInline.Code("*a* [b](c) <d>"), Plain(" here")), inlines("use `*a* [b](c) <d>` here"))
    }

    @Test
    fun `a code span closes on a run of the same length`() {
        assertEquals(listOf(MarkdownInline.Code("a ` b")), inlines("`` a ` b ``"))
    }

    @Test
    fun `an unclosed backtick is text`() {
        literal("a ` b")
        literal("`` a ` b")
    }

    @Test
    fun `a code span folds its line ends into spaces`() {
        assertEquals(listOf(MarkdownInline.Code("a b")), inlines("`a\nb`"))
    }

    // ── Links ────────────────────────────────────────────────────────────────

    @Test
    fun `a link has a target and text with markup`() {
        assertEquals(listOf(Link("https://a.dev", listOf(Plain("docs")))), inlines("[docs](https://a.dev)"))
        assertEquals(
            listOf(Link("u", listOf(Emphasis(listOf(Plain("a"))), Plain(" "), MarkdownInline.Code("b")))),
            inlines("[*a* `b`](u)"),
        )
    }

    @Test
    fun `a link's title is read past`() {
        assertEquals(listOf(Link("u", listOf(Plain("a")))), inlines("""[a](u "the title")"""))
        assertEquals(listOf(Link("u", listOf(Plain("a")))), inlines("[a](u 'the title')"))
        assertEquals(listOf(Link("u", listOf(Plain("a")))), inlines("[a](u (the title))"))
    }

    @Test
    fun `a link's target takes balanced parentheses, angle brackets and escapes`() {
        assertEquals(listOf(Link("a_(b)", listOf(Plain("x")))), inlines("[x](a_(b))"))
        assertEquals(listOf(Link("a b", listOf(Plain("x")))), inlines("[x](<a b>)"))
        assertEquals(listOf(Link("a)b", listOf(Plain("x")))), inlines("""[x](a\)b)"""))
        assertEquals(listOf(Link("a?b&c", listOf(Plain("x")))), inlines("[x](a?b&amp;c)"))
    }

    @Test
    fun `brackets with no target are text`() {
        literal("[Foo]")
        literal("[a][b]")
        literal("[a] (b)")
        literal("[a](b")
        literal("]")
        literal("[")
    }

    @Test
    fun `a link inside a link leaves the outer brackets as text`() {
        assertEquals(listOf(Plain("["), Link("b", listOf(Plain("a"))), Plain("](c)")), inlines("[[a](b)](c)"))
    }

    @Test
    fun `a bare URL inside link text does not nest`() {
        assertEquals(listOf(Link("u", listOf(Plain("see https://a.dev")))), inlines("[see https://a.dev](u)"))
    }

    @Test
    fun `an autolink and a bare URL are links`() {
        assertEquals(listOf(Link("https://a.dev/x", listOf(Plain("https://a.dev/x")))), inlines("<https://a.dev/x>"))
        assertEquals(listOf(Link("mailto:a@b.dev", listOf(Plain("a@b.dev")))), inlines("<mailto:a@b.dev>"))
        assertEquals(
            listOf(Plain("See "), Link("https://a.dev/a_b_c", listOf(Plain("https://a.dev/a_b_c"))), Plain(".")),
            inlines("See https://a.dev/a_b_c."),
        )
    }

    @Test
    fun `a URL's underscores are not emphasis`() {
        assertEquals(listOf(Link("https://a.dev/_x_", listOf(Plain("https://a.dev/_x_")))), inlines("https://a.dev/_x_"))
    }

    @Test
    fun `the emphasis around a bare URL is not part of it`() {
        val link = Link("https://a.dev/x", listOf(Plain("https://a.dev/x")))
        assertEquals(listOf(Strong(listOf(link))), inlines("**https://a.dev/x**"))
        assertEquals(listOf(Emphasis(listOf(link, Plain(".")))), inlines("*https://a.dev/x.*"))
    }

    private fun url(target: String) = Link(target, listOf(Plain(target)))

    @Test
    fun `a hard break after a bare URL is not part of it`() {
        assertEquals(
            listOf(Plain("See "), url("https://a.dev/docs"), Break, Plain("for more")),
            inlines("See https://a.dev/docs\\\nfor more"),
        )
    }

    @Test
    fun `an escaped mark after a bare URL is the text after it`() {
        assertEquals(listOf(Plain("["), url("https://a.dev/x"), Plain("]")), inlines("\\[https://a.dev/x\\]"))
        assertEquals(listOf(Plain("`"), url("https://a.dev/x"), Plain("`")), inlines("\\`https://a.dev/x\\`"))
        assertEquals(listOf(url("https://a.dev/x"), Plain("*")), inlines("https://a.dev/x\\*"))
        assertEquals(listOf(url("https://a.dev/x"), Plain(".")), inlines("https://a.dev/x\\."))
    }

    @Test
    fun `an escape inside a bare URL is resolved in its text and its target`() {
        assertEquals(listOf(Plain("Values: "), url("https://a.dev/x_y?a=1&b=2")), inlines("Values: https://a.dev/x\\_y?a=1\\&b=2"))
    }

    @Test
    fun `a backslash that escapes nothing of a bare URL ends it`() {
        assertEquals(listOf(url("https://a.dev/x"), Plain("\\y")), inlines("https://a.dev/x\\\\y"))
        assertEquals(listOf(url("https://a.dev/x"), Plain("\\y")), inlines("https://a.dev/x\\y"))
    }

    @Test
    fun `a word that only starts like a URL is text`() {
        literal("http is a protocol, shttp://x is not one")
    }

    @Test
    fun `a script scheme is never a link, however it is written`() {
        assertEquals(listOf(Plain("a")), inlines("[a](javascript:alert(1))"))
        assertEquals(listOf(Plain("a")), inlines("[a](JavaScript:alert(1))"))
        assertEquals(listOf(Plain("a")), inlines("[a](java&#9;script:alert(1))"))
        assertEquals(listOf(Plain("a")), inlines("[a](<java script:alert(1)>)"))
        assertEquals(listOf(Plain("a")), inlines("[a](vbscript:x)"))
        assertEquals(listOf(Plain("a")), inlines("[a](data:text/html,x)"))
    }

    @Test
    fun `an empty target is no link`() {
        assertEquals(listOf(Plain("a")), inlines("[a]()"))
    }

    @Test
    fun `a host's own scheme reaches the host`() {
        assertEquals(listOf(Link("aardflex:open/1", listOf(Plain("a")))), inlines("[a](aardflex:open/1)"))
    }

    @Test
    fun `an image shows its alt text and loads nothing`() {
        assertEquals(listOf(Plain("the badge")), inlines("![the *badge*](https://a.dev/b.png)"))
        literal("a! b")
        literal("![not an image]")
    }

    // ── Literal text ─────────────────────────────────────────────────────────

    @Test
    fun `raw HTML stays literal text`() {
        literal("<b>bold</b>")
        literal("""<a title="*no*" href="x_y_z">""")
        literal("<!-- *a* -->")
        literal("<br/>")
        literal("<script>alert(1)</script>")
    }

    @Test
    fun `raw HTML does not stop what is around it`() {
        assertEquals(listOf(Plain("<b>"), Emphasis(listOf(Plain("a"))), Plain("</b>")), inlines("<b>*a*</b>"))
    }

    @Test
    fun `a namespaced tag is a tag and not a link`() {
        literal("<android:id>")
        literal("<xsl:template>")
    }

    @Test
    fun `angle brackets that are no tag are text`() {
        literal("a < b and b > c")
        literal("List<")
        literal("1 <2")
    }

    @Test
    fun `a backslash escapes punctuation and nothing else`() {
        assertEquals(listOf(Plain("*a*")), inlines("""\*a\*"""))
        assertEquals(listOf(Plain("[a](b)")), inlines("""\[a](b)"""))
        assertEquals(listOf(Plain("""C:\Users""")), inlines("""C:\Users"""))
        assertEquals(listOf(Plain("""a\""")), inlines("""a\"""))
    }

    @Test
    fun `the common entities are decoded and others left`() {
        assertEquals(listOf(Plain("a < b & c > d")), inlines("a &lt; b &amp; c &gt; d"))
        assertEquals(listOf(Plain("AB")), inlines("&#65;&#x42;"))
        literal("&nope; and &#0; and AT&T;")
    }

    @Test
    fun `a decoded bracket does not become markup`() {
        assertEquals(listOf(Plain("<b>*a*")), inlines("&lt;b>&#42;a&#42;"))
    }

    // ── Line ends ────────────────────────────────────────────────────────────

    @Test
    fun `a line end inside a paragraph is a space`() {
        assertEquals(listOf(paragraph("one two")), parseMarkdown("one\ntwo"))
    }

    @Test
    fun `two trailing spaces or a backslash break the line`() {
        assertEquals(listOf(Paragraph(listOf(Plain("one"), Break, Plain("two")))), parseMarkdown("one  \ntwo"))
        assertEquals(listOf(Paragraph(listOf(Plain("one"), Break, Plain("two")))), parseMarkdown("one\\\ntwo"))
    }

    @Test
    fun `spaces at the end of a paragraph are dropped`() {
        assertEquals(listOf(paragraph("one")), parseMarkdown("one   "))
    }

    // ── Strings the built-in services show ───────────────────────────────────

    @Test
    fun `the built-in services' strings read as they did`() {
        val strings = listOf(
            "XML element <LinearLayout>",
            "Attribute android:layout_width",
            "Attribute value wrap_content",
            "Attribute value match_parent",
            "@Composable annotation",
            "Reference to [versions] entry",
            "Inline library specification with version reference",
            "Executes block with it as receiver",
            "Jetpack Compose Composable function snippet",
        )
        for (string in strings) assertEquals(listOf(paragraph(string)), parseMarkdown(string))
    }

    @Test
    fun `plain text the LSP bridge escapes reads to the letter, line for line`() {
        val escaped = "\\# not a heading\\\n\\- not a list\\\n1\\. not a list\\\n\\=== not a rule\\\n\\*a\\* \\_b\\_ \\`c\\` \\<d> \\&amp;"
        val shown = listOf("# not a heading", "- not a list", "1. not a list", "=== not a rule", "*a* _b_ `c` <d> &amp;")
        val expected = shown.flatMapIndexed { index, line -> if (index == 0) listOf(Plain(line)) else listOf(Break, Plain(line)) }
        assertEquals(listOf(Paragraph(expected)), parseMarkdown(escaped))
    }

    @Test
    fun `plain text with a URL at a line's end keeps the line and the URL`() {
        val blocks = parseMarkdown("See https://a.dev/docs\\\nfor more\\\n\\[https://a.dev/x\\]")
        val expected =
            listOf(
                Plain("See "),
                url("https://a.dev/docs"),
                Break,
                Plain("for more"),
                Break,
                Plain("["),
                url("https://a.dev/x"),
                Plain("]"),
            )
        assertEquals(listOf(Paragraph(expected)), blocks)
    }

    // ── One line ─────────────────────────────────────────────────────────────

    @Test
    fun `one line of Markdown drops its marks and resolves its escapes`() {
        assertEquals("Uses a_b only here", markdownLine("Uses a\\_b *only* here"))
        assertEquals("See the docs and code", markdownLine("See [the docs](https://a.dev) and `code`"))
    }

    @Test
    fun `one line of Markdown is its first block that has words`() {
        assertEquals("Title", markdownLine("# Title\n\nBody"))
        assertEquals("first words", markdownLine("first\nwords\n\nsecond paragraph"))
        assertEquals("after", markdownLine("---\n\nafter"))
        assertEquals("first", markdownLine("- first\n- second"))
        assertEquals("val x = 1", markdownLine("```kotlin\n\n  val x = 1\nval y = 2\n```"))
    }

    @Test
    fun `one line of nothing is empty`() {
        assertEquals("", markdownLine(""))
        assertEquals("", markdownLine("---"))
    }

    // ── Bounds ───────────────────────────────────────────────────────────────

    @Test
    fun `documentation past the cap is cut, with an ellipsis of its own`() {
        val blocks = parseMarkdown("word ".repeat(5_000))
        assertEquals(2, blocks.size)
        val shown = assertIs<Plain>(assertIs<Paragraph>(blocks[0]).text.single())
        assertTrue(shown.text.length <= MARKDOWN_MAX_LENGTH)
        assertEquals(paragraph("…"), blocks[1])
    }

    @Test
    fun `the cut takes a line end when one is near and keeps out of an open fence`() {
        val line = "x".repeat(99)
        val blocks = parseMarkdown("```\n" + (line + "\n").repeat(200))
        assertEquals(paragraph("…"), blocks.last())
        val code = assertIs<Code>(blocks.first())
        assertTrue(code.code.lines().all { it == line })
    }

    @Test
    fun `the cut does not split a surrogate pair`() {
        val text = "x".repeat(MARKDOWN_MAX_LENGTH - 1) + "\uD83D\uDE00" + "x".repeat(10)
        val shown = assertIs<Plain>(assertIs<Paragraph>(parseMarkdown(text)[0]).text.single())
        assertEquals(MARKDOWN_MAX_LENGTH - 1, shown.text.length)
    }

    @Test
    fun `text at the cap is not cut`() {
        assertEquals(1, parseMarkdown("x".repeat(MARKDOWN_MAX_LENGTH)).size)
    }

    @Test
    fun `lists nest only so deep, and the rest is text`() {
        var blocks = parseMarkdown((0..40).joinToString("\n") { "  ".repeat(it) + "- a" })
        var depth = 0
        while (true) {
            val list = blocks.firstOrNull() as? Items ?: break
            depth++
            blocks = list.items.first().filterIsInstance<Items>()
        }
        assertEquals(8, depth)
    }

    @Test
    fun `input built to be slow or deep parses all the same`() {
        val hostile = listOf(
            "*a ".repeat(3_000) + " a*".repeat(3_000),
            "**a ".repeat(2_500),
            "_".repeat(10_000),
            "[".repeat(10_000),
            "[a](".repeat(2_500),
            "[a](b 'c ".repeat(1_000),
            "`".repeat(5_000) + " " + "`".repeat(4_999),
            (1..140).joinToString(" ") { "`".repeat(it) },
            "<a ".repeat(3_000),
            "- ".repeat(5_000),
            "> ".repeat(5_000),
            "&".repeat(10_000),
            "\\".repeat(10_000),
        )
        for (text in hostile) assertTrue(parseMarkdown(text).isNotEmpty(), text.take(12))
    }

    @Test
    fun `emphasis nests only so deep`() {
        val depth = 16
        val text = "*a ".repeat(3_000) + "b" + " c*".repeat(3_000)
        var nodes = parseInlines(text)
        var levels = 0
        while (true) {
            val inner = nodes.filterIsInstance<Emphasis>().firstOrNull() ?: break
            levels++
            nodes = inner.children
        }
        assertEquals(depth, levels)
    }

    @Test
    fun `a closer that finds no opener leaves the marks as text`() {
        literal("a* b* c*")
    }
}
