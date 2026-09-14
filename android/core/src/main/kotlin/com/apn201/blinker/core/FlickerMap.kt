package com.apn201.blinker.core

import kotlin.math.max
import kotlin.math.min

/**
 * Finds WHERE the transmitter is by looking for pixels whose colour keeps changing.
 * Port of FlickerMap in pc_receiver.py. Detection is by CHANGE, never by appearance -
 * a static object (printer, face, shirt print) cannot fake temporal change, so measuring
 * change FIRST removes that whole class of impostor before anything is compared.
 *
 * Cheap by design: the frame is downscaled 4x and the measure is just the spread between
 * the brightest and darkest recent value of saturation*value at each pixel. All buffers
 * are reused across frames - nothing here allocates in steady state.
 */
class FlickerMap {
    companion object {
        const val SCALE_DIV = 4
        const val HISTORY = 10       // FLICKER_HISTORY
        const val FLICKER_MIN = 26f  // per-pixel change to count as flickering (pc_receiver.py)
        const val DILATE_K = 5
    }

    private var sw = 0
    private var sh = 0
    private var ring: Array<FloatArray> = emptyArray()
    private var filled = 0
    private var head = 0

    private var smallMask = BooleanArray(0)
    private var smallDil = BooleanArray(0)
    private var smallTmp = BooleanArray(0)

    /** Full-resolution inclusive ROI covering the flickering pixels; valid after maskInto. */
    var roiX0 = 0; private set
    var roiY0 = 0; private set
    var roiX1 = -1; private set
    var roiY1 = -1; private set
    var area = 0; private set

    private fun ensure(w: Int, h: Int) {
        val nw = w / SCALE_DIV
        val nh = h / SCALE_DIV
        if (nw == sw && nh == sh && ring.isNotEmpty()) return
        sw = nw; sh = nh
        ring = Array(HISTORY) { FloatArray(sw * sh) }
        smallMask = BooleanArray(sw * sh)
        smallDil = BooleanArray(sw * sh)
        smallTmp = BooleanArray(sw * sh)
        filled = 0
        head = 0
    }

    fun update(frame: HsvFrame) {
        ensure(frame.width, frame.height)
        if (sw <= 0 || sh <= 0) return
        val metric = ring[head]
        head = (head + 1) % HISTORY
        if (filled < HISTORY) filled++
        for (sy in 0 until sh) {
            val y0 = sy * SCALE_DIV
            for (sx in 0 until sw) {
                var sSum = 0
                var vSum = 0
                var n = 0
                val x0 = sx * SCALE_DIV
                for (dy in 0 until SCALE_DIV) {
                    val y = y0 + dy
                    if (y >= frame.height) break
                    val row = y * frame.width
                    for (dx in 0 until SCALE_DIV) {
                        val x = x0 + dx
                        if (x >= frame.width) break
                        sSum += frame.s[row + x].toInt() and 0xFF
                        vSum += frame.v[row + x].toInt() and 0xFF
                        n++
                    }
                }
                metric[sy * sw + sx] = if (n > 0) (sSum.toFloat() / n) * (vSum.toFloat() / n) / 255f else 0f
            }
        }
    }

    /**
     * Write the full-resolution flicker mask into [out] (nearest-neighbour upscaled).
     * Returns false while still measuring (<4 frames of history).
     */
    fun maskInto(out: BooleanArray, width: Int, height: Int): Boolean {
        if (filled < 4 || sw <= 0 || sh <= 0) return false
        val n = sw * sh
        for (i in 0 until n) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (j in 0 until filled) {
                val v = ring[j][i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            smallMask[i] = (hi - lo) > FLICKER_MIN
        }
        dilateBoxRegion(smallMask, smallDil, smallTmp, sw, sh, DILATE_K, 0, 0, sw - 1, sh - 1)

        var minX = sw; var minY = sh; var maxX = -1; var maxY = -1
        var count = 0
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                if (!smallDil[sy * sw + sx]) continue
                count++
                if (sx < minX) minX = sx
                if (sx > maxX) maxX = sx
                if (sy < minY) minY = sy
                if (sy > maxY) maxY = sy
            }
        }
        java.util.Arrays.fill(out, false)
        if (maxX < 0) {
            roiX0 = 0; roiY0 = 0; roiX1 = -1; roiY1 = -1
            area = 0
            return true
        }
        roiX0 = max(0, minX * SCALE_DIV)
        roiY0 = max(0, minY * SCALE_DIV)
        roiX1 = min(width - 1, (maxX + 1) * SCALE_DIV - 1)
        roiY1 = min(height - 1, (maxY + 1) * SCALE_DIV - 1)
        for (y in roiY0..roiY1) {
            val sy = min(sh - 1, y / SCALE_DIV)
            val row = y * width
            val srow = sy * sw
            for (x in roiX0..roiX1) {
                val sx = min(sw - 1, x / SCALE_DIV)
                out[row + x] = smallDil[srow + sx]
            }
        }
        area = count * SCALE_DIV * SCALE_DIV
        return true
    }

    fun reset() {
        filled = 0
        head = 0
        area = 0
        roiX1 = -1; roiY1 = -1
    }
}
