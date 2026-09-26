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
//
//   pnpm build && node checklist.mjs

import { existsSync } from 'node:fs';
import puppeteer from 'puppeteer-core';
import { preview } from 'vite';

const executablePath = [
  process.env.CHROME_BIN,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean).find((p) => existsSync(p));
if (!executablePath) throw new Error('No Chrome found; set CHROME_BIN');

const server = await preview({ preview: { port: 0 } });
const url = server.resolvedUrls.local[0];
const browser = await puppeteer.launch({ executablePath, headless: true, args: ['--no-sandbox'] });

const kotlinLines = [
  'package demo',
  '',
  '/** A sample block, repeated to build a large document. */',
  'data class Point(val x: Int, val y: Int) {',
  '    fun plus(other: Point): Point = Point(x + other.x, y + other.y)',
  '    // a comment with "quotes" and 0x1F numbers',
  '}',
];
const largeKotlin = (lines) => Array.from({ length: lines }, (_, i) => kotlinLines[i % kotlinLines.length]).join('\n');

// Each check mounts into its own container laid over the viewport; #editor already holds the
// smoke test's editor, and a second viewport in the same element would take the input.
const MOUNT = `(() => {
  document.getElementById('editor').style.display = 'none';
  const box = document.createElement('div');
  box.id = 'check';
  box.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:10;background:#fff';
  document.body.append(box);
  return box;
})()`;

async function freshPage(options = {}) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1000, height: 600, deviceScaleFactor: options.dpr ?? 1 });
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__aardinkSmoke?.done, { timeout: 60000 });
  return page;
}

const results = [];
const record = (id, text) => {
  results.push(`${id}: ${text}`);
  console.log(`${id}: ${text}`);
};

try {
  // ── W-1: mount + dispose 50×, heap after forced GC ─────────────────────────
  {
    const page = await freshPage();
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

  // ── W-3: 100 KB paste latency and CRLF ─────────────────────────────────────
  {
    const page = await freshPage();
    await browser.defaultBrowserContext().overridePermissions(url, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write']);
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
  {
    const page = await freshPage();
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
  {
    const page = await freshPage({ dpr: 2 });
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
  {
    const page = await freshPage();
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
    // Few keys, generous timeout: each keystroke re-lays-out the whole document (see
    // docs/WEB_INTEGRATION.md), so at this size one key takes on the order of a second.
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
    const p95 = timings[timings.length - 1];
    record('W-10', `${(text.length / 1024).toFixed(0)} KB Kotlin: longest main-thread task during initial tokenization ${longest.max.toFixed(0)} ms (${longest.count} long tasks); keystroke→change median ${median.toFixed(0)} ms, worst ${p95.toFixed(0)} ms over ${timings.length} keys`);
    await page.close();
  }
} finally {
  await browser.close();
  await new Promise((resolve) => server.httpServer.close(resolve));
}
