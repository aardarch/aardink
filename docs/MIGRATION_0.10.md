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

# Migrating from Aardink 0.9 to 0.10

[AARDINK_ROADMAP.md](AARDINK_ROADMAP.md) §8 has the plan for 0.10, §16 the API ledger and §16.1 what
was built where it differs.

Every new option in 0.10 is off, or does what the editor did before, with one exception: `wordWrap`
on the web is now off, as in Monaco. What can break an upgrade, and what behaves differently with no
change on your side, is in the table. The sections after it give the details, with a before and after
where code changes.

## At a glance

| Change | Who it reaches | What to do |
| --- | --- | --- |
| `wordWrap` defaults to off on the web | Web hosts that never set it | Pass `wordWrap: 'on'` to keep lines wrapped: [Word wrap on the web](#word-wrap-on-the-web) |
| `EditorCommand` gains `SearchWorkspace`, bound to Ctrl/Cmd+Shift+F | An exhaustive `when` over `EditorCommand`; a host that already used that key | Add the branch; the key reaches your own handler as before unless you give `CodeEditorLayout` an `onSearchWorkspace`: [Workspace search](#workspace-search) |
| `EditorOptions`, `EditorTheme`, `InlayHint`, `CodeAction`, `InlineCompletion`, `FindReplaceState`, `LanguageDefinition`, `WebEditorOptions` and `WebLanguageProviders` gain fields | Code compiled against 0.9 that calls their constructors, `copy` or `componentN` | Recompile. Every new field is last and has a default |
| `CodeEditorLayout`, `HoverDocPopup` and `SignatureHelpPopup` gain a defaulted parameter | Code compiled against 0.9 | Recompile |
| The export template gains `aardinkRegisterFont` and `aardinkOnSearchWorkspace` | A wasm module built from `ExportsTemplate.kt` | Copy them: [A wasm module built from the export template](#a-wasm-module-built-from-the-export-template) |
| Monaco's `base: 'hc-black'` and `'hc-light'` start from the new high-contrast themes | `defineTheme` calls with those bases | Check the theme looks as you meant: [High-contrast bases](#high-contrast-bases) |
| `BuiltInLanguages.all` lists twelve more languages | Hosts that offer every built-in language | Nothing, or filter the list: [More built-in languages](#more-built-in-languages) |
| Enter in Markdown keeps the indentation and carries a list or a quote on | Markdown editing | Nothing |
| TypeScript, CSS and Markdown's services are `CompositeLanguageService`s | Code that checks or casts `BuiltInLanguages.X.languageService` | Use the `LanguageService` interface: [Built-in services](#built-in-services) |
| A `{ snippet }` inline completion keeps its tab stops | Web hosts answering inline completions with snippets | Nothing: [Snippets as ghost text](#snippets-as-ghost-text) |
| `aardink-web.wasm` and `skiko.wasm` have a size ceiling in CI; `.wasm` is best served with Brotli | Web hosts | Serve Brotli where you can: [Serving the bundle](#serving-the-bundle) |

## Word wrap on the web

`wordWrap` was on by default in the npm package and in `WebEditorOptions`, where Monaco's is off. It
is off now, so an upgrade looks like Monaco:

```js
// 0.9: wrapped unless told otherwise
await createEditor(container, onChange, { value });

// 0.10: say so to keep it
await createEditor(container, onChange, { value, wordWrap: 'on' });
```

`wordWrap` also takes `'wordWrapColumn'` and `'bounded'` now, both wrapping at `wordWrapColumn` or
the edge of the view, whichever comes first. Android and desktop hosts are not affected:
`EditorOptions.softWrap` keeps its default.

## Workspace search

`EditorCommand.SearchWorkspace` is new, so an exhaustive `when` needs a branch:

```kotlin
when (command) {
    // ...
    EditorCommand.SearchWorkspace -> label = "Search all files"
}
```

`EditorKeyBindings.Default` binds it to Ctrl+Shift+F (Cmd+Shift+F on macOS). It runs only in an editor
given `CodeEditorLayout(onSearchWorkspace = { query, options -> ... })`; elsewhere the command cannot
run and the key goes to the host, as in 0.9. On the web, `editor.onSearchWorkspace(listener)` and
`trigger('workbench.action.findInFiles')` are the same thing.

## A wasm module built from the export template

Users of the npm package are not affected. A module built from `ExportsTemplate.kt` needs two new
exports for 0.10's features, copied from the template as they are:

```kotlin
@JsExport
fun aardinkRegisterFont(name: String, bytes: Int8Array): String = try {
    AardinkWeb.registerFont(name, ByteArray(bytes.length) { bytes[it] })
    ""
} catch (e: IllegalArgumentException) {
    e.message ?: "not a font"
}

@JsExport fun aardinkOnSearchWorkspace(id: Int, callback: ((String) -> Unit)?) = AardinkWeb.onSearchWorkspace(handle(id), callback)
```

`verifyExportsMatchTemplate` in `:sample-web` shows how to keep a copy in step.

## High-contrast bases

0.10 adds `EditorThemes.HighContrastDark` and `HighContrastLight`, and the web's `hc-black` and
`hc-light`. A Monaco theme defined with `base: 'hc-black'` or `'hc-light'` now fills in what it
leaves out from these, where 0.9 took Dark+ and Light+. A theme that sets every colour it cares about
looks the same; one that relied on the base gets high-contrast colours for the rest.

Themes may also set the new chrome colours (`EditorTheme.chromeColors`: the minimap, scrollbars,
guides, rulers, the cards and menus, hints, lenses and ghost text) and token backgrounds. A theme that
sets none looks as it did.

## More built-in languages

`BuiltInLanguages` gains YAML, Properties, INI, Shell, Python, SQL, Java, Groovy (also `.gradle`),
Dockerfile, Diff, SCSS and LESS, and `all` and `LanguageRegistry.withBuiltIns()` list them. A host
that shows every built-in language shows these too:

```kotlin
val offered = BuiltInLanguages.all.filter { it.id in setOf("kotlin", "json", "xml") }
```

`LanguageDefinition` gains `fileNames`, and `LanguageRegistry.byFileName(name)` finds a language by a
whole name (`Dockerfile`, `Jenkinsfile`, `.bashrc`) as well as by extension.

## Built-in services

TypeScript, CSS and Markdown's `languageService` is now a `CompositeLanguageService`: a service from
the language's VS Code configuration over the language's own service, where it had one. They were
internal objects before, so only code that looked at the runtime type notices. Use them through
`LanguageService`.

With it, Markdown's Enter keeps the line's indentation and carries on a `-`, `*` or `+` item, a task
or a quote; CSS formats; and Markdown formats its tables.

## Snippets as ghost text

`InlineCompletion` gains `isSnippet` (false). An inline completion that is a snippet shows what it
expands to, and accepting it replaces the whole range with the snippet, as Monaco does, so Tab steps
through its tab stops. On the web, an `insertText` of `{ snippet }` now does this; in 0.9 it went in
as plain text with its tab stops dropped.

## Serving the bundle

CI now fails a change that grows `aardink-web.wasm` or `skiko.wasm` by more than 2% past the gzipped
sizes recorded for the release. For 0.10, everything the package loads is 15.0 MB, 5.2 MB gzipped and
4.0 MB with Brotli: serve `.wasm` with Brotli where the server or CDN can.

The package no longer ships `skikod8.mjs` or `js-reexport-symbols.mjs`, which nothing in it imported.
An editor whose `fontFamily` names a registered font no longer fetches the bundled JetBrains Mono.

## Language servers

No API changes, but `LspClient` now declares `semanticTokens.requests.full.delta`. A server that
offers deltas is asked for the edits since its last answer, and a server that does not is asked for
the whole answer as before.
