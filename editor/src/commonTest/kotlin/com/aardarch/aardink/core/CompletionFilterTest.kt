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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionFilterTest {

    private fun item(label: String, priority: Int = 0, filterText: String = label) =
        CompletionItem(label, CompletionKind.Value, label, filterText = filterText, sortPriority = priority)

    @Test
    fun `matching is in order, ignoring case, from a word start`() {
        assertTrue(CompletionFilter.matches("gv", "getValue"))
        assertTrue(CompletionFilter.matches("val", "getValue"))
        assertTrue(CompletionFilter.matches("SV", "set_value"))
        assertFalse(CompletionFilter.matches("etv", "getValue"), "not from a word start")
        assertFalse(CompletionFilter.matches("vg", "getValue"), "out of order")
        assertTrue(CompletionFilter.matches("", "anything"))
    }

    @Test
    fun `without filtering only the priority orders, stably`() {
        val items = listOf(item("b", 1), item("a", 0), item("c", 1), item("d", 0))
        assertEquals(listOf("a", "d", "b", "c"), CompletionFilter.arrange(items, filter = false) { "zzz" }.map { it.label })
    }

    @Test
    fun `filtering keeps the matches, prefixes first within a priority`() {
        val items = listOf(item("setValue"), item("value"), item("other"), item("valueOf", priority = 1))
        val arranged = CompletionFilter.arrange(items, filter = true) { "val" }
        assertEquals(listOf("value", "setValue", "valueOf"), arranged.map { it.label })
    }

    @Test
    fun `filtering reads filterText, not the label`() {
        val items = listOf(item("Display name", filterText = "key"))
        assertEquals(1, CompletionFilter.arrange(items, filter = true) { "ke" }.size)
        assertEquals(0, CompletionFilter.arrange(items, filter = true) { "dis" }.size)
    }
}
