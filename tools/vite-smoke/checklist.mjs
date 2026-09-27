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

// The automatable part of the web verification checklist (docs/WEB_INTEGRATION.md, W-1..W-10),
// run against the production build of this Vite app in headless Chrome. Prints measurements;
// it does not fail on thresholds, because the numbers are for a human to record and compare.
// Latency gates live in perf.mjs.
//
//   pnpm build && node checklist.mjs [W-1 W-2 ...]   # no ids = every check

import { freshPage, largeKotlin, MOUNT, startHarness } from './harness.mjs';

const only = process.argv.slice(2).filter((a) => /^W-\d+$/.test(a));
const wanted = (id) => only.length === 0 || only.includes(id);

const harness = await startHarness();
const open = (options = {}) => freshPage(harness, options);
const record = (id, text) => console.log(`${id}: ${text}`);

try {
  // ── W-1: mount + dispose 50×, heap after forced GC ─────────────────────────
  if (wanted('W-1')) {
    const page = await open();
    const cdp = await page.createCDPSession();
    await cdp.send('Performance.enable');
    const heapMb = async () => {
      await cdp.send('HeapProfiler.collectGarbage');
      const { metrics } = await cdp.send('Performance.getMetrics');
      return metrics.find((m) => m.name === 'JSHeapUsedSize').value / 2 ** 20;
    };
    const cycle = (n) => page.evaluate(async (n) => {
      for (let i = 0; i < n; i++) {
        const box = document.createElement('div');
        box.style.cssText = 'width:400px;height:300px';
        document.body.append(box);
        const editor = await window.__aardink.createEditor(box, undefined, { value: 'fun main() {}\n', language: 'kotlin' });
        await new Promise(requestAnimationFrame);
        editor.dispose();
        box.remove();
      }
      return document.querySelectorAll('canvas').length;
    }, n);
    await cycle(5); // warm-up: first mounts allocate one-off caches
    const before = await heapMb();
    await cycle(50);
    const after50 = await heapMb();
    const canvases = await cycle(50);
    const after100 = await heapMb();
    record('W-1', `JS heap after forced GC: ${before.toFixed(1)} MB → ${after50.toFixed(1)} MB (+50 mount/dispose) → ${after100.toFixed(1)} MB (+50 more); ${canvases} canvas element(s) left in the page`);
    await page.close();
  }

  // ── W-2 (automated part): IME composition through the DevTools protocol ─────
  // Input.imeSetComposition / Input.insertText are what Chrome's own IME bridge calls, so this
  // covers composition start, update and commit, not the platform IMEs themselves (Pinyin,
  // Japanese, Gboard, dead keys), which stay on the manual checklist.
  if (wanted('W-2')) {
    const page = await open();
    await page.evaluate(async (mount) => {
      window.__smokeEditor = await window.__aardink.createEditor(eval(mount), undefined, { value: '' });
    }, MOUNT);
    await new Promise((r) => setTimeout(r, 500));
    await page.mouse.click(200, 20);
    const cdp = await page.createCDPSession();
    const compose = async (steps, commit) => {
      for (const text of steps) {
        await cdp.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length });
        await new Promise((r) => setTimeout(r, 50));
      }
      await cdp.send('Input.insertText', { text: commit });
      await new Promise((r) => setTimeout(r, 300));
    };
    const value = () => page.evaluate(() => window.__smokeEditor.getValue());
    const cases = [];
    await compose(['n', 'に', 'にh', 'にほ', 'にほn', 'にほん'], '日本');
    cases.push(['Japanese kana → kanji', await value(), '日本']);
    await compose(['ご'], '語');
    cases.push(['second composition appends', await value(), '日本語']);
    await page.keyboard.type(' ');
    await compose(['´'], 'é');
    cases.push(['dead key (´ then e)', await value(), '日本語 é']);
    await compose(['zhong', 'zhongwen'], '中文');
    cases.push(['Pinyin-style letters → hanzi', await value(), '日本語 é中文']);
    const failed = cases.filter(([, actual, expected]) => actual !== expected);
    record('W-2', `${cases.length - failed.length}/${cases.length} simulated compositions committed exactly` +
      (failed.length ? `; wrong: ${failed.map(([name, actual, expected]) => `${name} gave ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`).join('; ')}` : ''));
    await page.close();
  }

  // ── W-3: 100 KB paste latency and CRLF ─────────────────────────────────────
  if (wanted('W-3')) {
    const page = await open();
    await harness.browser.defaultBrowserContext().overridePermissions(harness.url, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write']);
    const pasted = largeKotlin(3000).slice(0, 100 * 1024).replace(/\n/g, '\r\n');
    await page.evaluate(async (text, mount) => {
      window.__changes = [];
      window.__smokeEditor = await window.__aardink.createEditor(eval(mount), () => window.__changes.push(performance.now()), { value: '' });
      await navigator.clipboard.writeText(text);
    }, pasted, MOUNT);
    await new Promise((r) => setTimeout(r, 500));
    await page.mouse.click(200, 20);
    const start = await page.evaluate(() => performance.now());
    // A synthetic Ctrl+V does not run the browser's paste command in headless Chrome unless
    // asked to explicitly; this is the same paste a user's shortcut triggers.
    await page.keyboard.down('Control');
    await page.keyboard.press('KeyV', { commands: ['paste'] });
    await page.keyboard.up('Control');
    await page.waitForFunction(() => window.__changes.length > 0, { timeout: 30000 });
    const { elapsed, hasCr, length } = await page.evaluate((start) => {
      const value = window.__smokeEditor.getValue();
      return { elapsed: window.__changes[0] - start, hasCr: value.includes('\r'), length: value.length };
    }, start);
    record('W-3', `pasted ${(pasted.length / 1024).toFixed(0)} KB with CRLF; change reported after ${elapsed.toFixed(0)} ms; document length ${length}; carriage returns ${hasCr ? 'KEPT in the document' : 'normalised to LF'}`);
    await page.close();
  }

  // ── W-7: wheel over the editor must not scroll the page ───────────────────
  if (wanted('W-7')) {
    const page = await open();
    await page.evaluate(async (text) => {
      document.getElementById('editor').style.display = 'none';
      document.body.style.height = '3000px';
      const editor = document.createElement('div');
      editor.style.cssText = 'width:100%;height:400px';
      document.body.prepend(editor);
      window.__smokeEditor = await window.__aardink.createEditor(editor, undefined, { value: text, language: 'kotlin' });
    }, largeKotlin(500));
    await page.mouse.move(300, 200);
    await page.mouse.wheel({ deltaY: 600 });
    await new Promise((r) => setTimeout(r, 500));
    const scrollY = await page.evaluate(() => window.scrollY);
    record('W-7', `after a 600 px wheel over the editor the page scrollY is ${scrollY} (0 = contained)`);
    await page.close();
  }

  // ── W-8: devicePixelRatio 2 ──────────────────────────────────────────────
  if (wanted('W-8')) {
    const page = await open({ dpr: 2 });
    await new Promise((r) => setTimeout(r, 1000));
    await page.screenshot({ path: 'dist/checklist-dpr2.png' });
    const canvas = await page.evaluate(() => {
      // Compose renders into a canvas inside its viewport's shadow root.
      const host = [...document.querySelectorAll('*')].find((e) => e.shadowRoot?.querySelector('canvas'));
      const c = host?.shadowRoot.querySelector('canvas');
      return c ? `${c.width}×${c.height} backing for ${c.clientWidth}×${c.clientHeight} CSS px` : 'no canvas';
    });
    record('W-8', `at DPR 2 the canvas is ${canvas}; screenshot at dist/checklist-dpr2.png`);
    await page.close();
  }

  // ── W-10: typing latency in a 5,000-line file; longest task while tokenizing ─
  if (wanted('W-10')) {
    const page = await open();
    const text = largeKotlin(5000);
    const longest = await page.evaluate(async (text, mount) => {
      const longTasks = [];
      new PerformanceObserver((list) => list.getEntries().forEach((e) => longTasks.push(e.duration))).observe({ type: 'longtask' });
      window.__changes = [];
      window.__smokeEditor = await window.__aardink.createEditor(eval(mount), () => window.__changes.push(performance.now()), { value: text, language: 'kotlin' });
      await new Promise((r) => setTimeout(r, 3000)); // initial tokenization, chunked above 64 KB
      return { max: Math.max(0, ...longTasks), count: longTasks.length };
    }, text, MOUNT);
    await page.mouse.click(300, 30);
    // Few keys, generous timeout: at 0.5 each keystroke re-lays-out the whole document, so at
    // this size one key takes on the order of a second. perf.mjs has the full latency profile.
    const typed = 'val z ';
    const timings = [];
    for (const ch of typed) {
      const before = await page.evaluate(() => { window.__changes = []; return performance.now(); });
      await page.keyboard.type(ch);
      await page.waitForFunction(() => window.__changes.length > 0, { timeout: 30000 });
      timings.push(await page.evaluate((b) => window.__changes[0] - b, before));
    }
    timings.sort((a, b) => a - b);
    const median = timings[Math.floor(timings.length / 2)];
    const worst = timings[timings.length - 1];
    record('W-10', `${(text.length / 1024).toFixed(0)} KB Kotlin: longest main-thread task during initial tokenization ${longest.max.toFixed(0)} ms (${longest.count} long tasks); keystroke→change median ${median.toFixed(0)} ms, worst ${worst.toFixed(0)} ms over ${timings.length} keys`);
    await page.close();
  }
} finally {
  await harness.close();
}
