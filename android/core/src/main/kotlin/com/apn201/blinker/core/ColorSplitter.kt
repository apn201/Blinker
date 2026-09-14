package com.apn201.blinker.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Learns to tell the two data colours apart WITHOUT being told what they are.
 * Direct port of ColorSplitter in pc_receiver.py - read that class's docstring and
 * the HANDOFF for why every constant is where it is. The short version:
 *
 *  - Never anchor to a fixed hue. A real camera renders green at 84-88, blue at
 *    104-148 depending on light. Learn both colours from what the tracked
 *    transmitter actually produces and split at the widest gap.
 *  - Learning margin after a fit is TIGHT and absolute (LEARN_MARGIN), and freezing
 *    happens on stability (FREEZE_AFTER admitted samples), NOT on a verified chunk -
 *    a chunk cannot verify until the colours stop moving, so waiting for one deadlocks.
 *  - Count identical runs in BITS not frames (each bit spans several frames). A real
 *    header legitimately has 13 identical bits, so MAX_RUN is 32, not 14.
 *  - Which group means 1 is deliberately not decided here; the chunk CRC picks.
 */
class ColorSplitter {
    companion object {
        const val MAX_SAMPLES = 140
        const val MIN_SAMPLES = 20
        const val MIN_VOID = 5.0          // empty span separating the two clusters
        const val MIN_CENTRE_SEP = 12.0   // distance between the two cluster centres
        const val MAX_RUN = 32            // identical BITS in a row before the fit is disowned
        const val BIT_GAP_MS = 70L        // a lit frame this long after the last one starts a new bit
        const val LEARN_MARGIN = 10.0     // post-fit refinement window, absolute
        const val FREEZE_AFTER = 120      // consistent post-fit samples before freezing

        const val HUE_ACCEPT = 45.0       // bootstrap window around the home hues
        const val HUE_ONE_HOME = 60.0
        const val HUE_ZERO_HOME = 120.0
        const val OUTLIER_MIN_MARGIN = 16.0
    }

    private val samples = ArrayList<Double>()
    var centre: Double? = null           // rotation reference, handles hue wrap-around
        private set
    var threshold: Double? = null
        private set
    var loC: Double? = null
        private set
    var hiC: Double? = null
        private set
    private var runSym: String? = null
    private var runLen = 0
    var refits = 0
        private set
    private var lastLitMs: Long? = null
    var frozen = false
        private set
    private var stable = 0

    fun sampleCount(): Int = samples.size

    private fun rot(hue: Double): Double = ((hue - centre!! + 90.0).mod(180.0)) - 90.0

    /** Stop adapting. Called once real data verifies: the colours cannot change mid-message. */
    fun freeze() {
        frozen = true
    }

    fun reset() {
        samples.clear()
        threshold = null
        loC = null
        hiC = null
        runSym = null
        runLen = 0
        frozen = false
        stable = 0
    }

    fun observe(hue: Double) {
        if (frozen) return
        if (ready()) {
            // Once a fit exists an outlier is not evidence - it is something else that
            // drifted into the tracked box. Tight, absolute learning margin only.
            val x = rot(hue)
            if (min(abs(x - loC!!), abs(x - hiC!!)) > LEARN_MARGIN) return
            stable += 1
            if (stable >= FREEZE_AFTER) {
                frozen = true
                return
            }
        } else {
            // BOOTSTRAP GATE: before a fit exists, only accept hues near where the LEDs
            // plausibly render, so a warm-coloured object cannot seed the first samples.
            if (min(hueDistance(hue, HUE_ONE_HOME), hueDistance(hue, HUE_ZERO_HOME)) > HUE_ACCEPT) return
        }
        samples.add(hue)
        if (samples.size > MAX_SAMPLES) samples.removeAt(0)
        fit()
    }

    private fun fit() {
        if (samples.size < MIN_SAMPLES) return
        centre = circularMeanHue(samples)
        val xs = samples.map { rot(it) }.sorted()
        // widest gap between consecutive samples = the boundary between the two colours
        var bestGap = 0.0
        var bestAt: Double? = null
        for (i in 0 until xs.size - 1) {
            val g = xs[i + 1] - xs[i]
            if (g > bestGap) {
                bestGap = g
                bestAt = (xs[i] + xs[i + 1]) / 2.0
            }
        }
        if (bestGap < MIN_VOID || bestAt == null) return   // only one colour seen so far
        val lo = xs.filter { it <= bestAt }
        val hi = xs.filter { it > bestAt }
        if (min(lo.size, hi.size) < xs.size * 0.12) return
        // Trimmed refit: throw away the worst-fitting samples and fit again, so a frame
        // that clipped something else does not drag a centre.
        var loC2 = lo.sum() / lo.size
        var hiC2 = hi.sum() / hi.size
        repeat(2) {
            val resid = xs.map { min(abs(it - loC2), abs(it - hiC2)) }.sorted()
            val cutoff = resid[max(0, (resid.size * 0.8).toInt() - 1)]
            val keep = xs.filter { min(abs(it - loC2), abs(it - hiC2)) <= max(cutoff, 4.0) }
            val lo2 = keep.filter { abs(it - loC2) <= abs(it - hiC2) }
            val hi2 = keep.filter { abs(it - loC2) > abs(it - hiC2) }
            if (min(lo2.size, hi2.size) < 3) return@repeat
            loC2 = lo2.sum() / lo2.size
            hiC2 = hi2.sum() / hi2.size
        }
        if ((hiC2 - loC2) < MIN_CENTRE_SEP) return
        threshold = (loC2 + hiC2) / 2.0
        loC = loC2
        hiC = hiC2
    }

    fun ready(): Boolean = threshold != null

    /**
     * Test-only: inject a raw sample set and run the fit, bypassing the bootstrap gate.
     * The wrap test (clusters straddling 0/180) cannot bootstrap through observe() because
     * no hue near the wrap point falls inside the bootstrap window, so the fit geometry is
     * exercised directly here.
     */
    internal fun forceSamplesAndFit(hues: List<Double>) {
        samples.clear()
        samples.addAll(hues)
        fit()
    }

    /** Returns "A" or "B" - deliberately not "one" or "zero". null while not ready or on disown. */
    fun classify(hue: Double, nowMs: Long? = null): String? {
        if (!ready()) return null
        val x = rot(hue)
        // A LIT frame is always one of the two colours - nearest wins, full stop.
        val sym = if (x <= threshold!!) "A" else "B"
        var newBit = true
        if (nowMs != null) {
            val last = lastLitMs
            if (last != null && (nowMs - last) < BIT_GAP_MS) newBit = false
            lastLitMs = nowMs
        }
        if (!newBit) return sym
        if (sym == runSym) {
            runLen += 1
            if (runLen > MAX_RUN) {
                // both colours landing on one side of the split -> the fit is wrong
                threshold = null
                val keep = if (samples.size > MIN_SAMPLES) samples.subList(samples.size - MIN_SAMPLES, samples.size).toList() else samples.toList()
                samples.clear()
                samples.addAll(keep)
                runSym = null
                runLen = 0
                refits += 1
                frozen = false
                stable = 0
                return null
            }
        } else {
            runSym = sym
            runLen = 1
        }
        return sym
    }

    /** Is this hue one of the learned colours? Side-effect free, used to screen candidates. */
    fun matches(hue: Double): Boolean {
        if (!ready()) return true    // still learning: do not filter, or it cannot bootstrap
        val x = rot(hue)
        val half = (hiC!! - loC!!) / 2.0
        return min(abs(x - loC!!), abs(x - hiC!!)) <= max(half * 0.9, OUTLIER_MIN_MARGIN)
    }

    fun distanceToNearest(hue: Double): Double {
        if (!ready()) return 0.0
        val x = rot(hue)
        return min(abs(x - loC!!), abs(x - hiC!!))
    }

    fun describe(): String {
        if (!ready()) return "learning (${samples.size} samples)"
        val c = centre!!
        val loHue = (c + loC!!).mod(180.0)
        val hiHue = (c + hiC!!).mod(180.0)
        val gap = hiC!! - loC!!
        return if (frozen) {
            "%.0f|%.0f gap%.0f LOCKED".format(loHue, hiHue, gap)
        } else {
            "%.0f|%.0f gap%.0f refit%d".format(loHue, hiHue, gap, refits)
        }
    }
}
