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

/** Editor options. Every field is optional; `updateOptions` changes only the ones you pass. */
export interface AardinkOptions {
  /** Initial text. Only read by `createEditor`. */
  value?: string;
  /** A language id: kotlin, typescript, json, toml, xml, html, css, markdown, plaintext. */
  language?: string;
  /** vscode-dark, vscode-light, material-dark, material-light, midnight-ocean, solarized-dark. */
  theme?: string;
  /** In CSS pixels. */
  fontSize?: number;
  wordWrap?: 'on' | 'off';
  readOnly?: boolean;
  showGutter?: boolean;
  showLineNumbers?: boolean;
  showFoldMarkers?: boolean;
}

/** Monaco-marker shape: 1-based line and columns, `endColumn` exclusive. */
export interface AardinkDiagnostic {
  line: number;
  startColumn: number;
  endColumn: number;
  message: string;
  severity: 'error' | 'warning' | 'info';
}

export interface AardinkEditor {
  getValue(): string;
  /** Replaces the whole text and clears undo history. Fires the change listeners. */
  setValue(text: string): void;
  updateOptions(options: Partial<AardinkOptions>): void;
  /** Full text after every change, at most once per frame. Returns an unsubscribe function. */
  onDidChangeContent(listener: (text: string) => void): () => void;
  /** 1-based caret position. Returns an unsubscribe function. */
  onDidChangeCursor(listener: (line: number, column: number) => void): () => void;
  setDiagnostics(diagnostics: AardinkDiagnostic[]): void;
  revealPosition(line: number, column: number): void;
  showFind(): void;
  undo(): boolean;
  redo(): boolean;
  /** Stops the editor. Safe to call twice. Remove or reuse the container yourself. */
  dispose(): void;
}

/**
 * Mounts an editor that fills `container`. Loads the WebAssembly module on first use (see
 * `preloadAardink`). `onChange` is registered as the first content listener.
 */
export function createEditor(
  container: HTMLElement,
  onChange?: (text: string) => void,
  options?: AardinkOptions,
): Promise<AardinkEditor>;

/** Starts loading the WebAssembly module without mounting anything. */
export function preloadAardink(): Promise<void>;

/** The Aardink version this package was built from. */
export const version: string;
