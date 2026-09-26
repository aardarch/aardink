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

# Aardink Kotlin Multiplatform Implementation Plan

**Status:** Supersedes the earlier sketch of this document. That version pinned stale
versions (Compose Multiplatform 1.7.3, Ktor), assumed `androidTarget()` with
`com.android.library` (incompatible with this repo's AGP 9.4), proposed rewriting
`LspTransport` as a `Flow`-based API (a binary-incompatible break for Android consumers),
and did not account for this repo's actual blockers. This document is written to be
executed directly by an implementation agent: every section gives concrete file paths,
Gradle snippets, and Kotlin signatures rather than general guidance.

## Progress

| PR | State | Notes |
| --- | --- | --- |
| 0–5 | Done | Reviewed in `docs/KMP_MIGRATION_REVIEW.md`. |
| 5.5 | Done | Review §E remediation: CI/scripts, ABI baseline, Dokka, `LspClient` fixes, §3.2–3.4 items, `WebSocketLspTransport`, PR 1's owed tests, docs. |
| 6 | Done | `a887bab`. `:sample-desktop`, `EditorShortcuts`, `EditorScrollbars`, `platformDefault`, `CodeEditorLayoutUiTest`. |
| — | Done | Build DSL moved to the current plugin APIs (CMP 1.12.1, AGP 9.4.1, vanniktech 0.37) — see §2.4b. **Every snippet in this document that predates it has been updated; follow §2.4b over any older example you find elsewhere.** |
| 7 | Done | Cooperative tokenization + `EditorLimits`; see §5.4a for what differs from the sketch. |
| 8 | Done | `:editor-web`; see §6.5 for what differs from the sketch. |
| 9 | **Next** | `:sample-web` harness, npm packaging (§6.4), W-1…W-10 checklist. |
| 10–11 | Not started | |

## 0. Executive summary and decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Platform targets | Android (zero regression) + `jvm()` desktop + `wasmJs` browser. No iOS. | Android is the existing published product; desktop gives the fastest dev/test loop; wasmJs is the path to replacing Monaco in `aardflex-web-app`. |
| Text input | Migrate `BasicTextField(TextFieldValue)` + `VisualTransformation` to `BasicTextField(TextFieldState)` + `InputTransformation`/`OutputTransformation` **now**, before any KMP source move. | The old API is deprecated; doing the rewrite once, on the Android-only build, isolates its risk from the multiplatform move and lets Roborazzi/manual testing prove it before the source tree changes shape. |
| `aardflex-xml` grammar location | Owned by `aardflex-web-app` in its own small Gradle wasmJs module, **not** shipped inside Aardink. | Keeps Aardink product-agnostic; Aardflex's schema can evolve without an Aardink release. |
| Web delivery | Gradle-built npm package `@aardarch/aardink-web` published to GitHub Packages; `aardflex-web-app` consumes it via `pnpm`/Vite. | One registry, ordinary `package.json` version pinning, no submodules. |

Reconciling the last two: Aardink publishes ordinary Maven artifacts for `:editor`,
`:languages`, `:languages-lsp` on all three targets, **plus** a new wasmJs-only library
`:editor-web` that wraps `CodeEditorLayout` behind a small `@JsExport` surface and ships
its Kotlin sources as a copyable template (see §6). `aardflex-web-app` gets its own tiny
Gradle project (`editor-wasm/`, living in that repo) that depends on `:editor-web` +
`:languages` and adds one file, `AardflexLanguages.kt`, containing the ported Monarch
grammar. That project's wasmJs **executable** build produces the `.mjs`/`.wasm` that is
packaged and published as `@aardarch/aardink-web` from *that* repo's CI — Aardink itself
never builds an executable, only libraries. §11 gives that repo's side of the work.

Target version for this whole migration: **0.5.0** (additive API surface, no removals —
see the ABI notes in §9). Android consumers keep writing
`implementation("com.aardarch:aardink:0.5.0")` unchanged: Kotlin Multiplatform publishing
produces a root publication (`com.aardarch:aardink`, Gradle Module Metadata only) plus one
per target (`com.aardarch:aardink-android` .aar, `com.aardarch:aardink-jvm` .jar,
`com.aardarch:aardink-wasm-js` .klib); Gradle resolves the `-android` variant automatically
via the module metadata's `org.jetbrains.kotlin.platform.type` attribute — the exact
mechanism `kotlinx.coroutines` already relies on. Same shape for `aardink-languages` and
`aardink-languages-lsp`.

## 1. Target module layout

```
editor/                 com.aardarch:aardink                android, jvm, wasmJs   (Compose)
languages/               com.aardarch:aardink-languages      android, jvm, wasmJs   (pure Kotlin — Compose plugin removed)
languages-lsp/           com.aardarch:aardink-languages-lsp  android, jvm, wasmJs
editor-web/              com.aardarch:aardink-editor-web     wasmJs only            (bridge library, NEW)
sample/                  Android app                                                (unchanged sources — the zero-regression gate)
sample-desktop/          jvm application                                            (NEW — dev loop, Compose UI test bed)
sample-web/              wasmJs application                                         (NEW — browser harness, reference for aardflex-web-app)
tools/consumer-smoke/    Android app, `implementation("com.aardarch:aardink:...")`  (NEW — proves the published .aar still resolves)
```

Per-library source-set tree after the move (created with `git mv` to preserve history):

```
editor/src/commonMain/kotlin/com/aardarch/aardink/{core,ui}/...
editor/src/commonMain/kotlin/com/aardarch/aardink/platform/         # expect declarations — ONLY expect/actual lives here
editor/src/androidMain/kotlin/com/aardarch/aardink/platform/        # actuals
editor/src/jvmMain/kotlin/com/aardarch/aardink/platform/
editor/src/wasmJsMain/kotlin/com/aardarch/aardink/platform/
editor/src/commonTest/kotlin/...                                    # all existing tests, converted to kotlin.test
editor/src/jvmTest/kotlin/...                                       # Compose UI tests (runComposeUiTest)
```

`languages-lsp` additionally gets a hand-created `jvmAndAndroidMain` / `jvmAndAndroidTest`
pair (§2) to hold `StreamLspTransport` and its test, since `applyDefaultHierarchyTemplate()`
does not create a JVM+Android shared source set by itself.

Delete `editor/src/main/AndroidManifest.xml`, `languages/src/main/AndroidManifest.xml`
(the Android KMP plugin takes `namespace` from the Gradle DSL, not the manifest).
`consumer-rules.pro`/`proguard-rules.pro` are wired through `optimization.consumerKeepRules`
if non-empty, otherwise deleted.

## 2. Gradle configuration

### 2.1 `gradle/libs.versions.toml` additions

```toml
[versions]
plugin-compose-multiplatform = "1.12.0"
# plugin-kotlin, plugin-agp, kotlinx-coroutines, kotlinx-serialization: already pinned, reused as-is

[plugins]
plugin-kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "plugin-kotlin" }
plugin-android-kmp-library  = { id = "com.android.kotlin.multiplatform.library", version.ref = "plugin-agp" }
plugin-compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "plugin-compose-multiplatform" }

[libraries]
kotlin-test              = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "plugin-kotlin" }
kotlin-test-junit5        = { module = "org.jetbrains.kotlin:kotlin-test-junit5", version.ref = "plugin-kotlin" }
kotlinx-coroutines-test   = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
kotlinx-coroutines-swing  = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "kotlinx-coroutines" }
```

Remove `plugin-kotlin-binary-compat` and its plugin alias entirely — KGP's built-in ABI
validation (§9) replaces it, and it never worked on this repo's Android modules anyway.
Drop the `androidx-compose-ui-text-google-fonts` coordinate from `bundles.compose-core` —
grep confirms it is unused, and it is an Android-only artifact with no CMP equivalent.

### 2.2 Root `build.gradle.kts`

Add `apply false` entries for `plugin-kotlin-multiplatform`, `plugin-android-kmp-library`,
`plugin-compose-multiplatform`, alongside the existing ones. Delete the BCV comment block
at the bottom of the file (the one starting `// NOTE: kotlinx.binary-compatibility-validator...`).
Extend `dokkaAll`/`dokkaAllGfm` to depend on `:editor-web`'s Dokka tasks too.

### 2.3 New convention plugin `buildSrc/src/main/kotlin/aardink.kmp-library.gradle.kts`

Factors out what `:editor`, `:languages`, `:languages-lsp` share:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("aardink.dokka-gfm")
}

extensions.findByType<com.diffplug.gradle.spotless.SpotlessExtension>() ?: run {
    apply(plugin = "com.diffplug.spotless")
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        target("src/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_no-empty-file" to "disabled",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

if (providers.gradleProperty("signingInMemoryKey").orNull == null) {
    apply(plugin = "signing")
    configure<SigningExtension> { useGpgCmd() }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation { }
}
```

(`target("src/**/*.kt")` matches every KMP source set unchanged — no Spotless config
needs to change per module.)

### 2.4 `:editor/build.gradle.kts` (the template every library follows)

```kotlin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.plugin.kotlin.multiplatform)
    alias(libs.plugins.plugin.android.kmp.library)
    alias(libs.plugins.plugin.compose.multiplatform)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.kotlin.serialization) // EditorThemeParser port, §3.1
    alias(libs.plugins.plugin.vanniktech.maven.publish)
    id("aardink.kmp-library")
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())

    android {
        namespace = "com.aardarch.aardink"
        compileSdk = 37
        minSdk = 26
        // Host/device tests intentionally NOT enabled here — every test is commonTest
        // and already runs on jvmTest + wasmJsBrowserTest; add withHostTestBuilder{}
        // only if an Android-framework-dependent test ever appears.
    }
    jvm()
    wasmJs {
        browser {
            // No explicit useKarma { useChromeHeadless() } -- see §2.4a.
        }
        // No binaries.executable() — this module is a klib, consumed by :editor-web
        // or a downstream app module.
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.mp.runtime)
            api(libs.compose.mp.foundation)
            api(libs.compose.mp.material3)
            api(libs.compose.mp.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        named("jvmTest") {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                runtimeOnly(libs.junit.jupiter.engine)
                runtimeOnly(libs.junit.platform.launcher)
                implementation(libs.compose.mp.ui.test)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        groupId = "com.aardarch",
        artifactId = "aardink",
        version = providers.gradleProperty("VERSION_NAME").get(),
    )
    pom { /* unchanged from today's block */ }
}
```

`:languages/build.gradle.kts` differences: **no** `plugin.compose.multiplatform` /
`plugin.kotlin.compose` / `composeCompiler {}` (grep confirms zero `androidx.compose`
imports in this module — keeping the plugin only inflates the compile classpath for no
benefit); `commonMain.dependencies { api(project(":editor")) }` in place of the Compose
`api(...)` lines. Consumers still get Compose transitively through `:editor`.

`:languages-lsp/build.gradle.kts` differences: add
`alias(libs.plugins.plugin.kotlin.serialization)`; no Compose plugins;
`commonMain.dependencies { api(project(":editor")); api(libs.kotlinx.serialization.json); api(libs.kotlinx.coroutines.core) }`
(kept `api` — `JsonElement`/`CoroutineScope` are in public signatures, matching today's
policy). Add the hand-created shared source set (§1):

```kotlin
kotlin {
    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain") { dependsOn(commonMain.get()) }
        val jvmAndAndroidTest = create("jvmAndAndroidTest") { dependsOn(commonTest.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)
        jvmTest.get().dependsOn(jvmAndAndroidTest)
        androidUnitTest.get().dependsOn(jvmAndAndroidTest) // if host tests are ever enabled
    }
}
```

### 2.4a Deviations from the above, as actually implemented

These were deliberate and are load-bearing. Do not "fix" them back to what §2.4 sketches.

- **Compose plugins ARE applied to `:languages` and `:languages-lsp`**, despite neither
  containing Compose code. Compose Multiplatform's `wasmJsBrowserTest` needs the bundled
  skiko runtime to run *any* browser test, including plain `kotlin.test` ones.
- **`binaries.executable()` IS declared on all three library `wasmJs` targets**, even though
  the published artifact is a klib. Same reason — see CMP-4906.
- **`androidUnitTest.dependsOn(jvmAndAndroidTest)` is omitted** in `:languages-lsp`. Android
  host tests are not enabled (every test already runs on `jvmTest` and `wasmJsBrowserTest`),
  so wiring it would reference a source set nothing consumes.
- **No explicit `useKarma { useChromeHeadless() }`.** The default browser test configuration
  already resolves headless Chrome via `CHROME_BIN`.
- **No `aardink.kmp-library` convention plugin** (§2.3). It needs the Kotlin and Spotless
  plugins on `buildSrc`'s classpath for typed extension access, and that makes Gradle reject
  each module's own versioned `alias(...)` with "already on the classpath with an unknown
  version". `compileOnly` does not help: the types are then invisible at execution time. The
  same collision is why AGP was always kept off that classpath. `buildSrc` is gone entirely
  as a result, and `abiValidation { }` is configured per module.
- **Dokka is applied per module, not from `buildSrc`** (§9.1), for the same classloader
  reason: a precompiled script plugin cannot see plugins resolved through a module's
  `plugins {}` block, so Dokka could not find `KotlinBasePlugin` and generated nothing.
- **There is no GFM/Markdown Dokka output** (§9.1, §9.4). Dokka 2's Gradle plugin does not
  support it: the dependency resolves and the task reports success while emitting zero files.
  `dokkaAllGfm` is removed and `release.yml` bundles the HTML output.
- **The libraries have no `lint` task.** `com.android.kotlin.multiplatform.library` registers
  none, so §9.3's `:editor:lint` line is not achievable; only `:sample:lintDebug` runs.
- **ABI task names are `checkKotlinAbi` / `updateKotlinAbi`** (§9.2's first guess), aggregated
  by root `checkAbiAll` / `updateAbiAll`. `checkLegacyAbi` / `updateLegacyAbi` also exist as
  aliases.

### 2.4b Current build DSL — use this in every new or changed module

Commits `00ec1d7` / `7f49676` (and the catalog fix after them) moved the build onto the
non-deprecated APIs of Compose Multiplatform 1.12.1, AGP 9.4.1 and vanniktech 0.37. New
modules (`:editor-web`, `:sample-web`, `aardflex-web-app`'s `editor-wasm/`) follow the same
rules:

| Instead of (deprecated) | Use |
| --- | --- |
| `compose.runtime`, `compose.foundation`, `compose.material3`, `compose.ui`, `compose.uiTest`, `compose.components.resources` | The `libs.compose.mp.*` catalog entries (`compose-mp-runtime`, `-foundation`, `-material3`, `-ui`, `-ui-test`; add `-components-resources` for §6). **These coordinates carry no implicit version** — the plugin is not a BOM, so a bare `"org.jetbrains.compose.ui:ui"` string fails resolution. Everything except material3 uses `version.ref = "plugin-compose-multiplatform"`; material3 has its own line (`compose-multiplatform-material3`), so read the CMP release notes for it on every bump. `compose.desktop.currentOs` is not deprecated and stays. |
| `androidLibrary { }` | `android { }` inside `kotlin { }` |
| `sourcesJar = true` | `sourcesJar = SourcesJar.Sources()` |
| `val jvmTest by getting { }` | `named("jvmTest") { dependencies { } }` (`commonMain.dependencies { }` / `commonTest.dependencies { }` accessors are fine) |
| `val x by creating { }` | `val x = create("x") { }` |

Renovate groups `org.jetbrains.compose.*:**` with Kotlin, so the library coordinates move in
lockstep with the plugin; material3's separate version still needs a manual check.

### 2.5 `gradle.properties`

Add `kotlin.mpp.enableCInteropCommonization=false` (no native targets); raise
`org.gradle.jvmargs` to `-Xmx6g` if the wasm link step needs it (verify during PR 3);
commit the generated `kotlin-js-store/` lockfile once it appears.

*As of PR 8:* `org.gradle.jvmargs` stayed at `-Xmx4g`, but `kotlin.daemon.jvmargs` had to go
from `-Xmx2g` to `-Xmx4g`. Linking the four libraries' wasmJs test executables (each bundles
Compose and Skiko) in one build ran the Kotlin daemon out of heap at 2g, even with
`--no-parallel`.

## 3. Portability fixes in `commonMain`

### 3.1 `ui/EditorThemeParser.kt` — drop `org.json`

Currently imports `org.json.JSONObject`/`JSONException` (Android-only, no common
equivalent). Port to `kotlinx.serialization.json`, keeping the public signature
`fun fromJson(json: String): EditorTheme?` unchanged:

```kotlin
fun fromJson(json: String): EditorTheme? = try {
    parseTheme(Json.parseToJsonElement(json).jsonObject)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.hexColor(key: String): Color? {
    val hex = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("#") } ?: return null
    return parseHex(hex)
}
```

Replace `optJSONObject`/`optJSONArray`/`optString`/`.has()`/`.get()` with
`jsonObject["colors"]?.jsonObject`, `["tokenColors"]?.jsonArray`,
`jsonPrimitive.contentOrNull`, `containsKey(...)`, `[...]` respectively — the branching
logic in `parseTheme`/`buildTokenColors`/`mapScope` is otherwise unchanged. This is the
**only** dependency change to `:editor`'s public dependency graph; document the exception
to "no runtime dependencies without necessity" in AGENTS.md (§10), noting
`:languages-lsp` already carries `kotlinx-serialization-json`.

### 3.2 New package `com.aardarch.aardink.platform` (expect/actual lives ONLY here)

`EditorDispatchers.kt`:

```kotlin
expect object EditorDispatchers {
    /** Off-main on JVM/Android; the single event loop on wasmJs. */
    val compute: CoroutineDispatcher
    /** True when [compute] is the UI thread — callers should chunk long work. */
    val computeIsMainThread: Boolean
}
```
- `androidMain`/`jvmMain` actual: `compute = Dispatchers.Default`, `computeIsMainThread = false` (no behaviour change on Android).
- `wasmJsMain` actual: `compute = Dispatchers.Default` (single-threaded on wasm), `computeIsMainThread = true`.

`PlatformInfo.kt`:

```kotlin
expect object PlatformInfo {
    val isMacOs: Boolean
    val hasSoftKeyboard: Boolean
    val defaultToolbarPlacement: KeyboardToolbarPlacement
}
```
- `androidMain`: `isMacOs = false`, `hasSoftKeyboard = true`, `defaultToolbarPlacement = KeyboardToolbarPlacement.BottomHover` (today's behaviour, unchanged).
- `jvmMain`: `isMacOs` from `System.getProperty("os.name")`, `hasSoftKeyboard = false`, `defaultToolbarPlacement = KeyboardToolbarPlacement.Hidden`.
- `wasmJsMain`: `isMacOs` from `navigator.userAgent`/`navigator.platform`, `hasSoftKeyboard = navigator.maxTouchPoints > 0`, `defaultToolbarPlacement = if (hasSoftKeyboard) KeyboardToolbarPlacement.BottomFixed else KeyboardToolbarPlacement.Hidden` (`WindowInsets.ime` is always 0 on web, so `BottomHover` would never trigger there).

`ui/KeyboardToolbarPlacement.kt` gains an additive companion:
```kotlin
companion object { val platformDefault: KeyboardToolbarPlacement get() = PlatformInfo.defaultToolbarPlacement }
```
`CodeEditorLayout`'s existing `keyboardToolbarPlacement: KeyboardToolbarPlacement = KeyboardToolbarPlacement.BottomHover` parameter default is **left unchanged** (Android source/binary compatibility); `:sample-desktop` and `:editor-web` explicitly pass `KeyboardToolbarPlacement.platformDefault`.

### 3.3 `core/CodeEditorState.kt` — dispatcher injection

`computeDispatcher` (today `var computeDispatcher: CoroutineDispatcher = Dispatchers.Default`)
becomes `= EditorDispatchers.compute` (identical value on Android/JVM; tests already
override it, unaffected). Every `withContext(Dispatchers.Default)` call site in
`CodeEditorLayout.kt` (rename resolution, signature help, code actions, diff lane —
7 sites) becomes `withContext(state.computeDispatcher)`.

### 3.4 New `ui/EditorTypography.kt`

```kotlin
@Immutable
data class EditorTypography(
    val fontFamily: FontFamily = FontFamily.Monospace,
    val fontSize: TextUnit = 14.sp,
    val lineHeight: TextUnit = 20.sp,
)

val LocalEditorTypography: ProvidableCompositionLocal<EditorTypography> = staticCompositionLocalOf { EditorTypography() }
```

`CodeEditorLayout`, `EditorGutter`, `CompletionDropdown`, `FindReplacePanel`,
`HoverDocPopup`, `SignatureHelpPopup`, `KeyboardToolbarStyle` read
`LocalEditorTypography.current` instead of the hardcoded `FontFamily.Monospace` +
`EditorDefaults.fontSize`/`lineHeight`. Defaults are identical to today's constants, so
Android output (and Roborazzi screenshots) is pixel-unchanged unless a host explicitly
provides the local. **No font is bundled inside `:editor`** — that would add ~250 KB to
every Android consumer and force `components-resources` into the library's public
dependency graph for no Android benefit. `:editor-web` and `:sample-web` bundle JetBrains
Mono (OFL license) via `libs.compose.mp.components.resources` (§2.4b) and provide `LocalEditorTypography`
themselves (§6).

### 3.5 `:languages-lsp` — `LspClient.kt`

Public contract to preserve: `class LspClient(val transport: LspTransport, private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO))`.
Current blockers: `java.util.concurrent.{ConcurrentHashMap,CopyOnWriteArrayList,atomic.AtomicLong}`,
five `@Synchronized` methods (`start`, `onReceiveLoopEnded`, `registerPending`,
`onConnectionLost`, `stop`), `@Volatile`, `Dispatchers.IO` as a public constructor default.

Rewrite using `kotlinx.coroutines.sync.Mutex` — reject `kotlinx.atomicfu` (extra compiler
plugin + runtime dep for every consumer) and `kotlin.concurrent.atomics` (still
`@ExperimentalAtomicApi` in Kotlin 2.4):

```kotlin
class LspClient(
    val transport: LspTransport,
    private val scope: CoroutineScope = CoroutineScope(EditorDispatchers.io),
) {
    private val lock = Mutex()
    private var nextRequestId: Long = 1L                                    // guarded by lock
    private val pendingRequests = HashMap<Long, CompletableDeferred<LspMessage.Response>>() // guarded by lock
    @kotlin.concurrent.Volatile private var diagnosticsListeners: List<DiagnosticsListener> = emptyList() // copy-on-write, lock-free read
    private var listeningJob: Job? = null                                   // guarded by lock
    @kotlin.concurrent.Volatile private var closed = false
    private var transportClosed = false                                    // guarded by lock
    ...
}
```

`EditorDispatchers.io` is added alongside `.compute` in `:editor`'s `platform` package
(`androidMain`/`jvmMain`: `Dispatchers.IO`; `wasmJsMain`: `Dispatchers.Default` — the
`Dispatchers.IO` symbol does not exist on wasmJs). This keeps `LspClient`'s JVM-visible
constructor shape and default-value bytecode identical (binary compatible).

`start()`/`stop()`/`addDiagnosticsListener`/`removeDiagnosticsListener` remain
**non-suspend** public API (unchanged call sites in `:sample`). Since `Mutex.tryLock()` is
non-suspend and succeeds whenever no coroutine holds the lock (true almost always — every
`withLock` block is microseconds), implement each as:
```kotlin
fun stop() {
    if (lock.tryLock()) {
        try { stopLocked() } finally { lock.unlock() }
    } else {
        scope.launch { lock.withLock { stopLocked() } }
    }
}
```
`registerPending`/`onReceiveLoopEnded`/`onConnectionLost` are already called from suspend
context (`sendRequest`, the receive loop, `sendOrLoseConnection`) and become plain
`lock.withLock { ... }` blocks. `diagnosticsListeners` uses copy-on-write reassignment
under the same lock pattern so the hot read path (dispatching a notification) stays
allocation-free and lock-free. Existing `LspClientTest` scenarios ("stop() during an
in-flight request", concurrent add/remove) validate the rewrite; add one more:
"stop() called from a non-suspend context while sendRequest holds the lock" to exercise
the `tryLock` fallback path.

Drop the `java.io.IOException` import (`LspClient.kt:35`) — it is referenced only in KDoc
prose, not code.

### 3.6 `:languages-lsp` — `LspTransport.kt` and new `WebSocketLspTransport`

- `interface LspTransport`, `ChannelLspTransport` — unchanged, stay in `commonMain` (already fully portable).
- `StreamLspTransport(InputStream, OutputStream)` — move **verbatim** (same public constructor, same behaviour, same `MAX_FRAME_BYTES`/`MAX_HEADER_BYTES` limits) into `languages-lsp/src/jvmAndAndroidMain/kotlin/com/aardarch/aardink/languages/lsp/StreamLspTransport.kt`. Its two `withContext(Dispatchers.IO)` sites are untouched (`Dispatchers.IO` is available in this source set).
- New `languages-lsp/src/wasmJsMain/kotlin/com/aardarch/aardink/languages/lsp/WebSocketLspTransport.kt`.

> **The sketch below has three bugs and was NOT implemented as written.** Read the shipped
> `WebSocketLspTransport.kt` instead; it is kept here only to document the intent.
>
> 1. `onerror` is assigned after `onopen` inside the `connect` continuation, so a socket that
>    opens and then errors resumes the continuation twice.
> 2. There is no `invokeOnCancellation { socket.close() }`, so a cancelled `connect` leaks
>    the socket.
> 3. `connect` installs handlers that the instance's `init` then replaces, dropping any frame
>    that arrives in between.
>
> The implementation instead installs all four handlers once in the constructor and never
> replaces them, and `connect` awaits a `CompletableDeferred` those handlers complete — whose
> idempotent completion makes double-resume impossible rather than merely unlikely. It also
> treats a close *before* open as a failure, so a refused connection cannot hang the caller.


```kotlin
class WebSocketLspTransport private constructor(private val socket: WebSocket) : LspTransport {
    private val incoming = Channel<String>(Channel.UNLIMITED)

    init {
        socket.onmessage = { event -> incoming.trySend(event.data.toString()) }
        socket.onclose = { incoming.close() }
        socket.onerror = { incoming.close(IllegalStateException("WebSocket error")) }
    }

    override suspend fun sendPayload(payload: String) {
        if (socket.readyState != WebSocket.OPEN) throw IllegalStateException("transport closed")
        socket.send(payload)
    }

    override suspend fun receivePayload(): String? = incoming.receiveCatching().getOrNull()

    override fun close() {
        socket.close()
        incoming.close()
    }

    companion object {
        /** Resolves once the socket is open; one JSON-RPC payload per WebSocket frame (no Content-Length framing over WS). */
        suspend fun connect(url: String, protocols: List<String> = emptyList()): WebSocketLspTransport =
            suspendCancellableCoroutine { cont ->
                val socket = WebSocket(url, protocols.toTypedArray())
                socket.onopen = { cont.resume(WebSocketLspTransport(socket)) }
                socket.onerror = { cont.resumeWithException(IllegalStateException("WebSocket connection to $url failed")) }
            }
    }
}
```

Uses `org.w3c.dom.WebSocket` externals directly — **no Ktor dependency** (rejecting the
old plan's Ktor proposal keeps `:languages-lsp`'s dependency graph exactly as today plus
one new wasmJs-only transport). Add `WebSocketLspTransportTest` in `wasmJsTest` against a
local fake `WebSocket` (Karma has no server to connect to) covering open/send/close/error.

### 3.7 `LspLanguageService.kt`

Three `@Volatile` fields (diagnostics cache + version tracking) become
`@kotlin.concurrent.Volatile` (stable common annotation since Kotlin 1.9; JVM gets a real
volatile field, wasm gets a no-op since it is single-threaded). No other changes — the
four snippet-parsing `Regex` constants and all `kotlinx.serialization` usage are already
portable.

## 4. Text-input migration to `TextFieldState`

This is the largest single change and is done **first**, on the Android-only source tree
(PR 1 in §8), before any multiplatform source move — it isolates the highest-risk
behavioural change from the mechanical KMP migration.

### 4.1 Ownership

`CodeEditorState` (`core/CodeEditorState.kt`) gains a public field:
```kotlin
val textFieldState: TextFieldState = TextFieldState(initialText)
```
It is documented as the interop point for `BasicTextField` and is otherwise not meant to
be edited directly by hosts (all mutation goes through `CodeEditorState`'s existing public
methods). `CodeDocument` continues to own the canonical text/undo bookkeeping;
`textFieldState.text` is kept equal to `document.text` after every mutation. The
`selection` property's setter becomes `internal` exactly as today, but its getter now
reads `textFieldState.selection` instead of a separate `mutableStateOf<TextRange>` (source
compatible: `state.selection` still returns a `TextRange`). `textVersion`/`tokenVersion`
counters are unchanged.

### 4.2 User edits → document/undo (replaces `computeEditDelta`/`handleTextChange`)

`CodeEditorLayout` installs one `InputTransformation`:
```kotlin
private class EditorInputTransformation(
    private val state: CodeEditorState,
    private val languageService: LanguageService?,
    private val triggerChars: Set<Char>,
    private val onCompletionRequest: (Int, Char) -> Unit,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        changes.forEachChangeReversed { range, originalRange ->
            val deleteLen = originalRange.length
            val insertText = if (range.collapsed) "" else asCharSequence().substring(range.min, range.max)
            if (deleteLen > 0) {
                val deletedText = state.document.text.substring(originalRange.min, originalRange.max)
                state.document.delete(originalRange.min, deleteLen)
                state.undoManager.recordDelete(originalRange.min, deleteLen, deletedText)
            }
            if (insertText.isNotEmpty()) {
                state.document.insert(originalRange.min, insertText)
                state.undoManager.recordInsert(originalRange.min, insertText)
            }
        }
        state.bumpTextVersionAndScheduleTokenization() // new internal helper, replaces the tail of applyEdit
        // Post-edit language hooks — smart indent / auto-close / completion trigger —
        // operate on `this` (the same TextFieldBuffer) using the same logic that lives
        // in today's handleTextChange (CodeEditorLayout.kt:775-835), adapted to call
        // insert/delete/placeCursorAfterCharAt on the buffer instead of building a new
        // TextFieldValue.
    }
}
```
`computeEditDelta` and `handleTextChange` (`CodeEditorLayout.kt:754-838`,
`:989-1009`) are deleted; their logic (smart-indent-on-`\n`, auto-close, completion
trigger detection, completion-item range re-addressing) moves inside the block above,
operating on `TextFieldBuffer` directly. Because `InputTransformation` only ever runs for
user-originated edits (framework guarantee), no reentrancy flag is needed for the
input side — contrast with the `applyingExternal` guard `aardflex-web-app`'s own
`EditorPane.svelte` needs today for the same reason on the Monaco side.

### 4.3 Programmatic edits → field (replaces the 16 `TextFieldValue(...)` construction sites)

`applyEdit`, `applyTextEdits`, `undo`, `redo`, `loadText`, `applyCompletion`'s replacement,
and rename all keep mutating `CodeDocument` + `EditorUndoManager` exactly as today (that
logic — batch ordering, `mapThroughEdits`, caret carry-through — does not change), then
push the result into the field:
```kotlin
textFieldState.edit {
    replace(0, length, state.document.text) // or a targeted replace(start, end, newText) where the edit is a single contiguous range
    selection = newSelection
}
```
`loadText` additionally calls `textFieldState.undoState.clearHistory()` — the field's
built-in undo stack is otherwise never used, because Ctrl/Cmd+Z is intercepted at the
`onPreviewKeyEvent` level (§5.1) and routed through `EditorUndoManager` so the existing
insert-coalescing/batch semantics keep working identically to today.

### 4.4 Rendering (replaces `VisualTransformation`/`FoldingTransform.kt`'s `applyFolding`)

One `OutputTransformation` remembered in `CodeEditorLayout`, keyed the same way
`syntaxTransformation` is today (`effectiveAnnotatedText`, `textColor`, `matches`,
`currentMatchIndex`, `foldedRanges`):
```kotlin
private class EditorOutputTransformation(
    private val tokens: List<Token>,
    private val textColor: Color,
    private val matches: List<IntRange>,
    private val currentMatchIndex: Int,
    private val matchHighlight: Color,
    private val currentMatchHighlight: Color,
    private val foldedRanges: List<FoldRange>,
    private val document: CodeDocument,
    private val placeholderStyle: SpanStyle,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        // 1. Syntax colors: addStyle(SpanStyle(color = ...), token.start, token.end) per token,
        //    same default-color fallback as today's `base` AnnotatedString branch.
        // 2. Find-match backgrounds: addStyle(SpanStyle(background = ...), range.first, range.last + 1).
        // 3. Folding, LAST (so steps 1-2 address original offsets, matching today's ordering where
        //    folding is applied after highlighting/find in the VisualTransformation chain):
        //    for each non-nested fold range (sorted, skip-if-nested exactly as today's applyFolding):
        //      val hStart = document.lineEnd(fold.startLine); val hEnd = document.lineEnd(fold.endLine)
        //      delete(hStart, hEnd)
        //      insert(hStart, " ${fold.placeholder}")
        //      addStyle(placeholderStyle, hStart, hStart + 1 + fold.placeholder.length)
    }
}
```
`OutputTransformation.transformOutput()` supports `insert`/`delete`/`replace` plus
`addStyle(SpanStyle, start, end)`; the framework guarantees the cursor never lands inside
an inserted run (the placeholder text) and automatically maintains the offset mapping
between original and displayed text — the hand-written `origToTrans`/`transToOrig`
arrays in today's `FoldingTransform.kt` are no longer needed for the field itself.
`applyFolding` (the public function) and `originalToTransformedOffset` (internal) are
**kept** — `originalToTransformedOffset` is still needed by the gutter (§4.5), and
`applyFolding` is marked `@Deprecated("Superseded by TextFieldState + OutputTransformation; kept for hosts using the legacy field")`
for one minor version rather than deleted, in case any external consumer built their own
field around it.

### 4.5 Layout and gutter

```kotlin
var layoutResult by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
...
BasicTextField(
    state = state.textFieldState,
    outputTransformation = syntaxOutputTransformation,
    inputTransformation = editorInputTransformation,
    onTextLayout = { getResult -> layoutResult = getResult },
    lineLimits = TextFieldLineLimits.MultiLine(), // unbounded — matches today's fully-grown field inside a scrolling Box
    scrollState = rememberScrollState(), // hoisted separately from the outer Box's verticalScrollState; the field itself does not need to scroll internally since lineLimits is unbounded
    textStyle = TextStyle(fontFamily = typography.fontFamily, fontSize = typography.fontSize, lineHeight = typography.lineHeight, color = textColor),
    cursorBrush = SolidColor(cursorColor),
    readOnly = readOnly,
    modifier = Modifier.fillMaxSize().editorKeyboardShortcuts(shortcutActions), // §5.1
)
```
The gutter's `lineTops`/`lineBottoms` computation (`CodeEditorLayout.kt:592-660`) is
unchanged in structure — it still calls `tlr.getLineForOffset`/`getLineTop`/`getLineBottom`
— but reads `layoutResult?.invoke()` instead of a stored `TextLayoutResult?` from
`onValueChange`/`onTextLayout` combined; `originalToTransformedOffset` continues to map
logical document offsets into displayed offsets for the fold-aware lookup, exactly as
today. The outer `Box`'s `verticalScroll`/`horizontalScroll` + shared `ScrollState` with
`EditorGutter` is unchanged.

### 4.6 Navigation and cursor reporting

`pendingNavigation` handling becomes `textFieldState.edit { selection = TextRange(nav.targetOffset) }` (or the `nav.select` range) after computing the scroll target exactly as today (`getLineForOffset`/`getLineTop` off `layoutResult`). Cursor reporting becomes:
```kotlin
LaunchedEffect(state) {
    snapshotFlow { state.textFieldState.selection }.collect { sel ->
        val (line, col) = state.document.offsetToLineCol(sel.start)
        onCursorChange(line + 1, col + 1)
    }
}
```

### 4.7 Public API delta and required test updates

Additive: `CodeEditorState.textFieldState` (new public property). Deprecated-but-present:
`applyFolding`. No removals. Update `editor/src/test/.../ui/CompletionApplyTest.kt` and
`FoldingTransformTest.kt` to exercise the new `EditorOutputTransformation` path (new file
`EditorOutputTransformationTest.kt` replacing the parts of `FoldingTransformTest` that
tested `applyFolding`'s `TransformedText`/`OffsetMapping` directly — keep a handful of
tests on the deprecated `applyFolding` itself so its behavior stays proven while it exists).
`CodeEditorStateTest.kt` gains assertions that `textFieldState.text` tracks `document.text`
through `applyEdit`/`applyTextEdits`/`undo`/`redo`/`loadText`.

### 4.8 Verification

Run this PR through: existing unit tests green; `:sample` manual pass covering typing,
IME composition (a physical or emulator soft keyboard), fold toggle, find/replace,
completion accept, rename, undo/redo, paste; `scripts/capture-screenshots.ps1` re-run and
diffed against the current committed `screenshots/*.png` — a diff here must be
individually justified (e.g., cursor rendering) before merging, since this PR predates
any multiplatform change and must not silently alter Android behavior.

## 5. Desktop/web behaviours (additive, Android default unchanged)

### 5.1 Hardware keyboard shortcuts — new `ui/EditorShortcuts.kt`

```kotlin
internal fun Modifier.editorKeyboardShortcuts(actions: EditorShortcutActions): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val cmd = if (PlatformInfo.isMacOs) event.isMetaPressed else event.isCtrlPressed
        when {
            cmd && event.isShiftPressed && event.key == Key.Z -> { actions.onRedo(); true }
            cmd && event.key == Key.Z -> { actions.onUndo(); true }
            cmd && event.key == Key.Y -> { actions.onRedo(); true }
            cmd && event.key == Key.F -> { actions.onFind(); true }
            cmd && event.key == Key.H -> { actions.onReplace(); true }
            cmd && event.key == Key.G -> { actions.onGoToLine(); true }
            event.key == Key.Tab && !event.isShiftPressed -> { actions.onIndent(); true }
            event.key == Key.Tab && event.isShiftPressed -> { actions.onOutdent(); true }
            event.key == Key.Escape -> actions.onEscape()
            else -> false
        }
    }

internal class EditorShortcutActions(
    val onUndo: () -> Unit, val onRedo: () -> Unit,
    val onFind: () -> Unit, val onReplace: () -> Unit, val onGoToLine: () -> Unit,
    val onIndent: () -> Unit, val onOutdent: () -> Unit,
    val onEscape: () -> Boolean, // returns whether something was dismissed, to decide if the event is consumed
)
```
`CodeEditorLayout` gains one new trailing, defaulted parameter:
`onRequestGoToLine: () -> Unit = {}` (additive; hosts wire it to `GoToLineDialog`). Scope
is deliberately narrow — undo/redo/find/replace/go-to-line/indent-outdent/escape only; no
multi-cursor, no Ctrl+/, no Ctrl+D. On Android with a hardware keyboard attached this is a
net-new correctness fix: today's BTF1 built-in Ctrl+Z bypasses `EditorUndoManager`
entirely, desynchronizing the field from the document; intercepting at preview level
routes every undo through the same manager the toolbar buttons use.

### 5.2 Scrollbars — new `ui/EditorScrollbars.kt`

```kotlin
@Composable
internal expect fun EditorScrollbars(vertical: ScrollState, horizontal: ScrollState?, modifier: Modifier)
```
- `androidMain` actual: empty composable (today has no scrollbars — zero Android regression).
- `jvmMain` actual: `VerticalScrollbar(rememberScrollbarAdapter(vertical), modifier)` at the trailing edge of the text box; same for a horizontal one when `horizontal != null`.
- `wasmJsMain` actual: same `VerticalScrollbar`/`rememberScrollbarAdapter` call if it compiles against wasmJs (these live in Compose Foundation's shared `skiko`-family source set); if it does not compile, fall back to a ~30-line Canvas thumb drawn with `Modifier.drawBehind` reading `vertical.value`/`vertical.maxValue`. Confirm which path applies while implementing PR 6 and note the outcome in `docs/WEB_INTEGRATION.md`.

Mouse wheel scrolling needs no code — Compose Foundation's `verticalScroll`/`horizontalScroll` already handle wheel events on Skia-backed targets (desktop and wasm).

### 5.3 Clipboard

No new code — `BasicTextField(TextFieldState)`'s built-in cut/copy/paste is used as-is on
every target. Verification items (not implementation): large-paste latency, `\r\n`
normalization (add a `.replace("\r\n", "\n")` in the input transformation if the web
harness shows CRLF pastes breaking `CodeDocument`'s line index), and Firefox's clipboard
permission prompt. Tracked as checklist items W-3 in §7.

### 5.4 Cooperative tokenization (wasm main-thread mitigation)

Additive default method on `core/IncrementalTokenizer.kt`:
```kotlin
/** Like [tokenizeFull] but may suspend between chunks so a single-threaded host (wasm) keeps
 *  painting. Default delegates to [tokenizeFull] — override where tokenization can be chunked. */
suspend fun tokenizeFullCooperative(text: String): List<Token> = tokenizeFull(text)
```
`languages/internal/RegexTokenizer.kt` and the hand-written tokenizers
(`XmlTokenizer.kt`, `KotlinTokenizer.kt`, `TomlLanguageService.kt`'s tokenizer, etc.)
override it to `yield()` every ~2,000 tokens via one shared `internal suspend inline fun`
helper in `languages/internal/CooperativeTokenizing.kt`. `CodeEditorState.runTokenization`
calls `tokenizeFullCooperative` when `EditorDispatchers.computeIsMainThread` is true, the
plain `tokenizeFull`/`tokenizeLines` otherwise — **no behavior change on Android/JVM**.
New `core/EditorLimits.kt`:
```kotlin
object EditorLimits {
    /** Above this size, tokenization/folding switch to the cooperative, chunked path. */
    var cooperativeTokenizeThresholdChars: Int = 64 * 1024
    /** Above this size, tokenization and folding are skipped entirely (plain-color fallback). */
    var plainTextFallbackChars: Int = 2 * 1024 * 1024
}
```
Folding and find already run on a 200 ms debounce and are single O(n) passes — leave them
as-is except for the same size guard. `SimpleDiffProvider.diff` runs per keystroke only
when a host passes `savedText`; `:editor-web`/`:sample-web` do not pass it, so the diff
lane is inert on web by default.

### 5.4a As implemented (PR 7)

- **Both limits apply only where `EditorDispatchers.computeIsMainThread` is true** (wasmJs).
  Android and JVM never consult `EditorLimits`, which is what makes the "no Android behaviour
  change" DoD hold exactly, including for multi-megabyte documents. `CodeEditorState` carries
  an `internal var computeOnMainThread` (defaulting to the platform value) so JVM tests drive
  the wasm paths.
- **Above the cooperative threshold every pass is a full `tokenizeFullCooperative`**, even
  when only a few lines are dirty: `tokenizeLines` cannot suspend, and every built-in
  tokenizer's `tokenizeLines` is a full retokenize anyway. The result goes through
  `TokenCache.reset`, not `merge`. Each edit cancels the pass in flight, so on wasm a stale
  pass is abandoned at its next `yield()` instead of running to completion.
- **Plain-text fallback resets the cache to empty**, so tokens from before the document grew
  never outlive it, and shrinking back under the limit retokenizes from scratch. Folding gets
  the same guard (`CodeEditorState.exceedsAnalysisLimit`). **Find is not guarded**: it is
  user-initiated and dropping matches silently would look like a bug.
- The helper is a small `internal class CooperativePacer` (yield every 2,000 tokens), not an
  inline function. `RegexTokenizer` and `XmlTokenizer` share one `private inline fun scan`
  between the blocking and cooperative entry points, so the two cannot drift;
  `HtmlTokenizer` delegates.
- **`RegexTokenizer` was quadratic**, and the cooperative tests exposed it: every rule ran
  `find` from the cursor at every token and discarded the losers, so a rule with no nearby
  match rescanned to the end of the document each time (~105 KB of Kotlin took over a minute
  on the JVM). It now caches each rule's next match until the cursor passes it, which gives
  identical tokens (`RegexTokenizerTest` pins it against the old algorithm) at 0.3 s for the
  same input. The 2,000-token chunk size assumes this fix: before it, one chunk could take
  seconds.
- **Lookbehinds are poison on wasm.** Kotlin/wasm does not use the browser's `RegExp`: it ships
  the pure-Kotlin `kotlin.text.regex` engine, which evaluates a lookbehind at every candidate
  position. `KotlinTokenizer`'s `(?<=\b(?:class|object|interface|enum)\s)` rule took 4.7 s for
  ten `find`s on 3 KB, and even a fixed-length `(?<=\bfun\s)` costs ~2.5 ms per `find`. Both
  rules are gone. `RegexTokenizer` gained a `protected open fun refine(text, tokens)` pass and
  `KotlinTokenizer` types declaration names there, pinned by the JVM-only
  `KotlinTokenizerGoldenTest` against the old rules over every `.kt` file in the repo. Measured
  in headless Chrome at 64 KB: Kotlin 170 ms, TypeScript 113 ms, TOML 78 ms, CSS 62 ms,
  Markdown 58 ms, JSON 46 ms, XML/HTML 2 ms. **Do not add lookbehind rules to any tokenizer**;
  use `refine`. (The old comment saying these compiled to a JS `RegExp` was wrong, and so is
  review item B6's premise that browser lookbehind support is what matters.) Markdown's one
  negative lookbehind is inside that 58 ms and was left alone.
- wasm tests run under `runTest`, whose dispatcher never yields to the browser, so one test
  that blocks for more than about 2 s makes Karma drop the page ("Disconnected ... ping timeout")
  with no test named. If that happens, look at the first test class alphabetically after the
  last `TEST-*.xml` that was written.

## 6. `:editor-web` bridge library and npm package

### 6.1 `editor-web/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.plugin.kotlin.multiplatform)
    alias(libs.plugins.plugin.compose.multiplatform)
    alias(libs.plugins.plugin.kotlin.compose)
    alias(libs.plugins.plugin.kotlin.serialization)
    alias(libs.plugins.plugin.spotless)
}
kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    wasmJs {
        browser()
        // Still a library klib for publishing, but -- as in the other three libraries (§2.4a,
        // CMP-4906) -- wasmJsBrowserTest needs an executable with the Skiko runtime to run any
        // test at all. The consuming app module (:sample-web, or aardflex-web-app's own module)
        // declares its own binaries.executable() and links this library plus its languages.
        binaries.executable()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        named("wasmJsMain") {
            dependencies {
                api(project(":editor"))
                api(project(":languages"))
                // Compose itself arrives through :editor's api dependencies.
                implementation(libs.compose.mp.components.resources) // JetBrains Mono, bundled HERE not in :editor
                implementation(libs.kotlinx.serialization.json) // WebEditorOptions / diagnostics JSON
            }
        }
        named("wasmJsTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
compose.resources { packageOfResClass = "com.aardarch.aardink.web.res" }
```

Catalog addition (§2.4b): `compose-mp-components-resources = { module =
"org.jetbrains.compose.components:components-resources", version.ref =
"plugin-compose-multiplatform" }`. Also wire `abiValidation { }`, Dokka and
`mavenPublishing { configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka(...),
sourcesJar = SourcesJar.Sources())) }` exactly as `:editor/build.gradle.kts` does, and add
`:editor-web` to `checkAbiAll` / `updateAbiAll` / `dokkaAll` in the root build.

### 6.2 Kotlin API — `editor-web/src/wasmJsMain/kotlin/com/aardarch/aardink/web/AardinkWeb.kt`

```kotlin
@Serializable
data class WebEditorOptions(
    val language: String = "plaintext",
    val theme: String = "vscode-dark",
    val fontSize: Float = 14f,
    val wordWrap: Boolean = true,
    val readOnly: Boolean = false,
    val showGutter: Boolean = true,
    val showLineNumbers: Boolean = true,
    val showFoldMarkers: Boolean = true,
)

class AardinkEditorHandle internal constructor(
    internal val scope: CoroutineScope,
    internal val state: CodeEditorState,
    internal val findReplaceState: FindReplaceState,
    internal val foldState: FoldState,
    internal val options: MutableState<WebEditorOptions>,
    internal val diagnostics: MutableState<List<Diagnostic>>,
    internal var onChangeCb: ((String) -> Unit)?,
    internal var onCursorCb: ((Int, Int) -> Unit)?,
)

object AardinkWeb {
    /** Mounts a Compose Multiplatform editor into the DOM element with id [containerId] via
     *  [ComposeViewport]. [registry] and [themes] let the caller (e.g. a product's own wasmJs
     *  app module) supply additional [LanguageDefinition]s/[EditorTheme]s beyond [LanguageRegistry.withBuiltIns]. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun mount(
        containerId: String,
        initialText: String,
        options: WebEditorOptions,
        registry: LanguageRegistry = LanguageRegistry.withBuiltIns(),
        themes: Map<String, EditorTheme> = EditorThemes.all,
    ): AardinkEditorHandle {
        val scope = CoroutineScope(SupervisorJob() + EditorDispatchers.compute)
        val language = registry.byId(options.language) ?: registry.byId("plaintext")!!
        val state = CodeEditorState(initialText, tokenizer = language.tokenizer)
        val handle = AardinkEditorHandle(scope, state, FindReplaceState(), FoldState(), mutableStateOf(options), mutableStateOf(emptyList()), null, null)
        // CMP 1.12.1 ships both this id overload and ComposeViewport(viewportContainer: Element).
        // Check which is non-deprecated (and whether the opt-in is still required) in PR 8.
        ComposeViewport(viewportContainerId = containerId) {
            CompositionLocalProvider(
                LocalEditorTheme provides (themes[handle.options.value.theme] ?: EditorThemes.VsCodeDark),
                LocalEditorTypography provides EditorTypography(fontSize = handle.options.value.fontSize.sp),
            ) {
                LaunchedEffect(state) {
                    snapshotFlow { state.textVersion }.collect { handle.onChangeCb?.invoke(state.document.text) }
                }
                CodeEditorLayout(
                    state = state,
                    languageService = language.languageService,
                    findReplaceState = handle.findReplaceState,
                    foldState = handle.foldState,
                    foldingProvider = language.foldingProvider,
                    diagnostics = handle.diagnostics.value,
                    readOnly = handle.options.value.readOnly,
                    keyboardToolbarPlacement = KeyboardToolbarPlacement.platformDefault,
                    showGutter = handle.options.value.showGutter,
                    showLineNumbers = handle.options.value.showLineNumbers,
                    showFoldMarkers = handle.options.value.showFoldMarkers,
                    softWrap = handle.options.value.wordWrap,
                    onCursorChange = { line, col -> handle.onCursorCb?.invoke(line, col) },
                )
            }
        }
        return handle
    }

    fun getValue(handle: AardinkEditorHandle): String = handle.state.document.text
    fun setValue(handle: AardinkEditorHandle, text: String) = handle.state.loadText(text)
    fun updateOptions(handle: AardinkEditorHandle, patch: WebEditorOptions) { handle.options.value = patch }
    fun onChange(handle: AardinkEditorHandle, callback: (String) -> Unit) { handle.onChangeCb = callback }
    fun onCursorChange(handle: AardinkEditorHandle, callback: (Int, Int) -> Unit) { handle.onCursorCb = callback }
    fun setDiagnostics(handle: AardinkEditorHandle, diagnostics: List<Diagnostic>) { handle.diagnostics.value = diagnostics }
    fun navigateTo(handle: AardinkEditorHandle, line: Int, column: Int) { /* offset from line/col via handle.state.document, then handle.state.navigateTo(offset) */ }
    fun showFind(handle: AardinkEditorHandle, replace: Boolean) = handle.findReplaceState.show(replace)
    fun undo(handle: AardinkEditorHandle): Boolean = handle.state.undo() != null
    fun redo(handle: AardinkEditorHandle): Boolean = handle.state.redo() != null
    fun dispose(handle: AardinkEditorHandle) { handle.scope.cancel(); handle.onChangeCb = null; handle.onCursorCb = null }
}
```

`AardinkWeb` (the object above) is the reusable, Maven-published part — it takes a
`LanguageRegistry`/theme map as parameters precisely so a downstream product can supply
its own grammar without Aardink knowing about it (this is what fulfils the "grammar lives
in the consumer" decision from §0).

### 6.3 `@JsExport` surface — a template, not a published file

Kotlin/Wasm's `@JsExport` only recognizes top-level functions declared **in the module
that produces the final executable** — verify during PR 8 whether functions in a library
klib (`:editor-web`) are re-exported by a consuming app module's `binaries.executable()`,
or whether each consumer must declare its own `@JsExport` wrappers calling into
`AardinkWeb`. If re-export does not work, ship the following as a documented,
copy-pasteable file under `editor-web/src/wasmJsMain/kotlin/.../ExportsTemplate.kt.txt`
(not compiled — a template) and inline it in this document and in `docs/WEB_INTEGRATION.md`:

```kotlin
@file:OptIn(ExperimentalJsExport::class)
package <consumer package>

private val handles = mutableMapOf<Int, AardinkEditorHandle>()
private var nextHandleId = 0

@JsExport
fun createAardinkEditor(containerId: String, initialText: String, optionsJson: String): Int {
    val id = nextHandleId++
    handles[id] = AardinkWeb.mount(containerId, initialText, Json.decodeFromString(optionsJson), registry = <consumer's LanguageRegistry>, themes = <consumer's themes>)
    return id
}
@JsExport fun getValue(id: Int): String = AardinkWeb.getValue(handles[id]!!)
@JsExport fun setValue(id: Int, text: String) = AardinkWeb.setValue(handles[id]!!, text)
@JsExport fun updateOptions(id: Int, optionsJson: String) = AardinkWeb.updateOptions(handles[id]!!, Json.decodeFromString(optionsJson))
@JsExport fun onChange(id: Int, callback: (String) -> Unit) = AardinkWeb.onChange(handles[id]!!, callback)
@JsExport fun onCursorChange(id: Int, callback: (Int, Int) -> Unit) = AardinkWeb.onCursorChange(handles[id]!!, callback)
@JsExport fun setDiagnosticsJson(id: Int, json: String) = AardinkWeb.setDiagnostics(handles[id]!!, Json.decodeFromString(json))
@JsExport fun navigateTo(id: Int, line: Int, column: Int) = AardinkWeb.navigateTo(handles[id]!!, line, column)
@JsExport fun showFind(id: Int, replace: Boolean) = AardinkWeb.showFind(handles[id]!!, replace)
@JsExport fun undo(id: Int): Boolean = AardinkWeb.undo(handles[id]!!)
@JsExport fun redo(id: Int): Boolean = AardinkWeb.redo(handles[id]!!)
@JsExport fun dispose(id: Int) { AardinkWeb.dispose(handles[id]!!); handles.remove(id) }
@JsExport fun aardinkVersion(): String = "<VERSION_NAME>"
```
(Integer handle ids rather than `JsReference<AardinkEditorHandle>` — simpler across the
`@JsExport` boundary and avoids relying on `JsReference` semantics that are still marked
experimental; revisit if `JsReference` proves clearly preferable during implementation.)
`:sample-web` (§7) contains the working, compiled copy of this template and is the
reference every downstream consumer (including `aardflex-web-app`, §11) copies from.

### 6.4 npm packaging — Gradle task + wrapper sources

`editor-web/src/npm/package.json` (version stamped from `VERSION_NAME` by the Gradle task
below), `index.d.ts` (the TypeScript surface a Svelte/React/Vue app consumes — mirrors the
Monaco subset `aardflex-web-app/src/lib/components/EditorPane.svelte` already uses):
```ts
export interface AardinkOptions {
  language?: string; theme?: string; fontSize?: number; wordWrap?: 'on' | 'off';
  readOnly?: boolean; showGutter?: boolean; showLineNumbers?: boolean; showFoldMarkers?: boolean;
}
export interface AardinkDiagnostic { line: number; startColumn: number; endColumn: number; message: string; severity: 'error' | 'warning' | 'info'; }
export interface AardinkEditor {
  getValue(): string;
  setValue(text: string): void;
  updateOptions(options: Partial<AardinkOptions>): void;
  onDidChangeContent(cb: (text: string) => void): () => void;
  onDidChangeCursor(cb: (line: number, column: number) => void): () => void;
  setDiagnostics(diagnostics: AardinkDiagnostic[]): void;
  revealPosition(line: number, column: number): void;
  showFind(replace?: boolean): void;
  undo(): boolean; redo(): boolean;
  dispose(): void;
}
export function createEditor(container: HTMLElement, onChange: (text: string) => void, options?: AardinkOptions): Promise<AardinkEditor>;
export function preloadAardink(): Promise<void>;
export const version: string;
```
`index.js` lazily `await import('./<module>.mjs')` (memoised across calls), assigns the
container an id if it lacks one, and adapts the numeric-handle exports from §6.3 into the
`AardinkEditor` object shape above (`wordWrap: 'on'|'off'` mapped to the boolean the Kotlin
side expects; `onDidChangeContent`/`onDidChangeCursor` return an unsubscribe closure).

This npm package's Gradle-side production, publishing, and consumption by
`aardflex-web-app` are described fully in §11, since the actual `.mjs`/`.wasm` are built by
the **consumer's** executable module (§6.1's comment on `binaries.executable()`), not by
`:editor-web` itself. `:editor-web` is what publishes to Maven Central; `:sample-web` (§7)
is the reference for how a consumer turns it into a runnable bundle and, from *that*
module, into an npm package — copy `:sample-web`'s `npmPackage` Gradle task and `src/npm/`
folder verbatim into `aardflex-web-app`'s own Gradle project (§11).

Browser requirements to document (Wasm GC + exception handling): Chrome/Edge 119+,
Firefox 120+, Safari 18.2+.

### 6.5 As implemented (PR 8)

- **API differences from §6.2:** `showFind(handle)` has no `replace` flag, because
  `FindReplaceState.show()` has none. `AardinkWeb.builtInThemes` replaces `EditorThemes.all`,
  which does not exist; adding it would widen `:editor`'s public API for a web-only need.
  Added: `currentOptions`, `patchOptions(json)` (Monaco-style partial update, so the template
  needs no JSON merging), `parseOptions`, `setDiagnosticsJson`, `isDisposed`.
- **Diagnostics** take a new `@Serializable WebDiagnostic` (Monaco-marker shape: 1-based, end
  column exclusive) and convert to `Diagnostic`, whose `range` is *inclusive* of its last
  character (that is how `SquiggleUnderline` reads it; `Diagnostic`'s KDoc saying "exclusive
  end" is wrong).
- **Language changes rebuild the `CodeEditorState`**, since its tokenizer is fixed at
  construction. The text survives but undo history does not.
- **Each editor gets its own `CoroutineScope(SupervisorJob() + Dispatchers.Main)`** passed to
  `CodeEditorState`, because the default scope is never cancelled. `dispose()` cancels that
  scope and drops the editor's composition (the viewport's content becomes empty).
  **Compose 1.12.1 has no public API to tear down a `ComposeViewport`**, so the canvas and its
  frame loop remain until the host removes the container. W-1 (PR 9) must measure what that
  leaks.
- **`ComposeViewport(element)`**, not the `viewportContainerId` overload. Neither is deprecated
  in 1.12.1, and `@OptIn(ExperimentalComposeUiApi::class)` is still required (verified by
  removing it).
- **The font is loaded with `Res.readBytes` behind a fallback**, not `Font(Res.font...)`. The
  latter throws inside composition when the file 404s, which kills the whole editor; that is
  what happened under Karma, which does not serve `composeResources/`. Now a failed load logs one
  line and keeps `FontFamily.Monospace`. It loads once per page. Only Regular is bundled (~270
  KB); Skia synthesizes bold/italic.
- **`onChange` compares against a version recorded when the state is created**, not the first
  value the composition sees. Otherwise `mount()` followed immediately by `setValue()`, before
  the first frame, is silently not reported.
- **`@JsExport` re-export works** from a dependency klib into the consuming executable at
  Kotlin 2.4.20, as checked with `:editor-web`'s test executable (main and test are separate
  klibs). Confirm it with `:sample-web` in PR 9. The template (§6.3) is kept regardless, because
  only the consumer knows its registry and themes. It is compiled in `editor-web/src/wasmJsTest/`
  as `ExportsTemplate.kt` with `ExportsTemplateTest`, rather than shipped as a `.kt.txt`. Exports are
  prefixed `aardink` (`aardinkCreate`, `aardinkGetValue`, ...) so they cannot collide with the
  consumer's own. `docs/WEB_INTEGRATION.md` links the file instead of inlining a copy that could
  drift. **§6.4's `index.js` must use these names.**
- `compose.resources { generateResClass = Always }` is required: `Auto` only generates `Res` when
  `components-resources` is a `commonMain` dependency, and here it is `wasmJsMain`-only.
- `editor-web/karma.config.d/mocha-timeout.js` raises Mocha's 2 s per-test timeout: the first
  mount in a page starts Skiko, which can outlast it on a cold headless Chrome.

## 7. Samples

### 7.1 `:sample-desktop`

`jvm { }` `application`-style module: `implementation(project(":editor"))`,
`implementation(project(":languages"))`, `implementation(project(":languages-lsp"))`,
`implementation(compose.desktop.currentOs)`, `implementation(libs.kotlinx.coroutines.swing)`
(needed for `Dispatchers.Main` on the JVM target). Renders the same UI the Android
`:sample` does (language cards, theme picker, CSS LSP demo) via `application { main = ... }`
calling `singleWindowApplication`. This is the fastest loop for iterating on §5's keyboard
shortcuts and scrollbars, and hosts the `runComposeUiTest` scenarios from §8.

### 7.2 `:sample-web`

wasmJs **executable** module (this is where `binaries.executable()` is actually declared):
```kotlin
kotlin {
    wasmJs {
        browser()
        binaries.executable()
    }
    sourceSets {
        named("wasmJsMain") {
            dependencies {
                implementation(project(":editor-web"))
                implementation(project(":languages"))
            }
        }
    }
}
```
`src/wasmJsMain/kotlin/.../SampleWebMain.kt` calls `AardinkWeb.mount(...)` directly (no
`@JsExport` needed for the harness itself) and adds: language dropdown over
`LanguageRegistry.withBuiltIns()`, theme dropdown over `EditorThemes.all`, toggles for
soft-wrap/gutter/toolbar placement, a "load 5,000-line file" button (performance probe,
§5.4), and a live stats bar (line count, char count, caret position). A second entry point
`src/wasmJsMain/kotlin/.../BridgeMain.kt` **is** the compiled copy of §6.3's export
template, used to smoke-test the JS-facing surface from a plain HTML page
(`src/wasmJsMain/resources/bridge.html`) before any product repo depends on it. A scratch
Vite project `tools/vite-smoke/` imports the built npm package output and renders it,
proving the whole pipeline end-to-end without needing `aardflex-web-app` present.

### 7.3 Web verification checklist (`docs/WEB_INTEGRATION.md`)

Executed against `:sample-web` during PR 9, results recorded in the doc:

| ID | Check |
| --- | --- |
| W-1 | `ComposeViewport` teardown/memory: mount+dispose 50×, watch heap for leaks |
| W-2 | IME composition: CJK input, macOS dead keys, Android Chrome Gboard — composing text must not double-apply |
| W-3 | Paste: 100 KB paste latency; `\r\n` normalization; Firefox clipboard permission prompt |
| W-4 | Selection: mouse drag, Shift+arrows, double-click word select, touch handles on mobile browsers |
| W-5 | Mobile soft keyboard: visual viewport resize vs. canvas; toolbar placement policy (§3.2) |
| W-6 | Focus: click-to-focus; Tab must not escape the field; Ctrl+F/Ctrl+S must not open the browser's own dialogs |
| W-7 | Wheel scroll containment: page must not scroll when the cursor is over the editor |
| W-8 | DPI/zoom: `devicePixelRatio` changes re-layout correctly; font sharpness |
| W-9 | Fallback font loading: no visible flicker when Compose downloads a Noto subset for an unsupported glyph |
| W-10 | Performance: typing latency in the 5,000-line file, with and without the cooperative-tokenization path (§5.4) |

## 8. Test strategy

- Convert all 23 existing test files from JUnit 5 (`org.junit.jupiter.api.Test`,
  `Assertions.assertEquals/assertTrue/.../assertThrows(X::class.java){}`) to `kotlin.test`
  (`Test`, `assertEquals`, `assertTrue`, `assertFailsWith<X>{}`) and `runBlocking{}` (~70
  sites across `CodeEditorStateTest`, `JsonLanguageServiceTest`, `KotlinLanguageServiceTest`,
  `TomlLanguageServiceTest`, `XmlLanguageServiceTest`, `LspClientTest`,
  `LspLanguageServiceTest`, `LspTransportTest`) to `runTest{}` from
  `kotlinx-coroutines-test` (wasm has no `runBlocking`). Move them into `commonTest` except
  `LspTransportTest.kt` (uses `java.io.ByteArray{Input,Output}Stream`), which moves into
  `languages-lsp/src/jvmAndAndroidTest/`.
- These run on `jvmTest` (JUnit Platform via `kotlin-test-junit5`) and `wasmJsBrowserTest`
  (Karma + headless Chrome).
- New Compose UI tests in `editor/src/jvmTest/kotlin/com/aardarch/aardink/ui/CodeEditorLayoutUiTest.kt`
  using `@OptIn(ExperimentalTestApi::class) runComposeUiTest { }`: typing updates
  `state.text`; Ctrl+Z/Ctrl+Y round-trip through `EditorUndoManager`; Ctrl+F opens
  `FindReplacePanel` (`onNodeWithTag("aardink.find")`); toggling a fold hides its lines;
  accepting a completion inserts the expected text; Tab indents the current selection.
  Add an internal `EditorTestTags` object with the tag constants used above.
- `editor-web/src/wasmJsTest/kotlin/.../AardinkWebTest.kt`: create a `<div>` via
  `kotlinx.browser.document`, call `AardinkWeb.mount`, assert `setValue`/`getValue`
  round-trip, `onChange` fires after a programmatic edit, `dispose` is idempotent.
- `:sample`'s Roborazzi screenshot tests are unchanged and run in verify mode in CI
  (`:sample:testDebugUnitTest`) — this is the primary Android zero-regression gate.
- Consumer smoke: `scripts/verify-consumer.ps1` runs `publishToMavenLocal` for the four
  libraries, then `tools/consumer-smoke:assembleDebug`, then
  `./gradlew :tools:consumer-smoke:dependencies --configuration debugRuntimeClasspath` and
  greps for `aardink-android` to confirm the correct KMP variant was selected.

## 9. Build infrastructure, CI, and scripts

### 9.1 `buildSrc/src/main/kotlin/aardink.dokka-gfm.gradle.kts`

Replace the entire `pluginManager.withPlugin("com.android.library") { afterEvaluate { ... reflective bootClasspath/compileDebugKotlin ... } }`
block with a hook on the multiplatform plugin instead:
```kotlin
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    if (!isDokkaRequested) return@withPlugin
    pluginManager.apply("org.jetbrains.dokka")
    apply<DokkaMarkdownPlugin>()
    extensions.configure<DokkaExtension> {
        dokkaSourceSets.configureEach {
            externalDocumentationLinks.register("androidx") {
                url.set(URI("https://developer.android.com/reference/kotlin/"))
                packageListUrl.set(URI("https://developer.android.com/reference/kotlin/androidx/package-list"))
            }
            externalDocumentationLinks.register("coroutines") { url.set(URI("https://kotlinlang.org/api/kotlinx.coroutines/")) }
        }
    }
}
```
Dokka 2.2's Kotlin Gradle Plugin adapter auto-registers `commonMain`, `androidMain`,
`jvmMain`, `wasmJsMain` (and `jvmAndAndroidMain` in `:languages-lsp`) with correct
classpaths on its own — none of the `bootClasspath`/`compileDebugKotlin` reflection this
repo currently needs for AGP 9 is required once the module is multiplatform. Remove the
`grep -vE 'failed to get extension androidComponents|...'` noise filter from
`release.yml`'s "Generate API docs" step once a KMP build confirms it is no longer emitted.

### 9.2 ABI validation

Uses Kotlin 2.4's built-in `abiValidation {}` (wired in `aardink.kmp-library.gradle.kts`,
§2.3) instead of the parked `binary-compatibility-validator` plugin. Tasks are
`checkKotlinAbi` (CI) / `updateKotlinAbi` (local, commit the result under `editor/api/`,
`languages/api/`, `languages-lsp/api/`). Confirm the exact task/dump-file names on first
run (`./gradlew :editor:tasks --all | grep -i abi`); if only legacy-named tasks
(`checkLegacyAbi`/`updateLegacyAbi`) are present at the pinned Kotlin version, use those
instead and note it in AGENTS.md. Since no `.api` dump exists today, PR 3 (§10) takes the
**first** baseline — every subsequent PR's ABI diff must be purely additive.

### 9.3 `.github/workflows/ci.yml`

```yaml
      - uses: browser-actions/setup-chrome@v2   # provides CHROME_BIN for Karma

      - name: Spotless check
        run: ./gradlew :editor:spotlessCheck :languages:spotlessCheck :languages-lsp:spotlessCheck :editor-web:spotlessCheck :sample:spotlessCheck

      - name: Lint
        run: ./gradlew :editor:lint :languages:lint :languages-lsp:lint :sample:lintDebug

      - name: ABI check
        run: ./gradlew :editor:checkKotlinAbi :languages:checkKotlinAbi :languages-lsp:checkKotlinAbi

      - name: JVM unit tests
        run: ./gradlew :editor:jvmTest :languages:jvmTest :languages-lsp:jvmTest

      - name: wasmJs browser tests
        run: ./gradlew :editor:wasmJsBrowserTest :languages:wasmJsBrowserTest :languages-lsp:wasmJsBrowserTest :editor-web:wasmJsBrowserTest

      - name: Build sample app (debug + release) and verify screenshots
        run: ./gradlew :sample:assembleDebug :sample:assembleRelease :sample:testDebugUnitTest

      - name: Build desktop and web samples
        run: ./gradlew :sample-desktop:packageUberJarForCurrentOS :sample-web:wasmJsBrowserDistribution

      - name: Upload artifacts
        uses: actions/upload-artifact@v7
        with:
          name: sample-web-dist
          path: sample-web/build/dist/wasmJs/productionExecutable
```
(Keep the existing APK/test-results/lint-results upload steps; extend their paths to
include `languages-aardflex`-equivalent modules only if any are added later — none are
added inside this repo per §0's decision.)

### 9.4 `.github/workflows/release.yml`

`publishAndReleaseToMavenCentral` already publishes every KMP publication (root + android
+ jvm + wasm-js) per library once the modules are multiplatform — no new step is needed for
Maven Central beyond what exists today. Add, after the existing steps: build
`:sample-web:wasmJsBrowserDistribution` and attach its output as a zip to the GitHub
Release (useful for manually verifying a tagged build in a browser without a full Vite
project). `:editor-web` is included in `dokkaAllGfm`'s bundling automatically once §2.2's
change lands.

### 9.5 `scripts/pre-push.ps1`

Replace the four hardcoded module-list literals with one variable:
```powershell
$Libs = @(':editor', ':languages', ':languages-lsp')
```
and derive the Spotless/lint/test task arrays from it (`$Libs | ForEach-Object { "$_:spotlessCheck" }`, etc.), adding `:editor-web:spotlessCheck` explicitly since it has no
Android/lint step. Replace the "Unit tests" step with two: `jvmTest` for `$Libs`, and
`wasmJsBrowserTest` for `$Libs + ':editor-web'` gated behind a new `-SkipWasm` switch
(Chrome must be installed locally; document this in AGENTS.md). Add an "ABI check" step
running `checkKotlinAbi` for `$Libs`. Extend the license-header `$SourceRoots` array with
`editor-web/src`, `sample-desktop/src`, `sample-web/src`, `tools/consumer-smoke/src`.
Replace the "Publish to mavenLocal" step with a call to the new
`scripts/verify-consumer.ps1` (§8), which itself calls `publishToMavenLocal` for `$Libs`
before building the consumer-smoke app. `scripts/capture-screenshots.ps1` needs no change.

## 10. PR sequence with Definition of Done

Each PR is independently revertible (`git revert`); Android zero-regression for PRs 1–9 is
verified by: `:sample:assembleDebug`/`assembleRelease` succeed, `:sample:testDebugUnitTest`
(Roborazzi, verify mode) shows no unintended pixel diffs, `scripts/verify-consumer.ps1`
passes, and (from PR 3 onward) `checkKotlinAbi` shows only additions versus the previous
PR's dump.

| # | PR | Definition of Done |
| --- | --- | --- |
| 0 | Commit the pending Roborazzi work already in the tree; add `tools/consumer-smoke/` + `scripts/verify-consumer.ps1`; regenerate and commit baseline screenshots | Script and CI green on the current Android-only build — this is the pre-migration baseline everything else diffs against |
| 1 | `TextFieldState` migration (§4), still Android-only | Unit tests updated and green; Roborazzi diffs individually justified or absent; manual pass: typing, IME, paste, fold, find/replace, completion, rename, undo/redo |
| 2 | Add new plugins to the catalog/root as `apply false`; no module behavior change | CI green |
| 3 | `:editor` → KMP: source move to `commonMain`, `EditorThemeParser` port (§3.1), `EditorDispatchers`/`PlatformInfo`/`EditorTypography` (§3.2–3.4), tests → `commonTest`, Dokka convention rewrite (§9.1), first ABI dump | `jvmTest` + `wasmJsBrowserTest` green; Roborazzi unchanged; consumer smoke green; `editor/api/` committed |
| 4 | `:languages` → KMP (Compose plugin removed, §2.4) | Same gates + `:languages:wasmJsBrowserTest` |
| 5 | `:languages-lsp` → KMP: `LspClient` Mutex rewrite (§3.5), transport split + `WebSocketLspTransport` (§3.6), `@kotlin.concurrent.Volatile` (§3.7) | `LspClientTest`/`LspLanguageServiceTest` green on jvm + wasm; `LspTransportTest` green on jvm; `:sample`'s CSS LSP demo still works manually |
| 6 | `:sample-desktop`; keyboard shortcuts (§5.1); scrollbars (§5.2); `KeyboardToolbarPlacement.platformDefault`; Compose UI tests (§8) | UI tests green on jvm; Roborazzi unchanged (shortcuts only fire on hardware keys, off by default on Android touch) |
| 7 | Cooperative tokenization + `EditorLimits` (§5.4) | Tokenizer tests cover the cooperative path; no Android behavior change |
| 8 | `:editor-web` library: `AardinkWeb` object (§6.2), export template (§6.3), wasmJs bridge test (§8) | `:editor-web:wasmJsBrowserTest` green; template compiles when pasted into `:sample-web` |
| 9 | `:sample-web` harness (§7.2); execute checklist W-1…W-10 (§7.3) and fix findings | Checklist filled in `docs/WEB_INTEGRATION.md`; `tools/vite-smoke/` successfully renders the editor; CI uploads the web dist |
| 10 | CI/release/scripts updates (§9.3–9.5); documentation (§11 below); release 0.5.0 | A dry-run tag (`v0.5.0-rc1`) through `release.yml` publishes all Maven artifacts across 3 targets for 3 libraries; `docs/WEB_INTEGRATION.md` complete |
| 11 | **Separate repo, `aardflex-web-app`**: switch-over from Monaco (§11) | `pnpm check`/`pnpm test`/`pnpm build` green; ported tokenizer golden tests pass; bundle size compared and recorded |

## 11. `aardflex-web-app` switch-over (executed in that repo, after PR 10 above ships)

This work happens in `C:\repos\aardarch\aardflex-web-app`, not in this repository, and
depends on Aardink 0.5.0 being available (either from `mavenLocal()`/`includeBuild` during
development, or from Maven Central once released).

1. **New Gradle project** `editor-wasm/` inside `aardflex-web-app` (a JDK 21 + Gradle 9
   toolchain living alongside the pnpm workspace — add a JDK setup step before `vp build`
   in that repo's `.github/workflows/ci.yml` and `deploy-firebase.yml`). Depends on
   `com.aardarch:aardink-editor-web` + `com.aardarch:aardink-languages` (Maven Central, or
   `includeBuild("../aardink")` for local iteration against an unreleased Aardink change).
2. Port `src/editor/setup.ts`'s grammar into
   `editor-wasm/src/wasmJsMain/kotlin/.../AardflexLanguages.kt`:
   - `AardflexXmlTokenizer` extending `com.aardarch.aardink.languages.internal.xml.XmlTokenizer`, adding token rules for `{expr}`, `{module:prop}`, `| transform`, `:format` (source: `setup.ts:261-346`'s Monarch grammar).
   - `AardflexLanguageService` extending `TagValidator`, with completions built from the five data tables at `setup.ts:13-245` (`ELEMENTS`, `ATTRIBUTES`, `MODULE_TYPES`, `TEXT_STYLES`, `TRANSFORMS`), trigger characters `<`, space, `"`, `|`, `:` (mirrors `setup.ts:368-447`).
   - `AardflexDarkTheme: EditorTheme` reproducing the 8 token-color rules and `#1E1E2E` background from `setup.ts:349-365`.
   - `AardflexLanguages.register(registry: LanguageRegistry): LanguageRegistry` and a `main()`/`Exports.kt` that is the compiled copy of §6.3's template, calling `AardinkWeb.mount(..., registry = AardflexLanguages.register(LanguageRegistry.withBuiltIns()), themes = mapOf("aardflex-dark" to AardflexLanguages.AardflexDarkTheme))`.
3. `editor-wasm/build.gradle.kts` sets `binaries.executable()` and a `dist`/`npmPackage`
   Gradle task copied from `:sample-web` (§7.2) that copies the built `.mjs`/`.wasm`/
   `composeResources`/`.d.ts` into `aardflex-web-app/src/editor/wasm/` (add that path to
   `.gitignore`) or packages them for GitHub Packages the same way `:editor-web`'s notes
   describe.
4. `src/lib/components/EditorPane.svelte`: `createEditor` becomes async
   (`onMount(async () => { instance = await createEditor(container, onChange, options) })`);
   `getValue`/`setValue`/`updateOptions`/`dispose` calls are otherwise unchanged since
   `index.d.ts` (§6.4) mirrors the existing Monaco-shaped calls.
5. `vite.config.ts`: add `optimizeDeps: { exclude: ['@aardarch/aardink-web'] }` (so Vite's
   dev-server pre-bundler does not break the wasm loader's relative asset URLs); remove the
   Monaco-specific `optimizeDeps.include` and the `editor.worker?worker` import; keep
   `chunkSizeWarningLimit: 3500`.
6. Remove the `monaco-editor` dependency from `package.json`; rewrite
   `src/editor/setup.test.ts`'s tokenizer-only tests against `AardflexXmlTokenizer`'s
   Kotlin-side golden output (ported as fixed input/expected-token-list pairs, run through
   the wasm bridge in a Vitest test, or — simpler — kept as Kotlin tests inside
   `editor-wasm`'s own `wasmJsTest`, whichever this repo's test runner can execute fastest).
7. Ship behind a `VITE_EDITOR=monaco|aardink` build-time flag for one release so the switch
   is instantly revertible; remove Monaco and the flag in the following release once the
   team is confident.
8. Update that repo's `specs/ui.md` note about phone-view Monaco limitations — the
   motivating problem for this whole migration — to reflect that Aardink is now the editor
   on every viewport size, not just mobile.

## 12. Documentation deliverables

- **AGENTS.md**: updated layout table (four libraries + `editor-web` + three sample
  modules + `tools/consumer-smoke`), tech stack section noting KMP/CMP and `kotlin.test`
  in `commonTest` (JUnit 5 only survives in `jvmTest` via the `kotlin-test-junit5` bridge),
  build commands (`jvmTest`, `wasmJsBrowserTest`, `checkKotlinAbi`/`updateKotlinAbi`,
  `:sample-desktop:run`, `:sample-web:wasmJsBrowserDevelopmentRun`), the
  `kotlinx-serialization-json` dependency exception for `:editor` (§3.1), and two new
  hard rules: "`expect`/`actual` declarations live only under `platform/`" and "never call
  `Dispatchers.Default`/`Dispatchers.IO` directly in `:editor`/`:languages-lsp` — use
  `EditorDispatchers`".
- **README.md**: a platform matrix (Android / Desktop / Web) replacing the current
  Android-only framing; fix the existing stale `rememberCodeEditorState` snippet (it shows
  a nonexistent `language =` parameter and pins version `0.1.0`); add the npm package
  install section with the GitHub Packages `.npmrc` scope line.
- **CHANGELOG.md** `## [0.5.0]`: **Added** — jvm/wasmJs targets for all three libraries,
  `:sample-desktop`, `:sample-web`, `:editor-web`, `CodeEditorState.textFieldState`,
  keyboard shortcuts, scrollbars, `LocalEditorTypography`, `EditorDispatchers`,
  `WebSocketLspTransport`, `tokenizeFullCooperative`, `EditorLimits`, the
  `@aardarch/aardink-web` npm package. **Changed** — `EditorThemeParser`'s JSON backend,
  `LspClient`'s internal synchronization, `:languages` no longer applies the Compose
  plugin. **Deprecated** — `applyFolding`.
- **`docs/WEB_INTEGRATION.md`** (new): install instructions (`.npmrc` + `pnpm add`), Vite
  config snippet, the `EditorPane.svelte` usage pattern, Monaco-option-name → Aardink-
  option-name mapping table, browser support matrix, the completed W-1…W-10 checklist
  (§7.3), and known limitations (no minimap, no bracket-pair coloring, no multi-cursor).
- **KDoc** on every new public symbol introduced above (`EditorDispatchers`,
  `PlatformInfo`, `EditorTypography`/`LocalEditorTypography`, `EditorLimits`,
  `tokenizeFullCooperative`, `WebSocketLspTransport`, `AardinkWeb` and its members,
  `CodeEditorState.textFieldState`) — Dokka GFM output is already bundled into the release
  archive by the existing `release.yml` step, so nothing else is needed to publish it.

## 13. Risk register

| Risk | Mitigation |
| --- | --- |
| `TextFieldState`/`OutputTransformation` behaves subtly differently under IME on Android | PR 1 is isolated on the Android-only tree; gated by Roborazzi + a manual IME/paste/fold/undo pass before any KMP work begins |
| `ComposeViewport` has no documented teardown API | `dispose()` cancels the coroutine scope and detaches the DOM subtree; W-1 in the checklist explicitly probes for leaks |
| Whether `@JsExport` in a library klib is re-exported by a consuming executable is unverified | §6.3 ships a working template either way; `:sample-web` proves the working pattern before any product repo depends on it |
| `VerticalScrollbar`/`rememberScrollbarAdapter` availability on wasmJs is unverified | §5.2 specifies a Canvas-thumb fallback if the shared-source-set API does not resolve |
| `abiValidation` task/file names may differ from `checkKotlinAbi`/`updateKotlinAbi` at the pinned Kotlin version | §9.2 says to confirm via `./gradlew :editor:tasks --all` and use whatever names are actually registered |
| wasm is single-threaded; heavy tokenization could jank typing | §5.4's cooperative tokenization + `EditorLimits` size guards; measured against the 5,000-line harness (W-10) |
| Compose Multiplatform for Web is Beta, not stable | Tracked explicitly; the plan does not block on a stable release, but `docs/WEB_INTEGRATION.md` should note the Beta status to downstream consumers |
