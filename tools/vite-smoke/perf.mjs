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

// Latency measurements for the 0.6.0 performance gates (docs/AARDINK_0.6_PLAN.md §6, PR 7),
// on the W-10 reference document (5,000 lines of highlighted Kotlin) in headless Chrome.
// Writes dist/perf.json. Without --gate it only reports; with --gate it fails when a measurement
// misses its CI gate.
//
//   pnpm build && node perf.mjs [--gate] [--keys 30] [--lines 5000] [--renderer virtualized]

import { writeFileSync } from 'node:fs';
import { freshPage, largeKotlin, MOUNT, percentile, startHarness } from './harness.mjs';

const args = process.argv.slice(2);
const gate = args.includes('--gate');
const keyCount = Number(args[args.indexOf('--keys') + 1]) || 30;
// The gates are defined on the 5,000-line reference document; other sizes are for comparison.
const lineCount = Number(args[args.indexOf('--lines') + 1]) || 5000;
// The editor's own renderer, while it is opt-in (docs/AARDINK_0.6_PLAN.md, PRs 5-6).
const renderer = args.includes('--renderer') ? args[args.indexOf('--renderer') + 1] : 'textfield';

// Target: what 0.6.0 aims for, recorded in WEB_INTEGRATION.md. Gate: what CI enforces with
// --gate, looser to absorb shared-runner noise. null = informational only.
const LIMITS = {
  mountToIdleMs: { target: 200, gate: 400 },
  keyToIdleP50Ms: { target: 16, gate: null },
  keyToIdleP95Ms: { target: 33, gate: 50 },
  keyToChangeP95Ms: { target: 20, gate: null },
  setValueToIdleMs: { target: 150, gate: null },
  scrollFrameP95Ms: { target: 20, gate: null }, // 60 Hz with jitter, no dropped frames
  droppedKeysSequential: { target: 0, gate: 0, unit: 'keys' },
  droppedKeysAtBurst: { target: 0, gate: 0, unit: 'keys' },
};

const harness = await startHarness();
const results = {};
const text = largeKotlin(lineCount);

try {
  const page = await freshPage(harness, { query: renderer === 'textfield' ? '' : `?aardinkRenderer=${renderer}` });
  results.renderer = renderer;
  results.chrome = await harness.browser.version();
  results.package = await page.evaluate(() => window.__aardinkSmoke.version);
  results.documentKb = Math.round(text.length / 1024);
  results.keys = keyCount;

  // Instrumentation lives in the page: key events are timed from their own timeStamp to the
  // point where frames are smooth again (settled, below), so a key's time includes its layout
  // and paint however many frames later that work lands.
  await page.evaluate(() => {
    window.__perf = { keys: [], longTasks: [] };
    // Resolves with the time from `start` until the editor is idle: the first of 20 short frames
    // in a row (a third of a second at 60 Hz, longer than the 150 ms tokenize debounce). All the
    // work an edit or a mount causes, including frames that land later such as re-highlighting,
    // is inside that time; a fixed double requestAnimationFrame would stop before it.
    window.__perf.settled = (start) => new Promise((resolve) => {
      let last = performance.now();
      let frames = 0;
      let smooth = 0;
      let smoothSince = 0;
      requestAnimationFrame(function tick(now) {
        const interval = now - last;
        last = now;
        frames++;
        if (frames > 1 && interval < 34) {
          if (smooth++ === 0) smoothSince = now;
          if (smooth === 20) return resolve(smoothSince - start);
        } else {
          smooth = 0;
        }
        requestAnimationFrame(tick);
      });
    });
    new PerformanceObserver((list) => list.getEntries().forEach((e) => window.__perf.longTasks.push(e.duration))).observe({ type: 'longtask' });
    window.addEventListener('keydown', (e) => {
      const entry = { ts: e.timeStamp, change: null, paint: null };
      window.__perf.keys.push(entry);
      window.__perf.settled(entry.ts).then((ms) => { entry.paint = ms; });
    }, { capture: true });
  });

  // ── Mount → idle ────────────────────────────────────────────────────────────
  results.mountToIdleMs = await page.evaluate(async (text, mount) => {
    const onChange = () => {
      const pending = window.__perf.keys.find((k) => k.change === null && !k.dropped);
      if (pending) pending.change = performance.now() - pending.ts;
    };
    const start = performance.now();
    window.__perfEditor = await window.__aardink.createEditor(eval(mount), onChange, { value: text, language: 'kotlin' });
    return window.__perf.settled(start);
  }, text, MOUNT);

  // Let the initial tokenization finish (it runs in chunks above 64 KB) before typing.
  await new Promise((r) => setTimeout(r, 3000));

  // ── Keystroke → idle, keystroke → onChange ──────────────────────────────────
  // Keys go one at a time, each waiting for its frame plus a pause, so none overlaps a previous
  // key's work; the burst below is what measures typing faster than the editor keeps up.
  const documentLength = () => page.evaluate(() => window.__perfEditor.getValue().length);
  // Line 6 is a line comment: typing there exercises layout and highlighting without opening
  // the completion dropdown, which would take keys of its own.
  await page.mouse.click(600, 114);
  // Focusing starts the text-input session, and End costs a frame of its own; let both settle.
  await new Promise((r) => setTimeout(r, 1500));
  await page.keyboard.press('End');
  await new Promise((r) => setTimeout(r, 1500));
  await page.evaluate(() => { window.__perf.keys = []; }); // End changes no text
  const lengthBefore = await documentLength();
  const letters = 'abcdefghijklmnopqrstuvwxyz ';
  for (let i = 0; i < keyCount; i++) {
    await page.keyboard.type(letters[i % letters.length]);
    // Every key gets a frame; a key the editor lost never gets a change callback, so give up
    // on the callback after 5 s and count the key as dropped.
    await page.waitForFunction(() => {
      const last = window.__perf.keys.at(-1);
      if (last && last.change === null && performance.now() - last.ts > 5000) last.dropped = true;
      return last && last.paint !== null && (last.change !== null || last.dropped);
    }, { timeout: 60000, polling: 'raf' });
    // Isolate the keys: none should start inside the previous key's work. The burst below is
    // what measures typing faster than the editor keeps up.
    await new Promise((r) => setTimeout(r, 400));
  }
  const allKeys = await page.evaluate(() => window.__perf.keys);
  const keys = allKeys.filter((k) => k.change !== null);
  results.droppedKeysSequential = allKeys.length - keys.length;
  const grewBy = (await documentLength()) - lengthBefore;
  if (grewBy !== keys.length) {
    throw new Error(`${keys.length} keys reported a change but the document grew by ${grewBy}`);
  }
  const paints = keys.map((k) => k.paint).sort((a, b) => a - b);
  const changes = keys.map((k) => k.change).sort((a, b) => a - b);
  results.keyToIdleP50Ms = percentile(paints, 50);
  results.keyToIdleP95Ms = percentile(paints, 95);
  results.keyToChangeP95Ms = percentile(changes, 95);

  // ── Dropped keys: a 30-key burst at 10 keys/s (about 120 words a minute) ─────
  // At 0.5 a key that arrives while the previous key's whole-document layout is still running
  // can be lost. Every key must land.
  const burst = 'the quick brown fox jumps over';
  const lengthBeforeBurst = await documentLength();
  await page.keyboard.type(burst, { delay: 100 });
  let grown = -1;
  for (let settled = 0; settled < 3;) { // wait until the length stops changing for 3 s
    await new Promise((r) => setTimeout(r, 1000));
    const now = (await documentLength()) - lengthBeforeBurst;
    settled = now === grown ? settled + 1 : 0;
    grown = now;
  }
  results.droppedKeysAtBurst = burst.length - grown;

  // ── setValue → idle ─────────────────────────────────────────────────────────
  results.setValueToIdleMs = await page.evaluate(async (text) => {
    const start = performance.now();
    window.__perfEditor.setValue(text);
    return window.__perf.settled(start);
  }, largeKotlin(lineCount + 1));
  await new Promise((r) => setTimeout(r, 3000));

  // ── Scrolling: frame intervals while wheeling through the document ──────────
  await page.evaluate(() => {
    window.__perf.frames = [];
    let last = performance.now();
    window.__perf.recording = true;
    (function tick(now) {
      window.__perf.frames.push(now - last);
      last = now;
      if (window.__perf.recording) requestAnimationFrame(tick);
    })(last);
  });
  await page.mouse.move(400, 300);
  for (let i = 0; i < 90; i++) {
    await page.mouse.wheel({ deltaY: 120 });
    await new Promise((r) => setTimeout(r, 16));
  }
  const frames = await page.evaluate(() => {
    window.__perf.recording = false;
    return window.__perf.frames.slice(1); // the first delta is the loop's start
  });
  results.scrollFrameP95Ms = percentile([...frames].sort((a, b) => a - b), 95);
  results.longestTaskMs = await page.evaluate(() => Math.max(0, ...window.__perf.longTasks));
  await page.close();
} finally {
  await harness.close();
}

const failures = [];
console.log(`@aardarch/aardink-web ${results.package} (${results.renderer}) in ${results.chrome}, ${results.documentKb} KB Kotlin, ${results.keys} keys`);
for (const [name, limit] of Object.entries(LIMITS)) {
  const value = results[name];
  const unit = limit.unit ?? 'ms';
  const verdict = value <= limit.target ? 'meets target' : limit.gate !== null && value > limit.gate ? 'OVER GATE' : 'over target';
  if (limit.gate !== null && value > limit.gate) failures.push(name);
  console.log(`  ${name.padEnd(22)} ${value.toFixed(1).padStart(8)} ${unit.padEnd(4)}  target ${limit.target}${limit.gate !== null ? `, gate ${limit.gate}` : ''}   ${verdict}`);
}
console.log(`  ${'longestTaskMs'.padEnd(22)} ${results.longestTaskMs.toFixed(1).padStart(8)} ms`);
writeFileSync('dist/perf.json', `${JSON.stringify({ ...results, limits: LIMITS, date: new Date().toISOString() }, null, 2)}\n`);
console.log('Wrote dist/perf.json');

if (gate && failures.length) {
  console.error(`Performance gate failed: ${failures.join(', ')}`);
  process.exit(1);
}
