# Aardink 0.6.0 plan: virtualised editor, feature wave, npm, aardflex switch-over

Status: **draft for review** (2026-09-27). Supersedes `KMP_MIGRATION_PLAN.md` §11 and its
"Post-release" note; that document stays as the record of 0.5.0.

## Progress

| PR | State | Notes |
| --- | --- | --- |
| 0 | Not started | Spike: IME on three platforms, virtualised drawing on wasm. Gate for PR 5 onward. |
| 1 | Done | Branch `v0.6-uplift`. Dependencies already current (§3); `0.6.0-SNAPSHOT`; canary workflow; `perf.mjs` + 0.5 baseline; CDP IME check; CMP 1.13 findings (W-1 fixed upstream). |
| 15 | Done (code) | npm job in `release.yml`, package metadata, LICENSE and README in the package, publint + attw in CI. **Waiting on the manual bootstrap** in `docs/NPM_BOOTSTRAP.md`. |
| 2 | Done | `core/text/{GapBuffer,LineIndex,DocumentChange}`, `CodeDocument` as a `CharSequence` with snapshots, internal `TokenStore` (line-aligned, shifted by change events, fixes stale colours after inserted lines), `tokensForLine`, language services get snapshots. 10k keystrokes into 2 MB: ~17 ms (JVM). |
| 3 | Done | Incremental `RegexTokenizer` and `XmlTokenizer` (restart + convergence, scan cache keyed by the identity of the tokenizer's last result, zero-length markers for the covered span), golden test over every built-in language; viewport-first on single-threaded hosts, with a learned fallback for slow partial passes; `tokenizer` var; chunked, capped, cancellable find; replace mode; debounced, prefix/suffix-trimmed diff lane. |
| 4–14, 16, 17 | Not started | See §6. |
| A1–A3 | Not started | `aardflex-web-app` switch-over (§7.1). |
| B1 | Not started | `aardflex` Android upgrade 0.4.0 → 0.6.0 (§7.2). |

## 1. Why

0.5.0 made Aardink multiplatform. It shipped with three documented known issues on the web, and a
plain audit turned up more gaps behind them:

- **Typing latency is linear in document size (W-10).** About 50 ms per keystroke at 6 KB,
  190 ms at 26 KB, and 1 s at 139 KB. The obvious cause is that Compose lays out the whole
  `BasicTextField` as one paragraph, twice per frame. It is not the only cost that scales with
  the document, though. Each of these also runs over the whole document per edit or per frame:
  - `annotateTokens` builds a full-document `AnnotatedString`.
  - `EditorOutputTransformation` compares the full string, then replays every span.
  - `lineTops`/`lineBottoms` (`CodeEditorLayout.kt:644-688`) cover every line, re-sorting the
    folds once per line.
  - The gutter loop starts at line 0.
  - The diff lane runs over `lines()` of both texts.
  - `CodeDocument` rebuilds its line index in O(n) after every edit.
  - `syncFieldToDocument` replaces the whole field text on every programmatic edit, undo and
    redo.
  - CMP's web input writes the **whole text into a hidden `<textarea>`** on every change
    (`DomInputStrategy.updateState`).

  Fixing W-10 therefore means a new render and input path, not a tweak.
- **A disposed web editor leaks about 275 KB (W-1).** `ComposeViewport` has no teardown in
  CMP 1.12.1. Upstream tracks this as CMP-9090 and CMP-10507, and has fixed it for CMP 1.13
  (compose-multiplatform-core PR #3242, merged 2026-07-21). The viewport's root is now a
  `<compose-component>` custom element whose `disconnectedCallback` disposes the whole app
  (recomposer, scene, Skia layer, canvas event listeners) as soon as it leaves the DOM.
- **Fast typing loses keys on the web** (found while building PR 1's performance gate). A key
  that arrives while the editor is still laying out after the previous one can be dropped. At
  1,000 lines a 10 keys/s burst loses about half the keys, and at 5,000 lines nearly all of
  them. At 250 lines nothing is lost. PR 6 and PR 7 gate on zero lost keys.
- **Manual device checks were never run:** W-2 IME, W-4 selection/touch, W-5 mobile keyboard,
  W-6 browser shortcuts, W-8 zoom, W-9 font flicker.
- **Existing capability that is never shown to the user:**
  - `HoverDocPopup` is never called.
  - `definition`, `references`, `format` and `formatRange` have no UI.
  - Built-in language-service diagnostics are never collected automatically. The web never
    shows them.
  - `EditorTheme.lineHighlight` is unused.
  - There is no bracket matching.
  - Popups sit at fixed positions, not at the caret (`SignatureHelpPopup.kt:40-43`).
- **Bugs found along the way:**
  - Squiggles use document offsets against the folded layout, so they are misplaced when
    anything is folded.
  - Pending navigation passes an untransformed offset into transformed layout space
    (`CodeEditorLayout.kt:383-389`).
  - Language services run on `EditorDispatchers.compute` against the *live, mutable*
    `CodeDocument`, which is a data race on Android and JVM.
  - Changing the language on the web rebuilds `CodeEditorState` and loses undo history.
- **Delivery:** `@aardarch/aardink-web` is built and tested but not published anywhere, and
  `aardflex-web-app` still runs Monaco.

## 2. Decisions (made 2026-09-27)

| Decision | Choice |
| --- | --- |
| Renderer | One virtualised renderer and our own text-input layer on **all** platforms (Android, desktop JVM, wasmJs). One code path; the whole-document `BasicTextField` goes away. |
| API compatibility | **Clean break in 0.6.0** plus `docs/MIGRATION_0.6.md`. No deprecation bridge. Every new public symbol is listed in §5 for approval. |
| Features | Multi-cursor (Ctrl/Cmd+D, Ctrl+Shift+L, Alt+click, column selection), Ctrl+/ comment toggle, minimap, go-to-definition / references peek / format UI, bracket-pair colouring, sticky scroll. Also: hover wired, automatic diagnostics, current-line highlight, bracket matching, caret-anchored popups, find replace mode. |
| Web delivery | Publish `@aardarch/aardink-web` to **public npmjs** from `release.yml`. `aardflex-web-app` consumes it from npm and drops Monaco; this is the final phase. |

### Follow-up decisions (settled 2026-09-27)

| # | Question | Decision |
| --- | --- | --- |
| 1 | Seven boolean parameters vs. an options object | **`EditorOptions` object.** `CodeEditorLayout(options: EditorOptions = EditorOptions())` replaces the booleans and carries every new toggle, so adding a toggle later no longer changes the composable's JVM signature. |
| 2 | Undo surface | **Follow Monaco** (§4.4a): the undo stack is internal; the public surface is `undo`/`redo`/`canUndo`/`canRedo`, explicit undo stops, undoable edits by default, `loadText` as a flush, an alternative version id for dirty tracking, and the kind of each change. |
| 3 | npm auth | **npm trusted publishing (OIDC) only, no token**, using the pattern already in production in `sjohansson/astro-components` (`.github/workflows/release.yml`), under a new `@aardarch` npm org. Full runbook in §6 PR 15; the PR lands early, right after PR 1. |

## 3. Version baseline

Current stack, from `gradle/libs.versions.toml`:

| Component | Version |
| --- | --- |
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.1 (material3 1.9.0, on its own version line) |
| AGP | 9.4.1 |
| Gradle | 9.8.0 |
| coroutines / serialization | 1.11.0 / 1.11.0 |
| Dokka | 2.2.0 |
| vanniktech | 0.37.0 |
| Roborazzi | 1.75.0 |
| JVM toolchain | 21 |
| Web tooling | Vite 8.3, puppeteer-core 25.12, Node 24, pnpm 11 |

Policy for this plan:

- **PR 1** takes every available stable bump, including a CMP 1.12.x patch if one exists.
  Checked 2026-09-27: every catalog entry, Gradle 9.8.0 and every GitHub Action were already on
  their newest stable release. pnpm stays on 11 (as in astro-components); pnpm 12 is a major
  with a new lockfile and gains nothing here.
  - CMP 1.13.0-alpha01 already compiles, and `:editor:jvmTest` passes, with no source change
    (local canary run).
- **CMP 1.13 is wanted for 0.6.0.** It fixes W-1 (§1), and its web text input moved to a
  `contenteditable` backing element (compose-multiplatform-core #3167). Checked on `jb-main`
  (2026-09-27): the skiko `PlatformTextInputMethodRequest` only gained `editorToken: Any?` with
  a default getter, so a request written against 1.12.1 still compiles.
  - As of 2026-09-27, 1.13.0-alpha01 is the newest build.
  - The spike (S1, S3, S5) runs against both 1.12.1 and the newest 1.13 pre-release.
  - Adopt 1.13 at the first stable release before PR 17. If it isn't stable by then, ship
    0.6.0 on 1.12.x with the W-1 known issue, and adopt 1.13 in 0.6.1.
- **Build conventions:** every snippet follows the current DSL in `KMP_MIGRATION_PLAN.md`
  §2.4b (`android {}`, `SourcesJar.Sources()`, `named("jvmTest") { dependencies {} }`,
  `libs.compose.mp.*`).
- **Canary CI job:** add a non-blocking job that builds `:editor` against the next CMP dev
  build, because the skiko input members are `@ExperimentalComposeUiApi`.
- **Regexes:** still no lookbehind anywhere, including in user-registered grammars (§4.8).

## 4. Architecture

### 4.1 Text input: our own node, two thin platform actuals

The following was verified against the CMP 1.12.1 sources jars in the Gradle cache.

**Common, public and stable (`ui`):**

- `PlatformTextInputModifierNode`
- `establishTextInputSession { startInputMethod(request) }`
- `PlatformTextInputMethodRequest`, which is an `expect interface` with no common members.

**Android** (`ui-android`): `fun interface PlatformTextInputMethodRequest { createInputConnection(EditorInfo) }`.
This is stable. We write `EditorInputConnection` directly as an `InputConnection`, not a
`BaseInputConnection` subclass. The references to follow are foundation's
`StatelessInputConnection.android.kt` and `CursorAnchorInfoController.android.kt`.

**Desktop and wasm (skiko):**

- The request is a public `actual interface` whose members are `@ExperimentalComposeUiApi`:
  `state: TextEditorState`, `editText`, `value()`, `onEditCommand`, `focusedRectInRoot`,
  `textClippingRectInRoot` and others.
- One implementation lives in a new `skikoMain` intermediate source set (jvm + wasmJs). It is
  modelled on foundation's `SkikoPlatformTextInputMethodRequest` in
  `TextInputSession.skiko.kt`.
- Desktop maps AWT `InputMethodEvent`s to `TextEditingScope` calls.
- Web routes `beforeinput` through `onEditCommand`, and positions its hidden textarea from
  `focusedRectInRoot`.

**One common core:** `ui/input/ImeWindowBuffer.kt` (internal).

- Holds a window `[start, end)` over the document, with selection and composition in window
  coordinates.
- Interprets the public `EditCommand` classes: commit, setComposingText, setComposingRegion,
  finish, deleteSurrounding (UTF-16 and code points), setSelection and backspace.
- `flush()` diffs the common prefix and suffix and emits **one** minimal edit through the same
  transaction path as keyboard edits.

**Window policy:**

- **wasm:** caret line ±20 lines, at most about 4 KB on each side. It is re-based only when
  there is no composition. This fixes the full-document textarea copy.
- **Android and desktop:** a zero-copy whole-document `CharSequence`, because those platforms
  only query bounded slices. Re-basing on Android would force `restartInput`, which makes the
  keyboard flicker.

**Key events:** the node implements `KeyInputModifierNode`. `platform/EditorKeyEvents.kt`
(expect) decides whether a key event carries a typed character:

| Platform | Typed character comes from |
| --- | --- |
| Android | `unicodeChar`, plus a dead-key combiner |
| JVM | AWT `KEY_TYPED` |
| wasm | Never from the key event; typed text arrives as `EditCommand`s |

**Fallback, spike only:** if approach A fails on a platform, use a 1×1 hidden
`BasicTextField` "input proxy" that holds the window and diffs into the document, on that
platform only. Costs: `restartInput` on every rebase, suppressed duplicate handles and undo,
and two accessibility nodes.

### 4.2 Document model (`core/`)

- **`CodeDocument`** keeps its method names. Internally it becomes `core/text/GapBuffer.kt`
  plus `core/text/LineIndex.kt`, a gap-buffered array of line starts. Typing is amortised O(1),
  and `offsetToLineCol` stays O(log n).
- It implements `CharSequence`, so `Regex.find(document)` works without calling `document.text`.
- **`DocumentChange` events** (internal) carry offset, deleted length, inserted text, affected
  lines and version. They shift token lines, map diagnostics, find matches and folds until the
  next recompute, adjust selections and the bracket index, and invalidate the layout cache.
- **`snapshotText()`** is cached per version. **Language services receive an immutable
  snapshot**, which fixes the data race.
- **Batch edits** apply from high offsets to low; above about 16 edits the buffer is rebuilt
  once.
- **Not a piece tree:** at the existing 2 MB plain-text limit it is more code for no gain.

### 4.3 Tokens

- **`TokenCache` becomes internal:** a line-aligned array of line-relative runs,
  `(startCol, endCol, TokenType)`, shifted on change events. Today every edit orphans the keys
  below it.
- **Viewport first:** visible lines are tokenized first, then the rest cooperatively (wasm) or
  on `compute`. This fixes the 1.4 s first-layout long task.
- **`RegexTokenizer` goes incremental** (internal to `:languages`):
  - Restart at the first dirty line, or at the start of a multi-line token covering it.
  - Stop when, past the dirty range, a token boundary matches the old one shifted by the delta.
  - `IncrementalTokenizer`'s contract is unchanged.
- **Bracket index** (`core/BracketIndex.kt`, internal): bracket depth at the start of each
  line, computed during the token pass, skipping strings and comments. It drives bracket
  colours and matching.

### 4.4 Selections, commands, undo (`core/edit/`, UI-free, `commonTest`)

- **`Selections`:** a list of `TextRange` (reversed allowed) with a primary index and a
  preferred x position per cursor. Overlapping ranges are merged after each operation.
- **`EditTransaction`:** ranges and replacement texts, plus selections before and after. It is
  atomic and bumps `textVersion` once. **Every** edit source goes through it: keys, IME flush,
  paste, toolbar, completion, code action, rename, format, replace, comment toggle, line ops.
- **`TypingRules`:** logic moved out of `EditorInputTransformation` — CRLF normalisation,
  smart indent, auto-close and the completion trigger. Smart indent and auto-close are off
  while a composition is active.
- **`TextNavigator`:** character, word, smart Home, End, line up/down (through a `LayoutQuery`
  interface so wrapped rows work), page, document start/end, and every select variant.
- **Undo:** see §4.4a.
- **Language change keeps undo:** `tokenizer` becomes a `var`.

### 4.4a Undo and redo, modelled on Monaco

Monaco (checked against `monaco-editor` 0.55.1's `editor.api.d.ts` in aardflex-web-app) keeps
its undo stack private and gives hosts a small, stable surface:

| Monaco | What it does | Aardink 0.6.0 |
| --- | --- | --- |
| `model.undo()` / `redo()` | Step the stack; cursors restored | `CodeEditorState.undo()` / `redo()` (exist; now restore every cursor) |
| `model.canUndo()` / `canRedo()` | Enable toolbar buttons | `canUndo` / `canRedo`: snapshot-state `Boolean`s, so Compose buttons recompose |
| `editor.pushUndoStop()` / `model.pushStackElement()` | Close the open group, so the next edit starts a new entry | `pushUndoStop()` |
| `pushEditOperations` / `editor.executeEdits` | Undoable programmatic edits with cursor state | `applyEdit` / `applyTextEdits` stay **undoable**, one entry per call, with an undo stop before and after |
| `applyEdits` (documented as dangerous to the undo stack) | Edits that bypass undo | **Not offered.** Every edit path goes through `EditTransaction` |
| `setValue()`, change event `isFlush` | Replaces content and clears history | `loadText()` (already clears history); reported as a flush |
| `getVersionId()` | Increments on every change | `textVersion` (exists) |
| `getAlternativeVersionId()` | Returns to an earlier value when undo gets back there; used for dirty tracking | `alternativeVersionId: Long`. A host saves it on save; `isDirty = alternativeVersionId != saved`. This replaces comparing the text against `savedText` |
| Change event `isUndoing` / `isRedoing` / `isFlush` | Lets hosts skip work on undo (e.g. format on type) | `lastChangeKind: EditChangeKind` (`Edit`, `Undo`, `Redo`, `Flush`), also passed to web change listeners |

Grouping rules, as in Monaco:

- Consecutive typing stays in one entry. The entry closes when the cursor moves, when a word
  boundary or whitespace is typed after a word, when the edit kind changes (typing vs. delete),
  after 500 ms idle, or on `pushUndoStop()`.
- An IME composition is one entry, from the first composing text to the commit.
- A multi-cursor edit is one entry. Undo restores every cursor and selection from before the edit.
- Paste, completion, code actions, rename, format, replace-all, comment toggle and line moves
  are each one entry.

Internals: `EditorUndoManager` becomes internal and its cap rises from 200 entries to 1,000.
Entries store inverse edits plus selection sets, not text snapshots.

### 4.5 View model (`ui/view/`, internal)

- **`VisualLineMap`:** folded intervals with prefix sums, mapping visible index ↔ document
  line in O(log f).
- **`WrapRowIndex`** (soft wrap only): a Fenwick tree of rows per line.
  - The initial estimate, `ceil(chars × advance / width)`, is exact for monospace ASCII.
  - It is corrected when a line is measured.
  - Scroll anchoring stops the view from jumping as estimates change.
- **`EditorScrollController`:**
  - Anchor-based (`firstVisibleRow`, `rowOffsetPx`, `horizontalPx`).
  - Exposes `ScrollableState` for wheel, touch, fling and Android overscroll.
  - Exposes a `foundation.v2.ScrollbarAdapter` (public on skiko) for the desktop and web
    scrollbars.
  - Horizontal content width only grows. It is the larger of measured widths and
    `maxLineChars × advance`.
- **`LineLayoutCache`:**
  - An LRU of about 2 × visible rows + 64 per-line `TextLayoutResult`s.
  - Keyed by (line text, colour-span stamp, style epoch, wrap width). The style epoch covers
    theme, typography, density, fontScale and font-load state.
  - **Only colour spans go into a layout.** Selection, find matches, current line, squiggles,
    bracket boxes and the composition underline are drawn as rectangles from
    `getPathForRange`, `getBoundingBox` and `getCursorRect`, so none of them causes a relayout.
- **`EditorHitTester`:** y → row → (line, wrap row) → `getOffsetForPosition`. A hit on a fold
  placeholder unfolds. The gutter uses the same row map.

### 4.6 Drawing and interaction

**`ui/view/EditorViewportNode.kt`** is one `Modifier.Node` implementing layout, draw, pointer,
key input, focus target, semantics, `PlatformTextInputModifierNode` and
`CompositionLocalConsumerModifierNode`.

It draws three layers:

1. **Backgrounds:** current line, selections, find matches, bracket match and the fold chip.
2. **Text:** in a `graphicsLayer`, one `drawText` per visible row.
3. **Overlay:** squiggles, composition underline, carets, and optionally whitespace. Carets
   blink through `withFrameMillis`, only while focused.

**Why not `LazyColumn`:** selection across items, per-item horizontal scroll, keeping the
gutter aligned, and a single IME target would all have to be rebuilt on top of it anyway.

**Around the node:**

- The gutter, sticky scroll and minimap are internal composables that read the same view model.
  No second `ScrollState` has to be kept in sync.

**Pointer:**

- Click places the caret; drag selects, with auto-scroll at the edges.
- Double-click selects a word; triple-click selects a line.
- Shift+click extends; Alt+click adds a cursor.
- Shift+Alt+drag or middle-drag makes a column selection.
- Ctrl/Cmd+hover underlines a symbol; Ctrl/Cmd+click goes to its definition.
- Hovering 500 ms shows hover docs.

**Touch** (common `PointerType.Touch`, so web touch gets it too):

- Tap places the caret and shows the keyboard; long-press selects a word.
- We draw our own teardrop handles.
- The platform menu comes from `LocalTextToolbar.showMenu`.
- `Modifier.magnifier` on Android, through an expect/actual.

**Right-click** (desktop and web): a Material3 `DropdownMenu` with Cut, Copy, Paste, Select
All, Go to Definition, Find References and Format.

**Clipboard (`platform/EditorClipboard.kt`):**

- `LocalClipboard` on Android and JVM.
- On wasm, a `document`-level `paste`/`copy`/`cut` listener, active only while the editor has
  focus. It uses `clipboardData` with `preventDefault`, so Firefox shows no permission prompt.
- Our key handler must **not** consume Ctrl/Cmd+C/V/X on wasm, so that CMP lets the native
  event fire.

**Keyboard:** `ui/view/EditorKeyBindings.kt` replaces `EditorShortcuts.kt`. It is a table,
using Cmd on macOS via `PlatformInfo`:

- All navigation and select variants.
- Delete word, duplicate line, move line.
- Tab and Shift+Tab indent, Ctrl+/ comment.
- Ctrl/Cmd+D next occurrence, Ctrl+Shift+L all occurrences.
- Ctrl+Alt+↑/↓ add cursor, Esc collapses cursors.
- Ctrl+F find, Ctrl+H replace, Ctrl+G go to line.
- F12 definition, Shift+F12 references, Shift+Alt+F format, Ctrl+Space completions.
- Undo and redo.

Keys the browser owns (Ctrl+W, Ctrl+T, Ctrl+N) cannot be intercepted; this is documented under
W-6.

**IME with multiple cursors:** composition happens at the primary cursor only. The committed
edit is mirrored to the other cursors, as in VS Code.

**Semantics (`ui/view/EditorSemantics.kt`):**

- `editableText` is a window of the caret line ±50 lines, with the selection in window
  coordinates.
- Actions: `SetText`, `InsertTextAtCursor`, `SetSelection`, `RequestFocus`, `CopyText`,
  `CutText` and `PasteText`.
- The description reads "Code editor, line X of Y", and the node gets
  `testTag(EditorTestTags.EDITOR)`.
- UI tests switch from `TEXT_FIELD` to `EDITOR` and otherwise barely change.

**Popups:** `ui/EditorPopupLayer.kt` provides caret-anchored positioning that flips above or
below and clamps to the window. It hosts completion, signature help, hover, code actions and
the references peek. On touch, completion stays a strip above the keyboard. The public popup
composables keep their signatures.

### 4.7 Features

- **Automatic diagnostics:**
  - `CodeEditorLayout(diagnostics = null)` means the editor runs `languageService.diagnostics`
    on a snapshot, 500 ms after the last edit, guarded by document version and cooperative on
    wasm.
  - A non-null list means the host supplied it; the service is not asked. aardflex keeps this
    behaviour.
  - Ranges are mapped through edits until the next run, and `onDiagnosticsChange` reports each
    new list.
- **Current-line highlight:** `theme.lineHighlight` on each cursor's line while the selection
  is collapsed.
- **Bracket matching:** a box around the matching bracket, found through the bracket index and
  limited to 50k characters from the caret.
- **Bracket-pair colouring:** colour = nesting depth mod n, taken from
  `EditorTheme.bracketPairColors`. The parser reads
  `editorBracketHighlight.foreground1..6`.
- **Sticky scroll:** up to five enclosing fold ranges of the top visible line stay pinned. Each
  is drawn from the layout cache; clicking one navigates to it.
- **Minimap:** a canvas on the right with 1–2 px coloured blocks per line (no glyphs).
  - Drawn as cached `ImageBitmap` tiles of 256 lines; only dirty tiles are redrawn.
  - Scaled above 10k lines, with a draggable slider.
  - Off by default.
- **Go to definition, references, format:**
  - A same-document location navigates directly; any other URI goes to
    `onNavigateToLocation`.
  - References open a peek list.
  - `format()`'s output is reduced to a minimal edit with one undo entry. `formatRange` is used
    when there is a selection.
- **Find:**
  - Replace mode (`show(replace = true)`, Ctrl+H).
  - Chunked and cooperative on wasm, with the visible range highlighted first.
  - Capped at `EditorLimits.maxFindMatches` (shown as "10000+").
- **Diff lane:** debounced, cooperative and limited by size. On the web it becomes live
  through `setBaseline(text)`.

### 4.8 Web: languages and themes registered from JavaScript

`aardflex-web-app` needs its own grammar, theme and completions. It should get them without
building its own wasm executable.

- **`:languages` → `DeclarativeGrammar` + `DeclarativeTokenizer`**, a Monarch subset:
  - Grammar: states; rules of `[regex, token | groups, next | @pop | @push | @rematch]`;
    `cases` with `@array`/`@default`; `defaultToken`.
  - The tokenizer is line-stateful, so it is truly incremental.
  - Lookbehind is **rejected at parse time**, with the offending path in the error.
  - `NamedTokenType` resolves colours by dotted prefix, so `tag.aardflex` falls back to `tag`.
- **`:editor-web`:**
  - `registerLanguage(definitionJson, providers)`, with `extends: "xml"` so a language
    inherits a built-in language service.
  - `WebLanguageProviders` carries JS completion, hover and diagnostics callbacks, bridged from
    Promises.
  - `registerTheme(name, vsCodeThemeJson)`, built on `EditorThemeParser.fromJson`.
  - The npm `index.js` also converts Monaco `defineTheme` rules.
- **W-1:** fixed by CMP 1.13's auto-dispose (§1).
  - `AardinkWeb.dispose()` removes the viewport's `<compose-component>` from its container,
    which on 1.13 triggers the full teardown. On 1.12.x the same call only stops the editor, and
    the "reuse one editor" guidance stays.
  - **No editor pool.** Parking a viewport by moving its element to another parent fires
    `disconnectedCallback`, which on 1.13 disposes the app, so a reparenting pool would destroy
    the editors it keeps.
  - Hosts must not move a mounted editor's container around the DOM (document it; Svelte
    `{#key}` blocks and React re-parenting do this).
- **W-9:** `preloadAardink()` also fetches the font, and mount waits up to 500 ms for it. A
  font-load epoch invalidates the layout cache.
- **W-8:** density and `fontScale` are part of the layout-cache key.
- **W-5:** documented host CSS (`100dvh`, `interactive-widget=resizes-content`). The backing
  textarea follows `focusedRectInRoot`.

## 5. Public API ledger (every line needs approval)

### `:editor` — removed

- `CodeEditorState.textFieldState`
- `CodeEditorState.tokenCache` and class `TokenCache` (becomes internal)
- `CodeEditorState.undoManager`, `EditorUndoManager` and `EditOperation` (become internal; §4.4a)
- The `CodeEditorLayout(annotatedText = …)` parameter
- `EditorGutter(...)`, `DrawScope.drawSquiggles(...)` and `annotateTokens(...)` (become
  internal)
- `applyFolding(...)`
- `EditorTheme.fontFamily`, `fontSize` and `lineHeight`

### `:editor` — changed

- `CodeEditorState.selection`: the setter becomes public and collapses to one cursor.
- `CodeEditorState.tokenizer`: `val` → `var`.
- `CodeDocument` implements `CharSequence`.
- `FindReplaceState.show()` → `show(replace: Boolean = false)`.
- `CodeEditorLayout(diagnostics: List<Diagnostic>? = null)`: `null` means "collect from the
  language service".

### `:editor` — new

1. `CodeEditorState.selections: List<TextRange>` and `setSelections(List<TextRange>)`, where
   the last entry is primary.
2. Undo (§4.4a): `CodeEditorState.canUndo`, `canRedo`, `pushUndoStop()`,
   `alternativeVersionId: Long`, `lastChangeKind: EditChangeKind`, and
   `enum class EditChangeKind { Edit, Undo, Redo, Flush }`.
3. `CodeEditorState.tokensForLine(line): List<Token>`
4. `FindReplaceState.replaceMode`
5. `EditorLimits.maxFindMatches`
6. `EditorTheme.bracketPairColors: List<Color>`, with a default.
7. `data class CommentSyntax(line, blockStart, blockEnd)` and
   `IncrementalTokenizer.commentSyntax: CommentSyntax? = null`. Built-in languages fill it in.
8. `CodeEditorLayout(onNavigateToLocation, onDiagnosticsChange)`
9. `@Immutable data class EditorOptions` (decision 1), replacing `readOnly`, `softWrap`,
   `showGutter`, `showLineNumbers`, `showFoldMarkers`, `showDiagnosticAnnotations` and
   `showDiffMarkers` on `CodeEditorLayout`, and adding `highlightCurrentLine`, `matchBrackets`,
   `bracketPairColorization`, `stickyScroll`, `showMinimap`, `tabSize` and `insertSpaces`
   alongside the existing toggles.
10. Temporary, never released: `@ExperimentalAardinkRenderer` and a renderer switch, used in
    PRs 5–6 and deleted in PR 7.

### `:languages` — new

- `DeclarativeGrammar.parse(json)`
- `DeclarativeTokenizer(grammar)`
- `NamedTokenType(name)`

### `:editor-web` — new

- **`WebEditorOptions` fields:** `minimap`, `stickyScroll`, `bracketPairColorization`,
  `highlightCurrentLine`, `tabSize`, `insertSpaces`
- **`AardinkWeb` functions:** `registerTheme`, `registerLanguage`, `setBaseline`, `format`,
  `focus`, `getSelections` / `setSelections`, `onDiagnosticsChange`, and a debug
  `tokenize(languageId, text)`
- **Undo, Monaco-named in `index.d.ts`:** `canUndo()`, `canRedo()`, `pushUndoStop()`,
  `getAlternativeVersionId()`, and a second `onDidChangeContent` argument
  `{ versionId, isUndoing, isRedoing, isFlush }`
- **`WebLanguageProviders`**
- **Everything above mirrored** in `ExportsTemplate.kt`, `sample-web/Exports.kt`, `index.js` and
  `index.d.ts`

### `:languages-lsp`

No change planned. Optional PR 14b adds `connectLanguageServer(url, languageId)` on the web
side only.

## 6. PR sequence

**Branch and version:**

- PRs 1–6 keep `main` working on the current `BasicTextField` path; PR 7 switches over.
- PR 15 (npm publishing) is independent of the renderer and merges right after PR 1.
- `VERSION_NAME=0.6.0-SNAPSHOT` from PR 1.
- If a 0.5.x patch is needed, branch `release/0.5.x` from `v0.5.0`.

**Every PR must pass:**

- `pre-push.ps1`: Spotless, ABI check with the dump diff reviewed, JVM and wasm tests,
  Roborazzi, Vite smoke test, consumer smoke test.
- CI on `main` before the next PR merges.

### PR 0 — Spike (branch `spike/0.6-input-renderer`, not merged)

The report is committed as `docs/spikes/0.6-input-renderer.md`, with a go/no-go per platform.

| Step | What must be proven |
| --- | --- |
| S1 input | Minimal node and request on all three platforms. No lost or duplicated characters, a visible composition underline, and the candidate window at the caret. Cases: Windows Pinyin and Japanese IME (desktop and Chrome); macOS dead keys and press-and-hold; Android Gboard (composition, autocorrect, swipe, voice); Samsung Keyboard; Android Chrome with Gboard; iOS Safari if a device is available. |
| S2 render | 100k lines from the LRU cache. Our own work stays under 8 ms per frame on wasm while scrolling and typing. |
| S3 source set | The `skikoMain` intermediate set compiles against CMP's skiko `actual interface`. If it doesn't, duplicate the roughly 150-line file into `jvmMain` and `wasmJsMain`. |
| S4 web clipboard | The document-level listener works in Chrome, Firefox and Safari with no prompt. |
| S5 teardown | On the newest CMP 1.13 pre-release, removing the viewport from the DOM frees it: 50 mount/dispose cycles grow the heap by less than 1 MB (the `checklist.mjs` W-1 probe). |
| S6 semantics | `runComposeUiTest` drives the node via `InsertTextAtCursor`; TalkBack reads the line. |
| S7 fallback | Only if S1 fails somewhere: the input proxy on that platform. |

Nothing from PR 5 onward starts until S1–S3 pass.

### PR 1 — Baseline and housekeeping

- **Dependency bumps** (§3).
- **AGENTS.md fixes:**
  - "through 0.5.0 additive" becomes the 0.6.0 clean-break rule.
  - Release notes: four artifacts plus the web zip.
  - The versioning note now includes `editor-web`.
- **Docs:** a skeleton `docs/MIGRATION_0.6.md`. No upstream issue to file: link CMP-9090 and
  CMP-10507 (fixed for CMP 1.13) from `WEB_INTEGRATION.md`'s W-1 entry.
- **Canary:** `.github/workflows/canary.yml`, run weekly and on demand. It builds and tests
  `:editor`, `:languages` and `:editor-web` against the newest CMP pre-release, with the version
  swapped into the catalog, and never blocks `main`.
- **Performance gate:** `tools/vite-smoke/perf.mjs [--gate]` (shared set-up in `harness.mjs`)
  reports mount → idle, keystroke → idle p50/p95, keystroke → `onChange` p95, `setValue` → idle,
  scroll frame p95, and keys lost one at a time and in a 10 keys/s burst. It writes
  `dist/perf.json`. CI runs it without `--gate` until PR 7. The 0.5 baseline is recorded in the
  PR 7 table and in `WEB_INTEGRATION.md`.
- **IME automation:** `checklist.mjs W-2` drives composition through CDP
  (`Input.imeSetComposition` / `Input.insertText`); it passes 4/4 on 0.5.
- **Unrelated fix:** the aardarch.github.io page shows `org.aardarch:aardink`; the real
  coordinate is `com.aardarch:aardink`. That is a separate repo, so it is noted here only.
- **Public API:** none.

### PR 2 — Document model

- **Files:** `core/text/{GapBuffer,LineIndex,DocumentChange}.kt`. `CodeDocument` is rewritten
  behind the same API, plus `CharSequence`. `TokenCache` becomes internal, line-relative and
  shifting. Language services get snapshots.
- **Tests (commonTest):**
  - A seeded random-edit property test against a `StringBuilder` model: insert, delete, CRLF,
    astral characters.
  - Line-index invariants and token shifting.
  - JVM timing, informational: 10k inserts into a 2 MB document in under 50 ms.
- **Done when:** existing tests are green, there is no Roborazzi diff, and W-10 is no worse.

### PR 3 — Tokenization, find, diff, language change

- Viewport-first tokenization and incremental `RegexTokenizer`.
- Cooperative find with `maxFindMatches`.
- Diff lane debounced, cooperative and limited.
- `tokenizer` becomes a `var`, so `AardinkWeb.updateOptions` no longer rebuilds the state.
- Replace mode and Ctrl+H.
- **Tests:**
  - For every built-in language, incremental output equals full output under random edits.
  - The find cap and cancellation.
  - `AardinkWebTest`: a language change keeps undo.

### PR 4 — Command core (UI-free)

- **Files:** `core/edit/{Selections,EditTransaction,TypingRules,TextNavigator,WordBoundaries,CommentToggler,OccurrenceFinder,LineOps}.kt`.
- Undo reworked per §4.4a: selection sets per entry, Monaco grouping rules, `pushUndoStop`,
  `alternativeVersionId`, `lastChangeKind`, the 1,000-entry cap.
- While `BasicTextField` is still in place, multiple selections collapse to the primary one.
- `CommentSyntax` is filled in for every built-in language.
- **Public API:** `selections`/`setSelections`, `canUndo`/`canRedo`, `pushUndoStop`,
  `alternativeVersionId`, `lastChangeKind`/`EditChangeKind`, `CommentSyntax` and
  `IncrementalTokenizer.commentSyntax`.
- **Tests:**
  - Exhaustive `commonTest` coverage of every action and every multi-cursor merge case, run on
    JVM and wasm.
  - Undo grouping: typing merges, word boundary, cursor move, idle, `pushUndoStop`.
  - `alternativeVersionId` returns to the saved value after undo and diverges after a new edit.

### PR 5 — View model and read-only renderer (behind `@ExperimentalAardinkRenderer`)

- **Files:** `ui/view/{VisualLineMap,WrapRowIndex,LineLayoutCache,EditorScrollController,EditorHitTester,EditorViewportNode,EditorDrawing,EditorGutterView}.kt`.
- Scrollbars move to the v2 `ScrollbarAdapter`.
- Covers soft wrap, folds, squiggles (fixing the fold misplacement), find highlights, mouse and
  touch selection, double/triple click, and carets.
- **Tests:**
  - `commonTest` for the maps, the Fenwick tree and hit-testing arithmetic.
  - `jvmTest` for the layout cache with a real `TextMeasurer`.
  - A new `EditorViewUiTest`.
  - New Roborazzi scenarios (wrap, fold, squiggles, selection), added **now**, so V1 and V2 are
    compared on the same scenes before the switch.

### PR 6 — Input (still opt-in)

- **Files:**
  - `ui/input/{ImeWindowBuffer,EditorImeAdapter}.kt`
  - `ui/view/EditorKeyBindings.kt`
  - `platform/EditorTextInput.kt` (expect), with `androidMain/.../EditorInputConnection.android.kt`
    and `skikoMain/.../EditorTextInput.skiko.kt`
  - `platform/{EditorKeyEvents,EditorClipboard,EditorMagnifier}.kt`
  - `editor/build.gradle.kts`: the `skiko` group via `applyDefaultHierarchyTemplate`.
- Handles, text toolbar, the context menu, and `KeyboardToolbarRow` routed through
  `TypingRules`.
- **Tests:**
  - `ImeWindowBuffer`: every `EditCommand`, code-point deletes, rebase, and composition refused
    across a rebase.
  - `CodeEditorLayoutUiTest` cloned against `EDITOR`.
  - A Robolectric `InputConnection` test in `:sample/src/test`: commit, composing text, delete
    surrounding, batch edits.
  - Vite smoke test: typing, IME via CDP, paste via a `ClipboardEvent`, touch.

### PR 7 — Switch-over (the breaking PR)

- `CodeEditorLayout` renders only through the new node.
- **Deleted:** `EditorInputTransformation.kt`, `EditorOutputTransformation.kt`,
  `FoldingTransform.kt`, the BasicTextField path, every §5 removal, and the opt-in.
- **Callers updated:** `editor-web/AardinkWeb.kt:338`, `sample/MainActivity.kt:390`,
  `sample/SampleAssets.kt:741`, `sample-desktop/Main.kt:125`,
  `tools/consumer-smoke/MainActivity.kt:52`, `README.md:93`.
- The ABI dumps are regenerated with `updateAbiAll` and the diff reviewed line by line against
  §5.
- **Roborazzi policy:**
  - One commit, `test(sample): re-baseline for the virtualised renderer`, touching only
    `screenshots/`.
  - CI uploads a before/after gallery, and each image is signed off in review.
  - No other commit touches `screenshots/`.
  - Zero-regression (`maxDistance 0.04`) resumes after merge.
  - Every later PR that changes pixels on purpose carries exactly one re-baseline commit.
- **Performance gates:** CI enforces the gate column; the targets are measured locally and
  recorded in `WEB_INTEGRATION.md`.

  All measured by `tools/vite-smoke/perf.mjs` on the 5,000-line / 163 KB reference document.
  "Idle" means frames have come at display rate for a third of a second, so it includes
  re-highlighting and every other frame the event causes.

  | Measurement (wasm, Chrome) | Target | CI gate | 0.5 baseline (2026-09-27) |
  | --- | --- | --- | --- |
  | Mount → idle (`mountToIdleMs`) | < 200 ms | < 400 ms | 3.2 s |
  | Keystroke → idle, p50 | < 16 ms | — | 3.7 s |
  | Keystroke → idle, p95 | < 33 ms | < 50 ms | 4.6 s |
  | Keystroke → `onChange`, p95 | < 20 ms | — | 163 ms |
  | `setValue` → idle | < 150 ms | — | 444 ms |
  | Scrolling, frame p95 | < 20 ms (no dropped frames at 60 Hz) | — | 16.8 ms |
  | Keys lost, one at a time | 0 | 0 | 0 |
  | Keys lost, 30-key burst at 10 keys/s | 0 | 0 | 15–29 |
  | JVM command plus one-line relayout, median | < 2 ms | informational | — |

- **Done when:** a manual pass on all three platforms covers typing, CJK IME, dead keys, Gboard,
  CRLF paste, fold, find/replace, completion, rename, undo/redo, mouse and touch selection,
  and TalkBack reading a line.

### PR 8 — Editor chrome

- Current-line highlight, bracket matching, and bracket-pair colours (`bracketPairColors` and
  the parser keys).
- Optional whitespace rendering.
- One re-baseline commit, plus new Roborazzi scenarios.

### PR 9 — Caret-anchored popups

- Hover by pointer on desktop and web; "Show info" in the Android menu.
- Signature help, code actions and completion anchored at the caret (a strip on touch).
- **Tests:** popup position relative to the caret rectangle, and flipping near the window
  edges.

### PR 10 — Automatic diagnostics

- The `diagnostics = null` behaviour: debounce, version guard, range mapping,
  `onDiagnosticsChange`.
- Built-in diagnostics appear on the web, with `onDidChangeDiagnostics`.
- **Tests:** a fake service with delays, checking that stale results are dropped;
  `AardinkWebTest`.

### PR 11 — Multi-cursor

- Alt+click, Ctrl+Alt+↑/↓, Ctrl/Cmd+D, Ctrl+Shift+L.
- Column selection with the mouse and with Shift+Alt+arrows.
- Ctrl+/, IME mirroring, and multi-cursor undo.
- **Tests:** command tests, UI tests via `performKeyInput`, and Roborazzi with multiple carets.

### PR 12 — Navigation UI

- Ctrl/Cmd+click with the hover underline, and F12.
- Shift+F12 references peek (`ui/ReferencesPeekPopup.kt`, internal).
- Shift+Alt+F format document and range.
- Context-menu entries and `onNavigateToLocation`.
- **Tests:** a fake `LanguageService` drives each flow; format produces a minimal edit.

### PR 13 — Sticky scroll and minimap

- `ui/view/{StickyScrollOverlay,MinimapView}.kt`.
- **Tests:** Roborazzi, and a gate that the minimap costs under 2 ms per frame at 5,000 lines on
  wasm.
- **Cut line:** if the schedule slips, PR 13 moves to 0.6.1 with no API churn. That only works
  if its `EditorOptions` fields land in PR 7.

### PR 14 — Web and npm expansion

- New options, events and commands; `registerTheme`, `registerLanguage` and the `tokenize`
  debug export.
- W-1 disposal through CMP 1.13 (§4.8), font preload (W-9), density in the cache key (W-8), and
  the W-5 CSS docs.
- **Tests:**
  - `AardinkWebTest`: registration, and a grammar with a lookbehind rejected.
  - Vite smoke test: register a toy grammar and check token colours by pixel probe.
  - On CMP 1.13: 50 mount/dispose cycles grow the heap by less than 1 MB.
- **Optional PR 14b:** `connectLanguageServer(url, languageId)` over `WebSocketLspTransport`
  and `LspLanguageService`.

### PR 15 — npmjs publishing (lands early: right after PR 1)

Nothing here depends on the renderer, so it lands second. The first automated publish is then
`0.6.0-rc1`, and aardflex-web-app can install from npm as soon as that exists.

**Pattern reused from `sjohansson/astro-components`**, whose `.github/workflows/release.yml`
publishes `@sjohansson/*` with OIDC today:

- `permissions: id-token: write`.
- `actions/setup-node` with `node-version: '24'` and `registry-url: 'https://registry.npmjs.org'`.
- `npm install -g npm@latest`: trusted publishing needs a recent npm CLI, newer than the one
  bundled with some Node 24 images.
- Publish with no `NODE_AUTH_TOKEN` and no `NPM_TOKEN` secret.

What differs here: no Changesets. Versions come from `VERSION_NAME` through the existing
`create-release.ps1` / `release.ps1` tag flow, the same version as Maven Central.

**Bootstrap (one time, manual, done by the npm account owner):**

1. On npmjs.com, create the free org **`aardarch`** (public packages only), owned by the
   account that owns `@sjohansson`. Require 2FA for every member.
2. **First publish, by hand.** npm only lets you configure a trusted publisher on a package that
   already exists, so the first version is published locally with `npm login` (2FA).
   - **Recommended:** publish `0.5.0` built from the `v0.5.0` tag, with the known issues its
     README already lists. It matches the Maven Central release.
   - **Alternative:** a `0.0.0-bootstrap` placeholder, deprecated immediately afterwards.
   - Commands:

     ```sh
     git worktree add ../aardink-v050 v0.5.0
     cd ../aardink-v050
     ./gradlew :sample-web:npmPackage
     cd sample-web/build/npm
     npm publish --access public
     ```

     The 0.5.0 `package.json` has no `publishConfig`, which is why `--access public` is passed
     explicitly.
3. On the package's settings page, under **Trusted publisher → GitHub Actions**, set:
   - organisation `aardarch`
   - repository `aardink`
   - workflow `release.yml`
   - environment `npm`
4. Under **Publishing access**, choose "Require two-factor authentication and disallow tokens".
   From then on only the OIDC workflow can publish.
5. In the GitHub repo **Settings → Environments**, create `npm`. Restrict it to tags matching
   `v*` and add a required reviewer if wanted. This gives npm publishing the same approval gate
   as Maven Central.

**Repo changes in this PR:**

- **`sample-web/src/npm/package.json`:**
  - `publishConfig: { access: public, provenance: true }`
  - `homepage` (`https://aardarch.github.io/aardink/`), `bugs`, `author`, `keywords`
  - `engines.node` `>=20`
  - `repository.directory: sample-web`. `repository.url` must stay exactly
    `git+https://github.com/aardarch/aardink.git`, or npm rejects the provenance statement.
- **New files shipped in the package:**
  - `sample-web/src/npm/README.md` (the npm page): install, the Vite set-up from
    `WEB_INTEGRATION.md`, the API summary and known limitations.
  - A copy of `LICENSE` added to `files`.
- **`release.yml`, new job `publish-npm`:**

  ```yaml
  publish-npm:
    name: Publish to npm
    needs: publish            # after Maven Central, so both registries carry the same version
    runs-on: ubuntu-latest
    environment: npm
    permissions:
      contents: read
      id-token: write
    steps:
      # checkout, setup-java 21, setup-gradle: as in the publish job
      - run: ./gradlew :sample-web:npmPackage
      # pnpm/action-setup, then setup-node 24 with registry-url https://registry.npmjs.org
      - run: npm install -g npm@latest
      # Vite smoke test against the built package: as in ci.yml
      - name: Publish
        working-directory: sample-web/build/npm
        run: |
          version="${GITHUB_REF_NAME#v}"
          if npm view "@aardarch/aardink-web@$version" version >/dev/null 2>&1; then
            echo "already published"; exit 0; fi
          tag=latest; case "$version" in *-*) tag=next;; esac
          npm publish --access public --tag "$tag"
  ```

  Provenance is attached automatically under trusted publishing; `publishConfig.provenance`
  makes it explicit.
- **`ci.yml`, package checks on every push:** in `sample-web/build/npm`, run
  `npm pack --dry-run`, `npx publint` and `npx @arethetypeswrong/cli --pack`. These catch a
  broken `exports`, types or file list before a tag does.
- **`scripts/`:** `create-release.ps1` warns if the npm package would be skipped. The
  `pre-push.ps1` web step also runs `npm pack --dry-run`.
- **Docs:**
  - README and `WEB_INTEGRATION.md` get "Install: `pnpm add @aardarch/aardink-web`" and drop the
    "not published yet" wording.
  - AGENTS.md's release section says a tag publishes to Maven Central and npm, and describes the
    `npm` environment.
  - Out of scope, noted: astro-components' `docs/GETTING_STARTED.md` still describes `NPM_TOKEN`,
    which its workflow no longer uses.

**Done when:**

- The bootstrap steps are complete.
- The `ci.yml` package checks are green.
- `v0.6.0-rc1` publishes to `next` with a provenance badge on npmjs.com.
- `npm view @aardarch/aardink-web dist-tags` shows `latest: 0.5.0, next: 0.6.0-rc1`.

### PR 16 — Documentation

- **`docs/MIGRATION_0.6.md`:** before/after for every §5 removal or change, plus an aardflex
  example.
- **CHANGELOG `[0.6.0]`**, and a README feature and platform matrix.
- **`WEB_INTEGRATION.md`:** W-1…W-10 re-run with new numbers, the Monaco mapping rows (minimap,
  bracket pairs, multi-cursor now mapped; `revealPosition` centring), and install from npmjs.
- **AGENTS.md:**
  - The new layout (`ui/view`, `ui/input`, `skikoMain`).
  - Rendering rules: never lay out the whole document; no `document.text` in per-frame paths;
    no lookbehinds, also in grammars.
- **KDoc** for every new symbol.
- **`docs/AARDFLEX_WEB_SWITCHOVER.md`** replaces `KMP_MIGRATION_PLAN.md` §11.

### PR 17 — Release

1. Tag `v0.6.0-rc1`. It goes to Maven Central and npm `next`, and aardflex-web-app A1 tests
   against it.
2. The manual real-device checklist: W-2, W-4, W-5, W-6, W-8, W-9, TalkBack and NVDA.
3. Tag `v0.6.0` through `create-release.ps1` and `release.ps1`, as for 0.5.0.

## 7. Downstream

### 7.1 aardflex-web-app (`C:\repos\aardarch\aardflex-web-app`: Svelte, Vite, pnpm, `monaco-editor ^0.55.1`)

The old §11 had aardflex-web-app build its own Gradle wasm executable. That design is dropped,
for two reasons: `XmlTokenizer` is an `object` and cannot be extended, and it would put a JDK
and Gradle into that repo's CI. The app now consumes npm and registers its language from
JavaScript (§4.8).

**A1 — behind `VITE_EDITOR=monaco|aardink`** (default `monaco`):

1. `pnpm add @aardarch/aardink-web@^0.6.0` (the rc from npm `next` first).
2. Move `ELEMENTS`, `ATTRIBUTES`, `MODULE_TYPES`, `TEXT_STYLES` and `TRANSFORMS` into
   `src/editor/aardflexData.ts`, shared by both editors.
3. Add three files under `src/editor/aardink/`:
   - **`aardflexGrammar.ts`:** a direct port of the Monarch states in `setup.ts:261-346` —
     root, tag, attrValueDouble/Single, comment, metatag, expression.
   - **`aardflexTheme.ts`:** the 8 rules plus `#1E1E2E`. Bold is dropped, because
     `EditorTheme` is colour-only.
   - **`aardflexCompletions.ts`:** a port of `setup.ts:368-447`. Snippet `$1` placeholders
     become plain text plus caret placement.
4. Register everything with `registerLanguage({ id: 'aardflex-xml', extends: 'xml', grammar, completionProvider, comments: { block: ['<!--', '-->'] } })`.
   The language inherits XML diagnostics, auto-close and tag folding.
5. `src/editor/index.ts` picks `import('./monaco')` or `import('./aardink')` by the flag. The
   dynamic import keeps Monaco out of the Aardink bundle.
6. `EditorPane.svelte`:
   - `onMount(async …)`, with a guard for unmount before the promise resolves.
   - Keep the `applyingExternal` echo guard.
   - Keep one pane mounted and switch files with `setValue`, and never move its container in
     the DOM (§4.8).
7. `vite.config.ts`: `optimizeDeps.exclude: ['@aardarch/aardink-web']`. The Monaco include and
   the worker import move behind the flag.
8. `firebase.json`: `application/wasm`, and long cache headers for hashed assets.
9. **Tests:**
   - Keep `setup.test.ts` for Monaco.
   - Add a browser golden test calling `tokenize('aardflex-xml', fixture)`.
   - Record the bundle size against Monaco.

**A2** — default the flag to `aardink` and soak for one release.

**A3** — remove Monaco, the Monaco half of `setup.ts`, and the flag; update `specs/ui.md`.

**Acceptance:** `pnpm check`, `pnpm test` and `pnpm build` green, golden tokens identical to
Monaco's on the fixtures, and bundle size recorded.

### 7.2 aardflex Android app (`C:\repos\aardarch\aardflex`, Aardink 0.4.0)

- Bump `aardink = "0.6.0"` in `apps/android/aardflex/v1.0/gradle/libs.versions.toml`, skipping
  0.5.0. The artifacts resolve to `-android` automatically.
- `ui/screen/XmlEditorScreen.kt:195`: move to `EditorOptions` if decision 1 is A. Its explicit
  `diagnostics` list keeps its host-supplied meaning.
- `ui/theme/AardflexEditorTheme.kt`: drop any `fontFamily`/`fontSize`/`lineHeight` arguments.
- `service/XmlIncrementalTokenizer.kt` still compiles and stays correct: it passes its own augmented
  list back as `previousTokens`, which the built-in XML tokenizer does not recognise, so every
  pass is a full scan (as in 0.5). Replace it with `DeclarativeTokenizer` over the web app's
  grammar JSON, so there is one grammar and it is incremental.
- Re-run its screenshots, plus a manual IME and TalkBack pass. This upgrade is also the proof of
  the migration guide.

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| The skiko request members are experimental and may change in CMP 1.13. | All of it sits in one `skikoMain` file; canary CI on CMP dev builds; the proxy fallback stays documented. |
| Android `InputConnection` edge cases: Samsung key-event deletes, batch edits, `restartInput` storms, handwriting. | Port foundation's patterns; Robolectric IC tests; a manual Gboard, Samsung and SwiftKey pass; full-document coordinates on Android, so there are no rebases. |
| Mobile autocorrect versus the windowed textarea on the web. | Never rebase during composition; the window is always wider than a word plus context; CDP IME tests; real-device W-2/W-5 in PR 17. |
| Screen-reader regressions. | The semantics window and actions (§4.6); TalkBack and NVDA in PR 7's done criteria. |
| A full re-baseline hides a real regression. | The new scenes land in PR 5 so V1 and V2 are compared; a dedicated re-baseline commit with gallery sign-off. |
| Scroll jumps from soft-wrap estimates. | Anchor-based scrolling; estimates are exact for monospace ASCII; corrections apply only below the anchor. |
| Per-line layout cost on wasm. | Measured in spike S2; colour-only cache keys; no relayout for selection or carets; the text layer in a `graphicsLayer`. |
| CMP 1.13 (the W-1 fix) is not stable before PR 17. | Ship 0.6.0 on 1.12.x with W-1 as a known issue and the reuse guidance; adopt 1.13 in 0.6.1. The canary job keeps the code ready for it. |
| Slow regexes in registered grammars on wasm. | Lookbehind rejected; a line-stateful tokenizer scans only the current state's rules; the cooperative pass. |
| Scope creep. | Cut line after PR 12; features are independent PRs after the switch-over. |
| npm publish failures (trusted-publisher mismatch, provenance, npm CLI version). | The bootstrap runbook in PR 15; `repository.url` pinned; `npm@latest` in the job; publint and attw in CI; an idempotent publish step; the rc goes to `next` first. |
| The clean break hurts consumers. | The migration guide, and we do the aardflex upgrade ourselves (§7.2). |

## 9. Verification

- **Per PR:** `pwsh ./scripts/pre-push.ps1 -NoFix` (all 11 checks), and CI green on `main`
  before the next merge.
- **Performance, from PR 1 on:** `node tools/vite-smoke/checklist.mjs --gate`. Results go into
  the PR description; the §6 PR 7 gates apply from PR 7.
- **Behaviour:** the commonTest suites for document, commands and IME buffer (JVM and wasm);
  Compose UI tests on the `EDITOR` tag; the Robolectric `InputConnection` test; Vite smoke with
  CDP IME, clipboard and touch.
- **Pixels:** Roborazzi, under the §6 PR 7 re-baseline policy.
- **Devices, before `v0.6.0`:** Android (Gboard, Samsung), Windows and macOS desktop, Chrome,
  Firefox and Safari; iOS Safari and Android Chrome for W-2/W-4/W-5; TalkBack and NVDA.
- **Downstream:** aardflex-web-app A1 green against `0.6.0-rc1` from npm `next`; aardflex
  Android builds and passes its screenshots on 0.6.0.
