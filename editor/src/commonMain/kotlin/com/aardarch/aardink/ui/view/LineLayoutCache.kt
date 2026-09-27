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
package com.aardarch.aardink.ui.view

/**
 * Laid-out lines ([T] is a `TextLayoutResult`), keyed by what they show rather than by line
 * number, so a line that moved (an edit above it added lines) keeps its layout. Only the colour
 * spans go into a layout; selections, find matches, squiggles and carets are drawn over it as
 * rectangles, so none of them costs a relayout.
 *
 * Least-recently-used, approximately: two generations of [capacity] entries each. A hit in the
 * older one moves the entry to the newer; when the newer fills up it becomes the older one and the
 * oldest entries go. Everything else that shapes a layout (theme, typography, density, wrap width,
 * font loading) is outside the key: the owner [clear]s the cache when any of it changes.
 */
internal class LineLayoutCache<T : Any>(private val capacity: Int = DEFAULT_CAPACITY) {

    /**
     * What a line shows: its [text], its token runs (compared by identity: the token store keeps a
     * line's runs object until they change) and anything else drawn into the layout, such as a
     * fold placeholder or bracket colours, in [decoration].
     */
    class Key(val text: String, val tokens: Any?, val decoration: Any?) {
        private val hash = (text.hashCode() * 31 + (tokens?.hashCode() ?: 0)) * 31 + (decoration?.hashCode() ?: 0)

        override fun equals(other: Any?): Boolean =
            other is Key && other.hash == hash && other.tokens === tokens && other.text == text && other.decoration == decoration

        override fun hashCode(): Int = hash
    }

    private var current = HashMap<Key, T>()
    private var previous = HashMap<Key, T>()

    val size: Int get() = current.size + previous.size

    fun getOrPut(key: Key, measure: () -> T): T {
        current[key]?.let { return it }
        val layout = previous.remove(key) ?: measure()
        if (current.size >= capacity) {
            previous = current
            current = HashMap()
        }
        current[key] = layout
        return layout
    }

    fun clear() {
        current = HashMap()
        previous = HashMap()
    }

    companion object {
        /** Enough for a few screens of lines on a tall display, twice over. */
        const val DEFAULT_CAPACITY = 256
    }
}
