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
package com.aardarch.aardink.sample.samples

/**
 * Sample text bank used by the picker. Each string is long enough to require vertical scrolling
 * on a phone-sized viewport and contains several fold-able blocks so reviewers can exercise the
 * editor's gutter triangles.
 *
 * Strings are intentionally inline (not assets/) to keep the sample app Compose-only with no
 * Android resource files — see AGENTS.md.
 */
object SampleAssets {

    fun forId(id: String): String = when (id) {
        "kotlin" -> KOTLIN
        "typescript" -> TYPESCRIPT
        "json" -> JSON
        "xml" -> XML
        "html" -> HTML
        "css" -> CSS
        "markdown" -> MARKDOWN
        "plaintext" -> PLAIN_TEXT
        else -> ""
    }

    private val KOTLIN: String = """
        /*
         * A small Compose screen modelled after a real settings page.
         * Demonstrates classes, sealed hierarchies, when expressions, lambdas,
         * default arguments, string templates, and KDoc.
         */
        package com.example.settings

        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Row
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp

        /** A single user-facing toggle in the settings screen. */
        data class SettingItem(
            val id: String,
            val title: String,
            val description: String,
            val initiallyOn: Boolean = false,
            val category: Category = Category.General,
        )

        sealed interface Category {
            object General : Category
            object Notifications : Category
            object Privacy : Category
            data class Custom(val label: String) : Category
        }

        @Composable
        fun SettingsScreen(
            items: List<SettingItem>,
            onToggle: (String, Boolean) -> Unit,
            modifier: Modifier = Modifier,
        ) {
            Column(modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = "Settings", modifier = Modifier.padding(bottom = 12.dp))
                items.groupBy { it.category }.forEach { (category, group) ->
                    CategoryHeader(category)
                    group.forEach { item ->
                        SettingRow(item = item, onToggle = onToggle)
                    }
                }
            }
        }

        @Composable
        private fun CategoryHeader(category: Category) {
            val label = when (category) {
                Category.General -> "General"
                Category.Notifications -> "Notifications"
                Category.Privacy -> "Privacy"
                is Category.Custom -> category.label
            }
            Text(label, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }

        @Composable
        private fun SettingRow(
            item: SettingItem,
            onToggle: (String, Boolean) -> Unit,
        ) {
            var on by remember(item.id) { mutableStateOf(item.initiallyOn) }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text(item.title)
                    Text(item.description)
                }
                Toggle(checked = on) { newValue ->
                    on = newValue
                    onToggle(item.id, newValue)
                }
            }
        }

        @Composable
        private fun Toggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
            // Placeholder — real impl would use a Switch composable.
            Text(
                text = if (checked) "ON" else "OFF",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        /** Pure data builder used by previews and tests. */
        object SampleSettings {
            fun build(): List<SettingItem> = listOf(
                SettingItem("dark_mode", "Dark mode", "Use a darker palette in low-light", true),
                SettingItem("haptics", "Haptic feedback", "Vibrate on key actions"),
                SettingItem(
                    id = "push_news",
                    title = "Newsletter",
                    description = "Receive a weekly digest",
                    category = Category.Notifications,
                ),
                SettingItem(
                    id = "analytics",
                    title = "Anonymous analytics",
                    description = "Share usage stats to help improve the app",
                    category = Category.Privacy,
                ),
                SettingItem(
                    id = "experimental",
                    title = "Experimental flags",
                    description = "Opt-in to in-progress features",
                    category = Category.Custom(label = "Experiments"),
                ),
            )
        }

        fun main() {
            val items = SampleSettings.build()
            val grouped = items.groupBy { it.category }
            for ((category, group) in grouped) {
                println("=== ${'$'}category ===")
                for (item in group) {
                    val state = if (item.initiallyOn) "[on] " else "[off]"
                    println("  ${'$'}state ${'$'}{item.title} — ${'$'}{item.description}")
                }
            }
        }
    """.trimIndent()

    private val TYPESCRIPT: String = """
        // Mini task tracker showing TypeScript classes, generics, async/await,
        // discriminated unions, and template literals. Long enough to scroll;
        // structured with several braces to test folding.

        type Status = "todo" | "in_progress" | "done" | "blocked";

        interface Task {
            readonly id: string;
            title: string;
            status: Status;
            tags: string[];
            createdAt: number;
            assignee?: string;
        }

        interface Repository<T> {
            findById(id: string): Promise<T | null>;
            findAll(): Promise<T[]>;
            save(value: T): Promise<void>;
            remove(id: string): Promise<boolean>;
        }

        class InMemoryRepository<T extends { id: string }> implements Repository<T> {
            private readonly store = new Map<string, T>();

            async findById(id: string): Promise<T | null> {
                return this.store.get(id) ?? null;
            }

            async findAll(): Promise<T[]> {
                return Array.from(this.store.values());
            }

            async save(value: T): Promise<void> {
                this.store.set(value.id, value);
            }

            async remove(id: string): Promise<boolean> {
                return this.store.delete(id);
            }
        }

        type StatusEvent =
            | { kind: "started"; taskId: string }
            | { kind: "completed"; taskId: string; durationMs: number }
            | { kind: "blocked"; taskId: string; reason: string };

        function describe(event: StatusEvent): string {
            switch (event.kind) {
                case "started":
                    return `task ${'$'}{event.taskId} started`;
                case "completed":
                    return `task ${'$'}{event.taskId} done in ${'$'}{event.durationMs}ms`;
                case "blocked":
                    return `task ${'$'}{event.taskId} blocked: ${'$'}{event.reason}`;
            }
        }

        async function runDemo(): Promise<void> {
            const repo = new InMemoryRepository<Task>();
            const seed: Task[] = [
                { id: "t1", title: "Wire picker UI", status: "in_progress", tags: ["ui"], createdAt: Date.now() },
                { id: "t2", title: "Tokenize markdown", status: "todo", tags: ["lang"], createdAt: Date.now() },
                { id: "t3", title: "Investigate fold off-by-one", status: "blocked", tags: ["editor"], createdAt: Date.now() },
            ];
            for (const task of seed) {
                await repo.save(task);
            }
            const all = await repo.findAll();
            const summary = all
                .filter(t => t.status !== "done")
                .map(t => `* ${'$'}{t.title} [${'$'}{t.status}]`)
                .join("\n");
            console.log(summary);

            const events: StatusEvent[] = [
                { kind: "started", taskId: "t1" },
                { kind: "completed", taskId: "t1", durationMs: 4200 },
                { kind: "blocked", taskId: "t3", reason: "needs design input" },
            ];
            for (const e of events) {
                console.log(describe(e));
            }
        }

        runDemo().catch(err => {
            console.error("demo failed", err);
            process.exitCode = 1;
        });
    """.trimIndent()

    private val JSON: String = """
        {
            "name": "aardink-sample",
            "version": "0.1.0",
            "private": true,
            "description": "A long JSON file showcasing nested objects, arrays, numbers, booleans, and null values for the AardInk sample app.",
            "engines": {
                "node": ">=20.0.0",
                "npm": ">=10.0.0"
            },
            "scripts": {
                "build": "tsc -p .",
                "test": "vitest run",
                "lint": "eslint --ext .ts,.tsx src",
                "format": "prettier --write src",
                "release": "node scripts/release.mjs"
            },
            "dependencies": {
                "react": "18.2.0",
                "react-dom": "18.2.0",
                "zustand": "4.5.0",
                "@tanstack/react-query": "5.0.0"
            },
            "devDependencies": {
                "typescript": "5.4.0",
                "vitest": "1.6.0",
                "eslint": "9.0.0",
                "prettier": "3.2.5",
                "@types/node": "20.10.0"
            },
            "compilerOptions": {
                "target": "ES2022",
                "module": "ESNext",
                "moduleResolution": "Bundler",
                "strict": true,
                "noUncheckedIndexedAccess": true,
                "esModuleInterop": true,
                "skipLibCheck": true,
                "resolveJsonModule": true,
                "lib": ["ES2022", "DOM"]
            },
            "themes": [
                {
                    "id": "midnight",
                    "displayName": "Midnight Ocean",
                    "background": "#0b1220",
                    "tokens": {
                        "keyword": "#7aa2f7",
                        "string": "#9ece6a",
                        "comment": "#565f89",
                        "number": "#ff9e64",
                        "type": "#bb9af7"
                    }
                },
                {
                    "id": "vscode-dark",
                    "displayName": "VS Code Dark",
                    "background": "#1e1e1e",
                    "tokens": {
                        "keyword": "#569cd6",
                        "string": "#ce9178",
                        "comment": "#6a9955",
                        "number": "#b5cea8",
                        "type": "#4ec9b0"
                    }
                }
            ],
            "feature_flags": {
                "completion": true,
                "diagnostics": true,
                "find_replace": true,
                "go_to_line": true,
                "experimental": {
                    "ai_assist": false,
                    "vim_bindings": false
                }
            },
            "stats": {
                "downloads": 12450,
                "stars": 327,
                "open_issues": 14,
                "contributors": 4,
                "license": "Apache-2.0"
            },
            "deprecated_keys": null
        }
    """.trimIndent()

    private val XML: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <!--
            Sample AndroidManifest-style XML. Demonstrates declarations, comments,
            namespaces, multiple nested elements, attributes and self-closing tags.
        -->
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  xmlns:tools="http://schemas.android.com/tools"
                  package="com.example.aardink.demo">

            <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37" />

            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
            <uses-permission
                android:name="android.permission.READ_EXTERNAL_STORAGE"
                android:maxSdkVersion="32" />

            <queries>
                <package android:name="com.android.chrome" />
                <intent>
                    <action android:name="android.intent.action.VIEW" />
                    <data android:scheme="https" />
                </intent>
            </queries>

            <application
                android:name=".DemoApp"
                android:allowBackup="true"
                android:icon="@mipmap/ic_launcher"
                android:label="@string/app_name"
                android:supportsRtl="true"
                android:theme="@style/Theme.Demo"
                tools:targetApi="34">

                <activity
                    android:name=".MainActivity"
                    android:exported="true"
                    android:launchMode="singleTask"
                    android:theme="@style/Theme.Demo.NoActionBar">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>

                    <intent-filter android:autoVerify="true">
                        <action android:name="android.intent.action.VIEW" />
                        <category android:name="android.intent.category.DEFAULT" />
                        <category android:name="android.intent.category.BROWSABLE" />
                        <data android:scheme="https" android:host="aardarch.com" />
                    </intent-filter>

                    <meta-data
                        android:name="android.app.shortcuts"
                        android:resource="@xml/shortcuts" />
                </activity>

                <activity
                    android:name=".DetailActivity"
                    android:exported="false"
                    android:parentActivityName=".MainActivity">
                    <meta-data
                        android:name="android.support.PARENT_ACTIVITY"
                        android:value=".MainActivity" />
                </activity>

                <service
                    android:name=".SyncService"
                    android:exported="false">
                    <intent-filter>
                        <action android:name="com.example.demo.action.SYNC" />
                    </intent-filter>
                </service>

                <provider
                    android:name="androidx.core.content.FileProvider"
                    android:authorities="${'$'}{applicationId}.fileprovider"
                    android:exported="false"
                    android:grantUriPermissions="true">
                    <meta-data
                        android:name="android.support.FILE_PROVIDER_PATHS"
                        android:resource="@xml/file_paths" />
                </provider>
            </application>
        </manifest>
    """.trimIndent()

    private val HTML: String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <meta name="description" content="A long HTML sample for the AardInk editor showcase." />
            <title>AardInk &mdash; HTML sample</title>
            <link rel="stylesheet" href="/styles/main.css" />
            <link rel="icon" href="/favicon.ico" type="image/x-icon" />
            <script defer src="/scripts/app.js"></script>
        </head>
        <body class="layout layout--docs">
            <header class="site-header">
                <a class="brand" href="/">
                    <img src="/img/logo.svg" alt="" width="32" height="32" />
                    <span>AardInk</span>
                </a>
                <nav class="site-nav" aria-label="Primary">
                    <ul>
                        <li><a href="/docs">Docs</a></li>
                        <li><a href="/samples">Samples</a></li>
                        <li><a href="/changelog">Changelog</a></li>
                        <li><a href="https://github.com/aardarch/aardink">GitHub</a></li>
                    </ul>
                </nav>
            </header>

            <main id="main">
                <article>
                    <header>
                        <h1>HTML showcase</h1>
                        <p class="lead">
                            This file exists to give the editor enough markup to fold, scroll,
                            and highlight. The content is otherwise unremarkable.
                        </p>
                    </header>

                    <section id="features">
                        <h2>Features</h2>
                        <ul>
                            <li>Incremental tokenisation</li>
                            <li>Code folding</li>
                            <li>Find &amp; replace</li>
                            <li>Go-to-line</li>
                            <li>Pluggable language registry</li>
                        </ul>
                    </section>

                    <section id="quickstart">
                        <h2>Quick start</h2>
                        <p>Add the dependency and wire up a registry:</p>
                        <pre><code class="language-kotlin">dependencies {
            implementation("com.aardarch:aardink:0.1.0")
            implementation("com.aardarch:aardink-languages:0.1.0")
        }</code></pre>
                    </section>

                    <section id="comparison">
                        <h2>Compared to plain text</h2>
                        <table>
                            <thead>
                                <tr><th>Feature</th><th>Plain</th><th>AardInk</th></tr>
                            </thead>
                            <tbody>
                                <tr><td>Highlighting</td><td>&minus;</td><td>&#10003;</td></tr>
                                <tr><td>Folding</td><td>&minus;</td><td>&#10003;</td></tr>
                                <tr><td>Gutter</td><td>&minus;</td><td>&#10003;</td></tr>
                            </tbody>
                        </table>
                    </section>
                </article>

                <aside class="sidebar">
                    <h3>On this page</h3>
                    <ol>
                        <li><a href="#features">Features</a></li>
                        <li><a href="#quickstart">Quick start</a></li>
                        <li><a href="#comparison">Comparison</a></li>
                    </ol>
                </aside>
            </main>

            <footer class="site-footer">
                <p>&copy; 2026 Aardarch. Apache 2.0.</p>
            </footer>
        </body>
        </html>
    """.trimIndent()

    private val CSS: String = """
        /* AardInk sample stylesheet — reset + theme + components. */

        :root {
            --bg: #0b1220;
            --surface: #111a2c;
            --surface-2: #16223a;
            --text: #e6edf3;
            --text-muted: #94a3b8;
            --accent: #7aa2f7;
            --accent-strong: #4f8cff;
            --danger: #f87171;
            --warning: #fbbf24;
            --radius: 8px;
            --gutter: 16px;
        }

        *,
        *::before,
        *::after {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        html {
            font-size: 16px;
            -webkit-text-size-adjust: 100%;
        }

        body {
            background-color: var(--bg);
            color: var(--text);
            font-family: "Inter", system-ui, -apple-system, "Segoe UI", sans-serif;
            line-height: 1.55;
            min-height: 100vh;
        }

        a {
            color: var(--accent);
            text-decoration: none;
        }

        a:hover,
        a:focus-visible {
            color: var(--accent-strong);
            text-decoration: underline;
        }

        .site-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: var(--gutter);
            background-color: var(--surface);
            position: sticky;
            top: 0;
            z-index: 10;
        }

        .site-nav ul {
            display: flex;
            gap: 12px;
            list-style: none;
        }

        .brand {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            font-weight: 600;
        }

        .layout--docs {
            display: grid;
            grid-template-columns: 1fr;
            gap: 24px;
            max-width: 920px;
            margin: 32px auto;
            padding: 0 var(--gutter);
        }

        @media (min-width: 880px) {
            .layout--docs {
                grid-template-columns: minmax(0, 3fr) minmax(0, 1fr);
            }
        }

        .lead {
            color: var(--text-muted);
            font-size: 1.1rem;
        }

        pre,
        code {
            font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
        }

        pre {
            background-color: var(--surface-2);
            border-radius: var(--radius);
            padding: 12px 14px;
            overflow-x: auto;
        }

        table {
            border-collapse: collapse;
            width: 100%;
        }

        th,
        td {
            padding: 8px 10px;
            text-align: left;
            border-bottom: 1px solid rgba(255, 255, 255, 0.08);
        }

        @keyframes pulse {
            from {
                opacity: 0.4;
            }
            to {
                opacity: 1;
            }
        }

        .badge--new {
            display: inline-block;
            padding: 2px 6px;
            border-radius: 4px;
            background-color: var(--accent);
            color: var(--bg);
            font-size: 0.75rem;
            animation: pulse 1.4s ease-in-out infinite alternate;
        }
    """.trimIndent()

    private val MARKDOWN: String = """
        # AardInk user guide

        AardInk is a Compose-native code editor library for Android. This document is here
        to give the editor's Markdown highlighting and folding something to chew on.

        ## Overview

        - Compose-only — no XML layouts in the editor module.
        - Pluggable language registry — ships built-ins, accepts overrides.
        - Incremental tokenisation, fold ranges, find & replace, go-to-line.

        ## Installation

        Add the dependencies:

        ```kotlin
        dependencies {
            implementation("com.aardarch:aardink:0.1.0")
            implementation("com.aardarch:aardink-languages:0.1.0")
        }
        ```

        ## Quick start

        Wire a registry-backed editor in three steps.

        ### 1. Pick a language

        ```kotlin
        val registry = LanguageRegistry.withBuiltIns()
        val def = registry.byExtension("kt")!!
        ```

        ### 2. Build editor state

        ```kotlin
        val state = rememberCodeEditorState(
            initialText = code,
            tokenizer = def.tokenizer,
        )
        ```

        ### 3. Render the layout

        ```kotlin
        CodeEditorLayout(
            state = state,
            foldingProvider = def.foldingProvider,
        )
        ```

        ## Supported languages

        | Language    | Extensions             | Folding |
        | ----------- | ---------------------- | ------- |
        | Kotlin      | `kt`, `kts`            | Braces  |
        | TypeScript  | `ts`, `tsx`, `js`      | Braces  |
        | JSON        | `json`, `jsonc`        | Braces  |
        | XML         | `xml`, `xsd`, `svg`    | Tags    |
        | HTML        | `html`, `htm`          | Tags    |
        | CSS         | `css`                  | Braces  |
        | Markdown    | `md`, `markdown`       | Headings |
        | Plain text  | `txt`                  | None    |

        ## Customising

        ### Override a built-in

        ```kotlin
        val registry = LanguageRegistry.withBuiltIns().apply {
            override("kotlin") { it.copy(tokenizer = MyKotlinTokenizer) }
        }
        ```

        ### Register a new language

        ```kotlin
        registry.register(
            LanguageDefinition(
                id = "log",
                displayName = "Log",
                fileExtensions = listOf("log"),
                tokenizer = PlainTextTokenizer,
            ),
        )
        ```

        ## Theming

        The editor exposes `EditorTheme`, settable via the `LocalEditorTheme` composition local.
        Pre-built themes live in `EditorThemes`:

        - `EditorThemes.VsCodeDark`
        - `EditorThemes.MidnightOcean`
        - `EditorThemes.Solarized`

        > **Tip:** Theme changes flow through composition — you can drive them from a
        > settings screen and see the editor update without restart.

        ## Performance notes

        For large documents (>100kB) the regex-based tokenizers retokenize the full document on
        every change. v2 will introduce true incremental lexers; until then, prefer keeping
        single editor instances under a few thousand lines.

        ## Reporting issues

        Use the GitHub tracker. Include:

        1. A minimal reproduction.
        2. The language id and a code snippet.
        3. Logs from `adb logcat -s AardInk`.

        ---

        _Happy editing._
    """.trimIndent()

    private val PLAIN_TEXT: String = """
        AardInk — a quick tour
        ===========================

        AardInk started as the editor inside Aardflex, a tool for designing
        Android wallpapers from XML and Compose. Once the editor outgrew that
        single use case it was extracted into its own library.

        The library has three goals:

          1. Be Compose-native. No XML layouts, no Android views, no
             surprise legacy.
          2. Stay small. The only runtime dependency is Jetpack Compose.
          3. Stay extensible. Tokenisation, folding, completions, and themes
             are all replaceable.

        Why a sample app?
        -----------------

        It is hard to evaluate a code editor from a README. The sample is the
        place where reviewers can poke at the actual experience: load a long
        file, scroll, fold a section, search, jump to a line. Plain prose
        cannot answer "is the gutter readable?" or "do the line numbers
        align?". A live editor running on a real device can.

        Why these eight languages?
        --------------------------

        They cover the bulk of files developers open day-to-day on a phone:

          - Kotlin for the Android side
          - TypeScript and JavaScript for everything in the browser
          - JSON for configuration
          - XML for resources, manifests, and SVG
          - HTML for documentation and the web
          - CSS for theming
          - Markdown for notes and READMEs
          - Plain text for everything else

        If your stack needs Python, Go, or Rust, the registry's override
        and register hooks are the way to add them.

        A note on regex tokenisers
        --------------------------

        The bundled tokenisers are regex-driven and retokenise the whole
        document on every change. For sample-sized files this is fine and
        the code is short enough to read in one sitting. For very large
        files an incremental lexer is the right answer; pull requests are
        welcome.

        Happy reading. The editor itself is one tap away — use the
        back arrow at the top to get back here.
    """.trimIndent()
}
