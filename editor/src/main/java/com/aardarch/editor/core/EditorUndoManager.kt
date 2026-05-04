package com.aardarch.editor.core

/**
 * Operation-log undo/redo manager.
 *
 * Stores [EditOperation] records instead of full-text snapshots, capping memory at
 * O(operations) rather than O(operations × document_size).
 *
 * Consecutive single-character insertions at adjacent offsets are merged into one batch (matching
 * the heuristic VS Code uses: batch until a word boundary or non-contiguous edit is detected).
 */
class EditorUndoManager(private val maxOperations: Int = 200) {

    sealed class EditOperation {
        /**
         * Characters [text] were inserted at [offset].
         * Undo: delete [text.length] chars starting at [offset].
         */
        data class Insert(val offset: Int, val text: String) : EditOperation()

        /**
         * [length] characters (originally [deletedText]) were deleted starting at [offset].
         * Undo: re-insert [deletedText] at [offset].
         */
        data class Delete(val offset: Int, val length: Int, val deletedText: String) : EditOperation()

        /**
         * A batch of operations applied as one logical edit (e.g. paste or format).
         * Undo reverses all contained operations in reverse order.
         */
        data class Batch(val operations: List<EditOperation>) : EditOperation()
    }

    private val undoStack = ArrayDeque<EditOperation>()
    private val redoStack = ArrayDeque<EditOperation>()

    // Batching state for single-char contiguous insertions
    private var pendingInsertOffset: Int = -1
    private var pendingInsertText = StringBuilder()

    val canUndo: Boolean get() = undoStack.isNotEmpty() || pendingInsertText.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Records that [text] was inserted at [offset].
     * Single-character insertions at adjacent positions are batched together.
     */
    fun recordInsert(offset: Int, text: String) {
        redoStack.clear()
        val isSingleChar = text.length == 1 && !text[0].isWhitespace()
        val isContiguous = pendingInsertOffset >= 0 && offset == pendingInsertOffset + pendingInsertText.length

        if (isSingleChar && isContiguous) {
            pendingInsertText.append(text)
        } else {
            flushPendingInsert()
            if (isSingleChar) {
                pendingInsertOffset = offset
                pendingInsertText.append(text)
            } else {
                push(EditOperation.Insert(offset, text))
            }
        }
    }

    /**
     * Records that [length] characters ([deletedText]) were deleted starting at [offset].
     */
    fun recordDelete(offset: Int, length: Int, deletedText: String) {
        flushPendingInsert()
        redoStack.clear()
        push(EditOperation.Delete(offset, length, deletedText))
    }

    /**
     * Records multiple operations as a single undoable batch (e.g. format-on-save, paste).
     */
    fun recordBatch(operations: List<EditOperation>) {
        flushPendingInsert()
        redoStack.clear()
        push(EditOperation.Batch(operations))
    }

    /** Commits any pending batched insert to the undo stack. Call before any non-insert operation. */
    fun flushPendingInsert() {
        if (pendingInsertText.isNotEmpty()) {
            push(EditOperation.Insert(pendingInsertOffset, pendingInsertText.toString()))
            pendingInsertOffset = -1
            pendingInsertText.clear()
        }
    }

    // ── Undo / Redo ───────────────────────────────────────────────────────────

    /**
     * Returns the operation to reverse (and records its inverse on the redo stack), or null if
     * there is nothing to undo.
     *
     * The caller is responsible for applying the returned operation to the [CodeDocument].
     * Use [inverseOf] to get the operation that should be applied.
     */
    fun undo(): EditOperation? {
        flushPendingInsert()
        if (undoStack.isEmpty()) return null
        val op = undoStack.removeLast()
        redoStack.addLast(op)
        return op
    }

    /**
     * Returns the operation to re-apply (and records it back on the undo stack), or null.
     */
    fun redo(): EditOperation? {
        if (redoStack.isEmpty()) return null
        val op = redoStack.removeLast()
        undoStack.addLast(op)
        return op
    }

    /**
     * Returns the inverse of [op] — i.e. the operation that should be applied to the document
     * when undoing [op].
     */
    fun inverseOf(op: EditOperation): EditOperation = when (op) {
        is EditOperation.Insert -> EditOperation.Delete(op.offset, op.text.length, op.text)
        is EditOperation.Delete -> EditOperation.Insert(op.offset, op.deletedText)
        is EditOperation.Batch -> EditOperation.Batch(op.operations.reversed().map { inverseOf(it) })
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        pendingInsertOffset = -1
        pendingInsertText.clear()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun push(op: EditOperation) {
        undoStack.addLast(op)
        if (undoStack.size > maxOperations) undoStack.removeFirst()
    }
}
