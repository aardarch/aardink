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
package com.aardarch.aardink.languages.internal.grammar

// Language configurations for the built-in languages whose tokenizers are written in Kotlin:
// what typing does in them, as VS Code's configuration for each says.
// In these raw strings a dollar sign is never followed by a letter or `{`, so nothing is a template.

/** TypeScript and JavaScript: C's pairs with template literals, JSDoc lines, and Monaco's indentation rules. */
internal const val TYPESCRIPT_CONFIGURATION = """
{
  "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [
    { "open": "{", "close": "}", "notIn": ["string"] },
    { "open": "[", "close": "]", "notIn": ["string"] },
    { "open": "(", "close": ")", "notIn": ["string"] },
    { "open": "\"", "close": "\"", "notIn": ["string"] },
    { "open": "'", "close": "'", "notIn": ["string", "comment"] },
    { "open": "`", "close": "`", "notIn": ["string", "comment"] },
    { "open": "/**", "close": " */", "notIn": ["string"] }
  ],
  "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"], ["`", "`"], ["<", ">"]],
  "folding": { "markers": { "start": "^\\s*//\\s*#?region\\b", "end": "^\\s*//\\s*#?endregion\\b" } },
  "indentationRules": {
    "decreaseIndentPattern": "^((?!.*?/\\*).*\\*/)?\\s*[\\}\\]].*$",
    "increaseIndentPattern": "^((?!//).)*(\\{([^}\"'`/]*|(\\t|[ ])*//.*)|\\([^)\"'`/]*|\\[[^\\]\"'`/]*)$",
    "unIndentedLinePattern": "^(\\t|[ ])*[ ]\\*[^/]*\\*/\\s*$|^(\\t|[ ])*[ ]\\*/\\s*$|^(\\t|[ ])*[ ]\\*([ ]([^\\*]|\\*(?!/))*)?$"
  },
  "onEnterRules": [
    { "beforeText": "^\\s*/\\*\\*(?!/)([^*]|\\*(?!/))*$", "afterText": "^\\s*\\*/$", "action": { "indent": "indentOutdent", "appendText": " * " } },
    { "beforeText": "^\\s*/\\*\\*(?!/)([^*]|\\*(?!/))*$", "action": { "indent": "none", "appendText": " * " } },
    { "beforeText": "^(\\t|[ ])*[ ]\\*([ ]([^*]|\\*(?!/))*)?$", "action": { "indent": "none", "appendText": "* " } },
    { "beforeText": "^(\\t|[ ])*[ ]\\*/\\s*$", "action": { "indent": "none", "removeText": 1 } }
  ]
}
"""

/** CSS: its brackets and quotes, and only block comments. */
internal const val CSS_CONFIGURATION = """
{
  "comments": { "blockComment": ["/*", "*/"] },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [
    { "open": "{", "close": "}", "notIn": ["string", "comment"] },
    { "open": "[", "close": "]", "notIn": ["string", "comment"] },
    { "open": "(", "close": ")", "notIn": ["string", "comment"] },
    { "open": "\"", "close": "\"", "notIn": ["string", "comment"] },
    { "open": "'", "close": "'", "notIn": ["string", "comment"] }
  ],
  "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"]],
  "folding": { "markers": { "start": "^\\s*/\\*\\s*#region\\b\\s*(.*?)\\s*\\*/", "end": "^\\s*/\\*\\s*#endregion\\b.*\\*/" } }
}
"""

/**
 * Markdown: brackets, and the marks a selection can be wrapped in. A list item or a quote carries
 * on to the next line when Enter ends a line of one that has text; `<` is not closed, as it is
 * mostly "less than".
 */
internal const val MARKDOWN_CONFIGURATION = """
{
  "comments": { "blockComment": ["<!--", "-->"] },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [["{", "}"], ["[", "]"], ["(", ")"]],
  "surroundingPairs": [
    ["(", ")"], ["[", "]"], ["`", "`"], ["_", "_"], ["*", "*"], ["{", "}"], ["'", "'"], ["\"", "\""],
    ["<", ">"], ["~", "~"]
  ],
  "folding": { "markers": { "start": "^\\s*<!--\\s*#?region\\b.*-->", "end": "^\\s*<!--\\s*#?endregion\\b.*-->" } },
  "onEnterRules": [
    { "beforeText": "^\\s*- \\[[ xX]\\]\\s+\\S.*$", "action": { "indent": "none", "appendText": "- [ ] " } },
    { "beforeText": "^\\s*-\\s+\\S.*$", "action": { "indent": "none", "appendText": "- " } },
    { "beforeText": "^\\s*\\*\\s+\\S.*$", "action": { "indent": "none", "appendText": "* " } },
    { "beforeText": "^\\s*\\+\\s+\\S.*$", "action": { "indent": "none", "appendText": "+ " } },
    { "beforeText": "^\\s*>\\s?\\S.*$", "action": { "indent": "none", "appendText": "> " } }
  ]
}
"""
