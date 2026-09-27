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
package com.aardarch.aardink.platform

import android.content.Context
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.TextRange
import com.aardarch.aardink.ui.input.EditorImeAdapter
import com.aardarch.aardink.ui.input.EditorImeGeometry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

internal actual val editorImeWindowed: Boolean = false

internal actual suspend fun PlatformTextInputSession.runEditorTextInput(adapter: EditorImeAdapter, geometry: EditorImeGeometry): Nothing {
    val view = view
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val cursor = CursorAnchorReporter(view, imm, adapter, geometry)
    adapter.listener = object : EditorImeAdapter.Listener {
        override fun restartInput() {
            imm.restartInput(view)
        }

        override fun updateSelection(selection: TextRange, composition: TextRange?) {
            imm.updateSelection(view, selection.min, selection.max, composition?.min ?: -1, composition?.max ?: -1)
            cursor.onChanged()
        }
    }
    try {
        coroutineScope {
            // A tap in the text asks for the keyboard; the first value is the current count, so a
            // request made just before this session started is not lost.
            launch {
                snapshotFlow { adapter.keyboardRequests }.collect { if (it > 0) imm.showSoftInput(view, 0) }
            }
            startInputMethod(
                PlatformTextInputMethodRequest { outAttributes ->
                    configure(outAttributes, adapter)
                    EditorInputConnection(adapter, view, cursor)
                },
            )
        }
    } finally {
        adapter.listener = null
    }
}

/** A multi-line text editor to the input method: corrections on, no fullscreen, Enter types a line break. */
private fun configure(outAttributes: EditorInfo, adapter: EditorImeAdapter) {
    outAttributes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
    val selection = adapter.documentSelection
    outAttributes.initialSelStart = selection.min
    outAttributes.initialSelEnd = selection.max
    if (Build.VERSION.SDK_INT >= 30) {
        // A slice around the selection, not the document: it travels to the input method in a Binder call.
        val text = adapter.text
        val start = max(0, selection.min - SURROUNDING_CHARS)
        val end = min(text.length, selection.max + SURROUNDING_CHARS)
        outAttributes.setInitialSurroundingSubText(text.subSequence(start, end), start)
    }
}

/**
 * The editor's `InputConnection`, written directly rather than on `BaseInputConnection`, after
 * Compose's own `StatelessInputConnection`: every edit goes into [adapter]'s batch, and reads see
 * the batch's edits so far. Reads return bounded slices, never the document, since each one
 * crosses into the input method's process.
 */
internal class EditorInputConnection(
    private val adapter: EditorImeAdapter,
    private val view: View,
    private val cursor: CursorAnchorReporter,
) : InputConnection {

    private var batchDepth = 0

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        adapter.beginBatch()
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth == 0) return false
        batchDepth--
        return adapter.endBatch()
    }

    override fun closeConnection() {
        while (batchDepth > 0) endBatchEdit()
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) adapter.edit { commitText(text, newCursorPosition) }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) adapter.edit { setComposingText(text, newCursorPosition) }
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        adapter.edit { setComposingRegion(start, end) }
        return true
    }

    override fun finishComposingText(): Boolean {
        adapter.edit { finishComposingText() }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        adapter.edit { deleteSurroundingText(max(0, beforeLength), max(0, afterLength)) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        adapter.edit { deleteSurroundingTextInCodePoints(max(0, beforeLength), max(0, afterLength)) }
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        adapter.edit { setSelection(start, end) }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = adapter.textBeforeSelection(n).toString()

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = adapter.textAfterSelection(n).toString()

    override fun getSelectedText(flags: Int): CharSequence? = adapter.selectedText()?.toString()

    override fun getCursorCapsMode(reqModes: Int): Int {
        val selection = adapter.documentSelection
        val text = adapter.text
        val start = max(0, selection.min - SURROUNDING_CHARS)
        return TextUtils.getCapsMode(text.subSequence(start, selection.min), selection.min - start, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val selection = adapter.documentSelection
        val text = adapter.text
        val start = max(0, selection.min - SURROUNDING_CHARS)
        val end = min(text.length, selection.max + SURROUNDING_CHARS)
        return ExtractedText().apply {
            this.text = text.subSequence(start, end).toString()
            startOffset = start
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = selection.min - start
            selectionEnd = selection.max - start
        }
    }

    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
        // Into Compose's own key dispatch, which reaches the focused editor's key handler.
        view.dispatchKeyEvent(event)
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean = true

    override fun performContextMenuAction(id: Int): Boolean {
        val code = when (id) {
            android.R.id.selectAll -> android.view.KeyEvent.KEYCODE_A
            android.R.id.cut -> android.view.KeyEvent.KEYCODE_CUT
            android.R.id.copy -> android.view.KeyEvent.KEYCODE_COPY
            android.R.id.paste -> android.view.KeyEvent.KEYCODE_PASTE
            else -> return false
        }
        val meta = if (id == android.R.id.selectAll) android.view.KeyEvent.META_CTRL_ON else 0
        view.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        view.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, code, 0, meta))
        return true
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        cursor.request(cursorUpdateMode)
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

    override fun clearMetaKeyStates(states: Int): Boolean = false

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true

    override fun getHandler(): Handler? = null

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}

/**
 * Tells the input method where the caret and the composing text are on screen, when it asks
 * (`requestCursorUpdates`): floating keyboards, handwriting and some input methods' candidate
 * windows follow it.
 */
internal class CursorAnchorReporter(
    private val view: View,
    private val imm: InputMethodManager,
    private val adapter: EditorImeAdapter,
    private val geometry: EditorImeGeometry,
) {
    private var monitoring = false

    fun request(mode: Int) {
        monitoring = mode and InputConnection.CURSOR_UPDATE_MONITOR != 0
        if (mode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) send()
    }

    fun onChanged() {
        if (monitoring) send()
    }

    private fun send() {
        val caret = geometry.caretRectInRoot() ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val selection = adapter.documentSelection
        val builder = CursorAnchorInfo.Builder()
            .setMatrix(Matrix().apply { setTranslate(location[0].toFloat(), location[1].toFloat()) })
            .setSelectionRange(selection.min, selection.max)
            .setInsertionMarkerLocation(caret.left, caret.top, caret.bottom, caret.bottom, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
        val composition = adapter.documentComposition
        if (composition != null) {
            builder.setComposingText(composition.min, adapter.text.subSequence(composition.min, composition.max))
            for (offset in composition.min until min(composition.max, composition.min + MAX_REPORTED_CHARACTERS)) {
                val bounds = geometry.characterBoundsInRoot(offset) ?: continue
                builder.addCharacterBounds(
                    offset,
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
                )
            }
        }
        imm.updateCursorAnchorInfo(view, builder.build())
    }
}

internal actual class TypedTextDecoder actual constructor() {
    // The accent of a dead key pressed before, waiting for the key it combines with.
    private var deadAccent = 0

    actual fun typedText(event: KeyEvent): String? {
        if (event.type != KeyEventType.KeyDown) return null
        val native = event.nativeKeyEvent
        if (native.isCtrlPressed || native.isMetaPressed) return null
        val unicode = native.unicodeChar
        if (unicode == 0) return null
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            deadAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return ""
        }
        val accent = deadAccent
        deadAccent = 0
        if (Character.isISOControl(unicode)) return null
        if (accent != 0) {
            val combined = KeyCharacterMap.getDeadChar(accent, unicode)
            // No combination (the accent then a letter it does not go on): both, as typed.
            return if (combined !=
                0
            ) {
                String(Character.toChars(combined))
            } else {
                String(Character.toChars(accent)) + String(Character.toChars(unicode))
            }
        }
        return String(Character.toChars(unicode))
    }
}

/** Characters on each side of the selection handed to the input method in one read. */
private const val SURROUNDING_CHARS = 2048

/** Composing characters whose bounds are reported; an input method needs the word, not a page. */
private const val MAX_REPORTED_CHARACTERS = 64
