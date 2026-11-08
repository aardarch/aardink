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

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aardarch.aardink.core.edit.TextNavigator
import com.aardarch.aardink.core.edit.TypingRules
import com.aardarch.aardink.core.text.DocumentChange
import com.aardarch.aardink.core.text.DocumentChangeListener
import kotlin.math.min

/**
 * What an [InlineCompletionProvider] offers at the caret, shown as ghost text: dim, in the line, and
 * in no way part of the document until it is accepted.
 *
 * @property text What the [range] becomes. It may run over several lines, and the rows it adds show
 *   below the caret's line, with what follows the caret after the last of them.
 * @property range The text it replaces, as [TextEdit.range]; `offset until offset` for none. It must
 *   hold the caret and end on the line the text it does not repeat starts on. What [text] repeats of
 *   the range is not shown again: over `)`, the text `x) {` shows `x` before the bracket and ` {`
 *   after it. So [text] has to hold all the range, in order, or it is not shown, as in Monaco.
 *
 *   Null is the word at the caret and the rest of the line, Monaco's default, or if [text] does not
 *   hold that, the rest of the line alone, and if it does not hold that either, it goes in at the
 *   caret and replaces nothing, where Monaco would show nothing. So a provider that only knows what
 *   comes next leaves this null, and a closing bracket it repeats is still not doubled. Text the
 *   document has already, from the caret on or from the word's start to the caret or past it, shows
 *   nothing. An empty range is a plain insertion: a bracket [text] repeats there is meant, and doubled.
 */
@Immutable
data class InlineCompletion(val text: String, val range: IntRange? = null)

/**
 * Offers what might come next at the caret, as Monaco's `provideInlineCompletions`. Where the
 * language service's completions finish a word, these can be whole lines: this is what an assistant
 * implements, and hands to `CodeEditorLayout`.
 *
 * Asked once typing pauses, and at once after one is accepted or when
 * [EditorCommand.CycleInlineCompletion] finds none showing. A call still running when the text or the
 * caret moves on is cancelled, which is Monaco's cancellation token, and if it answers all the same
 * the answer is dropped. While one shows, typing what it says asks nothing.
 */
fun interface InlineCompletionProvider {
    /**
     * What to offer at [cursorOffset] in [document], a read-only snapshot, best first: the first is
     * shown, and [EditorCommand.CycleInlineCompletion] goes through the others. Empty for nothing.
     * It runs on [CodeEditorState.computeDispatcher]; what it throws counts as nothing.
     */
    suspend fun provideInlineCompletions(document: CodeDocument, cursorOffset: Int): List<InlineCompletion>
}

/** [text] shown in front of the character at [offset]. Line breaks are LF. */
internal data class GhostPiece(val offset: Int, val text: String)

/** What ghost text shows on [line]: the [pieces] of a completion the document does not have yet, by offset. */
internal data class GhostText(val line: Int, val pieces: List<GhostPiece>)

/** Providers are wanted at [caret]: at once with [now], and otherwise once typing pauses. */
internal class InlineCompletionAsk(val caret: Int, val now: Boolean)

/**
 * The completions on offer at the caret, moved with the text, and which of them shows.
 *
 * A completion stays what the provider said, a [text][InlineCompletion.text] for a range, and what it
 * shows is worked out from the document, as in Monaco's `computeGhostText`: the range's text that
 * [text][InlineCompletion.text] starts with is taken as typed, and what is left of the range must be
 * found in what is left of the text, in order, which leaves only insertions. So typing what it says
 * grows the range and shortens the ghost with nothing to keep in step, and typing anything else
 * leaves nothing that fits, which ends it.
 *
 * It is told about an edit twice. As a listener, it moves first, ahead of any view: [shown] is then
 * right for rows worked out during the change, though the carets are not placed. [afterEdit] then
 * settles it, and says what changed through [onChanged]. Nothing here looks at the document while
 * nothing is on offer, so an editor without a provider pays one comparison per edit.
 *
 * @param caret The one caret, or null if ghost text cannot show now: a selection, several carets, a
 *   snippet being filled in, a read-only editor.
 * @param onChanged Told which lines' ghost text changed, sorted.
 */
internal class GhostTextTracker(
    private val document: CodeDocument,
    private val caret: () -> Int?,
    private val onChanged: (lines: List<Int>) -> Unit,
) : DocumentChangeListener {

    /** A completion as it stands: [text] with LF line breaks, for [start, end). */
    private class Offer(val text: String, val start: Int, var end: Int) {
        /** Follows [change]; false if that ends it. Only what is typed inside the range goes on. */
        fun follow(change: DocumentChange): Boolean {
            if (change.deletedLength > 0 || change.offset !in start..end) return false
            end += change.insertedLength
            return true
        }
    }

    private var offers: List<Offer> = emptyList()
    private var current: Offer? = null

    /** Where what views were last told of is, moved with edits; -1 when they were told of nothing. */
    private var toldAt = -1

    /** An edit has gone by since [refresh], so what is shown is not settled. */
    private var edited = false

    /** Where the caret was when last looked at: anywhere else, it moved. */
    private var caretWas = -1

    /** The document's version when [shown] was settled. */
    private var settledVersion = -1L

    private var composing = false
    private var hiddenByList = false

    /** What shows now. Snapshot state. */
    var shown by mutableStateOf<GhostText?>(null)
        private set

    /** What a layout should ask its provider, or null for nothing. Snapshot state. */
    var ask by mutableStateOf<InlineCompletionAsk?>(null)
        private set

    /** Whether a layout has a provider to ask. */
    var provided = false

    /** Whether [cycle] can do anything now. */
    val canCycle: Boolean get() = provided && !hiddenByList && !composing && caret() != null

    /** [shown] if it is about the text as it is: an edit made straight through the document settles nothing. */
    val settled: GhostText? get() = shown?.takeIf { document.version == settledVersion }

    private val isIdle: Boolean get() = offers.isEmpty() && shown == null && ask == null && toldAt < 0

    override fun onDocumentChange(change: DocumentChange) {
        if (offers.isEmpty()) return
        if (change.isReset) {
            offers = emptyList()
            current = null
        } else {
            offers = offers.filter { it.follow(change) }
            if (current !in offers) current = offers.firstOrNull()
            if (toldAt >= 0) toldAt = moved(toldAt, change)
        }
        edited = true
        shown = ghostNow(null)
    }

    private fun moved(offset: Int, change: DocumentChange): Int = when {
        offset < change.offset -> offset
        offset >= change.offset + change.deletedLength -> offset - change.deletedLength + change.insertedLength
        else -> change.offset
    }

    /** An edit's carets are placed. One [external] to typing ends what is on offer, and what is being asked. */
    fun afterEdit(external: Boolean) {
        if (isIdle) return
        val at = caret()
        if (external || at == null) {
            clear()
            return
        }
        offers = offers.filter { ghostOf(it, at) != null }
        if (current !in offers) current = offers.firstOrNull()
        if (offers.isEmpty()) clear() else refresh()
    }

    /** The selections were set with no edit: a caret that moved leaves what was on offer behind. */
    fun afterSelection() {
        if (!isIdle && caret() != caretWas) clear()
    }

    /** Text was typed or deleted: with nothing left on offer, ask, once typing pauses or [now]. */
    fun askAgain(now: Boolean = false) {
        if (!provided || composing || offers.isNotEmpty()) return
        val at = caret() ?: return
        caretWas = at
        ask = InlineCompletionAsk(at, now)
    }

    /** What [asked] got, for the document at [version]. Nothing that has moved on since is shown. */
    fun answer(asked: InlineCompletionAsk, version: Long, completions: List<InlineCompletion>) {
        if (asked !== ask) return
        ask = null
        val at = caret()
        if (document.version != version || at != asked.caret) return
        val seen = HashSet<GhostText>()
        offers = completions.mapNotNull { completion ->
            offerOf(completion, at)?.takeIf { offer -> ghostOf(offer, at)?.let(seen::add) == true }
        }
        current = offers.firstOrNull()
        if (offers.isNotEmpty()) refresh()
    }

    /** The next or previous completion on offer; with none, the provider is asked now. */
    fun cycle(forward: Boolean): Boolean {
        if (offers.isEmpty()) {
            askAgain(now = true)
            return ask != null
        }
        current = offers[(offers.indexOf(current) + if (forward) 1 else -1).mod(offers.size)]
        refresh()
        return true
    }

    /** An open completion list takes the place of ghost text, which comes back if it is still good when the list goes. */
    fun hideForList(hidden: Boolean) {
        if (hidden == hiddenByList) return
        hiddenByList = hidden
        if (offers.isNotEmpty()) refresh()
    }

    /**
     * An input method started or ended a composition. What it composes is not the text yet, and may
     * yet turn into something else, so nothing shows or is asked for until it ends. Ending asks
     * nothing: the caret leaving, an undo and losing focus end one too. The input adapter, which can
     * tell, calls [askAgain] when it was committed at the caret, which is typing.
     */
    fun setComposing(active: Boolean) {
        if (active == composing) return
        composing = active
        if (active) clear()
    }

    /** Ends what is on offer and anything being asked. */
    fun clear() {
        offers = emptyList()
        current = null
        ask = null
        caretWas = -1
        edited = false
        val lines = if (toldAt >= 0) listOf(lineOf(toldAt)) else emptyList()
        shown = null
        toldAt = -1
        if (lines.isNotEmpty()) onChanged(lines)
    }

    private fun lineOf(offset: Int): Int = document.offsetToLineCol(offset).first

    /** Settles what shows, for the caret as it is. */
    private fun refresh() {
        val at = caret()
        val next = ghostNow(at)
        val changed = edited || next != shown
        val lines = if (changed) listOfNotNull(toldAt.takeIf { it >= 0 }?.let(::lineOf), next?.line).distinct().sorted() else emptyList()
        shown = next
        toldAt = next?.pieces?.first()?.offset ?: -1
        edited = false
        caretWas = at ?: -1
        settledVersion = document.version
        if (lines.isNotEmpty()) onChanged(lines)
    }

    private fun ghostNow(at: Int?): GhostText? = if (hiddenByList) null else current?.let { ghostOf(it, at) }

    /** [completion] for the caret [at], if its range can be one and it has something to add. Not yet whether it shows anything. */
    private fun offerOf(completion: InlineCompletion, at: Int): Offer? {
        val text = TypingRules.normalizeLineEndings(completion.text)
        val range = completion.range
        if (range != null) {
            val end = if (range.isEmpty()) range.first else range.last + 1
            return if (range.first in 0..at && end in at..document.length) Offer(text, range.first, end) else null
        }
        val lineEnd = document.lineEnd(lineOf(at))
        val end = if (lineEnd > at && document[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        val wordStart = TextNavigator.wordAt(document, at).first
        // Text that is all there already has nothing to add. From the word's start it has to reach
        // the caret to be that: short of it, it says what comes next, and only happens to match.
        if (repeats(text, at) || (wordStart + text.length >= at && repeats(text, wordStart))) return null
        // Most text does not start with the word, as a provider asked what comes next says only that.
        return listOf(Offer(text, wordStart, end), Offer(text, at, end)).firstOrNull { ghostOf(it, at) != null } ?: Offer(text, at, at)
    }

    /** Whether the document has [text] at [offset], where each of its line breaks may be CR LF. */
    private fun repeats(text: String, offset: Int): Boolean {
        var from = offset
        for (char in text) {
            if (char == '\n' && from + 1 < document.length && document[from] == '\r') from++
            if (from >= document.length || document[from] != char) return false
            from++
        }
        return true
    }

    /** What [offer] shows, with the caret [at], or just by the text when that is not known yet; null for nothing. */
    private fun ghostOf(offer: Offer, at: Int?): GhostText? {
        val text = offer.text
        val end = offer.end
        var from = offer.start
        var taken = 0
        // What the text repeats of the range is there already: this much has been typed.
        while (from < end && taken < text.length) {
            val char = document[from]
            if (char == text[taken]) {
                from++
                taken++
            } else if (char == '\r' && text[taken] == '\n' && from + 1 < end && document[from + 1] == '\n') {
                from += 2
                taken++
            } else {
                break
            }
        }
        if (taken in 1 until text.length && text[taken - 1].isHighSurrogate()) {
            from--
            taken--
        }
        val line = lineOf(from)
        val lineStart = document.lineStart(line)
        val lineEnd = document.lineEnd(line)
        if (end > lineEnd || from - lineStart > MAX_GHOST_CHARS || text.length - taken > MAX_GHOST_CHARS) return null
        if (at != null && (at !in offer.start..end || at !in lineStart..lineEnd)) return null

        var inserted = text.substring(taken)
        var indentEnd = lineStart
        while (indentEnd < lineEnd && document[indentEnd].isIndent()) indentEnd++
        // Ghost text cannot take indentation away: the text keeps it, and shows what goes after it.
        if (from <= indentEnd) {
            val indentation = document.subSequence(from, indentEnd).toString()
            inserted =
                if (inserted.startsWith(indentation)) inserted.substring(indentation.length) else inserted.trimStart { it.isIndent() }
            from = min(from + indentation.length, end)
        }
        if (inserted.isEmpty()) return null

        val pieces = piecesOf(document.subSequence(from, end), inserted, from) ?: return null
        return GhostText(line, pieces).takeUnless { at != null && pieces.first().offset < at }
    }

    /**
     * [text] inserted around [kept], the document's text at [offset], so that it all stays; null if
     * it does not fit, which it does when every character of [kept] is in [text], in order. Each is
     * found as late as it can be. So what [text] ends with is the document's own, and the last piece
     * ends where Monaco leaves the caret: what the edit adds ends there, not the range.
     */
    private fun piecesOf(kept: CharSequence, text: String, offset: Int): List<GhostPiece>? {
        val pieces = ArrayList<GhostPiece>()
        var pieceEnd = text.length
        for (index in kept.lastIndex downTo 0) {
            val found = text.lastIndexOf(kept[index], pieceEnd - 1)
            if (found < 0) return null
            if (found + 1 < pieceEnd) pieces += GhostPiece(offset + index + 1, text.substring(found + 1, pieceEnd))
            pieceEnd = found
        }
        if (pieceEnd > 0) pieces += GhostPiece(offset, text.substring(0, pieceEnd))
        pieces.reverse()
        return pieces.takeIf { it.isNotEmpty() }
    }

    private fun Char.isIndent(): Boolean = this == ' ' || this == '\t'

    private companion object {
        /** The most a line's layout takes of what is injected, and the furthest column it reaches: `EditorView.MAX_RENDERED_LINE_CHARS`. */
        const val MAX_GHOST_CHARS = 10_000
    }
}
