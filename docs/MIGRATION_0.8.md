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

# Migrating from Aardink 0.7 to 0.8

0.8 lets the editor understand the document's structure and lets hosts draw on it: document
symbols, decorations, an overview ruler, occurrence highlights, linked editing, links, colour
decorators, folding markers, keybindings as data, and zoom. [AARDINK_ROADMAP.md](AARDINK_ROADMAP.md)
§6 has the plan and §14 the API ledger.

Most of it is additive. Every data class and composable that gained a parameter changed its
binary signature: recompile against 0.8. Calls by name or by position keep compiling.

## Dependencies

```kotlin
implementation("com.aardarch:aardink:0.8.0")
implementation("com.aardarch:aardink-languages:0.8.0")
```

On the web: `pnpm add @aardarch/aardink-web@^0.8.0`.
