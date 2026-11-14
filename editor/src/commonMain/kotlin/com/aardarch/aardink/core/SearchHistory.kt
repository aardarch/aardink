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

/**
 * A find field's history: what was entered, newest first, each once, at most [MAX] of them. Up
 * and Down in the field step through it ([older] and [newer]); stepping back past the newest
 * gives back what was being typed before the first step.
 */
internal class SearchHistory {
    private val entries = ArrayList<String>()

    /** Where the field is in [entries]: -1 for what is being typed. */
    private var position = -1
    private var draft = ""

    val items: List<String> get() = entries

    /** Keeps [text] as the newest entry, and goes back to what is being typed. */
    fun add(text: String) {
        position = -1
        if (text.isEmpty()) return
        entries.remove(text)
        entries.add(0, text)
        if (entries.size > MAX) entries.removeAt(entries.lastIndex)
    }

    /** The entry before [current], or null at the oldest. */
    fun older(current: String): String? {
        if (position + 1 >= entries.size) return null
        if (position == -1) draft = current
        // Skip an entry that is what the field holds already.
        var next = position + 1
        if (position == -1 && entries[next] == current) next++
        if (next >= entries.size) return null
        position = next
        return entries[position]
    }

    /** The entry after the one shown, or what was being typed; null when that shows already. */
    fun newer(): String? {
        if (position == -1) return null
        position--
        return if (position == -1) draft else entries[position]
    }

    companion object {
        /** VS Code's find widget keeps 50. */
        const val MAX = 50
    }
}
