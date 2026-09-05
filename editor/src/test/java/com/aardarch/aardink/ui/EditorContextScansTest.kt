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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorContextScansTest {

    // ── isInsideCallArguments ────────────────────────────────────────────────

    /** True when the cursor sits at the end of [text]. */
    private fun insideAtEnd(text: String) = isInsideCallArguments(text, text.length)

    @Test
    fun `open paren and every argument character stay inside the call`() {
        assertTrue(insideAtEnd("foo("))
        assertTrue(insideAtEnd("foo(a"), "typing the first argument must not close signature help")
        assertTrue(insideAtEnd("foo(a, "))
        assertTrue(insideAtEnd("foo(a, b"))
        assertTrue(insideAtEnd("foo(bar(1), "), "a completed inner call leaves us in the outer one")
    }

    @Test
    fun `a closed call is outside`() {
        assertFalse(insideAtEnd("foo(a)"))
        assertFalse(insideAtEnd("foo(a) + 1"))
        assertFalse(insideAtEnd("val x = 1"))
    }

    @Test
    fun `parens in literals and comments are ignored`() {
        assertFalse(insideAtEnd("""val s = "foo(" """))
        assertFalse(insideAtEnd("val c = '('"))
        assertFalse(insideAtEnd("val s = \"\\\"(\" "), "an escaped quote does not end the literal")
        assertFalse(insideAtEnd("// foo(\n"))
        assertFalse(insideAtEnd("/* foo( */ "))
        assertTrue(insideAtEnd("""foo("a(b", """), "a literal argument still leaves the call open")
    }

    @Test
    fun `the scan respects the cursor rather than the whole text`() {
        val text = "foo(a) + bar(b"
        assertFalse(isInsideCallArguments(text, 6))
        assertTrue(isInsideCallArguments(text, 14))
        assertFalse(isInsideCallArguments(text, 0))
    }

    // ── wordRangeAt ──────────────────────────────────────────────────────────

    @Test
    fun `word range covers the identifier around the offset`() {
        val text = "val myValue = 1"
        assertEquals(4 until 11, wordRangeAt(text, 4), "at the start of the word")
        assertEquals(4 until 11, wordRangeAt(text, 7), "inside the word")
        assertEquals(4 until 11, wordRangeAt(text, 11), "just past the word")
    }

    @Test
    fun `word range includes digits and underscores`() {
        val text = "val my_value2 = 1"
        assertEquals(4 until 13, wordRangeAt(text, 8))
    }

    @Test
    fun `word range is empty away from an identifier`() {
        assertTrue(wordRangeAt("a = b", 2).isEmpty())
        assertTrue(wordRangeAt("", 0).isEmpty())
    }

    @Test
    fun `word range clamps an out of bounds offset`() {
        val text = "value"
        assertEquals(0 until 5, wordRangeAt(text, 99))
    }
}
