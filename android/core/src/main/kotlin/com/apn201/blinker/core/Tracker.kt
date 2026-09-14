package com.apn201.blinker.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in frame pixels. */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val cx: Double get() = x + w / 2.0
    val cy: Double get() = y + h / 2.0
}

/** A detection candidate for one frame. */
data class Candidate(val score: Double, val hue: Double, val sat: Double, val v: Double, val box: Box)

/**
 * A persistent lock on the transmitter, with continuity enforced. Port of Tracker in
 * pc_receiver.py. Detection is a persistent belief, not a per-frame decision:
 *
 *   - once locked, only candidates NEAR the lock are considered at all
 *   - a candidate elsewhere is ignored no matter how good it looks
 *   - finding nothing near the lock means the transmitter is dark; the lock is kept
 *   - moving the lock requires sustained evidence (MOVE_HITS), never one frame
 *
 * Acquiring is slow too: a location must show the transmitter's real signature - a steady
 * blink rhythm AND two distinct hues at one spot - before it is believed. A red cable is
 * one hue however it flickers, so it never qualifies.
 */
class Tracker {
    companion object {
        const val ACQUIRE_HITS = 8
        const val MOVE_HITS = 24
        const val LOST_MS = 2500L
        const val PERIOD_MIN_MS = 120L
        const val PERIOD_MAX_MS = 900L
        const val MIN_PERIODS = 3
        const val TWO_COLOUR_SPREAD = 16.0
    }

    private class CandState(var hits: Int, var lastMs: Long, val hues: ArrayList<Double>, val changes: ArrayList<Long>)

    var pos: DoubleArray? = null
        private set
    var size = 60.0
        private set
    private var lastSeenMs = -1_000_000_000L
    private val cands = HashMap<Long, CandState>()

    private fun key(box: Box): Long {
        val kx = Math.floorDiv(box.cx.toInt(), 40)
        val ky = Math.floorDiv(box.cy.toInt(), 40)
        return (kx.toLong() shl 32) xor (ky.toLong() and 0xffffffffL)
    }

    private fun bump(box: Box, hue: Double, nowMs: Long, need: Int): Boolean {
        val k = key(box)
        var st = cands[k]
        if (st == null) {
            st = CandState(0, nowMs, ArrayList(), ArrayList())
            cands[k] = st
        }
        if (nowMs - st.lastMs > 1200) {
            st.hits = 0; st.hues.clear(); st.changes.clear()
        }
        st.hits += 1
        if (st.hues.isNotEmpty() && hueDistance(hue, st.hues.last()) > 12) {
            st.changes.add(nowMs)
            while (st.changes.size > 10) st.changes.removeAt(0)
        }
        st.hues.add(hue)
        while (st.hues.size > 30) st.hues.removeAt(0)
        st.lastMs = nowMs
        val it = cands.entries.iterator()
        while (it.hasNext()) { if (nowMs - it.next().value.lastMs > 2500) it.remove() }

        if (st.hits < need || st.hues.size < 6) return false
        if (st.changes.size >= MIN_PERIODS + 1) {
            val gaps = ArrayList<Long>()
            for (j in 1 until st.changes.size) gaps.add(st.changes[j] - st.changes[j - 1])
            gaps.sort()
            val median = gaps[gaps.size / 2]
            if (median !in PERIOD_MIN_MS..PERIOD_MAX_MS) return false
        } else if (st.changes.size < 2) {
            return false
        }
        val xs = st.hues.sorted()
        val lo = xs[max(0, (xs.size * 0.15).toInt())]
        val hi = xs[min(xs.size - 1, (xs.size * 0.85).toInt())]
        return (hi - lo) >= TWO_COLOUR_SPREAD
    }

    private fun maxJump(): Double = max(55.0, 0.9 * size)

    /** Returns the candidate to believe this frame, or null. */
    fun update(candidates: List<Candidate>, nowMs: Long): Candidate? {
        val locked = pos != null && (nowMs - lastSeenMs) < LOST_MS
        if (locked) {
            val p = pos!!
            val near = candidates.filter {
                abs(it.box.cx - p[0]) <= maxJump() && abs(it.box.cy - p[1]) <= maxJump()
            }
            if (near.isNotEmpty()) {
                val best = near.maxByOrNull { it.score }!!
                pos = doubleArrayOf(0.6 * p[0] + 0.4 * best.box.cx, 0.6 * p[1] + 0.4 * best.box.cy)
                size = 0.7 * size + 0.3 * max(best.box.w, best.box.h)
                lastSeenMs = nowMs
                cands.clear()
                return best
            }
            for (c in candidates) {
                if (bump(c.box, c.hue, nowMs, MOVE_HITS)) {
                    pos = doubleArrayOf(c.box.cx, c.box.cy)
                    size = max(c.box.w, c.box.h).toDouble()
                    lastSeenMs = nowMs
                    cands.clear()
                    return c
                }
            }
            return null
        }
        for (c in candidates.sortedByDescending { it.score }) {
            if (bump(c.box, c.hue, nowMs, ACQUIRE_HITS)) {
                pos = doubleArrayOf(c.box.cx, c.box.cy)
                size = max(c.box.w, c.box.h).toDouble()
                lastSeenMs = nowMs
                cands.clear()
                return c
            }
        }
        return null
    }

    fun reset() {
        pos = null
        size = 60.0
        lastSeenMs = -1_000_000_000L
        cands.clear()
    }

    fun box(): Box? {
        val p = pos ?: return null
        val s = max(20.0, size)
        return Box((p[0] - s / 2).toInt(), (p[1] - s / 2).toInt(), s.toInt(), s.toInt())
    }

    fun hasLock(nowMs: Long): Boolean = pos != null && (nowMs - lastSeenMs) < LOST_MS

    /** The box read lit this frame: the transmitter is still there. */
    fun touch(nowMs: Long) {
        if (pos != null) lastSeenMs = nowMs
    }
}
