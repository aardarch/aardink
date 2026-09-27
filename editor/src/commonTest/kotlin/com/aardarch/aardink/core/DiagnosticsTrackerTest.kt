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
package com.aardarch.aardink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagnosticsTrackerTest {

    // "val bad = 1\nval worse = 2": "bad" is 4..6 on line 0, "worse" is 16..20 on line 1.
    private val text = "val bad = 1\nval worse = 2"
    private val bad = Diagnostic(4..6, 0, "bad", DiagnosticSeverity.Error)
    private val worse = Diagnostic(16..20, 1, "worse", DiagnosticSeverity.Warning)

    private fun tracked(): Pair<CodeDocument, DiagnosticsTracker> {
        val document = CodeDocument(text)
        val tracker = DiagnosticsTracker(document)
        document.addChangeListener(tracker)
        tracker.replace(listOf(bad, worse))
        return document to tracker
    }

    private fun DiagnosticsTracker.marked(document: CodeDocument): List<String> =
        diagnostics.map { document.text.substring(it.range.first, it.range.last + 1) }

    @Test
    fun `typing above moves the ranges and their lines along`() {
        val (document, tracker) = tracked()
        document.insert(0, "// note\n")
        assertEquals(listOf("bad", "worse"), tracker.marked(document))
        assertEquals(listOf(1, 2), tracker.diagnostics.map { it.lineNumber })
    }

    @Test
    fun `typing after a range leaves it alone`() {
        val (document, tracker) = tracked()
        document.insert(document.length, " // done")
        assertSame(bad, tracker.diagnostics[0])
        assertSame(worse, tracker.diagnostics[1])
    }

    @Test
    fun `typing at a range's start moves it and at its end does not grow it`() {
        val (document, tracker) = tracked()
        document.insert(4, "very")
        assertEquals("bad", tracker.marked(document)[0])
        document.insert(11, "ly")
        assertEquals("bad", tracker.marked(document)[0])
    }

    @Test
    fun `typing inside a range grows it`() {
        val (document, tracker) = tracked()
        document.insert(5, "aa")
        assertEquals("baaad", tracker.marked(document)[0])
    }

    @Test
    fun `deleting part of a range shrinks it`() {
        val (document, tracker) = tracked()
        document.delete(3, 2)
        assertEquals("ad", tracker.marked(document)[0])
    }

    @Test
    fun `a range whose text is deleted keeps one character at the deletion`() {
        val (document, tracker) = tracked()
        document.delete(4, 3)
        assertEquals(4..4, tracker.diagnostics[0].range)
    }

    @Test
    fun `joining lines moves the next line's diagnostic up`() {
        val (document, tracker) = tracked()
        document.delete(11, 1)
        assertEquals("worse", tracker.marked(document)[1])
        assertEquals(0, tracker.diagnostics[1].lineNumber)
    }

    @Test
    fun `replacing the whole text drops them`() {
        val (document, tracker) = tracked()
        document.replaceAll("something else")
        assertTrue(tracker.diagnostics.isEmpty())
    }

    @Test
    fun `the list as given survives the edits`() {
        val (document, tracker) = tracked()
        val given = tracker.given
        document.insert(0, "x")
        assertSame(given, tracker.given)
        assertEquals(listOf(bad, worse), tracker.given)
    }
}
