package com.apn201.blinker.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One box reading. [lit] is null while the box has not shown enough swing to be trusted. */
data class BoxReading(val lit: Boolean?, val hue: Double, val sat: Double, val vtop: Double, val swing: Double)

/**
 * Reads the symbol directly off the LOCKED box every frame, without contours. Port of
 * BoxReader in pc_receiver.py. The protocol has exactly two facts per frame and this
 * reads exactly those:
 *   dark or lit  -> brightness of the brightest pixels vs the swing seen recently.
 *                   Colour never decides this.
 *   which colour -> circular mean hue of those same brightest pixels (the LEDs
 *                   themselves, not the surface they illuminate), so the reading is the
 *                   SAME every frame the LED is in that state even if it is not the
 *                   "true" LED hue. Stable is all the splitter needs.
 *
 * The "brightest 25%" selection uses a 256-bin histogram over the box rather than sorting
 * pixel indices: same selection, two linear passes, and no per-frame allocation.
 */
class BoxReader {
    companion object {
        const val HIST = 60
        const val MIN_SWING = 25.0
        const val DARK_FRAC = 0.4
        const val CROP = 0.2
        const val TOP_FRAC = 0.25
        const val HUE_MIN_SAT = 40
    }

    private val vals = DoubleArray(HIST)
    private var valCount = 0
    private var valHead = 0
    private val histogram = IntArray(256)

    fun read(frame: HsvFrame, box: Box): BoxReading {
        val W = frame.width
        val H = frame.height
        val mx = (box.w * CROP).toInt()
        val my = (box.h * CROP).toInt()
        val x0 = max(0, box.x + mx)
        val y0 = max(0, box.y + my)
        val x1 = min(W, box.x + box.w - mx)
        val y1 = min(H, box.y + box.h - my)
        if (x1 - x0 < 3 || y1 - y0 < 3) return BoxReading(null, 0.0, 0.0, 0.0, 0.0)

        val count = (x1 - x0) * (y1 - y0)

        // pass 1: brightness histogram over the cropped box
        java.util.Arrays.fill(histogram, 0)
        for (y in y0 until y1) {
            val row = y * W
            for (x in x0 until x1) histogram[frame.v[row + x].toInt() and 0xFF]++
        }
        val k = max(4, (count * TOP_FRAC).toInt())
        var vt = 255
        var acc = 0
        while (vt > 0) {
            acc += histogram[vt]
            if (acc >= k) break
            vt--
        }

        // pass 2: accumulate the top pixels (value, and hue as unit vectors)
        var vSum = 0.0
        var n = 0
        var satSin = 0.0; var satCos = 0.0; var satSum = 0.0; var satN = 0
        var allSin = 0.0; var allCos = 0.0; var allSum = 0.0; var allN = 0
        for (y in y0 until y1) {
            val row = y * W
            for (x in x0 until x1) {
                val i = row + x
                val v = frame.v[i].toInt() and 0xFF
                if (v < vt) continue
                vSum += v
                n++
                val s = frame.s[i].toInt() and 0xFF
                val rad = (frame.h[i].toInt() and 0xFF) * 2.0 * Math.PI / 180.0
                val si = sin(rad); val co = cos(rad)
                allSin += si; allCos += co; allSum += s; allN++
                if (s > HUE_MIN_SAT) { satSin += si; satCos += co; satSum += s; satN++ }
            }
        }
        if (n == 0) return BoxReading(null, 0.0, 0.0, 0.0, 0.0)
        val vtop = vSum / n

        // rolling history of vtop
        if (valCount < HIST) {
            vals[valCount++] = vtop
        } else {
            vals[valHead] = vtop
            valHead = (valHead + 1) % HIST
        }
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until valCount) {
            val v = vals[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val swing = hi - lo
        if (valCount < 8 || swing < MIN_SWING) return BoxReading(null, 0.0, 0.0, vtop, swing)

        val lit = vtop > lo + DARK_FRAC * swing
        // a top pixel must be saturated to vote on hue; fall back to all of them if too few
        val useSat = satN >= 3
        val sinS = if (useSat) satSin else allSin
        val cosS = if (useSat) satCos else allCos
        val cnt = if (useSat) satN else allN
        var deg = Math.toDegrees(atan2(sinS / cnt, cosS / cnt)) % 360.0
        if (deg < 0) deg += 360.0
        val hue = deg / 2.0
        val sat = (if (useSat) satSum else allSum) / cnt
        return BoxReading(lit, hue, sat, vtop, swing)
    }

    fun reset() {
        valCount = 0
        valHead = 0
    }
}
