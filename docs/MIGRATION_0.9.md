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

Most of 0.9 is new and off by default. What can break an upgrade, and what behaves differently with
no change on your side, is in the table; the sections after it have the details, with a before and
after where code changes.

## At a glance

| Change | Who it reaches | What to do |
| --- | --- | --- |
| Documentation strings are Markdown, and cut past 10,000 characters | Your own hover, signature help, completion and schema text; the npm package's `contents` and `documentation` | Escape a literal `*` or `_`, and end a line with a backslash to keep a break: [Documentation text is Markdown](#documentation-text-is-markdown) |
| `EditorCommand` gains `AcceptInlineCompletion`, `CycleInlineCompletion` and `DismissInlineCompletion` | An exhaustive `when` over `EditorCommand` | Add the branches: [New members and parameters](#new-members-and-parameters) |
| `EditorOptions`, `DecorationStyle`, `WebEditorOptions` and `WebLanguageProviders` gain fields | Code compiled against 0.8 that calls their constructors or `copy` | Recompile. Every new field is last and has a default, so source does not change but for a trailing lambda, two rows down |
| `CodeEditorLayout`, `LspClient` and `DeclarativeTokenizer` gain a defaulted parameter | Code compiled against 0.8 | Recompile |
| The last parameter of `CodeEditorLayout` is now `inlineCompletionProvider`, and of `WebLanguageProviders` `runCodeLensCommand` | A trailing lambda meant for `onFontSizeChange` or `documentColors` | Name the argument: [New members and parameters](#new-members-and-parameters) |
| `LanguageService` and `FoldingProvider` gain methods with defaults | A `FoldingProvider` that wraps another | Forward `requestFoldableRanges`: [Folding through a suspend call](#folding-through-a-suspend-call) |
| Grammars: `$` in a token name is refused; guard keys starting `~`, `!~`, `==`, `!=` or `!@` are operators; `@name` attributes expand as a group | Monarch grammars of your own, in Kotlin or registered on the web | Read your grammars through once: [Grammars](#grammars) |
| `aardinkRegisterLanguage` takes one dispatcher | A wasm module built from the export template | Copy the new template: [A wasm module built from the export template](#a-wasm-module-built-from-the-export-template) |
| `LspClient` declares real client capabilities, and `LspLanguageService` sends only what the server declared | Hosts of `aardink-languages-lsp` | Check your server answers as you expect: [Language servers](#language-servers) |
| Ctrl/Cmd+click on a URL opens a tab on the web | Web hosts | Nothing, or `editor.onOpenLink(listener)` to decide: [Links on the web](#links-on-the-web) |
| `CodeBlock` and fences tokenize with an empty `previousTokens` | An `IncrementalTokenizer` of your own | Answer every line asked for: [A tokenizer of your own](#a-tokenizer-of-your-own) |
| The npm package draws a decoration's `before` and `after` | Web hosts passing Monaco's decoration options as they are | Drop them, or expect their text in the line: [Inline text and inlay hints](#inline-text-and-inlay-hints) |
| An empty or collapsed decoration follows the edits | Hosts relying on one staying at its offset | Nothing, or set it again where you want it: [Inline text and inlay hints](#inline-text-and-inlay-hints) |
| `aardink-editor-web` depends on `aardink-languages-lsp`, and on `kotlinx-coroutines-core` as `api`; `aardink-web.wasm` is 486 KB larger than in 0.8 | Gradle consumers of `aardink-editor-web`; every web page | Check the dependency tree: [Language servers on the web](#language-servers-on-the-web) |

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
  with a backslash, or two spaces, to keep the break. A blank line starts a new paragraph. Text
  built with `lines.join('\n')` runs together into one paragraph.
- A line starting with `# `, `- `, `+ `, `* `, `1. `, `1) ` or four spaces is a heading, a list or
  code, and a line starting with three backticks or three tildes opens a code block.
- A line of three or more `-`, `*` or `_` alone is a rule. A line of `=` or `-` under a line of text
  makes that text a heading, so a numpy-style `Parameters` underlined with `----------` is one.
- Text between backticks is code.
- A backslash before ASCII punctuation is dropped, so `\.` and `\(` show as `.` and `(`: write `\\`
  for a backslash.
- An entity such as `&lt;`, `&amp;` or `&#60;` shows as its character.
- Any `http://` or `https://` URL in the text becomes a link.
- Text built from what the user typed can hold any of these, so escape it before putting it in.

Write a string once and it reads the same on hover and in the completion list: the list drops the
marks and resolves the escapes, so `Uses a\_b *only* here` shows as `Uses a_b only here` in both.

An attribute's list of values on a schema hover is escaped for you, so `_top_` shows as `_top_`.

Documentation longer than 10,000 characters is now cut, at a line end when there is one in the last
half of that, and ends with an ellipsis. 0.8 showed plain text whole.

## Links in documentation

A link in hover, signature help or a completion's detail pane goes to `CodeEditorLayout`'s
`onOpenLink`, and nowhere else. A host that leaves `onOpenLink` out gets a link as its plain text,
since nothing would open it. The public `HoverDocPopup` and `SignatureHelpPopup` take no link
handler, so links in them are plain text too.

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

## The hover card

A card now outlasts a mouse that moves off its symbol by 300 ms, and stays while the mouse is over
it, so that a link or Quick Fix can be reached. It used to go at once. Changing `languageService`
takes the card down.

## A tokenizer of your own

`CodeBlock`, and the code fences in documentation, now colour text with `tokenizeLines(text,
0..lastLine, emptyList())` rather than `tokenizeFull`, so a snippet leaves no trace in a tokenizer a
document is also using. If you implement `IncrementalTokenizer`, an empty `previousTokens` must give
tokens for every line of `dirtyRange` and keep nothing. A tokenizer that answers it with nothing
shows these plain.

## New members and parameters

Every new field and parameter is last and has a default, so code written against 0.8 compiles
unchanged but for a trailing lambda. Code compiled against 0.8, such as a library built on Aardink,
needs a recompile, because the constructors, `copy` and the composable have new signatures. That
holds for calls with named arguments too, which compile to the same constructor.

A trailing lambda goes to the last parameter, and that has changed for two of them. In 0.8 it went
to `CodeEditorLayout`'s `onFontSizeChange` and to `WebLanguageProviders`' `documentColors`; it now
goes to `inlineCompletionProvider` and `runCodeLensCommand`, and fails to compile. Name the
argument:

```kotlin
// 0.8
CodeEditorLayout(state) { size -> zoom = size }
WebLanguageProviders(hover = hover) { text -> colorsOf(text) }

// 0.9
CodeEditorLayout(state, onFontSizeChange = { size -> zoom = size })
WebLanguageProviders(hover = hover, documentColors = { text -> colorsOf(text) })
```

| Class | New, all at the end and all off or null by default |
| --- | --- |
| `EditorOptions` | `semanticHighlighting`, `inlayHints`, `codeLens` |
| `DecorationStyle` | `before`, `after`, `inlineColor` |
| `CodeEditorLayout` | `inlineCompletionProvider` |
| `LspClient` | `host` |
| `DeclarativeTokenizer` | `embedded`, a resolver from a language id to its grammar |
| `WebEditorOptions` | `semanticHighlighting`, `inlayHints`, `codeLens` |
| `WebLanguageProviders` | `inlayHints`, `codeLenses`, `runCodeLensCommand` |

`EditorCommand` gains three members, so an exhaustive `when` over it needs branches for them:

```kotlin
when (command) {
    // ...
    EditorCommand.AcceptInlineCompletion -> "Accept suggestion"
    is EditorCommand.CycleInlineCompletion -> if (command.forward) "Next suggestion" else "Previous suggestion"
    EditorCommand.DismissInlineCompletion -> "Hide suggestion"
}
```

`LanguageService` gains `resolveCompletion`, `surroundingClose`, `semanticTokens`, `inlayHints` and
`codeLenses`, each with a default that does nothing, so a service of your own needs no change.
`CompositeLanguageService` has a rule for each; its KDoc lists them. These defaults, and
`FoldingProvider.requestFoldableRanges`, are JVM default methods, so an implementation compiled
against 0.8 also runs unchanged on JVM and Android, without a recompile.

### Folding through a suspend call

`FoldingProvider` gains `requestFoldableRanges(document)`, a suspend method whose default calls
`foldableRanges`. The editor now asks for folds through it, once typing pauses for 200 ms rather
than while typing goes on, so a provider that waits on a language server can be awaited.

Two things follow:

- A provider that wraps another, as `withRegionMarkers` does, should forward
  `requestFoldableRanges`, or the wrapped provider's answer is never asked for. The built-in wrappers
  do.
- Throwing from `requestFoldableRanges` means no answer this time: the editor keeps the ranges it
  has. An empty list means nothing to fold, and opens every fold. A server that says "content
  modified" while the user types should therefore throw, not answer empty, and `LspFoldingProvider`
  does.

## Grammars

The Monarch subset in `DeclarativeGrammar` grows to cover `switchTo`, `goBack`, `$S0`..`$Sn` and `$#`
in guards and `next`, `@brackets`, `log` and `nextEmbedded`. Some grammars that parsed in 0.8 now
fail, and some guards now mean something else. Read your grammars through once for each of these.

**`$` in a token name is refused.** A token name, `defaultToken` and `tokenPostfix` are fixed when the
grammar is read, so `string.$S2` used to give a token literally named `string.$S2`, and now fails to
parse with where it is. Choose between fixed names with `cases`:

```json
// 0.8
["\"", "string.$S2"]

// 0.9
["\"", { "cases": { "$S2==double": "string.double", "@default": "string.single" } }]
```

A bracket's token in a top-level `brackets` is checked the same way, but only in a grammar with a
`@brackets` action: without one, `brackets` is an ordinary key, as in 0.8. With one, a `brackets`
that is a string or holds a bracket whose open and close are the same is refused.

**A guard is read as Monarch reads it.** A `cases` key is a subject (`$#`, the match, by default, or
`$S0`, `$S1` and so on), then a test. In 0.8 every key that was not `@default`, `@eos` or an `@array`
was a regular expression. Now:

- A plain word is an equality test. It gives the same result as before.
- A key starting `~`, `!~`, `==`, `!=` or `!@` is that operator, so `$#==$S2`, a literal regular
  expression in 0.8 that matched nothing, now closes on the text that opened.
- A key that is only an operator is a test against the empty string: `==` is "is empty", and `!=`
  "is not empty".

The last is the one to look for. A grammar that tells `==` from `!=` with keys of their own now sends
every match to `!=`:

```json
// 0.8: each key a regular expression, matching that text
{ "cases": { "==": "operator.eq", "!=": "operator.ne", "@default": "operator" } }

// 0.9: write them as regular expressions, or as equality tests
{ "cases": { "~==": "operator.eq", "~!=": "operator.ne", "@default": "operator" } }
{ "cases": { "$#====": "operator.eq", "$#==!=": "operator.ne", "@default": "operator" } }
```

The same goes for any regular expression key that starts with one of those operators. A regular
expression in a guard still has to match all of its subject, as in 0.8.

**An `@name` attribute expands as a group,** `(?:value)`, in a rule's regular expression and in a `~`
guard, as Monarch does. `x@name` with an alternation in `name`, and `@name+`, change meaning: the
alternation stays whole and the quantifier repeats all of it. An attribute that is half a regular
expression, such as an unclosed group that the rule closes, now fails to parse, and one used inside
a character class, `[@chars]`, puts `(?:` and `)` into the class. A `\` or `$` in an attribute
expanded wrongly in 0.8 and is right now.

**`nextEmbedded` is read.** It used to be ignored. Without a resolver a grammar's tokens stay as they
were, but a `nextEmbedded` that is not a string, or is empty, is now refused with where it is. Give
`DeclarativeTokenizer` its new `embedded` parameter to resolve the ids:

```kotlin
val tokenizer = DeclarativeTokenizer(grammar) { id -> grammars[id] }
```

`DeclarativeGrammar.tokenNames` holds a grammar's own names only, never those of a grammar it embeds,
which is known by id alone until it is used. A test that checks every name a grammar can produce has
a colour must read the embedded grammars' `tokenNames` as well.

## Inline completions

`CodeEditorLayout` gains `inlineCompletionProvider`, last and null by default, so a compiled caller
needs a recompile, and a trailing lambda meant for `onFontSizeChange` must become a named argument,
as [New members and parameters](#new-members-and-parameters) shows. `EditorCommand` gains
`AcceptInlineCompletion`, `CycleInlineCompletion` and `DismissInlineCompletion`, so an exhaustive
`when` over it needs branches.
`EditorKeyBindings.Default` gains Tab, Alt+], Alt+[ and Escape for them.

Tab is in that list now, where it said Tab was a fixed key, but it does what it did unless ghost text
shows: a binding whose command cannot run leaves its key alone. A host with its own binding on Alt+]
or Alt+[ keeps it, as yours come first, and has no key for going through what is on offer until it
binds one. Unbinding Tab with a null command takes away indenting too, as it already did.

Escape is in that list too, and does what it did unless ghost text shows. Unbinding it with a null
command takes the editor's Escape away whole, as it already did, so your own handler still gets
every Escape: nothing is dismissed, and no snippet, extra carets, find panel or hover is closed by
it. A list that is open takes its Escape first either way. To dismiss from a key of your own as
well, bind that key to `DismissInlineCompletion` and leave Escape as it is. With your own command on
Escape, ghost text stays up unless you run `DismissInlineCompletion`, even when your command cannot
run.

With a provider, Up and Down end what is on offer before they move, so Down from the middle of a
line goes to the next line rather than into the ghost text's rows.

On the web, `registerInlineCompletionsProvider` is new, with
`editor.action.inlineSuggest.commit`, `showNext`, `showPrevious` and `hide` for `trigger` and `getAction`. A wasm
module built from the template copies `aardinkRegisterInlineCompletionsProvider` and
`aardinkDisposeInlineCompletionsProvider`. `AardinkWeb.registerInlineCompletionsProvider` is the same
thing from Kotlin. Nothing changes unless you call them.

## Code lenses

`EditorOptions` and `WebEditorOptions` gain `codeLens`, and `WebLanguageProviders` gains `codeLenses`
and `runCodeLensCommand`, all at the end and off by default, so code compiled against 0.8 that calls
their constructors or `copy` needs a recompile; source changes only for a trailing lambda meant for
`documentColors`, as [New members and parameters](#new-members-and-parameters) shows.
`LanguageService.codeLenses` has a default, so a service of your own needs no change.

A `CodeLens` with a blank title is not an error: it keeps its row and the editor asks
`codeLenses` again when it is on screen. That is how a service leaves out what costs a lot until it
can be seen. `LspLanguageService` does it for a server's `codeLens/resolve`, so a server that resolves
lenses is asked only about those around the view, once the service is attached to the editor's state.
Rows do not wait on that: what has not resolved within half a second stays blank until the editor asks
again, so `LspLanguageService.codeLenses` can return blank titles even with no view. Asking again about
the same text then waits for the first resolve to answer, for as long as that takes.

A click on a lens from a server sends `workspace/executeCommand` only for a command the server listed
in `executeCommandProvider`, or in what it registered through `client/registerCapability`. Anything
else is its client's own, as VS Code's "show references" is, and the lens is then only something to
read. A registration of `textDocument/codeLens` counts as resolving lenses only if its options say
`resolveProvider: true`.

## Inline text and inlay hints

`DecorationStyle`, `EditorOptions` and `WebEditorOptions` gain fields, and `WebLanguageProviders` a
parameter, so code compiled against 0.8 that calls their constructors or `copy` needs a recompile.
Source does not change, but for a trailing lambda meant for `documentColors`:

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

## Semantic highlighting

`EditorOptions` and `WebEditorOptions` gain `semanticHighlighting`, off by default, and
`LanguageService` gains `semanticTokens`, with a default of none. With the option on, the tokens a
service answers are drawn over the syntax colours, in the text only: the minimap and
`CodeEditorState.tokensForLine` stay the syntax's.

Tokens from a language server are named as a theme already colours them: `class` becomes
`type.class`, `parameter` becomes `variable.parameter`, `method` becomes `function.method`, and
modifiers follow (`function.method.static`). A Monaco theme rule named `class` or `parameter` does not
match them; name it `type.class` or `variable.parameter`, or let `type` and `variable` colour them.
On the web the option is the plain key `semanticHighlighting: true`; Monaco's
`'semanticHighlighting.enabled'` is not read.

## Language servers

### The client

- **`LspClient` gains a `host` parameter**, an `LspClientHost` whose members all have defaults, so
  source compiles unchanged and compiled code needs a recompile. Pass one to hear `showMessage`,
  `logMessage` and `$/progress`, to answer `window/showMessageRequest`, and to apply
  `workspace/applyEdit` and renames that reach other files.
- **`initialize` declares `DefaultClientCapabilities`** where it declared `{}`. Servers now send
  Markdown hover, snippets, and completion details left for `completionItem/resolve`, which 0.8
  could not show. They may also stop pushing diagnostics, because the client says it can pull them:
  `LspLanguageService` pulls, but a host driving `LspClient` alone and relying on
  `addDiagnosticsListener` should pass capabilities without `textDocument.diagnostic`.
- **Requests a server sends run concurrently**, off the loop that reads its messages, so replies can
  go out of order, and a callback that suspends no longer holds up every answer. Diagnostics reach
  a `DiagnosticsListener` one at a time, but not always from that loop.
- **A default host refuses `workspace/applyEdit`** with `applied: false`, where 0.8 answered
  `MethodNotFound`.
- **`shutdown()`** sends `shutdown` and `exit` and stops the client, bounded at eleven seconds even
  when the server has stopped reading.
- **`StreamLspTransport.close()` no longer blocks** behind a read or write on a hung process. It
  may return before the streams are closed, so a host that started the server's process should
  destroy it after stopping the client. Closing now reaches a healthy server's input, which on
  Windows it did not.
- **A server that counts positions in anything but UTF-16** is kept to whole-text sync and pushed
  diagnostics, with a `logMessage` to the host saying so. Nothing else is asked of it, and its
  `workspace/applyEdit` is refused.

### The language service

- **What the server declared decides what is sent.** A request for a feature the server did not
  declare, or register, returns empty without being sent. Rename keeps its `supportsRename` gate,
  which now follows the server's capabilities when you pass no `serverRenameSupport`.
  `supports("renameProvider")` and the like tell a host's own menu what the server offers.
- **`attach(state)` keeps the server in step** edit by edit, with incremental `didChange` where the
  server takes it, and is the way to follow an editor; `didOpen` and `didChange` still work as in 0.8
  for a host that sends whole texts itself, and throw while a state is attached. A second `didOpen`
  for a document still open sends one whole-text `didChange`, or nothing when the text is the same,
  instead of a second `didOpen`, which the protocol forbids.
- **Pull diagnostics.** With a server that pulls, or whose capabilities are not known,
  `LspLanguageService` asks for diagnostics 400 ms after the document opens or changes, and stops
  asking if the server answers `MethodNotFound`. `diagnostics(document)` still reads only what has
  arrived, so a host learns of new ones through a diagnostics listener, as with pushed ones.
- **Renames that reach other files** go to `LspClientHost.applyWorkspaceEdit` first; the edits in
  this document are applied only if the host applied the rest. A default host refuses, so the rename
  gives nothing, as in 0.8. A rename or code action whose `documentChanges` cannot be read is now
  refused whole, where 0.8 applied the part it could read.
- **Folding and semantic tokens wait for the document to open**, and give up, with nothing sent, if
  the handshake fails or the connection ends.

### Markdown from a server

`LspLanguageService` now gives Markdown for hover, signature help and completion documentation:

- A `plaintext` `MarkupContent`, and a bare string in signature or completion documentation, are
  escaped to show to the letter, line for line, losing only each line's indentation. A host that
  reads `HoverDoc.content` or `CompletionItem.documentation` itself sees the escapes.
- A `MarkedString`'s `{ language, value }` becomes a fenced code block. With no code in it, it counts
  as no answer.
- A bare string in a hover is Markdown, as the protocol says, and stays as it is.

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

The template also gains `aardinkOnOpenLink`, `aardinkConnectLanguageServer`,
`aardinkDisconnectLanguageServer`, `aardinkRegisterInlineCompletionsProvider` and
`aardinkDisposeInlineCompletionsProvider`, which your copy needs for those features.

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
  `kotlinx-coroutines-core` is now `api`, as `DisposableHandle` is in a public signature.
- The npm package's `aardink-web.wasm` is 5,537,300 bytes, 1,693,769 gzipped, where 0.8.0's was
  5,051,231 and 1,548,756: 486,069 bytes larger (9.6%), or 145,013 gzipped (9.4%), for everyone,
  with or without a server. The language server bridge is 221,713 of that (62,724 gzipped), and
  the rest is the other features of 0.9. `skiko.wasm` is the same 8,648,883 bytes.
- With a server connected, a built-in language's answers come after the server's, so a formatter or
  outline the language has gives way to the server's. A language from `registerLanguage` answers
  before the server, and so does the language it `extends`.
- A server never changes what typing does: Enter, closing pairs and surrounding a selection stay the
  language's, in every language, and an editor with no language service types as without a server.
- `AardinkWeb.revalidate`, and diagnostics a server pushes, also reach the language's own
  diagnostics while a host's markers are shown beside them, which 0.8 left as they were.
