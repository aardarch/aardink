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
import { createEditor, version } from '@aardarch/aardink-web';

const report = (window.__aardinkSmoke = { done: false, checks: {}, error: null, version });
// checklist.mjs drives further editors through the same public API.
window.__aardink = { createEditor };

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

  editor.setValue('val answer = 42\n');
  await waitFor(() => changes.length > 0);
  report.checks.onChangeFired = changes.at(-1) === 'val answer = 42\n';
  report.checks.getValueAfterSet = editor.getValue() === 'val answer = 42\n';

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
} catch (e) {
  report.error = String(e && e.stack || e);
} finally {
  report.done = true;
}
