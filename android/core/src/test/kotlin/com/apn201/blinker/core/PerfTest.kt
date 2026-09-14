package com.apn201.blinker.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Throughput of the receiver pipeline at the real working frame size (480x640, what a
 * 640x480 camera frame becomes once rotated upright).
 *
 * This exists to separate "the algorithm is too slow" from "this device/emulator is slow".
 * The bound is deliberately loose - it is a regression guard against reintroducing
 * per-frame allocation or a full-frame sweep, not a benchmark score.
 */
class PerfTest {

    private val W = 480
    private val H = 640

    /** A frame with a warm background, a noisy scene and a blinking LED patch. */
    private fun frame(rnd: Random, lit: Boolean, hue: Int): HsvFrame {
        val n = W * H
        val h = ByteArray(n); val s = ByteArray(n); val v = ByteArray(n)
        val ledX = W / 2 - 20; val ledY = H / 2 - 20
        for (y in 0 until H) {
            for (x in 0 until W) {
                val i = y * W + x
                if (x >= ledX && x < ledX + 40 && y >= ledY && y < ledY + 40) {
                    h[i] = (hue + rnd.nextInt(7) - 3).toByte()
                    s[i] = (if (lit) 205 else 30).toByte()
                    v[i] = ((if (lit) 160 else 15) + rnd.nextInt(21) - 10).toByte()
                } else {
                    h[i] = (10 + rnd.nextInt(5)).toByte()
                    s[i] = (55 + rnd.nextInt(7)).toByte()
                    v[i] = (70 + rnd.nextInt(21) - 10).toByte()
                }
            }
        }
        return HsvFrame(W, H, h, s, v)
    }

    @Test
    fun pipelineThroughput() {
        val rnd = Random(7)
        val rx = Receiver()
        // pre-build a small set of frames so frame generation is not part of the timing
        val frames = ArrayList<HsvFrame>()
        for (i in 0 until 16) frames.add(frame(rnd, lit = (i / 8) % 2 == 1, hue = if (i < 8) 47 else 150))

        var now = 0L
        repeat(64) { i ->            // warm up the JIT
            rx.process(frames[i % frames.size], now); now += 33
        }

        val iterations = 200
        val t0 = System.nanoTime()
        repeat(iterations) { i ->
            rx.process(frames[i % frames.size], now); now += 33
        }
        val msPerFrame = (System.nanoTime() - t0) / 1e6 / iterations
        println("pipeline: %.2f ms/frame at ${W}x$H  (%.0f fps)".format(msPerFrame, 1000.0 / msPerFrame))
        println("stages: ${rx.timingSummary()}")

        assertTrue(
            "pipeline must stay well inside a 30 fps budget; was %.1f ms/frame".format(msPerFrame),
            msPerFrame < 25.0
        )
    }
}
