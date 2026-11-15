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

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.FoldRange
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.languages.internal.grammar.C_LIKE_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.DIFF_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.DIFF_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.DOCKERFILE_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.DOCKERFILE_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.GROOVY_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.INI_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.INI_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.JAVA_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.LESS_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.PROPERTIES_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.PROPERTIES_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.PYTHON_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.PYTHON_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.SCSS_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.SHELL_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.SHELL_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.SQL_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.SQL_GRAMMAR
import com.aardarch.aardink.languages.internal.grammar.STYLE_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.YAML_CONFIGURATION
import com.aardarch.aardink.languages.internal.grammar.YAML_GRAMMAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.10's grammar-as-data languages: each parses, and colours what it should. */
class GrammarLanguagesTest {

    /** The type scope of each token of [text] in [language], by its text. */
    private fun scopes(language: LanguageDefinition, text: String): List<Pair<String, String>> =
        language.tokenizer.tokenizeFull(text).map { text.substring(it.start, it.end) to it.type.scope }

    private fun assertScope(language: LanguageDefinition, text: String, token: String, scope: String) {
        val all = scopes(language, text)
        val found = all.filter { it.first == token }
        assertTrue(found.isNotEmpty(), "'$token' is a token of its own in ${language.id}: $all")
        assertTrue(found.all { it.second.startsWith(scope) }, "'$token' is $scope in ${language.id}: $all")
    }

    @Test
    fun `every grammar and configuration parses`() {
        for (grammar in listOf(
            YAML_GRAMMAR, PROPERTIES_GRAMMAR, INI_GRAMMAR, SHELL_GRAMMAR, PYTHON_GRAMMAR, SQL_GRAMMAR, JAVA_GRAMMAR,
            GROOVY_GRAMMAR, DOCKERFILE_GRAMMAR, DIFF_GRAMMAR, SCSS_GRAMMAR, LESS_GRAMMAR,
        )) {
            DeclarativeGrammar.parse(grammar)
        }
        for (configuration in listOf(
            YAML_CONFIGURATION,
            PROPERTIES_CONFIGURATION,
            INI_CONFIGURATION,
            SHELL_CONFIGURATION,
            PYTHON_CONFIGURATION,
            SQL_CONFIGURATION,
            C_LIKE_CONFIGURATION,
            DOCKERFILE_CONFIGURATION,
            DIFF_CONFIGURATION,
            STYLE_CONFIGURATION,
        )) {
            LanguageConfiguration.parse(configuration)
        }
    }

    @Test
    fun `the new languages are built in, by id and extension`() {
        val registry = LanguageRegistry.withBuiltIns()
        for ((id, extension) in listOf(
            "yaml" to "yml",
            "properties" to "properties",
            "ini" to "ini",
            "shell" to "sh",
            "python" to "py",
            "sql" to "sql",
            "java" to "java",
            "groovy" to "gradle",
            "dockerfile" to "Dockerfile",
            "diff" to "patch",
            "scss" to "scss",
            "less" to "less",
        )) {
            assertEquals(id, registry.byId(id)?.id)
            assertEquals(id, registry.byExtension(extension)?.id, extension)
        }
    }

    @Test
    fun yaml() {
        val text = "# settings\nname: \"aardink\"\nversion: 1.5\nenabled: true\nlist:\n  - &a plain value\n  - *a"
        val yaml = BuiltInLanguages.Yaml
        assertScope(yaml, text, "# settings", "comment")
        assertScope(yaml, text, "name", "type")
        assertScope(yaml, text, "\"aardink\"", "string")
        assertScope(yaml, text, "1.5", "number")
        assertScope(yaml, text, "true", "keyword")
        assertScope(yaml, text, "plain value", "string")
        assertScope(yaml, text, "&a", "tag")
    }

    @Test
    fun properties() {
        val text = "# comment\napp.name = Aardink\nlong.value = first \\\n  second\nnext=1"
        val properties = BuiltInLanguages.Properties
        assertScope(properties, text, "# comment", "comment")
        assertScope(properties, text, "app.name", "attribute.name")
        assertScope(properties, text, "Aardink", "string")
        assertScope(properties, text, "  second", "string")
        assertScope(properties, text, "next", "attribute.name")
    }

    @Test
    fun ini() {
        val text = "; comment\n[section]\nkey = \"value\"\ncount = 3"
        val ini = BuiltInLanguages.Ini
        assertScope(ini, text, "; comment", "comment")
        assertScope(ini, text, "section", "metatag")
        assertScope(ini, text, "key", "attribute.name")
        assertScope(ini, text, " \"value\"", "string")
        assertScope(ini, text, " 3", "number")
    }

    @Test
    fun shell() {
        val text = "#!/bin/bash\n# note\nif [ -f \"\$HOME/x\" ]; then\n  echo \${name} 'raw' \$1\nfi"
        val shell = BuiltInLanguages.Shell
        assertScope(shell, text, "#!/bin/bash", "metatag")
        assertScope(shell, text, "# note", "comment")
        assertScope(shell, text, "if", "keyword")
        assertScope(shell, text, "echo", "predefined")
        assertScope(shell, text, "\$HOME", "variable")
        assertScope(shell, text, "-f", "attribute.name")
        assertScope(shell, text, "'raw'", "string")
        assertScope(shell, text, "\$1", "variable")
    }

    @Test
    fun python() {
        val text = "@dataclass\nclass Point:\n    def norm(self) -> float:\n" +
            "        \"\"\"Length.\"\"\"\n        return abs(0x1F) # done\nprint(f'{x}')"
        val python = BuiltInLanguages.Python
        assertScope(python, text, "@dataclass", "annotation")
        assertScope(python, text, "Point", "type")
        assertScope(python, text, "norm", "function")
        assertScope(python, text, "self", "predefined")
        assertScope(python, text, "\"\"\"Length.\"\"\"", "string")
        assertScope(python, text, "return", "keyword")
        assertScope(python, text, "abs", "predefined")
        assertScope(python, text, "0x1F", "number")
        assertScope(python, text, "# done", "comment")
    }

    @Test
    fun `a Python string left open ends with its line`() {
        val text = "x = 'open\ny = 1"
        assertScope(BuiltInLanguages.Python, text, "y", "identifier")
    }

    @Test
    fun sql() {
        val text = "-- users\nSELECT name, COUNT(*) FROM users WHERE age > 18 AND note = 'it''s' /* c */;"
        val sql = BuiltInLanguages.Sql
        assertScope(sql, text, "-- users", "comment")
        assertScope(sql, text, "SELECT", "keyword")
        assertScope(sql, text, "COUNT", "predefined")
        assertScope(sql, text, "18", "number")
        assertScope(sql, text, "''", "string.escape")
        assertScope(sql, text, "/* c */", "comment")
        assertScope(sql, text, "users", "identifier")
    }

    @Test
    fun java() {
        val text = "/** Docs @param x */\n@Override\npublic String name(int x) {\n  return \"a\\n\" + 1L; // c\n}"
        val java = BuiltInLanguages.Java
        assertScope(java, text, "@param", "comment.doc")
        assertScope(java, text, "@Override", "annotation")
        assertScope(java, text, "public", "keyword")
        assertScope(java, text, "String", "type")
        assertScope(java, text, "name", "function")
        assertScope(java, text, "\\n", "string.escape")
        assertScope(java, text, "1L", "number")
        assertScope(java, text, "// c", "comment")
    }

    @Test
    fun groovy() {
        val text = "plugins { id 'java' }\ndef greet(name) {\n  println \"Hi \${name.trim()} and \$who\"\n" +
            "  def pattern = ~/a\\d+/\n  return 1..10\n}"
        val groovy = BuiltInLanguages.Groovy
        assertScope(groovy, text, "'java'", "string")
        assertScope(groovy, text, "def", "keyword")
        assertScope(groovy, text, "greet", "function")
        assertScope(groovy, text, "println", "identifier")
        assertScope(groovy, text, "\${", "delimiter.bracket")
        assertScope(groovy, text, "trim", "function")
        assertScope(groovy, text, "\$who", "variable")
        assertScope(groovy, text, "/a", "regexp")
        assertScope(groovy, text, "..", "operator")
        assertScope(groovy, text, "10", "number")
    }

    @Test
    fun `a Groovy interpolation ends at its own brace`() {
        val text = "\"\${list.collect { it * 2 }} done\"\nx"
        assertScope(BuiltInLanguages.Groovy, text, " done\"", "string")
        assertScope(BuiltInLanguages.Groovy, text, "x", "identifier")
    }

    @Test
    fun dockerfile() {
        val text = "# syntax=docker/dockerfile:1\nFROM node:20  AS build\n# step\nENV PORT=8080\n" +
            "COPY --from=build /app \"\$HOME/app\"\nRUN npm ci && \\\n    npm run build\nEXPOSE 8080/tcp"
        val docker = BuiltInLanguages.Dockerfile
        assertScope(docker, text, "# syntax=docker/dockerfile:1", "metatag")
        assertScope(docker, text, "FROM", "keyword")
        assertScope(docker, text, "AS", "keyword")
        assertScope(docker, text, "build", "type")
        assertScope(docker, text, "# step", "comment")
        assertScope(docker, text, "PORT", "variable")
        assertScope(docker, text, "--from", "attribute.name")
        assertScope(docker, text, "\$HOME", "variable")
        assertScope(docker, text, "&&", "operator")
        assertTrue(scopes(docker, text).none { it.first == "run" }, "a continued line's words are not instructions")
        assertScope(docker, text, "8080/tcp", "number")
    }

    @Test
    fun diff() {
        val text = "diff --git a/x.txt b/x.txt\nindex 1..2 100644\n--- a/x.txt\n+++ b/x.txt\n" +
            "@@ -1,3 +1,3 @@ fun main\n keep\n--- removed\n+added\n\\ No newline at end of file\ndiff --git a/y b/y"
        val diff = BuiltInLanguages.Diff
        assertScope(diff, text, "diff --git a/x.txt b/x.txt", "keyword")
        assertScope(diff, text, "index 1..2 100644", "comment")
        assertScope(diff, text, "--- a/x.txt", "type")
        assertScope(diff, text, "+++ b/x.txt", "type")
        assertScope(diff, text, "@@ -1,3 +1,3 @@", "metatag")
        assertScope(diff, text, " fun main", "function")
        assertScope(diff, text, "--- removed", "invalid.deleted")
        assertScope(diff, text, "+added", "string.inserted")
        assertScope(diff, text, "\\ No newline at end of file", "comment")
        assertScope(diff, text, "diff --git a/y b/y", "keyword")
    }

    @Test
    fun `a diff folds by file and by hunk, counting the hunk's lines`() {
        val text = listOf(
            "diff --git a/x b/x", // 0
            "--- a/x",
            "+++ b/x",
            "@@ -1,2 +1,2 @@", // 3
            " same",
            "--- gone",
            "+++ new", // 6: still the hunk, by its counts
            "@@ -9 +9,2 @@", // 7
            "-old",
            "+new",
            "+more", // 10
            "diff --git a/y b/y", // 11
            "--- a/y",
            "+++ b/y",
            "@@ -1 +1 @@", // 14
            "-a",
            "+b",
        ).joinToString("\n")
        val ranges = BuiltInLanguages.Diff.foldingProvider.foldableRanges(CodeDocument(text))
        assertEquals(
            listOf(FoldRange(0, 10), FoldRange(3, 6), FoldRange(7, 10), FoldRange(11, 16), FoldRange(14, 16)),
            ranges.sortedWith(compareBy({ it.startLine }, { -it.endLine })),
        )
    }

    @Test
    fun scss() {
        val text = "@use 'sass:math';\n\$gap: 8px !default;\n// note\n.card {\n  color: #ff0000;\n" +
            "  &:hover { margin: math.div(\$gap, 2); }\n  #{\$side}-x: 1em;\n  a:hover { b: 0 }\n}"
        val scss = BuiltInLanguages.Scss
        assertScope(scss, text, "@use", "annotation")
        assertScope(scss, text, "\$gap", "variable")
        assertScope(scss, text, "8px", "number")
        assertScope(scss, text, "!default", "annotation")
        assertScope(scss, text, "// note", "comment")
        assertScope(scss, text, ".card", "type")
        assertScope(scss, text, "color", "keyword")
        assertScope(scss, text, "#ff0000", "number")
        assertScope(scss, text, ":hover", "function")
        assertScope(scss, text, "#{", "delimiter.bracket")
        assertScope(scss, text, "a", "identifier")
        assertScope(scss, text, "div", "function")
    }

    @Test
    fun less() {
        val text = "@import \"base\";\n@width: 10px;\n.box { width: @width; .mixin(); }\n" +
            ".m() when (iscolor(@c)) { color: ~\"@{name}\"; }\n@media screen { a { b: 1 } }"
        val less = BuiltInLanguages.Less
        assertScope(less, text, "@import", "annotation")
        assertScope(less, text, "@width", "variable")
        assertScope(less, text, "10px", "number")
        assertScope(less, text, ".box", "type")
        assertScope(less, text, "width", "keyword")
        assertScope(less, text, ".mixin", "type")
        assertScope(less, text, "when", "keyword")
        assertScope(less, text, "iscolor", "function")
        assertScope(less, text, "@{name}", "variable")
        assertScope(less, text, "@media", "annotation")
    }

    @Test
    fun `SCSS and LESS show CSS's colours, and a diff closes nothing`() = kotlinx.coroutines.test.runTest {
        val document = CodeDocument("a { color: #00ff00; }")
        assertEquals(1, BuiltInLanguages.Scss.languageService!!.documentColors(document).size)
        assertEquals(1, BuiltInLanguages.Less.languageService!!.documentColors(document).size)
        assertNull(BuiltInLanguages.Diff.languageService!!.autoClose(CodeDocument("("), 0, '('))
    }

    @Test
    fun `typing follows each language's configuration`() {
        val options = FormattingOptions(tabSize = 4, insertSpaces = true)
        val python = BuiltInLanguages.Python.languageService!!
        assertEquals("    ", python.smartIndent(CodeDocument("def f():\n"), 1, options))
        val yaml = BuiltInLanguages.Yaml.languageService!!
        assertEquals("    ", yaml.smartIndent(CodeDocument("key:\n"), 1, options))
        val java = BuiltInLanguages.Java.languageService!!
        assertEquals("}", java.autoClose(CodeDocument("{"), 0, '{'))
        assertEquals(" * ", java.smartIndent(CodeDocument("/**\n"), 1, options))
        assertEquals(")", BuiltInLanguages.Sql.languageService!!.autoClose(CodeDocument("("), 0, '('))
    }
}
