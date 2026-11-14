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

// Grammars of programming languages, as DeclarativeGrammar JSON, with their language
// configurations. Token names are Monaco's, which the themes colour: `keyword`, `type`, `function`
// (a name called or defined), `predefined` (a built-in), `variable`, `annotation`, `string`,
// `string.escape`, `number`, `comment`, `comment.doc`, `operator`, `delimiter`.
// In these raw strings a dollar sign is never followed by a letter or `{`, so nothing is a template.

/** POSIX shell and Bash: keywords, built-ins, variables, the three quotes, options and operators. */
internal const val SHELL_GRAMMAR = """
{
  "tokenPostfix": ".shell",
  "comments": { "lineComment": "#" },
  "keywords": [
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until", "do", "done",
    "in", "function", "time", "coproc", "return", "exit", "break", "continue", "local", "export",
    "readonly", "declare", "typeset", "unset", "source"
  ],
  "builtins": [
    "echo", "printf", "read", "cd", "pwd", "test", "set", "shift", "trap", "eval", "exec", "alias",
    "unalias", "true", "false", "wait", "kill", "jobs", "fg", "bg", "umask", "getopts", "let", "mapfile",
    "readarray", "pushd", "popd", "dirs", "shopt", "command", "builtin", "type", "hash", "ulimit"
  ],
  "tokenizer": {
    "root": [
      ["^#!.*$", "metatag"],
      ["(^|\\s)(#.*)$", ["", "comment"]],
      ["\\s+", ""],
      ["\\$\\{", "variable", "@braced"],
      ["\\$\\(\\(?", "delimiter"],
      ["\\$[A-Za-z_]\\w*|\\$[0-9#?!@*$-]", "variable"],
      ["\"", "string", "@doubleQuoted"],
      ["\\$'", "string", "@ansiQuoted"],
      ["'", "string", "@singleQuoted"],
      ["`", "string", "@backtick"],
      ["\\b\\d+\\b", "number"],
      ["--?[A-Za-z][\\w-]*", "attribute.name"],
      ["[A-Za-z_][\\w-]*(?==)", "variable"],
      ["[A-Za-z_][\\w.-]*", { "cases": { "@keywords": "keyword", "@builtins": "predefined", "@default": "" } }],
      ["&&|\\|\\||;;|[|&;<>]+", "operator"],
      ["[{}()\\[\\]]", "@brackets"]
    ],
    "braced": [
      ["[^}]+", "variable"],
      ["\\}", "variable", "@pop"]
    ],
    "doubleQuoted": [
      ["[^\"\\\\$`]+", "string"],
      ["\\\\.", "string.escape"],
      ["\\$\\{", "variable", "@braced"],
      ["\\$[A-Za-z_]\\w*|\\$[0-9#?!@*$-]", "variable"],
      ["[$`]", "string"],
      ["\"", "string", "@pop"]
    ],
    "ansiQuoted": [
      ["[^'\\\\]+", "string"],
      ["\\\\.", "string.escape"],
      ["'", "string", "@pop"]
    ],
    "singleQuoted": [
      ["[^']+", "string"],
      ["'", "string", "@pop"]
    ],
    "backtick": [
      ["[^`\\\\]+", "string"],
      ["\\\\.", "string.escape"],
      ["`", "string", "@pop"]
    ]
  }
}
"""

internal const val SHELL_CONFIGURATION = """
{
  "comments": { "lineComment": "#" },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [
    ["{", "}"], ["[", "]"], ["(", ")"],
    { "open": "\"", "close": "\"", "notIn": ["string"] },
    { "open": "'", "close": "'", "notIn": ["string", "comment"] },
    { "open": "`", "close": "`", "notIn": ["string", "comment"] }
  ],
  "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"], ["`", "`"]],
  "folding": { "markers": { "start": "^\\s*#\\s*region\\b", "end": "^\\s*#\\s*endregion\\b" } },
  "indentationRules": {
    "increaseIndentPattern": "^\\s*(?:if|elif|else|for|while|until|case|select)\\b(?!.*\\b(?:fi|done|esac)\\b).*$|\\b(?:then|do)\\s*$|\\{\\s*$|\\)\\s*$",
    "decreaseIndentPattern": "^\\s*(?:fi|done|esac|else|elif|\\}|;;)"
  }
}
"""

/** Python 3: keywords, built-ins, strings with their prefixes, decorators, numbers. */
internal const val PYTHON_GRAMMAR = """
{
  "tokenPostfix": ".python",
  "comments": { "lineComment": "#" },
  "keywords": [
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
    "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
    "or", "pass", "raise", "return", "try", "while", "with", "yield", "True", "False", "None"
  ],
  "builtins": [
    "print", "len", "range", "int", "str", "float", "bool", "list", "dict", "set", "tuple", "frozenset",
    "bytes", "bytearray", "object", "type", "isinstance", "issubclass", "super", "open", "enumerate",
    "zip", "map", "filter", "sorted", "reversed", "min", "max", "sum", "abs", "round", "any", "all",
    "iter", "next", "getattr", "setattr", "hasattr", "delattr", "repr", "hash", "id", "input", "vars",
    "dir", "callable", "format", "chr", "ord", "hex", "oct", "bin", "divmod", "pow", "staticmethod",
    "classmethod", "property", "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
    "AttributeError", "RuntimeError", "StopIteration", "NotImplementedError", "self", "cls"
  ],
  "tokenizer": {
    "root": [
      ["\\s+", ""],
      ["#.*$", "comment"],
      ["@[A-Za-z_][\\w.]*", "annotation"],
      ["[rRbBuUfF]{0,2}\"\"\"", "string", "@tripleDouble"],
      ["[rRbBuUfF]{0,2}'''", "string", "@tripleSingle"],
      ["[rRbBuUfF]{0,2}\"", "string", "@double"],
      ["[rRbBuUfF]{0,2}'", "string", "@single"],
      ["0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+", "number.hex"],
      ["(?:\\d[\\d_]*(?:\\.[\\d_]*)?|\\.\\d[\\d_]*)(?:[eE][-+]?\\d+)?[jJ]?", "number"],
      ["(def)(\\s+)([A-Za-z_]\\w*)", ["keyword", "", "function"]],
      ["(class)(\\s+)([A-Za-z_]\\w*)", ["keyword", "", "type"]],
      ["[A-Za-z_]\\w*(?=\\s*\\()", { "cases": { "@keywords": "keyword", "@builtins": "predefined", "@default": "function" } }],
      ["[A-Za-z_]\\w*", { "cases": { "@keywords": "keyword", "@builtins": "predefined", "@default": "identifier" } }],
      ["\\*\\*=?|//=?|<<=?|>>=?|->|:=|[-+*/%@&|^~<>=!]=?", "operator"],
      ["[{}()\\[\\]]", "@brackets"],
      ["[,:;.]", "delimiter"]
    ],
    "double": [
      ["[^\\\\\"]+", { "cases": { "@eos": { "token": "string", "next": "@pop" }, "@default": "string" } }],
      ["\\\\.", "string.escape"],
      ["\\\\$", "string.escape"],
      ["\"", "string", "@pop"]
    ],
    "single": [
      ["[^\\\\']+", { "cases": { "@eos": { "token": "string", "next": "@pop" }, "@default": "string" } }],
      ["\\\\.", "string.escape"],
      ["\\\\$", "string.escape"],
      ["'", "string", "@pop"]
    ],
    "tripleDouble": [
      ["[^\\\\\"]+", "string"],
      ["\\\\.", "string.escape"],
      ["\"\"\"", "string", "@pop"],
      ["[\"\\\\]", "string"]
    ],
    "tripleSingle": [
      ["[^\\\\']+", "string"],
      ["\\\\.", "string.escape"],
      ["'''", "string", "@pop"],
      ["['\\\\]", "string"]
    ]
  }
}
"""

internal const val PYTHON_CONFIGURATION = """
{
  "comments": { "lineComment": "#" },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [
    ["{", "}"], ["[", "]"], ["(", ")"],
    { "open": "\"", "close": "\"", "notIn": ["string"] },
    { "open": "'", "close": "'", "notIn": ["string", "comment"] }
  ],
  "folding": { "markers": { "start": "^\\s*#\\s*region\\b", "end": "^\\s*#\\s*endregion\\b" } },
  "onEnterRules": [
    {
      "beforeText": "^\\s*(?:def|class|for|if|elif|else|while|try|with|finally|except|async|match|case)\\b.*:\\s*(?:#.*)?$",
      "action": { "indent": "indent" }
    },
    { "beforeText": "^\\s*(?:return|pass|raise|break|continue)\\b.*$", "action": { "indent": "outdent" } }
  ]
}
"""

/** Java: keywords, types by their capital, annotations, text blocks, Javadoc, number suffixes. */
internal const val JAVA_GRAMMAR = """
{
  "tokenPostfix": ".java",
  "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
  "keywords": [
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
    "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
    "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
    "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record", "sealed",
    "permits", "yield", "true", "false", "null"
  ],
  "tokenizer": {
    "root": [
      { "include": "@whitespace" },
      ["@\\s*[A-Za-z_][\\w.]*", "annotation"],
      ["\"\"\"", "string", "@textBlock"],
      ["\"", "string", "@string"],
      ["'(?:[^'\\\\]|\\\\.)'", "string"],
      ["0[xX][0-9a-fA-F_]+[lL]?", "number.hex"],
      ["0[bB][01_]+[lL]?", "number"],
      ["(?:\\d[\\d_]*(?:\\.[\\d_]*)?|\\.\\d[\\d_]*)(?:[eE][-+]?\\d+)?[fFdDlL]?", "number"],
      ["[a-z_$][\\w$]*(?=\\s*\\()", { "cases": { "@keywords": "keyword", "@default": "function" } }],
      ["[A-Z][\\w$]*", { "cases": { "@keywords": "keyword", "@default": "type" } }],
      ["[a-z_$][\\w$]*", { "cases": { "@keywords": "keyword", "@default": "identifier" } }],
      ["[{}()\\[\\]]", "@brackets"],
      ["->|::|\\+\\+|--|&&|\\|\\||<<=?|>>>?=?|[-+*/%&|^!=<>]=?|[?~:]", "operator"],
      ["[;,.]", "delimiter"]
    ],
    "whitespace": [
      ["\\s+", ""],
      ["/\\*\\*(?!/)", "comment.doc", "@javadoc"],
      ["/\\*", "comment", "@comment"],
      ["//.*$", "comment"]
    ],
    "comment": [
      ["[^*/]+", "comment"],
      ["\\*/", "comment", "@pop"],
      ["[*/]", "comment"]
    ],
    "javadoc": [
      ["@\\w+", "comment.doc.tag"],
      ["[^*@]+", "comment.doc"],
      ["\\*/", "comment.doc", "@pop"],
      ["[*@]", "comment.doc"]
    ],
    "string": [
      ["[^\\\\\"]+", "string"],
      ["\\\\.", "string.escape"],
      ["\"", "string", "@pop"]
    ],
    "textBlock": [
      ["[^\\\\\"]+", "string"],
      ["\\\\.", "string.escape"],
      ["\"\"\"", "string", "@pop"],
      ["[\"\\\\]", "string"]
    ]
  }
}
"""

/** What C-like languages (Java, Groovy) say about typing: brackets, quotes, comments and Javadoc lines. */
internal const val C_LIKE_CONFIGURATION = """
{
  "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [
    ["{", "}"], ["[", "]"], ["(", ")"],
    { "open": "\"", "close": "\"", "notIn": ["string"] },
    { "open": "'", "close": "'", "notIn": ["string", "comment"] },
    { "open": "/**", "close": " */", "notIn": ["string"] }
  ],
  "surroundingPairs": [["{", "}"], ["[", "]"], ["(", ")"], ["\"", "\""], ["'", "'"], ["<", ">"]],
  "folding": { "markers": { "start": "^\\s*//\\s*(?:#?region\\b|<editor-fold\\b)", "end": "^\\s*//\\s*(?:#?endregion\\b|</editor-fold>)" } },
  "onEnterRules": [
    { "beforeText": "^\\s*/\\*\\*(?!/)([^*]|\\*(?!/))*$", "afterText": "^\\s*\\*/$", "action": { "indent": "indentOutdent", "appendText": " * " } },
    { "beforeText": "^\\s*/\\*\\*(?!/)([^*]|\\*(?!/))*$", "action": { "indent": "none", "appendText": " * " } },
    { "beforeText": "^(\\t|[ ])*[ ]\\*([ ]([^*]|\\*(?!/))*)?$", "action": { "indent": "none", "appendText": "* " } },
    { "beforeText": "^(\\t|[ ])*[ ]\\*/\\s*$", "action": { "indent": "none", "removeText": 1 } }
  ]
}
"""
