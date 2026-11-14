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

// Grammars of data and configuration files, as DeclarativeGrammar JSON, with their language
// configurations. Token names are Monaco's, which the themes colour: `type` for a key, `string`,
// `number`, `keyword` for a literal such as `true`, `comment`, `delimiter`, `operator`.
// In these raw strings a dollar sign is never followed by a letter or `{`, so nothing is a template.

/** YAML: keys, the three kinds of scalar, anchors, tags, documents and flow collections. */
internal const val YAML_GRAMMAR = """
{
  "tokenPostfix": ".yaml",
  "comments": { "lineComment": "#" },
  "keywords": ["true", "false", "True", "False", "TRUE", "FALSE", "yes", "no", "on", "off", "null", "Null", "NULL", "~"],
  "tokenizer": {
    "root": [
      { "include": "@whitespace" },
      ["^%\\w+.*$", "meta.directive"],
      ["^(?:---|\\.\\.\\.)(?=\\s|$)", "operator.document"],
      ["(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s#'\"\\[\\]{},>|!&*][^#:]*?)(\\s*)(:)(?=\\s|$)", ["type", "", "delimiter"]],
      ["[-?:](?=\\s|$)", "operator"],
      ["[&*][^\\s,\\[\\]{}]+", "tag"],
      ["!\\S*", "tag"],
      ["[|>][-+0-9]*(?=\\s*(?:#|$))", "operator"],
      ["[\\[\\]{},]", "delimiter"],
      ["\"", "string", "@doubleQuoted"],
      ["'", "string", "@singleQuoted"],
      ["[-+]?(?:0x[0-9a-fA-F]+|0o[0-7]+|\\d+(?:\\.\\d*)?(?:[eE][-+]?\\d+)?|\\.inf|\\.nan)(?=\\s*(?:#|$|[,\\]}]))", "number"],
      ["[^\\s#,\\[\\]{}]+(?:\\s+[^\\s#,\\[\\]{}]+)*", { "cases": { "@keywords": "keyword", "@default": "string" } }]
    ],
    "whitespace": [
      ["\\s+", ""],
      ["#.*$", "comment"]
    ],
    "doubleQuoted": [
      ["[^\\\\\"]+", "string"],
      ["\\\\.", "string.escape"],
      ["\"", "string", "@pop"]
    ],
    "singleQuoted": [
      ["[^']+", "string"],
      ["''", "string.escape"],
      ["'", "string", "@pop"]
    ]
  }
}
"""

internal const val YAML_CONFIGURATION = """
{
  "comments": { "lineComment": "#" },
  "brackets": [["{", "}"], ["[", "]"]],
  "autoClosingPairs": [["{", "}"], ["[", "]"], { "open": "\"", "close": "\"", "notIn": ["string"] }, { "open": "'", "close": "'", "notIn": ["string"] }],
  "surroundingPairs": [["{", "}"], ["[", "]"], ["\"", "\""], ["'", "'"]],
  "folding": { "markers": { "start": "^\\s*#\\s*region\\b", "end": "^\\s*#\\s*endregion\\b" } },
  "onEnterRules": [
    { "beforeText": ":\\s*(?:#.*)?$", "action": { "indent": "indent" } },
    { "beforeText": "^\\s*-\\s*$", "action": { "indent": "indent" } }
  ]
}
"""

/** Java's `.properties`: comments, keys, their values, escapes and continued lines. */
internal const val PROPERTIES_GRAMMAR = """
{
  "tokenPostfix": ".properties",
  "comments": { "lineComment": "#" },
  "tokenizer": {
    "root": [
      ["^\\s*[#!].*$", "comment"],
      ["^(\\s*)((?:[^\\\\:=\\s]|\\\\.)+)(\\s*[:=]?\\s*)((?:[^\\\\]|\\\\.)*\\\\)$", ["", "attribute.name", "delimiter", { "token": "string", "next": "@continued" }]],
      ["^(\\s*)((?:[^\\\\:=\\s]|\\\\.)+)(\\s*[:=]?\\s*)(.*)$", ["", "attribute.name", "delimiter", "string"]]
    ],
    "continued": [
      ["(?:[^\\\\]|\\\\.)*\\\\$", "string"],
      [".+$", "string", "@pop"],
      ["^$", "", "@pop"]
    ]
  }
}
"""

internal const val PROPERTIES_CONFIGURATION = """
{
  "comments": { "lineComment": "#" },
  "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
  "autoClosingPairs": [["{", "}"], ["[", "]"], ["(", ")"], { "open": "\"", "close": "\"", "notIn": ["string"] }]
}
"""

/** INI: sections, keys, quoted and numeric values, `;` and `#` comments. */
internal const val INI_GRAMMAR = """
{
  "tokenPostfix": ".ini",
  "comments": { "lineComment": ";" },
  "tokenizer": {
    "root": [
      ["^\\s*[;#].*$", "comment"],
      ["^(\\s*)(\\[)([^\\]]*)(\\])", ["", "delimiter.square", "metatag", "delimiter.square"]],
      ["^(\\s*)([^=:;#\\s\\[][^=:]*?)(\\s*)([=:])(.*)$", ["", "attribute.name", "", "delimiter", { "cases": {
        "~\\s*\"[^\"]*\"\\s*": "string",
        "~\\s*'[^']*'\\s*": "string",
        "~\\s*-?\\d+(?:\\.\\d+)?\\s*": "number",
        "~\\s*(?:true|false|yes|no|on|off)\\s*": "keyword",
        "@default": ""
      } }]]
    ]
  }
}
"""

internal const val INI_CONFIGURATION = """
{
  "comments": { "lineComment": ";" },
  "brackets": [["[", "]"]],
  "autoClosingPairs": [["[", "]"], { "open": "\"", "close": "\"", "notIn": ["string"] }, { "open": "'", "close": "'", "notIn": ["string"] }]
}
"""

/** SQL, case-insensitive: keywords, types, functions, strings, quoted names, parameters. */
internal const val SQL_GRAMMAR = """
{
  "tokenPostfix": ".sql",
  "ignoreCase": true,
  "comments": { "lineComment": "--", "blockComment": ["/*", "*/"] },
  "keywords": [
    "select", "from", "where", "and", "or", "not", "insert", "into", "values", "update", "set", "delete",
    "create", "table", "view", "index", "drop", "alter", "add", "column", "primary", "key", "foreign",
    "references", "join", "inner", "left", "right", "full", "outer", "cross", "on", "as", "distinct",
    "group", "by", "order", "having", "limit", "offset", "union", "all", "exists", "in", "between", "like",
    "ilike", "is", "null", "case", "when", "then", "else", "end", "begin", "commit", "rollback",
    "transaction", "with", "recursive", "returning", "default", "constraint", "unique", "check", "if",
    "replace", "grant", "revoke", "asc", "desc", "top", "fetch", "next", "rows", "only", "over",
    "partition", "window", "cast", "true", "false", "using", "natural", "except", "intersect", "trigger",
    "procedure", "function", "returns", "declare", "schema", "database", "cascade", "restrict", "temporary",
    "temp", "materialized", "conflict", "do", "nothing", "lateral", "filter", "within", "any", "some", "escape"
  ],
  "types": [
    "int", "integer", "bigint", "smallint", "tinyint", "decimal", "numeric", "float", "real", "double",
    "precision", "char", "varchar", "text", "nchar", "nvarchar", "date", "time", "timestamp", "timestamptz",
    "datetime", "interval", "boolean", "bool", "blob", "clob", "bytea", "json", "jsonb", "uuid", "serial",
    "bigserial", "money", "xml", "binary", "varbinary"
  ],
  "functions": [
    "count", "sum", "avg", "min", "max", "coalesce", "nullif", "upper", "lower", "substring", "trim",
    "length", "round", "floor", "ceil", "abs", "now", "current_date", "current_time", "current_timestamp",
    "concat", "replace", "row_number", "rank", "dense_rank", "lag", "lead", "extract", "date_trunc",
    "string_agg", "array_agg", "greatest", "least"
  ],
  "tokenizer": {
    "root": [
      ["\\s+", ""],
      ["--.*$", "comment"],
      ["/\\*", "comment", "@comment"],
      ["'", "string", "@string"],
      ["\"", "identifier.quote", "@quoted"],
      ["`[^`]*`", "identifier.quote"],
      ["0[xX][0-9a-fA-F]+", "number.hex"],
      ["\\d+(?:\\.\\d*)?(?:[eE][-+]?\\d+)?|\\.\\d+(?:[eE][-+]?\\d+)?", "number"],
      ["[@:][A-Za-z_]\\w*|\\?|\\$\\d+", "variable"],
      ["[A-Za-z_][\\w$]*(?=\\s*\\()", { "cases": { "@functions": "predefined", "@keywords": "keyword", "@types": "type", "@default": "function" } }],
      ["[A-Za-z_][\\w$]*", { "cases": { "@keywords": "keyword", "@types": "type", "@functions": "predefined", "@default": "identifier" } }],
      ["<>|<=|>=|!=|=|<|>|\\|\\||::|[-+*/%]", "operator"],
      ["[()]", "@brackets"],
      ["[,;.]", "delimiter"]
    ],
    "comment": [
      ["[^*/]+", "comment"],
      ["\\*/", "comment", "@pop"],
      ["[*/]", "comment"]
    ],
    "string": [
      ["[^']+", "string"],
      ["''", "string.escape"],
      ["'", "string", "@pop"]
    ],
    "quoted": [
      ["[^\"]+", "identifier.quote"],
      ["\"\"", "identifier.quote"],
      ["\"", "identifier.quote", "@pop"]
    ]
  }
}
"""

internal const val SQL_CONFIGURATION = """
{
  "comments": { "lineComment": "--", "blockComment": ["/*", "*/"] },
  "brackets": [["(", ")"]],
  "autoClosingPairs": [["(", ")"], { "open": "'", "close": "'", "notIn": ["string", "comment"] }, { "open": "\"", "close": "\"", "notIn": ["string", "comment"] }],
  "surroundingPairs": [["(", ")"], ["'", "'"], ["\"", "\""], ["`", "`"]],
  "folding": { "markers": { "start": "^\\s*--\\s*#?region\\b", "end": "^\\s*--\\s*#?endregion\\b" } }
}
"""
