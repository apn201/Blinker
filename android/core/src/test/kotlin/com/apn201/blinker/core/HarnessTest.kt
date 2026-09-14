package com.apn201.blinker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 5.2 - synthetic frame harness. The full image pipeline (FlickerMap, shape filter,
 * Tracker, BoxReader, ColorSplitter, Decoder, Assembler) must recover the message from
 * noisy frames with a warm decoy before anything goes on a phone.
 */
class HarnessTest {

    private val message = "All your base are belong to us"

    @Test
    fun cleanSceneRecoversEveryChunk() {
        val r = SyntheticScene(seed = 1).run(message)
        println("clean: msg=${r.message} verified=${r.verifiedChunks}/${r.total} " +
                "ok=${r.chunksOk}/${r.chunksSeen} colours=${r.colorsDesc}")
        assertEquals("message must decode exactly", message, r.message)
        assertEquals("every chunk must verify", r.total, r.verifiedChunks)
        // the fit should land near the two emitted hues (~47 and ~150)
        assertTrue("colours should be learned, was ${r.colorsDesc}", r.colorsDesc.contains("gap"))
    }

    @Test
    fun robustToMisclassification2pct() {
        val r = SyntheticScene(misclassFrac = 0.02, seed = 2).run(message, laps = 3)
        println("misclass2%: msg=${r.message} verified=${r.verifiedChunks}/${r.total}")
        assertNotNull("message must still complete under 2% misclassification", r.message)
        assertEquals(message, r.message)
    }

    @Test
    fun robustToMisclassification5pct() {
        val r = SyntheticScene(misclassFrac = 0.05, seed = 3).run(message, laps = 4)
        println("misclass5%: msg=${r.message} verified=${r.verifiedChunks}/${r.total} ok=${r.chunksOk}/${r.chunksSeen}")
        assertEquals(message, r.message)
    }

    @Test
    fun robustToDroppedFrames5pct() {
        val r = SyntheticScene(dropFrac = 0.05, seed = 4).run(message, laps = 3)
        println("drop5%: msg=${r.message} verified=${r.verifiedChunks}/${r.total}")
        assertNotNull("message must still complete with 5% dropped frames", r.message)
        assertEquals(message, r.message)
    }

    @Test
    fun robustToExposureRamp() {
        val r = SyntheticScene(expoRamp = true, seed = 5).run(message, laps = 3)
        println("expoRamp: msg=${r.message} verified=${r.verifiedChunks}/${r.total}")
        assertNotNull("message must complete despite auto-exposure recovery ramps", r.message)
    }
}
