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

// A small language server on a local WebSocket, one JSON-RPC message per frame, for smoke.mjs to
// point connectLanguageServer at. It answers just enough to see each feature arrive, and keeps every
// message it hears so a check can read the wire.

import { once } from 'node:events';
import { WebSocketServer } from 'ws';

const CAPABILITIES = {
  textDocumentSync: 2,
  completionProvider: { triggerCharacters: ['.'] },
  hoverProvider: true,
  definitionProvider: true,
};

/**
 * `hoverLink` is where the link in every hover goes. `capabilities` adds to what it declares,
 * `answers` to what it answers, by method, and requests for the `unanswered` methods get no answer
 * at all, as from a server stuck on them.
 */
export async function startLoopbackLsp(hoverLink, { capabilities = {}, answers: more = {}, unanswered = [] } = {}) {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(server, 'listening');
  const heard = [];
  const answers = {
    initialize: () => ({ capabilities: { ...CAPABILITIES, ...capabilities } }),
    'textDocument/completion': () => ({ isIncomplete: false, items: [{ label: 'served', insertText: 'served' }] }),
    'textDocument/hover': () => ({ contents: { kind: 'markdown', value: `from the [server](${hoverLink})` } }),
    shutdown: () => null,
    ...more,
  };
  server.on('connection', (socket) => {
    const send = (message) => socket.send(JSON.stringify({ jsonrpc: '2.0', ...message }));
    socket.on('message', (data) => {
      const message = JSON.parse(data.toString());
      heard.push(message);
      if (message.id !== undefined && !unanswered.includes(message.method)) {
        send({ id: message.id, result: answers[message.method]?.(message.params) ?? null });
      }
      if (message.method === 'textDocument/didOpen' || message.method === 'textDocument/didChange') {
        const { uri, version } = message.params.textDocument;
        const range = { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } };
        send({
          method: 'textDocument/publishDiagnostics',
          params: { uri, version, diagnostics: [{ range, severity: 2, message: `from the server, version ${version}` }] },
        });
      }
    });
  });
  return {
    url: `ws://127.0.0.1:${server.address().port}`,
    heard,
    methods: () => heard.map((message) => message.method),
    count: (method) => heard.filter((message) => message.method === method).length,
    /** True once `condition` holds, false if it has not after five seconds. */
    async until(condition) {
      const started = Date.now();
      while (Date.now() - started < 5000) {
        if (condition()) return true;
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
      return condition();
    },
    connections: () => server.clients.size,
    /** Every socket goes away with no goodbye, as a crashed server's does. */
    drop: () => server.clients.forEach((socket) => socket.terminate()),
    close: () => new Promise((resolve) => {
      server.clients.forEach((socket) => socket.terminate());
      server.close(resolve);
    }),
  };
}
