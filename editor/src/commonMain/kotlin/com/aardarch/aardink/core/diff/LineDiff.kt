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
package com.aardarch.aardink.core.diff

/** Elements [aStart, aEnd) of the first sequence became elements [bStart, bEnd) of the second. */
internal class LineHunk(val aStart: Int, val aEnd: Int, val bStart: Int, val bEnd: Int)

/**
 * The [hunks] that turn one sequence into the other, sorted and never adjacent. [unresolved] is true
 * when time ran out: some hunk then spans elements that are equal, and the diff is valid but not minimal.
 */
internal class LineDiffResult(val hunks: List<LineHunk>, val unresolved: Boolean)

/**
 * Myers' O((N+M)D) diff of two integer sequences (line ids, or characters) in linear space: the
 * middle snake of the edit graph is found by searching from both ends, and the two halves are solved
 * in turn. The result is optimal, so the elements left out of the hunks are a longest common
 * subsequence.
 */
internal object LineDiff {

    /**
     * The hunks that turn [a] into [b]. Returns null when more than [maxEdits] insertions and
     * deletions are needed. Once [expired] returns true, the regions not yet resolved are reported
     * as one hunk each and [LineDiffResult.unresolved] is set.
     */
    fun diff(a: IntArray, b: IntArray, maxEdits: Int = Int.MAX_VALUE, expired: () -> Boolean = NEVER): LineDiffResult? =
        Differ(a, b, maxEdits, expired).run()

    private val NEVER: () -> Boolean = { false }

    private class Differ(private val a: IntArray, private val b: IntArray, private val maxEdits: Int, private val expired: () -> Boolean) {
        private val hunks = ArrayList<LineHunk>()
        private var unresolved = false
        private var exceeded = false
        private var vf = IntArray(0)
        private var vb = IntArray(0)

        // The middle snake found last, in coordinates relative to the problem it was found in.
        private var snakeX = 0
        private var snakeY = 0
        private var snakeU = 0
        private var snakeV = 0

        fun run(): LineDiffResult? {
            solve(0, a.size, 0, b.size, maxEdits)
            return if (exceeded) null else LineDiffResult(hunks, unresolved)
        }

        private fun emit(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
            val last = hunks.lastOrNull()
            if (last != null && last.aEnd == aStart && last.bEnd == bStart) {
                hunks[hunks.size - 1] = LineHunk(last.aStart, aEnd, last.bStart, bEnd)
            } else {
                hunks += LineHunk(aStart, aEnd, bStart, bEnd)
            }
        }

        private fun solve(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, cap: Int) {
            var aLo = aFrom
            var aHi = aTo
            var bLo = bFrom
            var bHi = bTo
            while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
                aLo++
                bLo++
            }
            while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
                aHi--
                bHi--
            }
            val n = aHi - aLo
            val m = bHi - bLo
            if (n == 0 && m == 0) return
            if (n == 0 || m == 0) {
                if (n + m > cap) {
                    exceeded = true
                    return
                }
                emit(aLo, aHi, bLo, bHi)
                return
            }
            if (expired()) {
                unresolved = true
                emit(aLo, aHi, bLo, bHi)
                return
            }
            when (middleSnake(aLo, n, bLo, m, cap)) {
                EXCEEDED -> exceeded = true

                EXPIRED -> {
                    unresolved = true
                    emit(aLo, aHi, bLo, bHi)
                }

                else -> {
                    val x = snakeX
                    val y = snakeY
                    val u = snakeU
                    val v = snakeV
                    solve(aLo, aLo + x, bLo, bLo + y, Int.MAX_VALUE)
                    solve(aLo + u, aHi, bLo + v, bHi, Int.MAX_VALUE)
                }
            }
        }

        /**
         * Finds the middle snake of a[aLo, aLo+n) against b[bLo, bLo+m): the diagonal run (x,y)-(u,v)
         * that an optimal path crosses after half its edits. Forward paths run from the start and
         * backward paths from the end, one edit further each round, until they meet.
         */
        private fun middleSnake(aLo: Int, n: Int, bLo: Int, m: Int, cap: Int): Int {
            val delta = n - m
            val odd = (delta and 1) != 0
            val limit = minOf((n + m + 1) / 2, cap / 2 + 1)
            val off = limit + 1
            if (vf.size < 2 * limit + 3) {
                vf = IntArray(2 * limit + 3)
                vb = IntArray(2 * limit + 3)
            }
            val vf = vf
            val vb = vb
            vf[off + 1] = 0
            vb[off + 1] = 0
            for (d in 0..limit) {
                if (2 * d - 1 > cap) return EXCEEDED
                if ((d and 31) == 31 && expired()) return EXPIRED
                var k = -d
                while (k <= d) {
                    var x = if (k == -d || (k != d && vf[off + k - 1] < vf[off + k + 1])) vf[off + k + 1] else vf[off + k - 1] + 1
                    var y = x - k
                    val x0 = x
                    val y0 = y
                    while (x < n && y < m && a[aLo + x] == b[bLo + y]) {
                        x++
                        y++
                    }
                    vf[off + k] = x
                    if (odd) {
                        val bk = delta - k
                        if (bk >= -(d - 1) && bk <= d - 1 && x + vb[off + bk] >= n) {
                            if (2 * d - 1 > cap) return EXCEEDED
                            snakeX = x0
                            snakeY = y0
                            snakeU = x
                            snakeV = y
                            return FOUND
                        }
                    }
                    k += 2
                }
                k = -d
                while (k <= d) {
                    var x = if (k == -d || (k != d && vb[off + k - 1] < vb[off + k + 1])) vb[off + k + 1] else vb[off + k - 1] + 1
                    var y = x - k
                    val x0 = x
                    val y0 = y
                    while (x < n && y < m && a[aLo + n - 1 - x] == b[bLo + m - 1 - y]) {
                        x++
                        y++
                    }
                    vb[off + k] = x
                    if (!odd) {
                        val fk = delta - k
                        if (fk >= -d && fk <= d && x + vf[off + fk] >= n) {
                            if (2 * d > cap) return EXCEEDED
                            snakeX = n - x
                            snakeY = m - y
                            snakeU = n - x0
                            snakeV = m - y0
                            return FOUND
                        }
                    }
                    k += 2
                }
            }
            return EXCEEDED
        }
    }

    private const val FOUND = 0
    private const val EXCEEDED = 1
    private const val EXPIRED = 2
}
