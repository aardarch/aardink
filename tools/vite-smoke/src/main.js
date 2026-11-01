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

// Uses the package exactly as aardflex-web-app's EditorPane will, and reports the outcome on
// window.__aardinkSmoke for smoke.mjs to assert on.
import { createEditor, defineTheme, registerLanguage, tokenize, version } from '@aardarch/aardink-web';

const report = (window.__aardinkSmoke = { done: false, checks: {}, error: null, version });
// checklist.mjs drives further editors through the same public API.
window.__aardink = { createEditor, defineTheme, registerLanguage };

function waitFor(condition, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const started = performance.now();
    (function poll() {
      if (condition()) return resolve();
      if (performance.now() - started > timeoutMs) return reject(new Error('timed out'));
      requestAnimationFrame(poll);
    })();
  });
}

try {
  const changes = [];
  const editor = await createEditor(document.getElementById('editor'), (text) => changes.push(text), {
    value: 'fun main() {\n    println("hello")\n}\n',
    language: 'kotlin',
    theme: 'vscode-dark',
    wordWrap: 'on',
  });

  report.checks.initialValue = editor.getValue().startsWith('fun main()');

  const events = [];
  editor.onDidChangeContent((text, change) => events.push(change));
  editor.setValue('val answer = 42\n');
  await waitFor(() => changes.length > 0);
  report.checks.onChangeFired = changes.at(-1) === 'val answer = 42\n';
  report.checks.changeEventSaysFlush = events.at(-1)?.isFlush === true && typeof events.at(-1)?.versionId === 'number';

  editor.setSelections([{ selectionStartLineNumber: 1, selectionStartColumn: 5, positionLineNumber: 1, positionColumn: 1 }]);
  const [selection] = editor.getSelections();
  report.checks.selectionsRoundTrip = selection.positionColumn === 1 && selection.startColumn === 1 && selection.endColumn === 5;
  report.checks.undoState = editor.canUndo() === false && typeof editor.getAlternativeVersionId() === 'number';
  report.checks.tokenizeExport = (await tokenize('val x = 1\nx', 'kotlin')).length === 2;
  editor.focus();
  await waitFor(() => document.activeElement && document.activeElement !== document.body, 3000).catch(() => {});
  report.checks.focus = !!document.activeElement && document.activeElement !== document.body;
  report.checks.getValueAfterSet = editor.getValue() === 'val answer = 42\n';

  // An assistant's rewrite: one undo step that keeps the history, reported as an edit, not a flush.
  const loadedAt = editor.getAlternativeVersionId();
  const loadedVersion = events.at(-1).versionId;
  const replaced = editor.replaceValue('val answer = 43\n');
  await waitFor(() => events.at(-1).versionId > loadedVersion);
  const replaceEvent = events.at(-1);
  const edited = editor.executeEdits('smoke', [
    { range: { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 4 }, text: 'var' },
  ]);
  report.checks.undoableReplace = replaced === true && edited === true && replaceEvent.isFlush === false &&
    editor.getValue() === 'var answer = 43\n' && editor.undo() && editor.undo() &&
    editor.getValue() === 'val answer = 42\n' && editor.getAlternativeVersionId() === loadedAt;
  editor.setValue('val answer = 42\n'); // no history again, for undoWithNoHistory below

  const unsubscribe = editor.onDidChangeContent(() => {});
  unsubscribe();

  editor.updateOptions({ theme: 'vscode-light', readOnly: true });
  editor.setDiagnostics([{ line: 1, startColumn: 5, endColumn: 11, message: 'demo', severity: 'warning' }]);
  editor.revealPosition(1, 1);
  report.checks.undoWithNoHistory = editor.undo() === false;

  // Leave it mounted for the screenshot, then prove dispose is idempotent on a second editor.
  const second = document.createElement('div');
  document.body.append(second);
  const other = await createEditor(second, undefined, { value: 'x' });
  other.dispose();
  other.dispose();
  second.remove();
  report.checks.disposeIdempotent = true;

  // A registered language's diagnostics, asked again by revalidate() without an edit.
  let unknown = 'a';
  await registerLanguage(
    { id: 'smoke-revalidate', grammar: { tokenizer: { root: [] } } },
    { provideDiagnostics: () => [{ line: 1, startColumn: 1, endColumn: 2, message: `unknown ${unknown}`, severity: 'warning' }] },
  );
  const third = document.createElement('div');
  third.style.cssText = 'width:300px;height:60px';
  document.body.append(third);
  const validated = await createEditor(third, undefined, { value: 'a', language: 'smoke-revalidate' });
  const messages = [];
  validated.onDidChangeDiagnostics((markers) => messages.push(markers[0]?.message));
  await waitFor(() => messages.length > 0);
  unknown = 'b';
  validated.revalidate();
  await waitFor(() => messages.at(-1) === 'unknown b', 3000).catch(() => {});
  report.checks.revalidate = messages.at(-1) === 'unknown b';
  validated.dispose();
  third.remove();

  // 0.8: symbols, colours, decorations, the view and owned markers, through a registered language.
  await registerLanguage(
    { id: 'smoke-08', grammar: { tokenizer: { root: [] } } },
    {
      provideDocumentSymbols: () => [
        { name: 'outer', kind: 18, range: { startLineNumber: 1, startColumn: 1, endLineNumber: 3, endColumn: 2 } },
      ],
      provideDocumentColors: () => [
        { range: { startLineNumber: 2, startColumn: 1, endLineNumber: 2, endColumn: 8 }, color: { red: 1, green: 0, blue: 0, alpha: 1 } },
      ],
    },
  );
  const fourth = document.createElement('div');
  fourth.style.cssText = 'width:300px;height:80px';
  document.body.append(fourth);
  const structured = await createEditor(fourth, undefined, {
    value: '{\n#ff0000\n}\n' + 'x\n'.repeat(40),
    language: 'smoke-08',
    colorDecorators: true,
    overviewRulerLanes: 3,
  });
  await waitFor(() => structured.getDocumentSymbols().length > 0, 5000).catch(() => {});
  report.checks.documentSymbols = structured.getDocumentSymbols()[0]?.name === 'outer' &&
    structured.getSymbolPathAt({ lineNumber: 2, column: 2 }).length === 1;
  const ids = structured.deltaDecorations([], [
    { range: { startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 2 }, options: { backgroundColor: '#00ff0040', isWholeLine: true } },
  ]);
  const collection = structured.createDecorationsCollection([
    { range: { startLineNumber: 2, startColumn: 1, endLineNumber: 2, endColumn: 4 }, options: { underlineColor: '#ff0' } },
  ]);
  report.checks.decorations = ids.length === 1 && collection.length === 1 && collection.getRanges()[0]?.startLineNumber === 2 &&
    structured.deltaDecorations(ids, []).length === 0;
  structured.setModelMarkers('linter', [{ line: 1, startColumn: 1, endColumn: 2, message: 'owned', severity: 'info' }]);
  const scrolls = [];
  structured.onDidScrollChange((event) => scrolls.push(event.scrollTop));
  structured.setScrollTop(60);
  await waitFor(() => structured.getScrollTop() > 0, 3000).catch(() => {});
  report.checks.scrollApi = structured.getScrollTop() > 0 && structured.getVisibleRanges()[0]?.startLineNumber > 1 &&
    scrolls.length > 0 && structured.getScrollHeight() > 60;
  structured.layout();
  structured.dispose();
  fourth.remove();
} catch (e) {
  report.error = String(e && e.stack || e);
} finally {
  report.done = true;
}
