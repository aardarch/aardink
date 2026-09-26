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

# Aardink KMP Migration Review (PRs 0–5)

> **Status:** historical. Every §E remediation item landed as PR 5.5 before PR 6. Current
> progress is tracked in the "Progress" table at the top of `docs/KMP_MIGRATION_PLAN.md`.

## Context

`docs/KMP_MIGRATION_PLAN.md` defines a 12-PR migration. Commits `ab8305b` (PR 0) through
`82f3247` (PR 5) are merged on `main`. This review compares the merged state against the
plan's own Definitions of Done (§10) and looks for correctness, portability, test, build,
and documentation gaps before PR 6 starts. Three parallel audits covered `:editor`,
`:languages` + `:languages-lsp`, and build/CI/docs; the highest-severity findings were
re-verified directly in source.

**Overall:** the mechanical conversion is solid (all three libraries compile for
android/jvm/wasmJs, zero tests dropped, headers/KDoc complete, no JVM-only APIs in
`commonMain`). But several items PRs 3 and 5 claim as done are missing, `main` CI has been
red since PR 3, and the Mutex rewrite of `LspClient` introduced two real concurrency
regressions.

---

## A. Spec items claimed done but missing

| # | Item | Spec | Owed by | Evidence |
|---|---|---|---|---|
| A1 | `WebSocketLspTransport` + `wasmJsTest` | §3.6 | PR 5 DoD | `languages-lsp/src/wasmJsMain/.../lsp/` is an empty, untracked dir. `StreamLspTransport.kt:33` and `LspTransport.kt:50` reference the nonexistent class. Commit 82f3247 never mentions it. |
| A2 | `PlatformInfo` expect/actual | §3.2 | PR 3 DoD | No file. `platform/` holds only `EditorDispatchers`. Blocks §3.2 `platformDefault` and §5.1 `EditorShortcuts` (Cmd-vs-Ctrl). |
| A3 | `EditorTypography` / `LocalEditorTypography` | §3.4 | PR 3 DoD | No file. 9 hardcoded `FontFamily.Monospace` + `EditorDefaults.fontSize/lineHeight` sites remain (`CodeEditorLayout.kt:701-703`, `EditorGutter.kt:102-104,331`, `CompletionDropdown.kt:115`, `FindReplacePanel.kt:96,137`, `HoverDocPopup.kt:82`, `SignatureHelpPopup.kt:92`, `KeyboardToolbarStyle.kt:73`). Blocks `:editor-web` font bundling (§6). |
| A4 | Dispatcher injection | §3.3 | PR 3 DoD | `CodeEditorState.kt:334` still `= Dispatchers.Default`; 7 `withContext(Dispatchers.Default)` sites in `CodeEditorLayout.kt:171,205,225,242,315,329,572`. `EditorDispatchers` is dead code inside `:editor`; its only consumer is `LspClient`. |
| A5 | First ABI baseline | §9.2 | PR 3 DoD | No `abiValidation {}`, no `*.api` files, `:editor:tasks --all` shows zero abi tasks. The "purely additive" gate for PRs 4–11 has never been enforceable. |
| A6 | Dokka convention rewrite | §9.1 | PR 3 | `aardink.dokka-gfm.gradle.kts` still hooks `com.android.library` (never fires now) with `sourceRoots.from("src/main/java")`. `dokkaAll`/`dokkaAllGfm` depend on tasks that don't exist; all three libraries publish `JavadocJar.Empty()`. |
| A7 | `tryLock`-fallback test | §3.5 | PR 5 DoD | `LspClientTest` is a 1:1 rename of the old 22 tests. `withLockOrAsync`'s `scope.launch` path has zero coverage. |
| A8 | `EditorOutputTransformationTest`, `textFieldState` tracking asserts, input-transformation tests | §4.7 | PR 1 | Not present. The deprecated `applyFolding` is tested; the live render path and `syncFieldToDocument`/`undoState.clearHistory()` are not. Grep for `textFieldState` in `editor/src/commonTest` returns nothing. |

---

## B. Correctness concerns (verified in source)

**B1. `LspClient` receive-loop teardown is skipped under cancellation (High, regression).**
`LspClient.kt:160-162`: `finally { onReceiveLoopEnded() }` where `onReceiveLoopEnded` does
`lock.withLock { ... }` (`:178-185`). A suspending `withLock` inside a cancelled coroutine
throws `CancellationException` before running the body. When a host cancels the shared
`scope` while the loop is alive, `closed` is never set, pending requests never fail,
transport is never closed. The pre-KMP `@Synchronized` version had no such hole, and
`sendRequest` already applies the fix at `:240` (`withContext(NonCancellable)`), so the
asymmetry looks accidental. Existing test at `LspClientTest.kt:361` cancels before start and
does not cover this.

**B2. Deferred listener add/remove are unordered and non-synchronous (High).**
`addDiagnosticsListener`/`removeDiagnosticsListener` (`LspClient.kt:102-108`) go through
`withLockOrAsync`; on contention each becomes an independent `scope.launch`. Consequences in
`LspLanguageService`: `didOpen` (`:154-162`) registers then immediately sends
`textDocument/didOpen`, so an early `publishDiagnostics` can be dropped; `didClose`
(`:181-185`) can have a late notification repopulate `lspDiagnostics`; and an add followed by
a remove can execute in the reverse order, leaking the listener. `@Synchronized` gave
program order; `Mutex.tryLock` + launch does not.

**B3. `stop()` can silently no-op (Medium).** If the lock is contended and `scope` is
already cancelled, `withLockOrAsync`'s `scope.launch` never runs (`LspClient.kt:123`).
`startLocked` guards `!scope.isActive` (`:139-145`); `stopLocked` does not. Separately,
`stop()` is not ordered before an in-flight `sendRequest`, but its KDoc (`:315-318`) promises
unconditional teardown.

**B4. `TokenCache` cross-thread mutation (Medium, pre-existing, now undocumented across 3 targets).**
`CodeEditorState.runTokenization` (`:357-370`) reads `tokenCache.tokens` inside
`withContext(computeDispatcher)`. `TokenCache.tokens` is a *mutating* getter
(`TokenCache.kt:37-46`, rewrites non-volatile `flatTokens`/`flatDirty`), while `merge`/
`reset`/`pruneLines` run on the scope dispatcher. No lock, no volatile, no stated
happens-before. Benign on wasm (single thread), racy on Android/JVM.

**B5. `Dispatchers.Main` default on JVM.** `CodeEditorState.kt:61` defaults `scope` to
`CoroutineScope(Dispatchers.Main)`. On desktop JVM this throws at runtime unless the
consumer adds `kotlinx-coroutines-swing`. The plan only adds it to `:sample-desktop`; nothing
tells a JVM library consumer.

**B6. `RegexOption.IGNORE_CASE` semantics differ JVM vs JS** (`FindEngine.kt:42-44`):
non-ASCII case-insensitive find will behave differently on web. `KotlinTokenizer.kt:44,46`
use lookbehind, which narrows the browser floor (Chrome 62+/Safari 16.4+) — fine, but
undocumented. *(Later correction, PR 7: the premise is wrong. Kotlin/wasm uses its own regex
engine, not the browser's, so browser support is irrelevant. The real problem was speed, and
the lookbehinds were removed; see plan §5.4a.)*

**B7. CRLF normalisation** (§5.3) has no code and no tracking comment. Desktop (PR 6) will
hit Windows CRLF paste before the web checklist (PR 9) does.

**B8. `EditorTheme.fontFamily`** (`EditorTheme.kt:50`) is never read anywhere. It overlaps
the planned `EditorTypography.fontFamily`; decide which wins before adding the second.

**B9. `EditorGutter.kt:331`** uses `fontSize.value * 0.7f` as a char-width heuristic; once
typography is injectable this silently breaks for non-default fonts.

---

## C. Build / CI / release state

**C1. `main` CI is red since PR 3.** `ci.yml:40` runs `:editor:lint` etc. and `:43` runs
`:editor:test` etc. Neither task exists on `com.android.kotlin.multiplatform.library`
(verified empirically). PRs 4 and 5 merged with no automated gating. `release.yml:47` has the
same six dead tasks, and `:66` (`dokkaAllGfm`) would also fail. A release tag today fails
immediately.

**C2. Roborazzi verify is not in CI at all** (`:sample:testDebugUnitTest`), so the §10
"zero pixel diffs" gate rests on commit-message claims.

**C3. Android Lint silently dropped** for all three libraries — no `lint` task is
registered by the KMP Android plugin, and nothing opts back in.

**C4. No `aardink.kmp-library` convention plugin** (§2.3). ~110 near-identical lines
duplicated across the three build files (spotless, signing, POM, jvmTest deps, target
declarations, the ABI TODO). This is also why A5/A6 keep getting deferred.

**C5. Stale catalog/root entries** §2.1/§2.2 said to remove: `plugin-kotlin-binary-compat`
(`libs.versions.toml:10,146`, root `build.gradle.kts:10,27-34`), `androidx-compose-ui-text-google-fonts`
still in `bundles.compose-core`.

**C6. `gradle.properties`:** missing `kotlin.mpp.enableCInteropCommonization=false`; jvmargs
still 4g with no note that the wasm link was verified under it; configuration-cache is on and
wasm/Karma tasks are never exercised in CI.

**C7. Scripts:** `pre-push.ps1:136,143` call the dead tasks; no wasm tests, no ABI step, no
`-SkipWasm`. `create-release.ps1` gates on `pre-push.ps1 -NoFix`, so releases are blocked.
`verify-consumer.ps1:51` only *prints* the dependency grep; it never asserts on
`aardink-android`, so the key claim of PR 3 is not machine-checked.

**C8. Deviations that are justified but leave the plan stale:** Compose plugins applied to
`:languages`/`:languages-lsp` (skiko.mjs for wasm tests); `binaries.executable()` on all
three library klibs (CMP-4906); `androidUnitTest.dependsOn(jvmAndAndroidTest)` omitted;
no explicit `useKarma { useChromeHeadless() }`. The plan text (§2.4) should be amended so the
next implementer doesn't "fix" these back.

**C9. `feat(editor)!:` marker.** Scoped to packaging only (commit body is explicit), which is
consistent with the plan's additive-API promise. But `create-release.ps1`'s SemVer suggestion
will propose 1.0.0; the operator must override to 0.5.0 and nothing warns about it. The
packaging change (single .aar → GMM root + per-target) is recorded only in a commit body.

**C10. Renovate** has no rule keeping `plugin-compose-multiplatform` in step with
`plugin-kotlin` — a known CMP breakage vector.

**C11. `:languages-lsp` depends on a Compose UI module for one dispatcher constant**
(`api(project(":editor"))` was already required for `core` types, so the cost is nil today,
but `EditorDispatchers` arguably belongs in a `:core`-shaped module if one is ever split out).

---

## D. Documentation gaps

- **AGENTS.md** still describes `src/main/java`, JUnit 5, `:editor:test`/`:editor:lint`/`apiDump`, and "for Android". §12's two hard rules (expect/actual only under `platform/`; never call `Dispatchers.*` directly) are written nowhere and A4 already violates the second.
- **CHANGELOG.md `[Unreleased]` is empty** across six merged PRs, including the user-visible `TextFieldState` migration and the org.json → kotlinx.serialization port. `create-release.ps1` refuses to cut on an empty section.
- **README.md**: Android-only framing, "two artifacts" (ignores `-languages-lsp`), pins 0.1.0, and the quick-start passes a `language =` parameter that does not exist on `rememberCodeEditorState`.
- **CONTRIBUTING.md** and **`.github/PULL_REQUEST_TEMPLATE.md`** reference dead tasks and a nonexistent `editor/api/editor.api` — not in §12's list; add them to PR 10.
- **Stale KDoc** promising `Dispatchers.Default`: `FindEngine.kt:21`, `FoldingProvider.kt:23`, `LanguageService.kt:24`; `CodeEditorState.kt:221` references `TextFieldValue`. `computeDispatcher` (`:333`) is a public `var` with a `//` comment, not KDoc. `LspTransport.kt:26` bakes "Content-Length" into the common interface contract, contradicting §3.6.
- **Plan §3.6 sketch has bugs** and should not be copied literally when A1 is done: `onerror` after `onopen` double-resumes the continuation; no `invokeOnCancellation { socket.close() }`; `connect`'s handlers are never replaced by the instance's `init` handlers.

---

## E. Recommended remediation (insert as "PR 5.5 — hardening" before PR 6)

Ordered so each step unblocks the next; nothing here changes public API except additions.

1. **Un-red CI and scripts** (C1, C2, C7): replace `:X:test`/`:X:lint` with `jvmTest` +
   `wasmJsBrowserTest` (+ `browser-actions/setup-chrome`), add `:sample:testDebugUnitTest`,
   add `$Libs` variable and `-SkipWasm` to `pre-push.ps1`, make `verify-consumer.ps1` assert
   on `aardink-android`. Fix `release.yml:47,64-67` the same way.
2. **Convention plugin + ABI baseline + Dokka** (C4, A5, A6): create
   `buildSrc/.../aardink.kmp-library.gradle.kts` per §2.3, wire `abiValidation {}`, commit the
   first dumps, rewrite `aardink.dokka-gfm.gradle.kts` to hook
   `org.jetbrains.kotlin.multiplatform`, restore `JavadocJar.Dokka(...)`. Remove BCV
   entries (C5). Confirm real task names and record them in AGENTS.md.
3. **`LspClient` fixes** (B1, B2, B3): wrap `LspClient.kt:161` in `withContext(NonCancellable)`;
   make listener add/remove synchronous (a `@Volatile` copy-on-write list can be updated with a
   plain non-suspend spin on `tryLock`, or drop the lock for listeners entirely and accept
   last-writer-wins since each write is a full replacement); guard `stopLocked` with
   `!scope.isActive` fallback that runs teardown inline; fix `stop()` KDoc. Add the §3.5
   `tryLock`-fallback test plus a "scope cancelled while loop running → transport closed" test.
4. **Finish PR 3's §3 items** (A2, A3, A4): `PlatformInfo` + 3 actuals; `EditorTypography`/
   `LocalEditorTypography` + convert the 9 sites and reconcile `EditorTheme.fontFamily`
   (B8) and the gutter heuristic (B9); switch `computeDispatcher` to `EditorDispatchers.compute`
   and the 7 layout sites to `state.computeDispatcher`. Document the `TokenCache` invariant
   or snapshot `tokenCache.tokens` on the main thread before hopping (B4). Fix stale KDoc.
5. **Finish PR 5's §3.6** (A1): `WebSocketLspTransport` with the three sketch bugs fixed, plus
   `wasmJsTest` against a fake `WebSocket`. Reword `LspTransport.kt:26`.
6. **Tests owed by PR 1** (A8): `EditorOutputTransformationTest`, `textFieldState` tracking
   assertions, `EditorInputTransformationTest` pinning the coalescing behaviour change PR 1's
   commit message admits to.
7. **Docs** (D): backfill `CHANGELOG.md [Unreleased]` for PRs 0–5 including the packaging
   change; update AGENTS.md layout/commands/rules, README platform matrix and quick-start,
   CONTRIBUTING.md, PR template; amend plan §2.4/§2.5 for the justified deviations (C8) and
   §3.6 for the sketch bugs; note the SemVer-suggestion override (C9); add
   `kotlin.mpp.enableCInteropCommonization=false` (C6); add a Renovate group rule (C10).
   Add a note for JVM consumers about `kotlinx-coroutines-swing` (B5) and a
   `\r\n` tracking comment in `EditorInputTransformation` (B7).

Optionally commit this review as `docs/KMP_MIGRATION_REVIEW.md` so the PR 6+ implementer
has it alongside the plan.

## Verification

- `./gradlew :editor:jvmTest :languages:jvmTest :languages-lsp:jvmTest` and the three
  `wasmJsBrowserTest` tasks green (CHROME_BIN set locally).
- `:sample:testDebugUnitTest` (Roborazzi verify) byte-identical; `:sample:assembleDebug/Release`.
- `./gradlew :editor:tasks --all | grep -i abi` lists the check/update tasks; dumps committed;
  a follow-up no-op run of the check task passes.
- `./gradlew dokkaAllGfm` produces `*/build/dokka/markdown/`; `publishToMavenLocal` yields a
  non-empty javadoc jar.
- `scripts/verify-consumer.ps1` exits non-zero when the `aardink-android` grep fails (test by
  temporarily breaking it).
- `scripts/pre-push.ps1 -NoFix` passes end to end; CI green on a PR.
- New `LspClientTest` cases: cancel-scope-while-running closes transport; contended `stop()`
  path; add→remove ordering under contention.
