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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What [EditorOptions] takes. */
class EditorOptionsTest {

    @Test
    fun `the hover delays default to 500 and 300 ms`() {
        assertEquals(500L, EditorOptions().hoverDelayMs)
        assertEquals(300L, EditorOptions().hoverHidingDelayMs)
    }

    @Test
    fun `a negative hover delay is refused`() {
        assertFailsWith<IllegalArgumentException> { EditorOptions(hoverDelayMs = -1) }
        assertFailsWith<IllegalArgumentException> { EditorOptions(hoverHidingDelayMs = -1) }
    }
}
