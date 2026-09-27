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
package com.aardarch.aardink.core

/**
 * What the last change to a [CodeEditorState]'s document was, as in Monaco's content-change event
 * (`isUndoing`, `isRedoing`, `isFlush`). A host can skip work on undo, such as formatting on type.
 */
enum class EditChangeKind {
    /** Typing, or a programmatic edit ([CodeEditorState.applyEdit] and friends). */
    Edit,

    /** [CodeEditorState.undo]. */
    Undo,

    /** [CodeEditorState.redo]. */
    Redo,

    /** [CodeEditorState.loadText]: the whole document replaced, history cleared. */
    Flush,
}
