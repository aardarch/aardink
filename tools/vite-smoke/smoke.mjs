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

// Serves the production build (`vite build` output) and drives it in headless Chrome:
// the in-page checks in src/main.js must all pass, no request may fail (a missing .wasm or
// font shows up here), and a screenshot is written to dist/smoke.png for a human to eyeball.
//
//   pnpm install && pnpm smoke          # CHROME_BIN overrides the Chrome location

import { existsSync } from 'node:fs';
import puppeteer from 'puppeteer-core';
import { preview } from 'vite';

const chromeCandidates = [
  process.env.CHROME_BIN,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean);
const executablePath = chromeCandidates.find((p) => existsSync(p));
if (!executablePath) throw new Error('No Chrome found; set CHROME_BIN');

const server = await preview({ preview: { port: 0 } });
const url = server.resolvedUrls.local[0];
const browser = await puppeteer.launch({ executablePath, headless: true, args: ['--no-sandbox'] });
const failures = [];
const notices = [];

// Upstream console output we cannot fix here. Printed, not fatal; revisit on each Compose/Kotlin bump.
const KNOWN_UPSTREAM = [
  // Emitted by the Kotlin/Wasm runtime on behalf of a dependency (Compose/Skiko) that still reads
  // memory through wasmExports. Seen with Kotlin 2.4.20 + Compose Multiplatform 1.12.1.
  /Accessing `memory` via `wasmExports` is deprecated/,
];

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 900, height: 500 });
  page.on('requestfailed', (r) => failures.push(`request failed: ${r.url()} (${r.failure()?.errorText})`));
  page.on('response', (r) => { if (r.status() >= 400) failures.push(`HTTP ${r.status()}: ${r.url()}`); });
  page.on('pageerror', (e) => failures.push(`page error: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error' && !m.text().startsWith('Aardink:')) return;
    (KNOWN_UPSTREAM.some((re) => re.test(m.text())) ? notices : failures).push(`console: ${m.text()}`);
  });

  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__aardinkSmoke?.done, { timeout: 60000 });
  const report = await page.evaluate(() => window.__aardinkSmoke);
  // A few frames so the light theme and the diagnostic are painted before the screenshot.
  await new Promise((resolve) => setTimeout(resolve, 1000));
  await page.screenshot({ path: 'dist/smoke.png' });

  console.log(`@aardarch/aardink-web ${report.version}`);
  for (const [name, ok] of Object.entries(report.checks)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  if (report.error) failures.push(`in-page error: ${report.error}`);
  const failedChecks = Object.entries(report.checks).filter(([, ok]) => !ok).map(([name]) => name);
  if (failedChecks.length) failures.push(`failed checks: ${failedChecks.join(', ')}`);
  if (Object.keys(report.checks).length < 5) failures.push('not every check ran');
} finally {
  await browser.close();
  await new Promise((resolve) => server.httpServer.close(resolve));
}

if (notices.length) {
  console.log('Known upstream messages (not failures):');
  notices.forEach((n) => console.log(`  ${n}`));
}
if (failures.length) {
  console.error(failures.map((f) => `  ${f}`).join('\n'));
  process.exit(1);
}
console.log('Vite smoke test passed; screenshot at dist/smoke.png');
