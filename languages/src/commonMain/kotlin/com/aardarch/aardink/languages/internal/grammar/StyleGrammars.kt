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

// Grammars of the CSS preprocessors, as DeclarativeGrammar JSON, with the language configuration
// they share. They colour as the built-in CSS does: a property is `keyword`, a selector `type`, an
// at-rule and `!important` `annotation`, a value with its unit `number`, a pseudo-class `function`.
// In these raw strings a dollar sign is never followed by a letter or `{`, so nothing is a template.

/** SCSS: CSS with `$` variables, `#{}` interpolation, `//` comments, nesting, `%` placeholders and `@` rules. */
internal const val SCSS_GRAMMAR = """
{
  "tokenPostfix": ".scss",
  "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
  "tokenizer": {
    "root": [
      ["(url)(\\()", ["function", { "token": "delimiter.parenthesis", "next": "@url" }]],
      { "include": "@whitespace" },
      ["#\\{", "delimiter.bracket", "@interpolation"],
      ["\\$[\\w-]+", "variable"],
      ["@[\\w-]+", "annotation"],
      ["!(?:important|default|global|optional)\\b", "annotation"],
      ["([A-Za-z_][\\w-]*)(\\.)([A-Za-z_][\\w-]*)(?=\\()", ["identifier", "delimiter", "function"]],
      { "include": "@common" }
    ],
    "interpolation": [
      ["\\}", "delimiter.bracket", "@pop"],
      { "include": "@root" }
    ],
    "double": [
      ["[^\\\\\"#]+", "string"],
      ["#\\{", "delimiter.bracket", "@interpolation"],
      ["\\\\.", "string.escape"],
      ["[#\\\\]", "string"],
      ["\"", "string", "@pop"]
    ],
    "single": [
      ["[^\\\\'#]+", "string"],
      ["#\\{", "delimiter.bracket", "@interpolation"],
      ["\\\\.", "string.escape"],
      ["[#\\\\]", "string"],
      ["'", "string", "@pop"]
    ],
    "common": [
      ["\"", "string", "@double"],
      ["'", "string", "@single"],
      ["#[0-9A-Fa-f]{3,8}\\b", "number.hex"],
      ["-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:%|[A-Za-z]+)?", "number"],
      ["--[\\w-]+", "type"],
      ["-?[A-Za-z][\\w-]*(?=\\s*:(?![^;{}]*\\{))", "keyword"],
      ["[.#%][A-Za-z_-][\\w-]*", "type"],
      ["::?[A-Za-z-]+", "function"],
      ["-?[A-Za-z_][\\w-]*(?=\\()", "function"],
      ["-?[A-Za-z_][\\w-]*", { "cases": { "@keywords": "keyword", "@default": "identifier" } }],
      ["[{}()\\[\\]]", "@brackets"],
      ["[;,:]", "delimiter"],
      ["==|!=|<=|>=|[-+*/%=<>~&!]", "operator"]
    ],
    "url": [
      ["[^)\"']+", "string"],
      ["\"", "string", "@double"],
      ["'", "string", "@single"],
      ["\\)", "delimiter.parenthesis", "@pop"]
    ],
    "whitespace": [
      ["\\s+", ""],
      ["/\\*", "comment", "@comment"],
      ["//.*$", "comment"]
    ],
    "comment": [
      ["[^*]+", "comment"],
      ["\\*/", "comment", "@pop"],
      ["\\*", "comment"]
    ]
  },
  "keywords": ["and", "or", "not", "from", "through", "to", "in", "if", "else"]
}
"""

/** LESS: CSS with `@` variables (an at-rule's name is not one), `@{}` interpolation, `//` comments, mixins and guards. */
internal const val LESS_GRAMMAR = """
{
  "tokenPostfix": ".less",
  "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
  "atRules": [
    "@media", "@import", "@charset", "@namespace", "@supports", "@document", "@page", "@font-face",
    "@keyframes", "@-webkit-keyframes", "@viewport", "@counter-style", "@font-feature-values", "@layer",
    "@container", "@property", "@plugin"
  ],
  "tokenizer": {
    "root": [
      ["(url)(\\()", ["function", { "token": "delimiter.parenthesis", "next": "@url" }]],
      { "include": "@whitespace" },
      ["@\\{[\\w-]+\\}|@@[\\w-]+", "variable"],
      ["@[\\w-]+", { "cases": { "@atRules": "annotation", "@default": "variable" } }],
      ["!important\\b", "annotation"],
      { "include": "@common" }
    ],
    "double": [
      ["[^\\\\\"@]+", "string"],
      ["@\\{[\\w-]+\\}", "variable"],
      ["\\\\.", "string.escape"],
      ["[@\\\\]", "string"],
      ["\"", "string", "@pop"]
    ],
    "single": [
      ["[^\\\\'@]+", "string"],
      ["@\\{[\\w-]+\\}", "variable"],
      ["\\\\.", "string.escape"],
      ["[@\\\\]", "string"],
      ["'", "string", "@pop"]
    ],
    "common": [
      ["\"", "string", "@double"],
      ["'", "string", "@single"],
      ["#[0-9A-Fa-f]{3,8}\\b", "number.hex"],
      ["-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:%|[A-Za-z]+)?", "number"],
      ["--[\\w-]+", "type"],
      ["-?[A-Za-z][\\w-]*(?=\\s*:(?![^;{}]*\\{))", "keyword"],
      ["[.#][A-Za-z_-][\\w-]*", "type"],
      ["::?[A-Za-z-]+", "function"],
      ["-?[A-Za-z_][\\w-]*(?=\\()", "function"],
      ["-?[A-Za-z_][\\w-]*", { "cases": { "@keywords": "keyword", "@default": "identifier" } }],
      ["[{}()\\[\\]]", "@brackets"],
      ["[;,:]", "delimiter"],
      ["==|=<|>=|[-+*/%=<>~&!]", "operator"]
    ],
    "url": [
      ["[^)\"']+", "string"],
      ["\"", "string", "@double"],
      ["'", "string", "@single"],
      ["\\)", "delimiter.parenthesis", "@pop"]
    ],
    "whitespace": [
      ["\\s+", ""],
      ["/\\*", "comment", "@comment"],
      ["//.*$", "comment"]
    ],
    "comment": [
      ["[^*]+", "comment"],
      ["\\*/", "comment", "@pop"],
      ["\\*", "comment"]
    ]
  },
  "keywords": ["when", "and", "not"]
}
"""

/** What SCSS and LESS say about typing: CSS's pairs, and comments of both kinds. */
internal const val STYLE_CONFIGURATION = """
{
  "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [
    ["{", "}"], ["[", "]"], ["(", ")"],
    { "open": "\"", "close": "\"", "notIn": ["string", "comment"] },
    { "open": "'", "close": "'", "notIn": ["string", "comment"] }
  ],
  "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"]],
  "folding": { "markers": { "start": "^\\s*(?://|/\\*)\\s*#?region\\b", "end": "^\\s*(?://|/\\*)\\s*#?endregion\\b" } }
}
"""
