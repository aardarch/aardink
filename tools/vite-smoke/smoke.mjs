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

import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { MOUNT, startHarness } from './harness.mjs';

// Which binaries ran, so a failure on CI can be compared with a local build of the same commit.
for (const file of readdirSync('dist/assets').filter((f) => f.endsWith('.wasm'))) {
  console.log(`${file} sha256 ${createHash('sha256').update(readFileSync(`dist/assets/${file}`)).digest('hex').slice(0, 16)}`);
}

const harness = await startHarness();
const { browser, url } = harness;
const failures = [];
// The step under way, so a page error says what set it off: an uncaught error reaches the page
// on its own schedule, and the list of failures is only printed at the end.
let phase = 'loading';
const notices = [];

// Upstream console output we cannot fix here. Printed, not fatal; revisit on each Compose/Kotlin bump.
const KNOWN_UPSTREAM = [
  // Logged by the Kotlin 2.4.20 glue the first time anything reads `wasmExports.memory`. The reader
  // is Compose Multiplatform resources' copyArrayBufferToWasmMemory (1.12.1, still in 1.13.0-alpha01),
  // which Res.readBytes uses to load the bundled font. Not in Aardink's code.
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
  phase = 'touch tap';
  await mountEditor('line one\nline two');
  await page.touchscreen.tap(150, 36); // the second line
  await settle();
  await page.touchscreen.tap(150, 16); // then the first
  await settle();
  await page.keyboard.type('!');
  await settle();
  const [first, second] = (await value()).split('\n');
  checks['touch tap places the caret'] = first.includes('!') && !second.includes('!');

  phase = 'typing';
  await mountEditor('');
  await page.mouse.click(300, 20);
  await page.keyboard.type('abc');
  await settle();
  checks['typing'] = (await value()) === 'abc';

  const cdp = await page.createCDPSession();
  phase = 'IME composition';
  await cdp.send('Input.imeSetComposition', { text: 'に', selectionStart: 1, selectionEnd: 1 });
  await settle();
  await cdp.send('Input.insertText', { text: '日' });
  await settle();
  checks['IME composition'] = (await value()) === 'abc日';

  phase = 'paste';
  await page.evaluate(() => {
    const data = new DataTransfer();
    data.setData('text/plain', 'X\r\nY');
    document.activeElement.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await settle();
  checks['paste (CRLF to LF)'] = (await value()) === 'abc日X\nY';

  phase = 'backspace';
  await page.keyboard.press('Backspace');
  await settle();
  checks['backspace'] = (await value()) === 'abc日X\n';

  // The find panel takes the keys while it is open, and Escape gives them back to the text.
  phase = 'find panel focus';
  await page.keyboard.down('Control');
  await page.keyboard.press('KeyF');
  await page.keyboard.up('Control');
  await settle();
  await page.keyboard.type('zz');
  await settle();
  const untouched = (await value()) === 'abc日X\n';
  await page.keyboard.press('Escape');
  await settle();
  await page.keyboard.type('q');
  await settle();
  checks['Ctrl+F types into the find field, Escape back into the text'] = untouched && (await value()) === 'abc日X\nq';

  phase = 'showFind focus';
  await page.evaluate(() => {
    document.activeElement?.blur();
    window.__smokeEditor.showFind();
  });
  await settle();
  await page.keyboard.type('zz');
  await settle();
  checks['showFind() from outside the editor takes the keys'] = (await value()) === 'abc日X\nq' &&
    (await page.evaluate(() => document.getElementById('check').contains(document.activeElement)));
  return checks;
}

/**
 * A grammar and a theme registered from JavaScript, in Monaco's shapes, colour the text: the
 * keyword `shout` in the theme's magenta, counted by pixel in a screenshot of the editor.
 */
async function grammarCheck(page) {
  phase = 'registered grammar';
  await page.evaluate(async (mount) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    const { createEditor, defineTheme, registerLanguage } = window.__aardink;
    await defineTheme('toy-dark', { base: 'vs-dark', inherit: true, rules: [{ token: 'keyword', foreground: 'ff00ff' }] });
    await registerLanguage({ id: 'toy', grammar: { tokenizer: { root: [[/\bshout\b/, 'keyword'], [/\w+/, 'text']] } } });
    window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'shout quietly shout', language: 'toy', theme: 'toy-dark', fontSize: 24 });
  }, MOUNT);
  await new Promise((r) => setTimeout(r, 1500));
  const png = await page.screenshot({ clip: { x: 0, y: 0, width: 600, height: 60 }, encoding: 'base64' });
  const magenta = await page.evaluate(async (png) => {
    const image = new Image();
    image.src = `data:image/png;base64,${png}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.width;
    canvas.height = image.height;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const { data } = context.getImageData(0, 0, image.width, image.height);
    let count = 0;
    for (let i = 0; i < data.length; i += 4) if (data[i] > 180 && data[i + 1] < 90 && data[i + 2] > 180) count++;
    return count;
  }, png);
  return { 'registered grammar and theme colour the text': magenta > 50 };
}

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 900, height: 500, hasTouch: true });
  page.on('requestfailed', (r) => failures.push(`request failed: ${r.url()} (${r.failure()?.errorText})`));
  page.on('response', (r) => { if (r.status() >= 400) failures.push(`HTTP ${r.status()}: ${r.url()}`); });
  page.on('pageerror', (e) => failures.push(`page error during ${phase}: ${e.stack || e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error' && !m.text().startsWith('Aardink:')) return;
    (KNOWN_UPSTREAM.some((re) => re.test(m.text())) ? notices : failures).push(`console: ${m.text()}`);
  });

  phase = 'in-page checks';
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__aardinkSmoke?.done, { timeout: 60000 });
  const report = await page.evaluate(() => window.__aardinkSmoke);
  // A few frames so the light theme and the diagnostic are painted before the screenshot.
  await new Promise((resolve) => setTimeout(resolve, 1000));
  phase = 'screenshot of the in-page checks';
  await page.screenshot({ path: 'dist/smoke.png' });

  console.log(`@aardarch/aardink-web ${report.version}`);
  for (const [name, ok] of Object.entries(report.checks)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  if (report.error) failures.push(`in-page error: ${report.error}`);
  const failedChecks = Object.entries(report.checks).filter(([, ok]) => !ok).map(([name]) => name);
  if (failedChecks.length) failures.push(`failed checks: ${failedChecks.join(', ')}`);
  if (Object.keys(report.checks).length < 5) failures.push('not every check ran');
  const interactions = await interactionChecks(page);
  for (const [name, ok] of Object.entries(interactions)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  const grammar = await grammarCheck(page);
  Object.assign(interactions, grammar);
  for (const [name, ok] of Object.entries(grammar)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
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
