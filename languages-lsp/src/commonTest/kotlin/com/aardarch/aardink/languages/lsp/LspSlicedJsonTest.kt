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

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class LspSlicedJsonTest {

    private val corpus = listOf(
        """{}""",
        """[]""",
        """ { "a" : [ 1 , 2.5 , -3e2 , 0 ] , "b" : null , "c" : true , "d" : false } """,
        """{"jsonrpc":"2.0","id":7,"result":{"resultId":"3","data":[0,4,5,1,0,1,2,3,0,2]}}""",
        """{"s":"plain","e":"q\"b\\s\/n\nt\tr\rb\bf\fué😀"}""",
        """{"nested":{"deeper":{"deepest":[[[[{"x":[]}]]]]}},"after":"x"}""",
        """{"dup":1,"dup":2}""",
        """[1,"two",{"three":3},[4],null,true]""",
        "\t\n\r [ \"ws\" ] \n",
    )

    @Test
    fun `it gives the tree parseToJsonElement gives`() = runTest {
        for (text in corpus) assertEquals(LspJson.parseToJsonElement(text), parseJsonInSlices(text), text)
    }

    @Test
    fun `a long answer parses in slices to the same tree`() = runTest {
        val data = (0 until 60_000).joinToString(",") { (it % 97).toString() }
        val text = """{"jsonrpc":"2.0","id":1,"result":{"resultId":"9","data":[$data]}}"""
        var turns = 0
        val parsed = parseJsonInSlices(text, CountingPacer { turns++ })
        assertEquals(LspJson.parseToJsonElement(text), parsed)
        assertEquals(60_000, ((parsed as JsonObject)["result"] as JsonObject)["data"].let { (it as JsonArray).size })
        assertEquals(59_999 % 97, (parsed["result"] as JsonObject)["data"].let { (it as JsonArray)[59_999].jsonPrimitive.int })
        assertEquals(true, turns > 1, "the pacer had a turn while it parsed")
    }

    @Test
    fun `nesting too deep for slices still parses`() = runTest {
        val text = "[".repeat(300) + "1" + "]".repeat(300)
        assertEquals(LspJson.parseToJsonElement(text), parseJsonInSlices(text))
    }

    @Test
    fun `what is not JSON throws as parseToJsonElement does`() = runTest {
        for (text in listOf("", "{", "[1,", "\"abc", """{"a" 1}""", """{"a":1,}x""", """[1] 2""", """{"a":"\x"}""", """{1:2}""")) {
            assertFailsWith<SerializationException>(text) { parseJsonInSlices(text) }
        }
    }

    /** A pacer that counts its turns and never waits. */
    private class CountingPacer(private val onTurn: () -> Unit) : LspDecodePacer(Duration.ZERO) {
        override suspend fun check() = onTurn()
    }
}
