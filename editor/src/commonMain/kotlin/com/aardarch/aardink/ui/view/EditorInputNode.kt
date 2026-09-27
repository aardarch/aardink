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
package com.aardarch.aardink.ui.view

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.platform.runEditorTextInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Focus, keys, text input and semantics for the editor's text area, as one node. Put it before a
 * focus target: `Modifier.then(EditorInputElement(controller)).focusRequester(...).focusTarget()`.
 */
internal data class EditorInputElement(val controller: EditorController) : ModifierNodeElement<EditorInputNode>() {
    override fun create(): EditorInputNode = EditorInputNode(controller)

    override fun update(node: EditorInputNode) = node.update(controller)

    override fun InspectorInfo.inspectableProperties() {
        name = "editorInput"
    }
}

/**
 * While focused (and editable), holds a text input session: [runEditorTextInput] connects the
 * platform's input method to [EditorController.ime], and a watcher tells the input method about
 * every change it did not make. Key events go to [EditorController.onKeyEvent].
 *
 * For accessibility and UI tests it is an editable text: the lines around the caret (the whole
 * document would be read out, and copied, on every change) with the selection in them, and the
 * actions a text field has.
 */
internal class EditorInputNode(private var controller: EditorController) :
    Modifier.Node(),
    FocusEventModifierNode,
    KeyInputModifierNode,
    PlatformTextInputModifierNode,
    SemanticsModifierNode,
    GlobalPositionAwareModifierNode,
    ObserverModifierNode {

    private var session: Job? = null
    private var focused = false

    override fun onAttach() {
        observe()
    }

    override fun onDetach() {
        stopInput()
        controller.focused = false
    }

    fun update(controller: EditorController) {
        if (controller === this.controller) return
        stopInput()
        this.controller.focused = false
        this.controller = controller
        controller.focused = focused
        syncSession()
        observe()
        invalidateSemantics()
    }

    /** Re-runs [syncSession] and the semantics when read-only, the text, the selection or focus change. */
    private fun observe() {
        observeReads {
            controller.readOnly
            controller.state.textVersion
            controller.state.selection
        }
    }

    override fun onObservedReadsChanged() {
        syncSession()
        invalidateSemantics()
        observe()
    }

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.isFocused
        if (now == focused) return
        focused = now
        controller.focused = now
        syncSession()
        invalidateSemantics()
    }

    private fun syncSession() {
        if (isAttached && focused && !controller.readOnly) startInput() else stopInput()
    }

    private fun startInput() {
        if (session != null) return
        val controller = controller
        session = coroutineScope.launch {
            establishTextInputSession {
                launch {
                    snapshotFlow { Triple(controller.state.textVersion, controller.state.selection, controller.ime.composition) }
                        .collect { controller.ime.onEditorChanged() }
                }
                runEditorTextInput(controller.ime, controller)
            }
        }
    }

    private fun stopInput() {
        session?.cancel()
        session = null
        controller.ime.finishComposition()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = controller.onKeyEvent(event)

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        controller.coordinates = coordinates
    }

    override val shouldMergeDescendantSemantics: Boolean get() = false

    override fun SemanticsPropertyReceiver.applySemantics() {
        val controller = controller
        val state = controller.state
        val document = state.document
        val selection = state.selection
        val caretLine = document.offsetToLineCol(selection.end).first
        val first = max(0, caretLine - SEMANTICS_LINES)
        val last = min(document.lineCount - 1, caretLine + SEMANTICS_LINES)
        val start = document.lineStart(first)
        val end = document.lineEnd(last)
        val length = end - start
        editableText = AnnotatedString(document.subSequence(start, end).toString())
        textSelectionRange = TextRange((selection.start - start).coerceIn(0, length), (selection.end - start).coerceIn(0, length))
        contentDescription = "Code editor"
        stateDescription = "Line ${caretLine + 1} of ${document.lineCount}"
        focused = this@EditorInputNode.focused
        isEditable = !controller.readOnly
        requestFocus {
            controller.requestFocus()
            true
        }
        onClick {
            controller.requestFocus()
            true
        }
        setSelection { selectionStart, selectionEnd, _ ->
            state.selection =
                TextRange((start + selectionStart).coerceIn(0, document.length), (start + selectionEnd).coerceIn(0, document.length))
            true
        }
        copyText {
            controller.copyToClipboard()
            true
        }
        if (!controller.readOnly) {
            setText { text ->
                state.applyEdit(0, document.length, text.text, TextRange(text.length))
                true
            }
            insertTextAtCursor { text ->
                controller.type(text.text)
                true
            }
            cutText {
                controller.cutToClipboard()
                true
            }
            pasteText {
                controller.pasteFromClipboard()
                true
            }
        }
    }

    private companion object {
        /** Lines on each side of the caret that accessibility services and tests see. */
        const val SEMANTICS_LINES = 50
    }
}
