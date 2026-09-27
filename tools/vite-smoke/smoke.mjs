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

import { MOUNT, startHarness } from './harness.mjs';

const harness = await startHarness();
const { browser, url } = harness;
const failures = [];
const notices = [];

// Upstream console output we cannot fix here. Printed, not fatal; revisit on each Compose/Kotlin bump.
const KNOWN_UPSTREAM = [
  // Emitted by the Kotlin/Wasm runtime on behalf of a dependency (Compose/Skiko) that still reads
  // memory through wasmExports. Seen with Kotlin 2.4.20 + Compose Multiplatform 1.12.1.
  /Accessing `memory` via `wasmExports` is deprecated/,
];

/**
 * Typing, an IME composition, a paste and a touch tap, the way a browser delivers them, into a
 * fresh editor filling the page.
 */
async function interactionChecks(page) {
  const checks = {};
  const value = () => page.evaluate(() => window.__smokeEditor.getValue());
  const settle = () => new Promise((r) => setTimeout(r, 300));
  const mountEditor = async (text) => {
    await page.evaluate(async (mount, text) => {
      window.__smokeEditor?.dispose();
      document.getElementById('check')?.remove();
      window.__smokeEditor = await window.__aardink.createEditor(eval(mount), undefined, { value: text });
    }, MOUNT, text);
    await new Promise((r) => setTimeout(r, 500));
  };

  // Touch first: once a (synthetic) mouse has been over the page, Compose's web input no longer
  // delivers Puppeteer's taps at all, for its own text field as much as for this editor.
  await mountEditor('line one\nline two');
  await page.touchscreen.tap(150, 36); // the second line
  await settle();
  await page.touchscreen.tap(150, 16); // then the first
  await settle();
  await page.keyboard.type('!');
  await settle();
  const [first, second] = (await value()).split('\n');
  checks['touch tap places the caret'] = first.includes('!') && !second.includes('!');

  await mountEditor('');
  await page.mouse.click(300, 20);
  await page.keyboard.type('abc');
  await settle();
  checks['typing'] = (await value()) === 'abc';

  const cdp = await page.createCDPSession();
  await cdp.send('Input.imeSetComposition', { text: 'に', selectionStart: 1, selectionEnd: 1 });
  await settle();
  await cdp.send('Input.insertText', { text: '日' });
  await settle();
  checks['IME composition'] = (await value()) === 'abc日';

  await page.evaluate(() => {
    const data = new DataTransfer();
    data.setData('text/plain', 'X\r\nY');
    document.activeElement.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await settle();
  checks['paste (CRLF to LF)'] = (await value()) === 'abc日X\nY';

  await page.keyboard.press('Backspace');
  await settle();
  checks['backspace'] = (await value()) === 'abc日X\n';
  return checks;
}

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 900, height: 500, hasTouch: true });
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
  const interactions = await interactionChecks(page);
  for (const [name, ok] of Object.entries(interactions)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  const failedInteractions = Object.entries(interactions).filter(([, ok]) => !ok).map(([name]) => name);
  if (failedInteractions.length) failures.push(`failed interactions: ${failedInteractions.join(', ')}`);
} finally {
  await harness.close();
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
