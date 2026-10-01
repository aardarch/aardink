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

// The npm package's loader (index.js) in Node, against a stand-in for the Kotlin module, so a
// load can be made to fail and then succeed:
//
//   node --test loader.test.mjs        # after pnpm install, which links the built package

import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

const packageIndex = fileURLToPath(import.meta.resolve('@aardarch/aardink-web'));
const dir = mkdtempSync(join(tmpdir(), 'aardink-loader-'));
after(() => rmSync(dir, { recursive: true, force: true }));

/** A copy of the package's index.js whose Kotlin module is missing until `provideKotlin`. */
copyFileSync(packageIndex, join(dir, 'index.js'));
writeFileSync(join(dir, 'package.json'), '{ "type": "module" }');
const provideKotlin = () => {
  mkdirSync(join(dir, 'kotlin'), { recursive: true });
  writeFileSync(
    join(dir, 'kotlin', 'aardink-web.mjs'),
    'export const aardinkSetBundledFontUrl = () => {};\nexport const aardinkTokenize = () => "[[]]";\n',
  );
};

test('a failed load is not kept: the next call loads again', async () => {
  const { tokenize } = await import(pathToFileURL(join(dir, 'index.js')).href);

  await assert.rejects(tokenize('x', 'plaintext'));
  provideKotlin();

  assert.deepEqual(await tokenize('x', 'plaintext'), [[]]);
});
