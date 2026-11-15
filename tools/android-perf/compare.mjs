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

// The Android gate (docs/AARDINK_ROADMAP.md §16, "Android performance"): :sample-benchmark's
// results for a change against main's, measured in the same CI job on the same emulator, since an
// emulator's timings are only comparable with its own. Fails when a measurement of the change is
// more than its tolerance over main's.
//
//   node compare.mjs <main results dir> <change results dir>
//
// Each directory is searched for Macrobenchmark's *-benchmarkData.json. A missing main directory
// (main before :sample-benchmark existed) records the change's numbers and passes.

import { appendFileSync, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// What is compared, and how much worse the change may be: a share of main's value plus a floor
// for the emulator's noise.
const GATES = [
  { benchmark: 'startup', metric: 'timeToInitialDisplayMs', stat: 'median', share: 0.15, floorMs: 30 },
  { benchmark: 'typeAndScroll', metric: 'frameDurationCpuMs', stat: 'P90', share: 0.2, floorMs: 2 },
];

const [mainDir, changeDir] = process.argv.slice(2);
if (!changeDir) throw new Error('usage: node compare.mjs <main results dir> <change results dir>');

function resultFiles(dir) {
  if (!dir || !existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return resultFiles(path);
    return name.endsWith('-benchmarkData.json') ? [path] : [];
  });
}

/** `{ benchmarkName: { metric: { median, P90, ... } } }` from every results file in [dir]. */
function read(dir) {
  const out = {};
  for (const file of resultFiles(dir)) {
    for (const benchmark of JSON.parse(readFileSync(file, 'utf8')).benchmarks ?? []) {
      const metrics = { ...(benchmark.metrics ?? {}), ...(benchmark.sampledMetrics ?? {}) };
      out[benchmark.name] = metrics;
    }
  }
  return out;
}

const value = (results, gate) => results[gate.benchmark]?.[gate.metric]?.[gate.stat];

const change = read(changeDir);
const main = read(mainDir);
const hasMain = Object.keys(main).length > 0;
if (Object.keys(change).length === 0) throw new Error(`No benchmark results in ${changeDir}`);

const rows = [];
const failures = [];
for (const gate of GATES) {
  const now = value(change, gate);
  const before = hasMain ? value(main, gate) : undefined;
  if (now === undefined) {
    failures.push(`${gate.benchmark} ${gate.metric} ${gate.stat} is missing from the change's results`);
    continue;
  }
  const ceiling = before === undefined ? undefined : before * (1 + gate.share) + gate.floorMs;
  const over = ceiling !== undefined && now > ceiling;
  if (over) failures.push(`${gate.benchmark} ${gate.metric} ${gate.stat}: ${now.toFixed(1)} ms against main's ${before.toFixed(1)} ms`);
  rows.push({ ...gate, main: before, change: now, ceiling, over });
}

const fmt = (ms) => (ms === undefined ? '-' : `${ms.toFixed(1)} ms`);
const table = [
  '| Benchmark | Metric | main | change | ceiling | |',
  '| --- | --- | --- | --- | --- | --- |',
  ...rows.map((r) => `| ${r.benchmark} | ${r.metric} ${r.stat} | ${fmt(r.main)} | ${fmt(r.change)} | ${fmt(r.ceiling)} | ${r.over ? 'OVER' : 'ok'} |`),
].join('\n');
console.log(table);
if (!hasMain) console.log('\nmain has no results (no :sample-benchmark yet): recorded, not gated.');
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Android performance\n\n${table}\n`);
writeFileSync('android-perf.json', `${JSON.stringify({ main, change, gates: rows, date: new Date().toISOString() }, null, 2)}\n`);

if (failures.length) {
  console.error(`Android performance gate failed:\n  ${failures.join('\n  ')}`);
  process.exit(1);
}
