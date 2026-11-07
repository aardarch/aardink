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

On the web a link is live: see [Links on the web](#links-on-the-web).

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

## A wasm module built from the export template

`aardinkRegisterLanguage` takes one dispatcher in place of a callback per provider, so that a later
provider needs no change to a module built from the template. Users of the npm package are not
affected: `registerLanguage`'s providers are as they were. Copy the new version from
`ExportsTemplate.kt`, with the JavaScript that calls it:

```kotlin
// 0.8
fun aardinkRegisterLanguage(
    definitionJson: String,
    completions: (String, Int, Int) -> Promise<JsString?>,
    hover: (String, Int, Int) -> Promise<JsString?>,
    diagnostics: (String) -> Promise<JsString?>,
    documentSymbols: (String) -> Promise<JsString?>,
    documentColors: (String) -> Promise<JsString?>,
): String

// 0.9
fun aardinkRegisterLanguage(definitionJson: String, dispatcher: (String, String, String) -> Promise<JsString?>): String
```

```js
// 0.8
k.aardinkRegisterLanguage(json, completions, hover, diagnostics, documentSymbols, documentColors);

// 0.9: method is 'completions', 'hover', 'diagnostics', 'documentSymbols', 'documentColors', 'inlayHints',
// 'codeLenses' or 'runCodeLensCommand'
k.aardinkRegisterLanguage(json, (method, text, argsJson) => {
  // [line, column] for completions and hover, [range] for inlayHints, [id, args] for runCodeLensCommand, else []
  const args = JSON.parse(argsJson);
  // answer with a Promise of JSON, or of null for nothing, as for a method you do not know
});
```

A Kotlin host passing `WebLanguageProviders` to `AardinkWeb.registerLanguage` changes nothing.

The template also gains `aardinkOnOpenLink`, `aardinkConnectLanguageServer` and
`aardinkDisconnectLanguageServer`, which your copy needs for those features.

## Links on the web

The web used to open no links, and hover and signature help showed them as text. Now `AardinkWeb`
always gives `CodeEditorLayout` an `onOpenLink`, so they are live, and two things behave differently
with no change on your side:

- **Ctrl/Cmd+click on a URL in the text opens a tab.** It did nothing before.
- **A link in a hover, signature help or completion detail pane opens a tab on a click.**

Both only for `http` and `https`, in a new tab with `noopener`. Anything else does nothing, so a
`javascript:` link in a server's hover text stays inert.

To decide for yourself, listen with `editor.onOpenLink(listener)`, or `AardinkWeb.onOpenLink` from
Kotlin. A listener gets every target whatever its scheme and nothing opens by itself, so one that
follows a target must check it first. To go back to no links opening at all, listen and do nothing.

## Language servers on the web

`connectLanguageServer(url, languageId)` is new; see
[WEB_INTEGRATION.md](WEB_INTEGRATION.md#language-servers). Nothing changes unless you call it, but:

- `aardink-editor-web` now depends on `aardink-languages-lsp` at runtime, and its dependency on
  `kotlinx-coroutines-core` is now `api`, as `DisposableHandle` is in a public signature. The npm
  package's `aardink-web.wasm` grew by about 217 KB, or 61 KB gzipped, for everyone.
- With a server connected, a built-in language's answers come after the server's, and a formatter or
  outline the language has gives way to the server's.

## Code lenses

`EditorOptions` and `WebEditorOptions` gain `codeLens`, and `WebLanguageProviders` gains `codeLenses`
and `runCodeLensCommand`, all at the end and off by default, so code calling their constructors by
position, or their `copy`, needs a recompile. `LanguageService.codeLenses` has a default, so a service
of your own needs no change.

A `CodeLens` with a blank title is not an error: it keeps its row and the editor asks
`codeLenses` again when it is on screen. That is how a service leaves out what costs a lot until it
can be seen. `LspLanguageService` does it for a server's `codeLens/resolve`, so a server that resolves
lenses is asked only about those around the view, once the service is attached to the editor's state.

A click on a lens from a server sends `workspace/executeCommand` only for a command the server listed
in `executeCommandProvider`. Anything else is its client's own, as VS Code's "show references" is, and
the lens is then only something to read.

## Inline text and inlay hints

`DecorationStyle`, `EditorOptions` and `WebEditorOptions` gain fields, and `WebLanguageProviders` a
parameter, so code calling their constructors by position, or their `copy`, needs a recompile:

| Class | New, all at the end and all off by default |
| --- | --- |
| `DecorationStyle` | `before`, `after`, `inlineColor` |
| `EditorOptions`, `WebEditorOptions` | `inlayHints` |
| `WebLanguageProviders` | `inlayHints` |

`LanguageService.inlayHints` has a default, so a service of your own needs no change.

`DecorationStyle` used to promise that a decoration costs no relayout. That still holds for what it
had; `before`, `after` and `inlineColor` are part of the line's layout, and the lines they touch are
laid out again when they change.

Two things behave differently with no change on your side:

- **The npm package's `before` and `after` are read.** Monaco's options passed as they are used to
  leave these out, and now show. Only `content` is read.
- **An empty decoration follows the edits**, as its documentation always said. One that was set
  empty, or whose text was deleted, used to stay at its offset while the text around it moved.
