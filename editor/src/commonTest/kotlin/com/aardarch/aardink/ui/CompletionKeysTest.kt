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

import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionKeysTest {

    private fun item(label: String, kind: CompletionKind = CompletionKind.Value) = CompletionItem(label, kind, label)

    @Test
    fun `keys are kind and label, numbered when they repeat`() {
        val keys = completionKeys(
            listOf(item("true"), item("false"), item("true"), item("true", CompletionKind.Snippet), item("true")),
        )

        assertEquals(listOf("Value:true", "Value:false", "Value:true#1", "Snippet:true", "Value:true#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }
}
