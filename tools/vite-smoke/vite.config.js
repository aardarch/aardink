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
import { defineConfig } from 'vite';

export default defineConfig({
  // The dev-server pre-bundler rewrites the package's modules into .vite/deps, which breaks the
  // relative new URL('./x.wasm', import.meta.url) references the wasm loader relies on.
  optimizeDeps: { exclude: ['@aardarch/aardink-web'] },
  build: {
    // The wasm module and Skiko are large by nature; the default 500 KB warning is noise here.
    chunkSizeWarningLimit: 3500,
    // Top-level await in the Kotlin module needs an ES2022 target.
    target: 'es2022',
  },
});
