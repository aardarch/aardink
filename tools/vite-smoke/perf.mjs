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
//   pnpm build && node perf.mjs [--gate] [--keys 30] [--lines 5000]
//
// Two more runs measure the same things with what the 0.9 inline layout and a language server add,
// each to a file of its own, and neither is part of --gate yet:
//
//   node perf.mjs --hints [--lines 5000]      dist/perf-hints.json
//   node perf.mjs --semantic [--lines 5000]   dist/perf-semantic.json
//
// --hints types in a registered language whose provideInlayHints answers 3 hints for each line it
// is asked about and whose provideCodeLenses puts a lens above every 20th line, with inlayHints and
// codeLens on, through the npm package's dispatcher. Its grammar is a small Kotlin one of its own
// (a registered language needs one), so compare it with a run of itself, not with dist/perf.json.
// --semantic connects the built-in Kotlin language to a WebSocket language server in this process
// that answers textDocument/semanticTokens/full with a token for every word of the document, with
// semanticHighlighting on. Its first answer is held until the editor is idle, and the frames and
// long tasks while it lands are measured on their own. A run with --lines other than 5,000 adds
// the size to the file name.

import { writeFileSync } from 'node:fs';
import { freshPage, largeKotlin, MOUNT, percentile, startHarness } from './harness.mjs';
import { startLoopbackLsp } from './loopback-lsp.mjs';

const args = process.argv.slice(2);
const gate = args.includes('--gate');
const keyCount = Number(args[args.indexOf('--keys') + 1]) || 30;
// The gates are defined on the 5,000-line reference document; other sizes are for comparison.
const lineCount = Number(args[args.indexOf('--lines') + 1]) || 5000;
const mode = args.includes('--hints') ? 'hints' : args.includes('--semantic') ? 'semantic' : null;
if (args.includes('--hints') && args.includes('--semantic')) throw new Error('--hints and --semantic are separate runs');
if (mode && gate) throw new Error(`--${mode} is not part of --gate yet`);

// Target: what 0.6.0 aims for, recorded in WEB_INTEGRATION.md. Gate: what CI enforces with
// --gate, looser to absorb shared-runner noise. null = informational only.
const LIMITS = {
  mountToIdleMs: { target: 200, gate: 400 },
  keyToIdleP50Ms: { target: 16, gate: null },
  keyToIdleP95Ms: { target: 33, gate: 50 },
  keyToChangeP95Ms: { target: 20, gate: null },
  setValueToIdleMs: { target: 150, gate: null },
  scrollFrameP95Ms: { target: 20, gate: null }, // 60 Hz with jitter, no dropped frames
  // Main-thread time per frame the minimap adds while scrolling (Chrome's TaskDuration, with minus without).
  minimapFrameCostMs: { target: 2, gate: 2 },
  minimapScrollFrameP95Ms: { target: 20, gate: null },
  droppedKeysSequential: { target: 0, gate: 0, unit: 'keys' },
  droppedKeysAtBurst: { target: 0, gate: 0, unit: 'keys' },
};

// ── --semantic: the language server, and the tokens it answers with ─────────────
const SEMANTIC_TYPES = ['function', 'type', 'parameter', 'property', 'variable'];

/** The text of `uri` as the server has heard it, kept up message by message. */
function mirrorOf(server) {
  const texts = new Map();
  let read = 0;
  const offsetOf = (text, { line, character }) => {
    let at = 0;
    for (let i = 0; i < line; i++) at = text.indexOf('\n', at) + 1;
    return at + character;
  };
  return (uri) => {
    for (; read < server.heard.length; read++) {
      const message = server.heard[read];
      const document = message.params?.textDocument;
      if (message.method === 'textDocument/didOpen') texts.set(document.uri, document.text);
      if (message.method !== 'textDocument/didChange' || !texts.has(document.uri)) continue;
      let text = texts.get(document.uri);
      for (const change of message.params.contentChanges) {
        text = change.range
          ? text.slice(0, offsetOf(text, change.range.start)) + change.text + text.slice(offsetOf(text, change.range.end))
          : change.text;
      }
      texts.set(document.uri, text);
    }
    return texts.get(uri) ?? '';
  };
}

/** A semantic token for every word of `text`, encoded as LSP's relative integers. */
function semanticTokensOf(text) {
  const data = [];
  let previousLine = 0;
  text.split('\n').forEach((line, number) => {
    let previousStart = 0;
    let index = 0;
    for (const word of line.matchAll(/[A-Za-z_]\w*/g)) {
      data.push(number - previousLine, number === previousLine ? word.index - previousStart : word.index, word[0].length);
      data.push(index++ % SEMANTIC_TYPES.length, 0);
      previousLine = number;
      previousStart = word.index;
    }
  });
  return data;
}

const semantic = { server: null, release: () => {}, answers: [] };
if (mode === 'semantic') {
  let releaseFirst;
  const firstHeld = new Promise((resolve) => { releaseFirst = resolve; });
  semantic.release = releaseFirst;
  semantic.server = await startLoopbackLsp('', {
    diagnostics: false,
    // Nothing but sync and semantic tokens: typing in a comment must not ask for completions.
    capabilities: {
      completionProvider: undefined,
      hoverProvider: undefined,
      definitionProvider: undefined,
      semanticTokensProvider: { legend: { tokenTypes: SEMANTIC_TYPES, tokenModifiers: [] }, full: true },
    },
    answers: {
      'textDocument/semanticTokens/full': async (params) => {
        if (semantic.answers.length === 0) await firstHeld;
        const data = semanticTokensOf(semantic.mirror(params.textDocument.uri));
        semantic.answers.push({ tokens: data.length / 5, kb: Math.round(JSON.stringify(data).length / 1024) });
        return { data };
      },
    },
  });
  semantic.mirror = mirrorOf(semantic.server);
}

const harness = await startHarness();
const results = {};
const text = largeKotlin(lineCount);

try {
  const page = await freshPage(harness);
  results.chrome = await harness.browser.version();
  results.package = await page.evaluate(() => window.__aardinkSmoke.version);
  results.documentKb = Math.round(text.length / 1024);
  results.keys = keyCount;
  if (mode) results.mode = mode;

  // What the editor is mounted as: the reference run's built-in Kotlin, or what a mode adds to it.
  let language = 'kotlin';
  let modeOptions = {};
  if (mode === 'hints') {
    language = 'perf-hints';
    modeOptions = { inlayHints: true, codeLens: true };
    await page.evaluate(async () => {
      window.__perfHints = { hintCalls: 0, hints: 0, lensCalls: 0, lenses: 0 };
      await window.__aardink.registerLanguage({
        id: 'perf-hints',
        grammar: {
          keywords: ['package', 'data', 'class', 'val', 'fun'],
          tokenizer: {
            root: [
              [/\/\/.*/, 'comment'],
              [/\/\*/, 'comment', '@comment'],
              [/"([^"\\]|\\.)*"/, 'string'],
              [/0[xX][0-9a-fA-F]+|\d+/, 'number'],
              [/[A-Za-z_]\w*/, { cases: { '@keywords': 'keyword', '@default': 'identifier' } }],
            ],
            comment: [[/\*\//, 'comment', '@pop'], [/./, 'comment']],
          },
        },
      }, {
        // Three on each line asked about: at its start, in its middle and at its end.
        provideInlayHints: (text, range) => {
          const lines = text.split('\n');
          const hints = [];
          for (let line = range.startLineNumber; line <= Math.min(range.endLineNumber, lines.length); line++) {
            const length = lines[line - 1].length;
            for (const column of [1, Math.floor(length / 2) + 1, length + 1]) hints.push({ position: { lineNumber: line, column }, label: ': Int' });
          }
          window.__perfHints.hintCalls++;
          window.__perfHints.hints += hints.length;
          return hints;
        },
        // A lens above every 20th line, the first on line 20, below the comment on line 6 that is typed on.
        provideCodeLenses: (text) => {
          const lenses = [];
          const lineCount = text.split('\n').length;
          for (let line = 20; line <= lineCount; line += 20) {
            const range = { startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 };
            lenses.push({ range, command: { id: 'perf.lens', title: `${line} references` } });
          }
          window.__perfHints.lensCalls++;
          window.__perfHints.lenses += lenses.length;
          return lenses;
        },
        runCodeLensCommand: () => {},
      });
    });
  } else if (mode === 'semantic') {
    modeOptions = { semanticHighlighting: true };
    await page.evaluate(async (url) => {
      window.__perfServer = await window.__aardink.connectLanguageServer(url, 'kotlin');
    }, semantic.server.url);
  }

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
  results.mountToIdleMs = await page.evaluate(async (text, mount, language, modeOptions) => {
    const onChange = () => {
      const pending = window.__perf.keys.find((k) => k.change === null && !k.dropped);
      if (pending) pending.change = performance.now() - pending.ts;
    };
    const start = performance.now();
    window.__perfEditor = await window.__aardink.createEditor(eval(mount), onChange, { value: text, language, ...modeOptions });
    return window.__perf.settled(start);
  }, text, MOUNT, language, modeOptions);

  // Let the initial tokenization finish (it runs in chunks above 64 KB) before typing.
  await new Promise((r) => setTimeout(r, 3000));

  // ── --semantic: the first answer landing, measured on its own ───────────────
  // Every frame and long task from the moment it is let go until the editor is idle with its
  // colours on screen. Long tasks are Chrome's, of 50 ms and more; the longest frame shows a
  // slice of work that went over its budget even when it stayed under that.
  if (mode === 'semantic') {
    const asked = await semantic.server.until(() => semantic.server.count('textDocument/semanticTokens/full') > 0);
    if (!asked && text.length <= 2 * 1024 * 1024) throw new Error('The editor never asked the server for semantic tokens');
    results.semanticAskedOnMount = asked;
    if (asked) {
      const before = await page.screenshot({ encoding: 'base64' });
      await page.evaluate(() => {
        const landing = { from: performance.now(), frames: [], longTasks: [], recording: true };
        window.__perf.landing = landing;
        new PerformanceObserver((list) => list.getEntries().forEach((e) => {
          if (landing.recording && e.startTime >= landing.from) landing.longTasks.push(e.duration);
        })).observe({ type: 'longtask' });
        let last = landing.from;
        requestAnimationFrame(function tick(now) {
          landing.frames.push(now - last);
          last = now;
          if (landing.recording) requestAnimationFrame(tick);
        });
      });
      semantic.release();
      // Shown is when the picture changes; the screenshots between cost the page little, and the
      // time to idle is counted from when it is let go, not from there.
      const started = Date.now();
      let shown = false;
      while (!shown && Date.now() - started < 60000) {
        await new Promise((r) => setTimeout(r, 250));
        shown = (await page.screenshot({ encoding: 'base64' })) !== before;
      }
      if (!shown) throw new Error('The semantic tokens never showed');
      const landed = await page.evaluate(async () => {
        const landing = window.__perf.landing;
        const idle = await window.__perf.settled(landing.from);
        landing.recording = false;
        return { idle, longestTask: Math.max(0, ...landing.longTasks), longestFrame: Math.max(0, ...landing.frames.slice(1)) };
      });
      results.semanticLandToIdleMs = landed.idle;
      results.semanticLandLongestTaskMs = landed.longestTask;
      results.semanticLandLongestFrameMs = landed.longestFrame;
      results.semanticAnswerTokens = semantic.answers[0].tokens;
      results.semanticAnswerKb = semantic.answers[0].kb;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }

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
  // A mode's runs must type where the reference run does, on the comment, or a lens moving it
  // would have them measure other work. A fixed pixel only finds it if nothing moved it.
  const TYPED_LINE = 6;
  const lineAt = (number) => page.evaluate((n) => window.__perfEditor.getValue().split('\n')[n - 1], number);
  const typedLineBefore = mode ? await lineAt(TYPED_LINE) : null;
  if (mode) {
    const caret = await page.evaluate(() => window.__perfEditor.getPosition());
    if (caret.lineNumber !== TYPED_LINE || !typedLineBefore.trimStart().startsWith('//')) {
      throw new Error(`The caret is on line ${caret.lineNumber}, not the comment on line ${TYPED_LINE}: '${typedLineBefore}'`);
    }
  }
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
  if (mode) {
    const typed = Array.from({ length: keyCount }, (_, i) => letters[i % letters.length]).join('');
    if ((await lineAt(TYPED_LINE)) !== typedLineBefore + typed) {
      throw new Error(`The typed text is not at the end of line ${TYPED_LINE}: '${await lineAt(TYPED_LINE)}'`);
    }
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
  // Also the main thread's busy time per frame (Chrome's TaskDuration), to weigh the minimap.
  const cdp = await page.createCDPSession();
  await cdp.send('Performance.enable');
  const taskSeconds = async () => (await cdp.send('Performance.getMetrics')).metrics.find((m) => m.name === 'TaskDuration').value;
  const scrollThrough = async () => {
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
    const busyBefore = await taskSeconds();
    await page.mouse.move(400, 300);
    for (let i = 0; i < 90; i++) {
      await page.mouse.wheel({ deltaY: 120 });
      await new Promise((r) => setTimeout(r, 16));
    }
    const frames = await page.evaluate(() => {
      window.__perf.recording = false;
      return window.__perf.frames.slice(1); // the first delta is the loop's start
    });
    return { frames, busyPerFrameMs: ((await taskSeconds()) - busyBefore) * 1000 / frames.length };
  };
  const plain = await scrollThrough();
  results.scrollFrameP95Ms = percentile([...plain.frames].sort((a, b) => a - b), 95);

  // ── The minimap: what it adds to each frame of the same scroll ──────────────
  await page.evaluate(() => window.__perfEditor.revealPosition(1, 1));
  await page.evaluate(() => window.__perfEditor.updateOptions({ minimap: { enabled: true } }));
  await new Promise((r) => setTimeout(r, 1500));
  const withMinimap = await scrollThrough();
  results.minimapFrameCostMs = Math.max(0, withMinimap.busyPerFrameMs - plain.busyPerFrameMs);
  results.minimapScrollFrameP95Ms = percentile([...withMinimap.frames].sort((a, b) => a - b), 95);
  results.longestTaskMs = await page.evaluate(() => Math.max(0, ...window.__perf.longTasks));
  if (mode === 'hints') {
    Object.assign(results, await page.evaluate(() => window.__perfHints));
    if (results.hintCalls === 0 || results.lensCalls === 0) throw new Error('The editor never asked for hints or lenses');
  }
  if (mode === 'semantic') results.semanticRequests = semantic.server.count('textDocument/semanticTokens/full');
  await page.close();
} finally {
  await harness.close();
  await semantic.server?.close();
}

const failures = [];
console.log(`@aardarch/aardink-web ${results.package} in ${results.chrome}, ${results.documentKb} KB Kotlin, ${results.keys} keys`);
for (const [name, limit] of Object.entries(LIMITS)) {
  const value = results[name];
  const unit = limit.unit ?? 'ms';
  const verdict = value <= limit.target ? 'meets target' : limit.gate !== null && value > limit.gate ? 'OVER GATE' : 'over target';
  if (limit.gate !== null && value > limit.gate) failures.push(name);
  console.log(`  ${name.padEnd(22)} ${value.toFixed(1).padStart(8)} ${unit.padEnd(4)}  target ${limit.target}${limit.gate !== null ? `, gate ${limit.gate}` : ''}   ${verdict}`);
}
console.log(`  ${'longestTaskMs'.padEnd(22)} ${results.longestTaskMs.toFixed(1).padStart(8)} ms`);
if (mode) {
  const extra = mode === 'hints'
    ? ['hintCalls', 'hints', 'lensCalls', 'lenses']
    : ['semanticLandToIdleMs', 'semanticLandLongestTaskMs', 'semanticLandLongestFrameMs', 'semanticAnswerTokens', 'semanticAnswerKb', 'semanticRequests'];
  for (const name of extra) {
    if (results[name] !== undefined) console.log(`  ${name.padEnd(26)} ${Number(results[name]).toFixed(name.endsWith('Ms') ? 1 : 0).padStart(8)}`);
  }
  if (mode === 'semantic' && !results.semanticAskedOnMount) console.log('  the document is over the 2 MiB analysis limit, so nothing was asked for and nothing landed');
  const output = `dist/perf-${mode}${lineCount === 5000 ? '' : `-${lineCount}`}.json`;
  writeFileSync(output, `${JSON.stringify({ ...results, limits: LIMITS, date: new Date().toISOString() }, null, 2)}\n`);
  console.log(`Wrote ${output} (not gated)`);
} else {
  writeFileSync('dist/perf.json', `${JSON.stringify({ ...results, limits: LIMITS, date: new Date().toISOString() }, null, 2)}\n`);
  console.log('Wrote dist/perf.json');
}

if (gate && failures.length) {
  console.error(`Performance gate failed: ${failures.join(', ')}`);
  process.exit(1);
}
