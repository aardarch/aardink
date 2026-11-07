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
import { startLoopbackLsp } from './loopback-lsp.mjs';

// Which binaries ran, so a failure on CI can be compared with a local build of the same commit.
for (const file of readdirSync('dist/assets').filter((f) => f.endsWith('.wasm'))) {
  console.log(`${file} sha256 ${createHash('sha256').update(readFileSync(`dist/assets/${file}`)).digest('hex').slice(0, 16)}`);
}

// The step under way, so a page error says what set it off: an uncaught error reaches the page
// on its own schedule, and the list of failures is only printed at the end.
let phase;
let phaseStarted;
let harness;
function setPhase(name) {
  phase = name;
  phaseStarted = Date.now();
  console.log(`  .. ${name}`);
}

// A step that never finishes (a promise in the page that never settles) fails the run and says
// which step it was, instead of leaving pre-push waiting with nothing on screen.
const PHASE_LIMIT_MS = 120_000;
setInterval(() => {
  if (Date.now() - phaseStarted < PHASE_LIMIT_MS) return;
  console.error(`  stuck for ${PHASE_LIMIT_MS / 1000}s during ${phase}`);
  harness?.browser.process()?.kill();
  process.exit(1);
}, 1000).unref();

setPhase('starting Chrome');
harness = await startHarness();
const { browser, url } = harness;
const failures = [];
const notices = [];

// Upstream console output we cannot fix here. Printed, not fatal; revisit on each Compose/Kotlin bump.
const KNOWN_UPSTREAM = [
  // Logged by the Kotlin 2.4.20 glue the first time anything reads `wasmExports.memory`. The reader
  // is Compose Multiplatform resources' copyArrayBufferToWasmMemory (1.12.1, still in 1.13.0-alpha01),
  // which Res.readBytes uses to load the bundled font. Not in Aardink's code.
  /Accessing `memory` via `wasmExports` is deprecated/,
];

// What a step sets off on purpose, for as long as that step wants it. Not printed.
let expectedConsole = [];

/** Fails the run on what a page says went wrong. */
function watch(page) {
  page.on('requestfailed', (r) => failures.push(`request failed: ${r.url()} (${r.failure()?.errorText})`));
  page.on('response', (r) => { if (r.status() >= 400) failures.push(`HTTP ${r.status()}: ${r.url()}`); });
  page.on('pageerror', (e) => failures.push(`page error during ${phase}: ${e.stack || e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error' && !m.text().startsWith('Aardink:')) return;
    if (expectedConsole.some((re) => re.test(m.text()))) return;
    (KNOWN_UPSTREAM.some((re) => re.test(m.text())) ? notices : failures).push(`console: ${m.text()}`);
  });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * A page with no touch screen, where completions show in the list that Enter reaches. It keeps what
 * `window.open` was called with and in what state, and tries one popup of its own before any
 * gesture: only if the blocker stops that does getting past it mean anything.
 */
async function desktopPage() {
  const page = await browser.newPage();
  await page.setViewport({ width: 900, height: 500 });
  watch(page);
  await page.evaluateOnNewDocument(() => {
    window.__popupWithoutGesture = window.open('about:blank') !== null;
    window.__opens = [];
    // The pointer event whose task this is, if any. Not window.event: Compose's listeners are in a
    // shadow tree, where that is always undefined.
    for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
      window.addEventListener(type, () => {
        window.__inTaskOf = type;
        setTimeout(() => { window.__inTaskOf = null; });
      }, true);
    }
    const open = window.open;
    window.open = function (url, target, features) {
      window.__opens.push({ url, target, features, active: navigator.userActivation.isActive, task: window.__inTaskOf ?? null });
      return open.call(this, url, target, features);
    };
  });
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__aardinkSmoke?.done, { timeout: 60000 });
  return page;
}

/** A provider's arguments and answer through the dispatcher, set off by the real mouse and by the editor's own schedule. */
async function dispatcherCheck(page) {
  setPhase('provider dispatcher');
  expectedConsole = [/^Aardink: a language provider failed: .*no diagnostics here/];
  await page.evaluate(async (mount) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    const { createEditor, registerLanguage } = window.__aardink;
    window.__asked = [];
    await registerLanguage(
      { id: 'toy-dispatch', grammar: { tokenizer: { root: [[/\w+/, 'text']] } } },
      {
        provideHover: async (text, line, column) => {
          window.__asked.push({ text, line, column });
          return { contents: 'dispatched' };
        },
        provideDocumentSymbols: () => [{
          name: 'dispatched',
          kind: 11,
          range: { startLineNumber: 1, startColumn: 1, endLineNumber: 2, endColumn: 5 },
        }],
        // Rejected, and asked on the editor's own schedule: it costs that answer and nothing else.
        provideDiagnostics: () => Promise.reject(new Error('no diagnostics here')),
      },
    );
    window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'first line\nsecond line', language: 'toy-dispatch' });
  }, MOUNT);
  await sleep(1000);
  await page.mouse.move(150, 30);
  await page.mouse.move(151, 30);
  await page.waitForFunction(() => window.__asked.length > 0, { timeout: 5000 }).catch(() => {});
  const asked = await page.evaluate(() => window.__asked[0] ?? null);
  const symbols = await page.evaluate(() => window.__smokeEditor.getDocumentSymbols().map((symbol) => symbol.name));
  await page.mouse.move(800, 400);
  expectedConsole = [];
  return {
    'a provider gets the text and a place through the dispatcher':
      asked?.text === 'first line\nsecond line' && asked?.line === 2 && asked?.column > 1,
    'a provider\'s answer comes back through the dispatcher': symbols.includes('dispatched'),
  };
}

/**
 * provideInlayHints through the dispatcher, and text from it and from a decoration that is on screen
 * and not in the document. What is on screen is compared whole: nothing else moves on an editor
 * without focus, so an equal picture after it goes says the difference was that text.
 */
async function inlayHintCheck(page) {
  setPhase('inlay hints and decoration text');
  await page.evaluate(async (mount) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    const { createEditor, registerLanguage } = window.__aardink;
    window.__hintsAsked = [];
    await registerLanguage(
      { id: 'toy-hints', grammar: { tokenizer: { root: [] } } },
      {
        provideInlayHints: async (text, range) => {
          window.__hintsAsked.push({ text, range });
          return [{ position: { lineNumber: 1, column: 5 }, label: ': WWWWWW' }];
        },
      },
    );
    window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'val x = 1\nsecond', language: 'toy-hints' });
  }, MOUNT);
  const shot = () => page.screenshot({ encoding: 'base64' });
  const settle = () => sleep(800);
  await settle();
  const plain = await shot();
  const stable = plain === await shot();
  const askedWhileOff = await page.evaluate(() => window.__hintsAsked.length);

  await page.evaluate(() => window.__smokeEditor.updateOptions({ inlayHints: { enabled: 'on' } }));
  await page.waitForFunction(() => window.__hintsAsked.length > 0, { timeout: 5000 }).catch(() => {});
  await settle();
  const hinted = await shot();
  const asked = await page.evaluate(() => window.__hintsAsked[0] ?? null);
  const hintedValue = await page.evaluate(() => window.__smokeEditor.getValue());
  await page.evaluate(() => window.__smokeEditor.updateOptions({ inlayHints: false }));
  await settle();
  const hintGone = await shot();

  await page.evaluate(() => {
    const range = { startLineNumber: 1, startColumn: 5, endLineNumber: 1, endColumn: 6 };
    const options = { before: { content: '<' }, after: { content: '>' }, inlineColor: '#ff0000' };
    window.__smokeIds = window.__smokeEditor.deltaDecorations([], [{ range, options }]);
  });
  await settle();
  const decorated = await shot();
  const decoratedValue = await page.evaluate(() => window.__smokeEditor.getValue());
  await page.evaluate(() => window.__smokeEditor.deltaDecorations(window.__smokeIds, []));
  await settle();
  const decorationGone = await shot();

  return {
    'nothing moves on screen by itself, so the pictures below can be compared': stable,
    'inlay hints are not asked for with the option off': askedWhileOff === 0,
    'provideInlayHints gets the text and a range through the dispatcher':
      asked?.text === 'val x = 1\nsecond' && asked?.range?.startLineNumber === 1 && asked?.range?.startColumn === 1 &&
      asked?.range?.endLineNumber === 2 && asked?.range?.endColumn === 7,
    'a hint is on screen and not in the document': hinted !== plain && hintedValue === 'val x = 1\nsecond',
    'turning the option off takes the hint away': hintGone === plain,
    'before and after are on screen and not in the document': decorated !== plain && decoratedValue === 'val x = 1\nsecond',
    'removing the decoration takes its text away': decorationGone === plain,
  };
}

/**
 * A host's provider with a language server behind it that declares inlay hints and never answers
 * the request: the provider's hint still has to reach the screen. The picture to compare with is
 * taken with the server connected, as what it publishes is on screen too.
 */
async function stuckServerHintCheck(page) {
  setPhase('inlay hints: a server stuck on the request');
  const server = await startLoopbackLsp('', { capabilities: { inlayHintProvider: true }, unanswered: ['textDocument/inlayHint'] });
  try {
    await page.evaluate(async (mount, serverUrl) => {
      window.__smokeEditor?.dispose();
      document.getElementById('check')?.remove();
      const { connectLanguageServer, createEditor, registerLanguage } = window.__aardink;
      await registerLanguage(
        { id: 'toy-stuck', grammar: { tokenizer: { root: [] } } },
        { provideInlayHints: () => [{ position: { lineNumber: 1, column: 5 }, label: ': WWWWWW' }] },
      );
      window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'val x = 1', language: 'toy-stuck' });
      window.__stuck = await connectLanguageServer(serverUrl, 'toy-stuck');
    }, MOUNT, server.url);
    await server.until(() => server.count('textDocument/didOpen') === 1);
    await sleep(800);
    const plain = await page.screenshot({ encoding: 'base64' });

    await page.evaluate(() => window.__smokeEditor.updateOptions({ inlayHints: true }));
    const asked = await server.until(() => server.count('textDocument/inlayHint') >= 1);
    await sleep(1800);
    const hinted = await page.screenshot({ encoding: 'base64' });
    await page.evaluate(() => window.__stuck.dispose());

    return {
      'a provider\'s hint reaches the screen past a server stuck on the request': asked && hinted !== plain,
    };
  } finally {
    await server.close();
  }
}

/** Long enough for a click at x 150 or 450 to land on the first or the second of two lenses on the top row. */
const LENS_TITLE = 'W'.repeat(30);

/** A language with two lenses over its first line, whose runs land in `window.__lensRuns`. */
async function lensEditor(page) {
  await page.evaluate(async (mount, title) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    const { createEditor, registerLanguage } = window.__aardink;
    window.__lensesAsked = [];
    window.__lensRuns = [];
    const range = { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 4 };
    await registerLanguage(
      { id: 'toy-lens', grammar: { tokenizer: { root: [] } } },
      {
        provideCodeLenses: async (text) => {
          window.__lensesAsked.push(text);
          return [
            { range, command: { id: 'first', title, arguments: [1, 'two'] } },
            { range, command: { id: 'second', title } },
          ];
        },
        runCodeLensCommand: (id, args) => {
          window.__lensRuns.push({ id, args });
        },
      },
    );
    window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'fun main() {}\nsecond', language: 'toy-lens' });
  }, MOUNT, LENS_TITLE);
}

/**
 * provideCodeLenses through the dispatcher, a row on screen and not in the document, and a real
 * click on each lens of the row coming back through runCodeLensCommand with the caret left alone.
 */
async function codeLensCheck(page) {
  setPhase('code lenses');
  await lensEditor(page);
  const shot = () => page.screenshot({ encoding: 'base64' });
  await sleep(800);
  const plain = await shot();
  const askedWhileOff = await page.evaluate(() => window.__lensesAsked.length);

  await page.evaluate(() => window.__smokeEditor.updateOptions({ codeLens: true }));
  await page.waitForFunction(() => window.__lensesAsked.length > 0, { timeout: 5000 }).catch(() => {});
  await sleep(800);
  const lensed = await shot();
  await page.evaluate(() => window.__smokeEditor.updateOptions({ codeLens: false }));
  await sleep(800);
  const gone = await shot();

  await page.evaluate(() => window.__smokeEditor.updateOptions({ codeLens: true }));
  await sleep(800);
  const state = () => page.evaluate(() => ({
    value: window.__smokeEditor.getValue(),
    caret: window.__smokeEditor.getPosition(),
    runs: window.__lensRuns.slice(),
  }));
  await page.evaluate(() => window.__smokeEditor.setPosition({ lineNumber: 2, column: 3 }));
  await page.mouse.click(150, 12);
  await sleep(300);
  await page.mouse.click(450, 12);
  await sleep(300);
  const clicked = await state();

  return {
    'code lenses are not asked for with the option off': askedWhileOff === 0,
    'provideCodeLenses gets the text through the dispatcher': (await page.evaluate(() => window.__lensesAsked[0])) === 'fun main() {}\nsecond',
    'a lens is on screen and not in the document': lensed !== plain && clicked.value === 'fun main() {}\nsecond',
    'a click on each lens of a row runs its own command, with its arguments':
      JSON.stringify(clicked.runs) === JSON.stringify([{ id: 'first', args: [1, 'two'] }, { id: 'second', args: [] }]),
    'a click on a lens leaves the caret where it was': clicked.caret?.lineNumber === 2 && clicked.caret?.column === 3,
    'turning the option off takes the row away': gone === plain,
  };
}

/** A tap on a page with a touch screen runs a lens as a click does. */
async function codeLensTapCheck(page) {
  setPhase('code lenses by touch');
  await lensEditor(page);
  await page.evaluate(() => window.__smokeEditor.updateOptions({ codeLens: true }));
  await page.waitForFunction(() => window.__lensesAsked.length > 0, { timeout: 5000 }).catch(() => {});
  await sleep(800);
  await page.touchscreen.tap(150, 12);
  await sleep(500);
  const runs = await page.evaluate(() => window.__lensRuns.slice());
  return { 'a tap on a lens runs it': runs.length === 1 && runs[0].id === 'first' };
}

/**
 * A language server's lenses through a real WebSocket: asked for, resolved, on screen, and a click
 * going back as workspace/executeCommand.
 */
async function serverCodeLensCheck(page) {
  setPhase('code lenses: a language server');
  const range = { start: { line: 0, character: 0 }, end: { line: 0, character: 3 } };
  const server = await startLoopbackLsp('', {
    capabilities: { codeLensProvider: { resolveProvider: true }, executeCommandProvider: { commands: ['server.run'] } },
    answers: {
      'textDocument/codeLens': () => [{ range, data: 7 }],
      'codeLens/resolve': (lens) => ({ ...lens, command: { title: LENS_TITLE, command: 'server.run', arguments: [lens.data] } }),
    },
  });
  try {
    await page.evaluate(async (mount, serverUrl) => {
      window.__smokeEditor?.dispose();
      document.getElementById('check')?.remove();
      const { connectLanguageServer, createEditor, registerLanguage } = window.__aardink;
      await registerLanguage({ id: 'toy-served-lens', grammar: { tokenizer: { root: [] } } });
      window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'fun main() {}', language: 'toy-served-lens', codeLens: true });
      window.__servedLens = await connectLanguageServer(serverUrl, 'toy-served-lens');
    }, MOUNT, server.url);
    const resolved = await server.until(() => server.count('codeLens/resolve') === 1);
    await sleep(800);
    await page.mouse.click(150, 12);
    const ran = await server.until(() => server.count('workspace/executeCommand') === 1);
    const sent = server.heard.find((message) => message.method === 'workspace/executeCommand')?.params;
    const heard = server.methods();
    await page.evaluate(() => window.__servedLens.dispose());
    return {
      'a server\'s lens is asked for after the document opens, and resolved with its data':
        resolved && heard.indexOf('textDocument/didOpen') < heard.indexOf('textDocument/codeLens') &&
        server.heard.find((message) => message.method === 'codeLens/resolve')?.params?.data === 7,
      'a click on a server\'s lens runs its command there': ran && sent?.command === 'server.run' && sent?.arguments?.[0] === 7,
    };
  } finally {
    await server.close();
  }
}

/** Where the link in the hover card is: the pixels of the theme's link colour, found on a screenshot. */
async function linkPixel(page) {
  const png = await page.screenshot({ encoding: 'base64' });
  return page.evaluate(async (png) => {
    const image = new Image();
    image.src = `data:image/png;base64,${png}`;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.width;
    canvas.height = image.height;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const { data } = context.getImageData(0, 0, image.width, image.height);
    const blue = [];
    for (let y = 0; y < image.height; y++) {
      for (let x = 0; x < image.width; x++) {
        const i = (y * image.width + x) * 4;
        if (data[i + 2] > 170 && data[i + 2] > data[i] + 60 && data[i + 1] > data[i]) blue.push([x, y]);
      }
    }
    if (blue.length === 0) return null;
    const middle = (values) => values.sort((a, b) => a - b)[Math.floor(values.length / 2)];
    return { x: middle(blue.map(([x]) => x)), y: middle(blue.map(([, y]) => y)), count: blue.length };
  }, png);
}

/**
 * connectLanguageServer against a real WebSocket: editors open before and created after it, what
 * the server knows reaching the text beside a host's providers, the end of a session, a connection
 * that drops and one that never comes up.
 */
async function languageServerCheck(page) {
  const checks = {};
  const hoverLink = `${url}smoke-hover-link.txt`;
  const server = await startLoopbackLsp(hoverLink);
  const value = () => page.evaluate(() => window.__smokeEditor.getValue());
  try {
    setPhase('language server: editors before and after');
    await page.evaluate(async (mount, serverUrl) => {
      window.__smokeEditor?.dispose();
      document.getElementById('check')?.remove();
      const { connectLanguageServer, createEditor, registerLanguage } = window.__aardink;
      await registerLanguage(
        { id: 'toy-server', grammar: { tokenizer: { root: [[/\w+/, 'text']] } } },
        { triggerCharacters: ['@'], provideCompletionItems: () => [{ label: 'hosted', insertText: 'hosted' }] },
      );
      window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'open before', language: 'toy-server' });
      window.__diagnostics = [];
      window.__smokeEditor.onDidChangeDiagnostics((list) => window.__diagnostics.push(list.map((d) => d.message)));
      window.__connection = await connectLanguageServer(serverUrl, 'toy-server');
    }, MOUNT, server.url);
    await server.until(() => server.count('textDocument/didOpen') === 1);
    await page.evaluate(async () => {
      const box = document.createElement('div');
      box.id = 'second';
      box.style.cssText = 'position:fixed;right:0;bottom:0;width:10px;height:10px';
      document.body.append(box);
      window.__second = await window.__aardink.createEditor(box, undefined, { value: 'open after', language: 'toy-server' });
    });
    await server.until(() => server.count('textDocument/didOpen') === 2);
    const opened = server.heard.filter((m) => m.method === 'textDocument/didOpen').map((m) => m.params.textDocument);
    checks['a server hears of editors open already and created later'] = opened.length === 2 &&
      opened[0].text === 'open before' && opened[1].text === 'open after' && opened[0].uri !== opened[1].uri &&
      opened.every((d) => d.languageId === 'toy-server') && server.methods()[0] === 'initialize';

    setPhase('language server: diagnostics');
    await page.waitForFunction(() => window.__diagnostics.some((list) => list.includes('from the server, version 1')), { timeout: 5000 })
      .then(() => { checks['a server\'s diagnostics reach the editor, without an edit'] = true; })
      .catch(() => { checks['a server\'s diagnostics reach the editor, without an edit'] = false; });

    setPhase('language server: completions');
    await page.mouse.click(600, 20);
    await page.keyboard.type('.');
    const asked = await server.until(() => server.count('textDocument/completion') >= 1);
    const methods = server.methods();
    checks['an edit reaches the server ahead of the completion it set off'] = asked &&
      methods.indexOf('textDocument/didChange') < methods.indexOf('textDocument/completion') && methods.includes('textDocument/didChange');
    await sleep(500);
    await page.keyboard.press('ArrowDown');
    await sleep(100);
    await page.keyboard.press('Enter');
    await sleep(300);
    checks['a server\'s completion is accepted into the text'] = (await value()) === 'open before.served';
    await page.keyboard.type('.');
    await sleep(800);
    await page.keyboard.press('Enter');
    await sleep(300);
    checks['a host\'s provider still answers, ahead of the server'] = (await value()) === 'open before.served.hosted';

    setPhase('language server: typing');
    await page.keyboard.type('\'');
    await sleep(100);
    checks['a language that closes nothing keeps a server\'s generic pairs out of typing'] = (await value()) === 'open before.served.hosted\'';
    await page.keyboard.press('Backspace');
    await sleep(100);

    setPhase('language server: hover');
    const hovers = server.count('textDocument/hover');
    await page.mouse.move(120, 12);
    await page.mouse.move(121, 12);
    await server.until(() => server.count('textDocument/hover') > hovers);
    await sleep(800);
    const link = await linkPixel(page);
    await page.evaluate(() => {
      window.__links = [];
      window.__stopLinks = window.__smokeEditor.onOpenLink((target) => window.__links.push(target));
    });
    checks['a server\'s hover reaches the editor, and the link in it the host'] = false;
    if (link) {
      await page.mouse.move(link.x, link.y);
      await sleep(100);
      await page.mouse.click(link.x, link.y);
      await sleep(300);
      const links = await page.evaluate(() => window.__links.slice());
      checks['a server\'s hover reaches the editor, and the link in it the host'] = links.length === 1 && links[0] === hoverLink;
      await page.evaluate(() => window.__stopLinks.dispose());
      // Following a link takes the card down, so it is brought back for the second click.
      await page.mouse.move(800, 400);
      await sleep(800);
      await page.mouse.move(120, 12);
      await page.mouse.move(121, 12);
      await sleep(1000);
      await page.mouse.move(link.x, link.y);
      await sleep(100);
      await page.mouse.click(link.x, link.y);
      const tab = await browser.waitForTarget((t) => t.url().includes('smoke-hover-link'), { timeout: 5000 }).catch(() => null);
      await (await tab?.page())?.close();
      const [open] = await page.evaluate(() => window.__opens.slice());
      checks['with no listener, a card\'s link opens a tab, past the popup blocker'] = tab !== null && open?.url === hoverLink && open?.active === true;
      console.log(`  .. a card's link: window.open ran with user activation ${open?.active}, in the task of ${open?.task}`);
      await page.bringToFront();
    }
    await page.mouse.move(800, 400);
    await sleep(500);

    setPhase('language server: dispose');
    const changes = server.count('textDocument/didChange');
    await page.evaluate(() => window.__connection.dispose());
    await server.until(() => server.count('exit') === 1 && server.connections() === 0);
    checks['dispose closes each document and shuts the server down'] =
      JSON.stringify(server.methods().slice(-4)) === JSON.stringify(['textDocument/didClose', 'textDocument/didClose', 'shutdown', 'exit']) &&
      server.connections() === 0;
    await page.mouse.click(600, 20);
    await page.keyboard.type('x');
    await sleep(300);
    checks['after dispose the editor goes on, and the server hears no more'] =
      (await value()) === 'open before.served.hostedx' && server.count('textDocument/didChange') === changes;

    setPhase('language server: a connection that drops');
    expectedConsole = [/^Aardink: the language server at .* closed the connection/];
    const reopened = server.count('textDocument/didOpen');
    await page.evaluate(async (serverUrl) => {
      window.__dropped = await window.__aardink.connectLanguageServer(serverUrl, 'toy-server');
    }, server.url);
    await server.until(() => server.count('textDocument/didOpen') === reopened + 2);
    await page.waitForFunction(() => window.__diagnostics.at(-1)?.some((m) => m.startsWith('from the server')), { timeout: 5000 }).catch(() => {});
    server.drop();
    await page.waitForFunction(() => window.__diagnostics.at(-1)?.length === 0, { timeout: 5000 })
      .then(() => { checks['a dropped server\'s squiggles go with it'] = true; })
      .catch(() => { checks['a dropped server\'s squiggles go with it'] = false; });
    // `.` was the server's trigger character, and went with it. The language's own stays.
    await page.keyboard.type('@');
    await sleep(800);
    await page.keyboard.press('Enter');
    await sleep(300);
    checks['after a drop the editor goes on with its language\'s own answers'] = (await value()) === 'open before.served.hostedx@hosted';
    await page.evaluate(() => window.__dropped.dispose());

    setPhase('language server: a connection that never comes up');
    expectedConsole = [/^Aardink: could not connect to the language server/, /WebSocket connection to .* failed/];
    await page.evaluate(async () => {
      window.__refused = await window.__aardink.connectLanguageServer('ws://127.0.0.1:1', 'toy-server');
      window.__badUrl = await window.__aardink.connectLanguageServer('ftp://example.invalid', 'toy-server');
    });
    await sleep(1000);
    await page.keyboard.type('y');
    await sleep(300);
    checks['a server that cannot be reached costs no editor'] = (await value()) === 'open before.served.hostedx@hostedy' &&
      await page.evaluate(() => {
        window.__refused.dispose();
        window.__badUrl.dispose();
        return true;
      });
    expectedConsole = [];
    await page.evaluate(() => {
      window.__second.dispose();
      document.getElementById('second').remove();
    });
  } finally {
    await server.close();
  }
  return checks;
}

/**
 * What a link does in text: a Ctrl+click on a URL opens a tab past the popup blocker with no
 * listener, and goes to the listener with one, and only then. The check that the blocker was on at
 * all comes first, or the tab would prove nothing.
 */
async function linkCheck(page) {
  setPhase('links');
  const checks = {};
  const target = `${url}smoke-link.txt`;
  checks['the popup blocker is on, so the next checks mean something'] = !(await page.evaluate(() => window.__popupWithoutGesture));
  await page.evaluate(async (mount, target) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    window.__opens = [];
    window.__smokeEditor = await window.__aardink.createEditor(eval(mount), undefined, { value: target });
  }, MOUNT, target);
  await sleep(500);
  const ctrlClick = async () => {
    await page.mouse.move(150, 12);
    await page.keyboard.down('Control');
    await page.mouse.move(151, 12);
    await sleep(100);
    await page.mouse.click(151, 12);
    await page.keyboard.up('Control');
    await sleep(300);
  };

  await ctrlClick();
  const tab = await browser.waitForTarget((t) => t.url().includes('smoke-link'), { timeout: 5000 }).catch(() => null);
  await (await tab?.page())?.close();
  const opens = await page.evaluate(() => window.__opens.slice());
  checks['Ctrl+click on a URL opens a tab, past the popup blocker'] = tab !== null;
  checks['the tab opens with noopener, in the user\'s gesture'] = opens.length === 1 && opens[0].url === target &&
    opens[0].target === '_blank' && opens[0].features === 'noopener' && opens[0].active === true;
  console.log(`  .. window.open ran with user activation ${opens[0]?.active}, in the task of ${opens[0]?.task}`);

  const listened = await page.evaluate(() => {
    window.__links = [];
    window.__stopLinks = window.__smokeEditor.onOpenLink((link) => window.__links.push(link));
    return true;
  });
  await ctrlClick();
  const links = await page.evaluate(() => window.__links.slice());
  checks['a listener gets the link, and no tab opens'] = listened && links.length === 1 && links[0] === target &&
    (await page.evaluate(() => window.__opens.length)) === 1;

  await page.evaluate(() => {
    window.__stopLinks.dispose();
    window.__stopLinks.dispose();
  });
  await ctrlClick();
  const again = await browser.waitForTarget((t) => t.url().includes('smoke-link'), { timeout: 5000 }).catch(() => null);
  await (await again?.page())?.close();
  checks['with the listener gone, a link opens a tab again'] = again !== null && (await page.evaluate(() => window.__opens.length)) === 2 &&
    (await page.evaluate(() => window.__links.length)) === 1;
  return checks;
}

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
  setPhase('touch tap');
  await mountEditor('line one\nline two');
  await page.touchscreen.tap(150, 36); // the second line
  await settle();
  await page.touchscreen.tap(150, 16); // then the first
  await settle();
  await page.keyboard.type('!');
  await settle();
  const [first, second] = (await value()).split('\n');
  checks['touch tap places the caret'] = first.includes('!') && !second.includes('!');

  setPhase('typing');
  await mountEditor('');
  await page.mouse.click(300, 20);
  await page.keyboard.type('abc');
  await settle();
  checks['typing'] = (await value()) === 'abc';

  const cdp = await page.createCDPSession();
  setPhase('IME composition');
  await cdp.send('Input.imeSetComposition', { text: 'に', selectionStart: 1, selectionEnd: 1 });
  await settle();
  await cdp.send('Input.insertText', { text: '日' });
  await settle();
  checks['IME composition'] = (await value()) === 'abc日';

  setPhase('paste');
  await page.evaluate(() => {
    const data = new DataTransfer();
    data.setData('text/plain', 'X\r\nY');
    document.activeElement.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await settle();
  checks['paste (CRLF to LF)'] = (await value()) === 'abc日X\nY';

  setPhase('backspace');
  await page.keyboard.press('Backspace');
  await settle();
  checks['backspace'] = (await value()) === 'abc日X\n';

  // The find panel takes the keys while it is open, and Escape gives them back to the text.
  setPhase('find panel focus');
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

  setPhase('showFind focus');
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
 * A registered language's trigger character (one the base language does not have) asks its
 * provider for completions when typed. Accepting one is left to the unit tests: this page has a
 * touch screen, so completions show in the strip above the keyboard, which Enter does not reach.
 */
async function triggerCheck(page) {
  setPhase('trigger characters');
  await page.evaluate(async (mount) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    window.__asked = [];
    const { createEditor, registerLanguage } = window.__aardink;
    await registerLanguage(
      { id: 'toy-trigger', grammar: { tokenizer: { root: [] } } },
      {
        triggerCharacters: ['{'],
        provideCompletionItems: (text, line, column) => {
          window.__asked.push(`${line}:${column}`);
          return [];
        },
      },
    );
    window.__smokeEditor = await createEditor(eval(mount), undefined, { value: '', language: 'toy-trigger' });
  }, MOUNT);
  await new Promise((r) => setTimeout(r, 500));
  await page.mouse.click(300, 20);
  await page.keyboard.type('{');
  await new Promise((r) => setTimeout(r, 500));
  const asked = await page.evaluate(() => window.__asked.slice());
  return { 'a registered trigger character asks for completions': asked.includes('1:2') };
}

/**
 * A grammar and a theme registered from JavaScript, in Monaco's shapes, colour the text: the
 * keyword `shout` in the theme's magenta, counted by pixel in a screenshot of the editor.
 */
async function grammarCheck(page) {
  setPhase('registered grammar');
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

/**
 * A language configuration from JavaScript, RegExp literals included: typing over a selection
 * surrounds it, and Enter follows a rule whose regex has a flag.
 */
async function configurationCheck(page) {
  setPhase('language configuration');
  const value = () => page.evaluate(() => window.__smokeEditor.getValue());
  const select = (line, startColumn, endColumn) => page.evaluate((line, startColumn, endColumn) => {
    window.__smokeEditor.setSelections([{
      selectionStartLineNumber: line, selectionStartColumn: startColumn, positionLineNumber: line, positionColumn: endColumn,
    }]);
  }, line, startColumn, endColumn);
  await page.evaluate(async (mount) => {
    window.__smokeEditor?.dispose();
    document.getElementById('check')?.remove();
    const { createEditor, registerLanguage } = window.__aardink;
    await registerLanguage({
      id: 'toy-configuration',
      grammar: { tokenizer: { root: [] } },
      configuration: {
        autoClosingPairs: [['(', ')']],
        onEnterRules: [{ beforeText: /^NOTE/i, action: { indent: 'none', appendText: '> ' } }],
      },
    });
    window.__smokeEditor = await createEditor(eval(mount), undefined, { value: 'note\nword', language: 'toy-configuration' });
  }, MOUNT);
  await new Promise((r) => setTimeout(r, 500));
  await page.mouse.click(300, 20);
  await select(2, 1, 5);
  await page.keyboard.type('(');
  await new Promise((r) => setTimeout(r, 300));
  const surrounded = await value();
  await select(1, 5, 5);
  await page.keyboard.press('Enter');
  await new Promise((r) => setTimeout(r, 300));
  const entered = await value();
  return {
    'a configuration surrounds a selection': surrounded === 'note\n(word)',
    'a configuration\'s RegExp rule decides Enter': entered === 'note\n> \n(word)',
  };
}

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 900, height: 500, hasTouch: true });
  watch(page);

  setPhase('in-page checks');
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__aardinkSmoke?.done, { timeout: 60000 });
  const report = await page.evaluate(() => window.__aardinkSmoke);
  // A few frames so the light theme and the diagnostic are painted before the screenshot.
  await new Promise((resolve) => setTimeout(resolve, 1000));
  setPhase('screenshot of the in-page checks');
  await page.screenshot({ path: 'dist/smoke.png' });

  console.log(`@aardarch/aardink-web ${report.version}`);
  for (const [name, ok] of Object.entries(report.checks)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  if (report.error) failures.push(`in-page error: ${report.error}`);
  const failedChecks = Object.entries(report.checks).filter(([, ok]) => !ok).map(([name]) => name);
  if (failedChecks.length) failures.push(`failed checks: ${failedChecks.join(', ')}`);
  if (Object.keys(report.checks).length < 5) failures.push('not every check ran');
  const interactions = await interactionChecks(page);
  for (const [name, ok] of Object.entries(interactions)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  const grammar = {
    ...(await triggerCheck(page)),
    ...(await grammarCheck(page)),
    ...(await configurationCheck(page)),
    ...(await codeLensTapCheck(page)),
  };
  Object.assign(interactions, grammar);
  for (const [name, ok] of Object.entries(grammar)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  setPhase('a page with no touch screen');
  const desktop = await desktopPage();
  const editorChecks = {
    ...(await dispatcherCheck(desktop)),
    ...(await inlayHintCheck(desktop)),
    ...(await stuckServerHintCheck(desktop)),
    ...(await codeLensCheck(desktop)),
    ...(await serverCodeLensCheck(desktop)),
    ...(await languageServerCheck(desktop)),
    ...(await linkCheck(desktop)),
  };
  Object.assign(interactions, editorChecks);
  for (const [name, ok] of Object.entries(editorChecks)) console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name}`);
  const failedInteractions = Object.entries(interactions).filter(([, ok]) => !ok).map(([name]) => name);
  if (failedInteractions.length) failures.push(`failed interactions: ${failedInteractions.join(', ')}`);
} finally {
  setPhase('closing Chrome');
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
