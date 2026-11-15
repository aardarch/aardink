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

// The wasm bundle's size ceiling (docs/AARDINK_ROADMAP.md §16, "The wasm bundle"): what the npm
// package loads, raw, gzipped and with Brotli, against the sizes recorded in bundle-size.json.
// Fails when aardink-web.wasm or skiko.wasm is more than 2% over its recorded gzipped size; the
// small .mjs files that load them are reported, as they grow a little with each export. Writes
// dist/bundle-size.json with what it measured.
//
//   ./gradlew :sample-web:npmPackage && node bundle-size.mjs [--record]
//
// --record writes the measured sizes to bundle-size.json, for a release or a change that is
// meant to grow the bundle; commit it with the change.

import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { constants, brotliCompressSync, gzipSync } from 'node:zlib';

const KOTLIN_DIR = new URL('../../sample-web/build/npm/kotlin/', import.meta.url);
const RECORDED = new URL('./bundle-size.json', import.meta.url);
const ALLOWANCE = 0.02;

const record = process.argv.includes('--record');

const files = readdirSync(KOTLIN_DIR).filter((name) => name.endsWith('.wasm') || name.endsWith('.mjs')).sort();
if (!files.includes('aardink-web.wasm') || !files.includes('skiko.wasm')) {
  throw new Error(`No npm package to measure in ${KOTLIN_DIR.pathname}: run ./gradlew :sample-web:npmPackage first`);
}

const measured = {};
for (const name of files) {
  const bytes = readFileSync(new URL(name, KOTLIN_DIR));
  measured[name] = {
    raw: bytes.length,
    gzip: gzipSync(bytes, { level: 9 }).length,
    brotli: brotliCompressSync(bytes, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length,
  };
}
const total = (key) => Object.values(measured).reduce((sum, size) => sum + size[key], 0);
const totals = { raw: total('raw'), gzip: total('gzip'), brotli: total('brotli') };

const kb = (bytes) => `${(bytes / 1024).toFixed(0).padStart(7)} KB`;
console.log(`${'file'.padEnd(32)} ${'raw'.padStart(10)} ${'gzip'.padStart(10)} ${'brotli'.padStart(10)}`);
for (const [name, size] of Object.entries(measured)) {
  console.log(`${name.padEnd(32)} ${kb(size.raw)} ${kb(size.gzip)} ${kb(size.brotli)}`);
}
console.log(`${'total'.padEnd(32)} ${kb(totals.raw)} ${kb(totals.gzip)} ${kb(totals.brotli)}`);

mkdirSync(new URL('./dist/', import.meta.url), { recursive: true });
writeFileSync(new URL('./dist/bundle-size.json', import.meta.url), `${JSON.stringify({ files: measured, totals, date: new Date().toISOString() }, null, 2)}\n`);

if (record) {
  writeFileSync(RECORDED, `${JSON.stringify({ files: measured, totals }, null, 2)}\n`);
  console.log('Recorded the sizes in bundle-size.json');
  process.exit(0);
}

const recorded = JSON.parse(readFileSync(RECORDED, 'utf8')).files;
const failures = [];
for (const [name, size] of Object.entries(measured)) {
  const before = recorded[name];
  if (!before) {
    if (name.endsWith('.wasm')) failures.push(`${name} is new: run node bundle-size.mjs --record`);
    else console.log(`  ${name} is new`);
    continue;
  }
  const ceiling = Math.floor(before.gzip * (1 + ALLOWANCE));
  const change = ((size.gzip - before.gzip) / before.gzip) * 100;
  const gated = name.endsWith('.wasm');
  console.log(`  ${name.padEnd(30)} gzip ${size.gzip} against ${before.gzip} (${change >= 0 ? '+' : ''}${change.toFixed(2)}%)${gated ? `, ceiling ${ceiling}` : ''}`);
  if (gated && size.gzip > ceiling) failures.push(`${name} is ${change.toFixed(2)}% over its recorded gzipped size`);
}
for (const name of Object.keys(recorded)) {
  if (!measured[name]) console.log(`  ${name} is recorded but no longer shipped: record again to drop it`);
}

if (failures.length) {
  console.error(`Bundle size ceiling failed:\n  ${failures.join('\n  ')}`);
  process.exit(1);
}
console.log('Bundle size within the recorded sizes plus 2%');
