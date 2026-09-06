# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

<!-- Release workflow:
  Day-to-day:
    Add entries under [Unreleased] in your PR. You can run ./update-changelog.ps1
    on main to auto-classify commits since the last tag into the right sections.

  Cutting a release:
    1. ./create-release.ps1            # bumps gradle.properties VERSION_NAME,
                                       # cuts [Unreleased] -> [X.Y.Z] - YYYY-MM-DD,
                                       # commits, and creates an annotated tag.
    2. git show vX.Y.Z                 # review the staged commit and tag.
    3. ./release.ps1                   # pushes main + tag, triggering CI publish.

  CI (.github/workflows/release.yml) verifies the tag matches VERSION_NAME, publishes
  to Maven Central, then extracts this matching ## [X.Y.Z] section to create the
  GitHub Release. It will fail if no matching section exists. -->

<!-- Commit prefix → Changelog heading (1:1 mapping)

  feat:        → Added        New features
  refactor:    → Changed      Changes to existing functionality
  deprecated:  → Deprecated   Soon-to-be removed features
  removed:     → Removed      Now removed features
  fix:         → Fixed        Bug fixes
  security:    → Security     Vulnerability fixes
  chore:       → (omitted)    Maintenance, tooling, config
  docs:        → (omitted)    Documentation-only changes

  Append ! for breaking changes: feat!:, fix!:, removed!: etc. -->

## [Unreleased]

### Added

- **TOML Language Support**: Built-in TOML tokenizer (`TomlTokenizer`), folding provider (`TomlFoldingProvider`), and language service (`TomlLanguageService`) supporting Android Version Catalog completions, duplicate key diagnostics, auto-closing, and formatting. Diagnostics, formatting and folding all read the document through one string-and-bracket scanner, so multiline values, inline comments after a table header, quoted keys containing `=`, and brackets or `#` inside a quoted value are treated as the data they are. Table headers are recognised only at the start of a line, so an array value such as `deps = ["a", "b"]` is highlighted as strings rather than as a header.
- **Enhanced XML & HTML Services**: Smart tag auto-closing (`<tag>` -> `</tag>`, `</` tag completion), Android XML element/attribute/value completions, duplicate attribute diagnostics, unescaped `&` warnings, and tag-depth formatting. Formatting re-indents whole-line markup only, leaving text nodes and mixed content verbatim; attribute names are scanned with quote state so `foo=` inside a value is not an attribute; element and attribute names fold case only in HTML mode; a bare `&` inside an XML attribute value is flagged the same way as one in a text node, while HTML tolerates it and treats `<script>` and `<style>` contents as raw text; and a `>` typed in text content no longer auto-closes a tag that was already closed.
- **Enhanced JSON Service**: Auto-closing (`{`, `[`, `"`), value/property completions, duplicate key diagnostics on the decoded key so two escapes naming one member are one member, smart indentation, and 4-space JSON formatting. Completions after a comma follow the innermost container, skipping strings, so an array gets value completions and an object gets property completions, and an opening `[` offers values.
- **Kotlin Language Service**: Built-in `KotlinLanguageService` with syntax error diagnostics, dot completions for stdlib methods (`map`, `filter`, `let`, `apply`), `@` annotation completions (`@Composable`, `@OptIn`, `@Preview`), `@Composable` snippets, auto-closing, and formatting. Character literals, raw strings and nested block comments are recognised as such, so a `'}'`, a brace in a comment, or whitespace inside a `"""` string is never mistaken for structure, and a lone apostrophe in a backtick-quoted name opens no character literal when formatting.
- **Extended LSP Protocol Models & Contract**: `TextEdit`, `Location`, `CodeAction`, `SignatureHelp` data models in `:editor`, and extended `LanguageService` default methods for code actions, definition lookup, references, signature help, range formatting, and rename refactorings. `ParameterInformation.labelRange` lets a provider say where a parameter sits in the signature, so `foo(Int, Int)` highlights the parameter the server meant rather than the first text match. `CompletionItem.additionalEdits` carries edits that belong with a completion but sit elsewhere in the document, so accepting an auto-import completion inserts the symbol and its import in one undo batch. `LanguageService.supportsRename` lets a host tell in advance whether a rename affordance is worth offering, since `prepareRename` and `rename` both default to declining.
- **Atomic Multi-Edit Batch Application**: `CodeEditorState.applyTextEdits()` to apply multi-edit batches in reverse offset order and record them as single undoable operations in `EditorUndoManager`. Edits sharing an offset are applied last-to-first so that, as LSP requires, several inserts at one position appear in the order the batch lists them, and the selection is carried through the batch, so a rename or an import inserted above the caret moves it along instead of leaving it at an offset that now points into unrelated text.
- **LSP Editor UI Components**: Floating `SignatureHelpPopup` for parameter hints, `CodeActionMenu` popup for quick fixes/refactorings, "💡 Quick Fix" button in `AnnotationTooltip`, and `RenameDialog` prompt for symbol renaming. A rename whose edits arrive after the document has changed is discarded rather than applied to offsets that no longer mean what the server measured. The tooltip's dismiss and quick-fix controls are real buttons with accessibility labels and minimum touch targets (as are the find panel's arrow and close buttons), the rename dialog's text field is labelled, and the signature-help popup is taken down when the host detaches the language service. A diagnostic banner and its quick-fix menu are dismissed by any edit, including the fix itself, since the diagnostic's range no longer describes the text; a rename is dropped when the document was replaced while its dialog was open; and a completion list that outlives a keystroke has its provider-supplied ranges re-addressed, so an item accepted before the fresh list arrives still replaces the right span.
- **Rename & Completion Editor Hooks**: `CodeEditorState.requestRename()` opens the rename dialog for the symbol at an offset (resolved through the language service's `prepareRename`, which also decides whether rename is offered at all - a null answer means the symbol cannot be renamed and no dialog opens), so a host app can drive the rename refactoring from its own menu; `CompletionItem.replaceRange` lets a provider state the exact range a completion replaces - used for a language server's `textEdit` / `InsertReplaceEdit` - instead of the editor guessing a token boundary.
- **CSS LSP Sample**: The sample app now demonstrates the external language-server bridge end to end with an in-process CSS language server, including initialization, document synchronization, CSS completions, hover text, and live `!important` diagnostics.
- **External Language Server Bridge Module (`:languages-lsp`)**: New published module `com.aardarch:aardink-languages-lsp` shipping `LspClient` (coroutine JSON-RPC 2.0 client built on kotlinx-serialization, with multi-listener `publishDiagnostics` fan-out, an `initialize`/`initialized` handshake helper, and generic replies to `client/registerCapability`, `window/showMessageRequest` and `workspace/configuration` server requests), `LspTransport` (stdio/socket stream framing, with whole-frame writes serialised across concurrent senders), and `LspLanguageService` — a full `LanguageService` adapter that maps completions, diagnostics, hover, formatting, code actions, definition/references, signature help, prepare-rename and rename onto LSP requests with offset ↔ line/column conversion. Completion trigger characters come from the server's own `completionProvider.triggerCharacters` via `LspLanguageService.triggerCharactersFrom()`, so member completion after `.` works; code actions that pair an edit with a command are omitted rather than applied by halves; and a closed connection stays closed, so a request sent afterwards fails instead of waiting for an answer that cannot arrive. A rename or code action whose workspace edit reaches another file, or creates, renames or deletes one, is declined outright rather than half-applied, and an action the server marked `disabled` is never offered. The transport bounds the frame size it will allocate for, and a stream failure ends the connection instead of escaping the receive loop into the host's uncaught-exception handler. `core.Location` gained optional `line`/`column` fields so a cross-file definition/reference still carries a usable position. `LspRenameSupport` and `LspLanguageService.renameSupportFrom()` carry the server's `renameProvider` capability, so a server without rename support shows no rename command and one without `prepareRename` is not asked for a range it cannot give; any failed write or read, not only an `IOException`, ends the connection, and the transport is closed exactly once; a client whose coroutine scope was cancelled fails requests instead of hanging them; an edit ending on a line past the last one reaches the end of the document, as a whole-file formatting edit intends; and a code-action request forwards only the diagnostics that actually overlap its range, not a neighbour that merely abuts it. The module's only runtime dependencies beyond `:editor` are kotlinx-serialization-json and kotlinx-coroutines-core - it pulls in no Compose of its own.

## [0.3.1] - 2026-08-29

### Fixed

- Use compileDebugKotlin classpath for Dokka fix: keep commit lists as arrays in release scripts fix: update note on binary compatibility validator and enhance LanguageService description

## [0.3.0] - 2026-06-22

### Added

- Add adaptive launcher icons and update themes for Android 12+
- Refactor Dokka configuration and add version management
- Add spotless to sample app, floss files
- Add proper mascot to the sample app

### Fixed

- Rename variable for user input in version prompt for clarity

## [0.2.0] - 2026-05-12

### Added

- Show find and replace functionality in sample editor screen
- Add dokka markdown output

### Changed

- Simplify the sample ui logo/mascot placeholder
- Fix aardink namespace typo

## [0.1.0] - 2026-05-12

### Added

- Initial version 0.1.0 of Aardink
