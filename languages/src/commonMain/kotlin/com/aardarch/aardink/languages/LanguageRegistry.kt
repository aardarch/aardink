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
package com.aardarch.aardink.languages

/**
 * Mutable, ordered map of [LanguageDefinition] values keyed by [LanguageDefinition.id].
 *
 * Typical use:
 * ```
 * val registry = LanguageRegistry.withBuiltIns().apply {
 *     override("kotlin") { it.copy(tokenizer = MyKotlinTokenizer) }
 *     register(myCustomLanguageDefinition)
 * }
 * val def = registry.byFileName("src/Main.kt")
 * ```
 *
 * Iteration order is insertion order (built-ins first, then anything the consumer added).
 */
class LanguageRegistry {

    private val definitions = LinkedHashMap<String, LanguageDefinition>()

    /** All registered languages, in insertion order. */
    val all: List<LanguageDefinition> get() = definitions.values.toList()

    /** Adds (or replaces) a definition. Returns this registry for chaining. */
    fun register(def: LanguageDefinition): LanguageRegistry {
        definitions[def.id] = def
        return this
    }

    /**
     * Replaces an existing definition with the result of [transform]. Throws if [id] is unknown —
     * use [register] to add a new language.
     */
    fun override(id: String, transform: (LanguageDefinition) -> LanguageDefinition): LanguageRegistry {
        val existing = definitions[id]
            ?: error("LanguageRegistry has no language with id '$id' to override")
        definitions[id] = transform(existing)
        return this
    }

    /** Removes the definition with [id]. Returns true if something was removed. */
    fun unregister(id: String): Boolean = definitions.remove(id) != null

    fun byId(id: String): LanguageDefinition? = definitions[id]

    /**
     * Looks up a language by file extension. Accepts either `"kt"` or `".kt"` (case-insensitive).
     * Returns the first registered definition that lists the extension; null if none match.
     */
    fun byExtension(ext: String): LanguageDefinition? {
        val normalized = ext.removePrefix(".").lowercase()
        if (normalized.isEmpty()) return null
        return definitions.values.firstOrNull { def ->
            def.fileExtensions.any { it.equals(normalized, ignoreCase = true) }
        }
    }

    /**
     * Looks up a language by a file's name or path: the first registered definition whose
     * [LanguageDefinition.fileNames] match the name, else [byExtension] of what follows its last
     * dot. `"Dockerfile"`, `"docker/Dockerfile.dev"`, `"~/.bashrc"` and `"src\\Main.kt"` each find
     * theirs. Returns null if none matches.
     */
    fun byFileName(fileName: String): LanguageDefinition? {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (name.isEmpty()) return null
        definitions.values.firstOrNull { def -> def.fileNames.any { fileNameMatches(it, name) } }?.let { return it }
        val dot = name.lastIndexOf('.')
        return if (dot < 0) null else byExtension(name.substring(dot + 1))
    }

    companion object {
        /** Builds a registry pre-populated with every entry in [BuiltInLanguages.all]. */
        fun withBuiltIns(): LanguageRegistry = LanguageRegistry().apply {
            BuiltInLanguages.all.forEach(::register)
        }
    }
}

/** Whether [name] is [pattern], without regard to case, where a `*` in [pattern] stands for any run of characters. */
internal fun fileNameMatches(pattern: String, name: String): Boolean {
    var p = 0
    var n = 0
    // The last `*` seen, and where in [name] its run would end if it took one more character.
    var star = -1
    var resume = 0
    while (n < name.length) {
        when {
            p < pattern.length && pattern[p] == '*' -> {
                star = p++
                resume = n
            }

            p < pattern.length && pattern[p].equals(name[n], ignoreCase = true) -> {
                p++
                n++
            }

            star >= 0 -> {
                p = star + 1
                n = ++resume
            }

            else -> return false
        }
    }
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}
