package com.apn201.blinker.core

import java.util.Random

/**
 * Honest synthetic scene generator (spec 5.2 and pitfall "synthetic scenes that lie").
 * Every frame carries a warm-coloured decoy (a red strip that brightens WITH the LED, so
 * it correlates temporally - a reflection), a noisy background, and per-pixel noise. The
 * LED is not a perfect square-on-black: it sits in a lit scene. If the pipeline only works
 * on clean renders, these frames expose it.
 *
 *   frame:      60x60 HSV
 *   background: hue 10, val ~70 (a warm surface)
 *   LED patch:  20x20 centred, hue 47 (bit=1) or 150 (bit=0), val ~160 when lit
 *   red strip:  hue 3, brightens with the LED (a reflection / decoy)
 *   noise:      +-6 hue, +-15 val
 *   timing:     8 dark frames then 8 lit frames per bit; 36 dark between chunks
 */
class SyntheticScene(
    val misclassFrac: Double = 0.0,
    val dropFrac: Double = 0.0,
    val expoRamp: Boolean = false,
    seed: Long = 1
) {
    private val rnd = Random(seed)

    companion object {
        const val W = 60
        const val H = 60
        const val LED_X0 = 21; const val LED_X1 = 39   // exclusive -> 18x18 = 324 px
        const val LED_Y0 = 21; const val LED_Y1 = 39
        const val HUE_A = 47   // bit = 1
        const val HUE_B = 150  // bit = 0
        const val FRAMES_PER_PHASE = 8
        const val GAP_FRAMES = 36
        const val FRAME_MS = 33L
    }

    private fun jitter(base: Int, amp: Int, lo: Int, hi: Int): Int {
        val v = base + (rnd.nextInt(amp * 2 + 1) - amp)
        return v.coerceIn(lo, hi)
    }

    /**
     * One frame. [ledHue] is the LED hue when lit; [litFrac] scales LED brightness
     * (for the exposure ramp); [dark] means the separator phase.
     */
    fun frame(dark: Boolean, ledHue: Int, litFrac: Double): HsvFrame {
        val h = ByteArray(W * H); val s = ByteArray(W * H); val v = ByteArray(W * H)
        val ledVal = if (dark) 15 else (70 + (90 * litFrac)).toInt()   // 70..160 ramp, ~160 steady
        val ledSat = if (dark) 30 else 205
        val redVal = if (dark) 40 else 140
        for (y in 0 until H) {
            for (x in 0 until W) {
                val i = y * W + x
                var hh: Int; var ss: Int; var vv: Int
                if (x in 0 until 8) {                       // red decoy strip (thin: width 8)
                    hh = jitter(3, 6, 0, 179); ss = jitter(200, 6, 0, 255); vv = jitter(redVal, 15, 0, 255)
                } else if (x in LED_X0 until LED_X1 && y in LED_Y0 until LED_Y1) {   // LED patch
                    hh = jitter(ledHue, 6, 0, 179); ss = jitter(ledSat, 10, 0, 255); vv = jitter(ledVal, 15, 0, 255)
                } else {                                    // warm, low-saturation background surface
                    hh = jitter(10, 3, 0, 179); ss = jitter(55, 4, 0, 255); vv = jitter(70, 15, 0, 255)
                }
                h[i] = hh.toByte(); s[i] = ss.toByte(); v[i] = vv.toByte()
            }
        }
        return HsvFrame(W, H, h, s, v)
    }

    /** Result of running one message through the receiver via this scene. */
    class RunResult(
        val message: String?,
        val total: Int,
        val verifiedChunks: Int,
        val chunksOk: Int,
        val chunksSeen: Int,
        val colorsDesc: String
    )

    /**
     * Drive a Receiver with [message], looped [laps] times, and report what decoded.
     * Bit 1 -> HUE_A, bit 0 -> HUE_B. Both wire polarities are exercised elsewhere; here
     * the point is the full image pipeline.
     */
    fun run(message: String, laps: Int = 2): RunResult {
        val rx = Receiver()
        val chunks = splitIntoChunks(message)
        val total = chunks.size
        var now = 0L
        var completed: ByteArray? = null

        fun feed(dark: Boolean, bitHue: Int, litFrac: Double) {
            if (!dark && dropFrac > 0 && rnd.nextDouble() < dropFrac) { now += FRAME_MS; return }
            val f = frame(dark, bitHue, litFrac)
            val r = rx.process(f, now)
            if (r.completedMessage != null) completed = r.completedMessage
            now += FRAME_MS
        }

        repeat(laps) {
            for (idx in 0 until total) {
                val bits = packetToBits(buildChunkPacket(idx, total, chunks[idx]))
                for (bit in bits) {
                    val trueHue = if (bit == 1) HUE_A else HUE_B
                    repeat(FRAMES_PER_PHASE) { feed(dark = true, bitHue = trueHue, litFrac = 1.0) }
                    repeat(FRAMES_PER_PHASE) { fi ->
                        // Per-FRAME misclassification: an individual lit frame reads the wrong
                        // colour. The 5-frame vote (need 3) is meant to absorb isolated flips.
                        var hue = trueHue
                        if (misclassFrac > 0 && rnd.nextDouble() < misclassFrac) hue = if (hue == HUE_A) HUE_B else HUE_A
                        val litFrac = if (expoRamp && fi < 4) 0.2 + 0.2 * fi else 1.0
                        feed(dark = false, bitHue = hue, litFrac = litFrac)
                    }
                }
                repeat(GAP_FRAMES) { feed(dark = true, bitHue = HUE_A, litFrac = 1.0) }
            }
        }
        // a little trailing dark so the final bit's run is flushed
        repeat(20) { feed(dark = true, bitHue = HUE_A, litFrac = 1.0) }

        return RunResult(
            message = completed?.let { String(it, Charsets.UTF_8) },
            total = total,
            verifiedChunks = rx.assembler.chunks.size,
            chunksOk = rx.chunksOk,
            chunksSeen = rx.chunksSeen,
            colorsDesc = rx.colors.describe()
        )
    }
}
