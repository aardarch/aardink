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

// Shared set-up for the scripts that drive the production build in headless Chrome:
// smoke.mjs (pass/fail), checklist.mjs (W-1..W-10 measurements) and perf.mjs (latency gates).

import { existsSync } from 'node:fs';
import puppeteer from 'puppeteer-core';
import { preview } from 'vite';

export function findChrome() {
  const executablePath = [
    process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
  ].filter(Boolean).find((p) => existsSync(p));
  if (!executablePath) throw new Error('No Chrome found; set CHROME_BIN');
  return executablePath;
}

/** Serves dist/ (the `vite build` output) and launches headless Chrome against it. */
export async function startHarness() {
  const server = await preview({ preview: { port: 0 } });
  const url = server.resolvedUrls.local[0];
  const browser = await puppeteer.launch({ executablePath: findChrome(), headless: true, args: ['--no-sandbox'] });
  return {
    server,
    url,
    browser,
    async close() {
      await browser.close();
      await new Promise((resolve) => server.httpServer.close(resolve));
    },
  };
}

/** A page that has loaded the app and finished the in-page smoke checks; `options.query` is appended to the URL. */
export async function freshPage(harness, options = {}) {
  const page = await harness.browser.newPage();
  await page.setViewport({ width: options.width ?? 1000, height: options.height ?? 600, deviceScaleFactor: options.dpr ?? 1 });
  await page.goto(harness.url + (options.query ?? ''), { waitUntil: 'load' });
  await page.waitForFunction(() => window.__aardinkSmoke?.done, { timeout: 60000 });
  return page;
}

const kotlinLines = [
  'package demo',
  '',
  '/** A sample block, repeated to build a large document. */',
  'data class Point(val x: Int, val y: Int) {',
  '    fun plus(other: Point): Point = Point(x + other.x, y + other.y)',
  '    // a comment with "quotes" and 0x1F numbers',
  '}',
];

/** Highlighted Kotlin of the given line count; 5,000 lines is the W-10 reference document. */
export const largeKotlin = (lines) => Array.from({ length: lines }, (_, i) => kotlinLines[i % kotlinLines.length]).join('\n');

/**
 * Page-side expression that hides the smoke test's editor and returns a fresh full-viewport
 * container; #editor already holds one viewport, and a second one in it would take the input.
 */
export const MOUNT = `(() => {
  document.getElementById('editor').style.display = 'none';
  const box = document.createElement('div');
  box.id = 'check';
  box.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:10;background:#fff';
  document.body.append(box);
  return box;
})()`;

export function percentile(sorted, p) {
  if (sorted.length === 0) return NaN;
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, index)];
}
