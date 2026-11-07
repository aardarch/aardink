<!--
  Copyright 2026 Aardarch

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Migrating from Aardink 0.8 to 0.9

[AARDINK_ROADMAP.md](AARDINK_ROADMAP.md) §7 has the plan for 0.9, §15 the API ledger and §15.1 what
was built where it differs.

## Documentation text is Markdown

No signature changes, but what these strings mean does:

| Text | 0.8 | 0.9 |
| --- | --- | --- |
| `HoverDoc.content`, the npm package's `AardinkHover.contents` | Plain text | Markdown |
| `SignatureInformation.documentation`, `ParameterInformation.documentation` | Plain text | Markdown |
| `CompletionItem.documentation`, the npm package's `documentation` | Plain text | Markdown: one line of plain text in the list, and in full in the pane beside it for the selected item |
| `XmlSchema.Element`, `Attribute` and `Value` `documentation`, and `doc` in schema JSON | Plain text | Markdown |

`HoverDoc.title` and `HoverDoc.example` stay plain. Raw HTML stays literal text, an image shows its
alt text and fetches nothing, and block quotes, tables and reference links stay as written.

If these strings come from your own service or schema, read them through once:

- A `*` or `_` that is not emphasis is written `\*` or `\_`. `snake_case` and `2 * 3 * 4` need
  nothing, but `*args*` and `_private_` do.
- A single newline no longer breaks a line: it reads as a space, as in Monaco's hover. End the line
  with a backslash, or two spaces, to keep the break. A blank line starts a new paragraph.
- A line starting with `# `, `- `, `+ `, `1. ` or four spaces is a heading, a list or code.
- Any `http://` or `https://` URL in the text becomes a link.

Write a string once and it reads the same on hover and in the completion list: the list drops the
marks and resolves the escapes, so `Uses a\_b *only* here` shows as `Uses a_b only here` in both.

An attribute's list of values on a schema hover is escaped for you, so `_top_` shows as `_top_`.

## A tokenizer of your own

`CodeBlock`, and the code fences in documentation, now colour text with `tokenizeLines(text,
0..lastLine, emptyList())` rather than `tokenizeFull`, so a snippet leaves no trace in a tokenizer a
document is also using. If you implement `IncrementalTokenizer`, an empty `previousTokens` must give
tokens for every line of `dirtyRange` and keep nothing. A tokenizer that answers it with nothing
shows these plain.

## Links in documentation

A link in hover, signature help or a completion's detail pane goes to `CodeEditorLayout`'s
`onOpenLink`, and nowhere else. A host that leaves `onOpenLink` out gets a link as its plain text,
since nothing would open it.

The npm package has no way to set a handler yet, so on the web every link shows as its text.

## Code in documentation

Code blocks in documentation are now drawn as `CodeBlock` draws them, in the editor's own colours,
scrolling sideways rather than wrapping. A fence tagged with a language is highlighted by what
`LocalCodeFenceTokenizers` answers for that tag, and shows plain when it answers null, as it does by
default. Provide it around `CodeEditorLayout` to get tagged fences highlighted:

```kotlin
CompositionLocalProvider(LocalCodeFenceTokenizers provides { registry.byId(it)?.tokenizer }) {
    CodeEditorLayout(state)
}
```

An untagged fence, and indented code, takes the tokenizer of the `CodeEditorState`. The web already
provides this from the language registry the editor was mounted with.

## Language servers

`LspLanguageService` now gives Markdown for all three:

- A `plaintext` `MarkupContent`, and a bare string in signature or completion documentation, are
  escaped to show to the letter, line for line, losing only each line's indentation. A host that
  reads `HoverDoc.content` or `CompletionItem.documentation` itself sees the escapes.
- A `MarkedString`'s `{ language, value }` becomes a fenced code block. With no code in it, it counts
  as no answer.
- A bare string in a hover is Markdown, as the protocol says, and stays as it is.

## The hover card

A card now outlasts a mouse that moves off its symbol by 300 ms, and stays while the mouse is over
it, so that a link or Quick Fix can be reached. It used to go at once. Changing `languageService`
takes the card down.
