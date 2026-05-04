package com.aardarch.editor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CodeDocumentTest {

    @Test
    fun `empty document has one line`() {
        val doc = CodeDocument("")
        assertEquals(1, doc.lineCount)
        assertEquals(0, doc.length)
    }

    @Test
    fun `single line document has correct metrics`() {
        val doc = CodeDocument("hello")
        assertEquals(1, doc.lineCount)
        assertEquals(0, doc.lineStart(0))
        assertEquals(5, doc.lineEnd(0))
        assertEquals("hello", doc.lineText(0))
    }

    @Test
    fun `multi-line document line starts are correct`() {
        val doc = CodeDocument("foo\nbar\nbaz")
        assertEquals(3, doc.lineCount)
        assertEquals(0, doc.lineStart(0))
        assertEquals(4, doc.lineStart(1))
        assertEquals(8, doc.lineStart(2))
    }

    @Test
    fun `offsetToLineCol on first line`() {
        val doc = CodeDocument("hello\nworld")
        assertEquals(Pair(0, 0), doc.offsetToLineCol(0))
        assertEquals(Pair(0, 5), doc.offsetToLineCol(5))
    }

    @Test
    fun `offsetToLineCol on second line`() {
        val doc = CodeDocument("hello\nworld")
        assertEquals(Pair(1, 0), doc.offsetToLineCol(6))
        assertEquals(Pair(1, 5), doc.offsetToLineCol(11))
    }

    @Test
    fun `lineColToOffset round-trips`() {
        val doc = CodeDocument("hello\nworld\nfoo")
        assertEquals(6, doc.lineColToOffset(1, 0))
        assertEquals(9, doc.lineColToOffset(1, 3))
    }

    @Test
    fun `insert in middle of line`() {
        val doc = CodeDocument("helo")
        doc.insert(3, "l")
        assertEquals("hello", doc.text)
        assertEquals(1, doc.lineCount)
    }

    @Test
    fun `insert newline increases line count`() {
        val doc = CodeDocument("ab")
        doc.insert(1, "\n")
        assertEquals("a\nb", doc.text)
        assertEquals(2, doc.lineCount)
    }

    @Test
    fun `insert marks correct dirty lines`() {
        val doc = CodeDocument("line1\nline2\nline3")
        doc.dirtyLines = null
        doc.insert(6, "X")
        assertNotNull(doc.dirtyLines)
        assertEquals(1, doc.dirtyLines!!.first)
    }

    @Test
    fun `delete removes characters`() {
        val doc = CodeDocument("hello world")
        doc.delete(5, 6)
        assertEquals("hello", doc.text)
    }

    @Test
    fun `delete across newline reduces line count`() {
        val doc = CodeDocument("foo\nbar")
        doc.delete(3, 1) // remove the \n
        assertEquals("foobar", doc.text)
        assertEquals(1, doc.lineCount)
    }

    @Test
    fun `replaceAll clears dirty from prior state`() {
        val doc = CodeDocument("old")
        doc.replaceAll("new content\nsecond line")
        assertEquals(2, doc.lineCount)
        assertEquals("new content", doc.lineText(0))
        assertEquals("second line", doc.lineText(1))
        assertNotNull(doc.dirtyLines)
    }

    @Test
    fun `initial document has null dirtyLines`() {
        val doc = CodeDocument("hello")
        assertNull(doc.dirtyLines)
    }
}
