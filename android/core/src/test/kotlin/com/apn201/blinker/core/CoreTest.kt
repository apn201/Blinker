package com.apn201.blinker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Spec section 5.1 - pure-core unit tests. Nothing goes on a phone until these pass.
 */
class CoreTest {

    // ---- 1. crc8 golden vectors (ANDROID_PORT_SPEC 1.3) - the first test in the project ----
    @Test
    fun crc8GoldenVectors() {
        assertEquals(0x00, crc8(ByteArray(0)))
        assertEquals(0x00, crc8(0x00))
        assertEquals(0x07, crc8(0x01))
        assertEquals(0xF4, crc8("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x6C, crc8(0, 4, 8))                       // header of chunk 0 of a 4-chunk msg
        assertEquals(0xDA, crc8("All your".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x53, crc8(" to us".toByteArray(Charsets.US_ASCII)))
    }

    // ---- 2. packet round-trip through Decoder + Assembler, BOTH polarities ----
    @Test
    fun packetRoundTripBothPolarities() {
        val message = "All your base are belong to us"   // 30 bytes -> 4 chunks, header idx0 total4 len8
        assertEquals(message, runMessage(message, aMeansOne = true))
        assertEquals(message, runMessage(message, aMeansOne = false))
    }

    @Test
    fun packetRoundTripShortAndEmpty() {
        assertEquals("Hi", runMessage("Hi", aMeansOne = true))
        assertEquals("Hi", runMessage("Hi", aMeansOne = false))
        assertEquals("", runMessage("", aMeansOne = true))
    }

    // ---- 3. ColorSplitter: fit, nearest classify, freeze, post-fit outlier rejection ----
    @Test
    fun colorSplitterFitsAndClassifies() {
        val cs = ColorSplitter()
        var t = 0L
        // clusters at 47 and 150 (both inside the bootstrap window around 60/120)
        repeat(30) {
            cs.observe(47.0 + noise(it))
            cs.observe(150.0 + noise(it + 7))
        }
        assertTrue("expected a fit after two well-separated clusters", cs.ready())
        val nearLo = cs.classify(47.0, null)
        val nearHi = cs.classify(150.0, null)
        assertNotNull(nearLo); assertNotNull(nearHi)
        assertNotEquals("47 and 150 must classify to different groups", nearLo, nearHi)
        // nearest wins: 49 groups with 47, 148 groups with 150
        assertEquals(nearLo, cs.classify(49.0, null))
        assertEquals(nearHi, cs.classify(148.0, null))
    }

    @Test
    fun colorSplitterFreezesAfter120() {
        val cs = ColorSplitter()
        repeat(30) { cs.observe(47.0 + noise(it)); cs.observe(150.0 + noise(it + 3)) }
        assertTrue(cs.ready())
        assertFalse(cs.frozen)
        repeat(130) { cs.observe(if (it % 2 == 0) 47.0 else 150.0) }
        assertTrue("fit must freeze after ~120 admitted samples", cs.frozen)
    }

    @Test
    fun colorSplitterRejectsOutlierForLearningAfterFit() {
        val cs = ColorSplitter()
        repeat(30) { cs.observe(47.0 + noise(it)); cs.observe(150.0 + noise(it + 5)) }
        assertTrue(cs.ready())
        val before = cs.sampleCount()
        val loBefore = cs.loC!!; val hiBefore = cs.hiC!!
        repeat(20) { cs.observe(4.0) }   // red, far from both learned colours
        assertEquals("outlier must not be admitted as a training sample", before, cs.sampleCount())
        assertEquals(loBefore, cs.loC!!, 1e-9)
        assertEquals(hiBefore, cs.hiC!!, 1e-9)
    }

    // ---- 4. ColorSplitter wrap test: clusters at 170 and 20 must fit 30 apart, not 150 ----
    @Test
    fun colorSplitterHandlesHueWrap() {
        val cs = ColorSplitter()
        val hues = ArrayList<Double>()
        repeat(15) { hues.add(170.0 + noise(it)); hues.add(20.0 + noise(it + 4)) }
        cs.forceSamplesAndFit(hues)
        assertTrue("wrapped clusters must still produce a fit", cs.ready())
        val gap = cs.hiC!! - cs.loC!!
        assertTrue("gap should be ~30 (wrap-aware), was $gap", gap in 20.0..45.0)
        val c = cs.centre!!
        val h1 = (c + cs.loC!!).mod(180.0)
        val h2 = (c + cs.hiC!!).mod(180.0)
        val hues2 = listOf(h1, h2).sorted()
        assertTrue("learned low hue ~20, was ${hues2[0]}", abs(hues2[0] - 20.0) < 6)
        assertTrue("learned high hue ~170, was ${hues2[1]}", abs(hues2[1] - 170.0) < 6)
    }

    // ---- 5. Decoder: a header with idx=0,total=4,len=8 has 13 identical bits; must decode ----
    @Test
    fun decoderHandles13IdenticalBits() {
        // The bit string for header bytes [0,4,8] starts with 13 consecutive zeros.
        val bits = packetToBits(byteArrayOf(0, 4, 8))
        val leadingZeros = bits.takeWhile { it == 0 }.size
        assertEquals("header [0,4,8] must contain 13 leading zero bits", 13, leadingZeros)
        // and a full 4-chunk message (whose chunk 0 has exactly this header) must decode:
        val message = "All your base are belong to us"
        assertEquals(message, runMessage(message, aMeansOne = true))
    }

    @Test
    fun colorSplitterMaxRunIsBitsAndAbove13() {
        // 13 identical BITS in a row must NOT disown the fit; a long run (>32) must.
        val cs = ColorSplitter()
        repeat(30) { cs.observe(47.0 + noise(it)); cs.observe(150.0 + noise(it + 2)) }
        assertTrue(cs.ready())
        val refitsBefore = cs.refits
        var t = 1000L
        repeat(13) { cs.classify(47.0, t); t += 100 }   // 13 distinct bits, same symbol
        assertEquals("13 identical bits must not disown the fit", refitsBefore, cs.refits)
        assertTrue(cs.ready())
        repeat(40) { cs.classify(47.0, t); t += 100 }   // now a genuinely stuck run
        assertEquals("a >32 identical-bit run must disown the fit", refitsBefore + 1, cs.refits)
    }

    // ---- 6. Assembler: false total must not wipe a message; two agreeing verified switch ----
    @Test
    fun assemblerDefendsMessageLength() {
        val a = Assembler()
        val orig = ByteArray(4) { it.toByte() }
        // establish a verified 4-chunk message
        assertNull(a.add(0, 4, "aaaa".toByteArray()))
        assertNull(a.add(1, 4, "bbbb".toByteArray()))
        assertNull(a.add(2, 4, "cccc".toByteArray()))
        val full = a.add(3, 4, "dddd".toByteArray())
        assertNotNull("message should complete at 4/4", full)
        assertEquals(4, a.total)

        // one stray verified chunk claiming total=2 must NOT wipe it
        assertNull(a.add(0, 2, "XXXX".toByteArray()))
        assertEquals("single false total must not switch length", 4, a.total)
        assertEquals("aaaabbbbccccdddd", String(rebuild(a), Charsets.UTF_8))

        // an unverified total=2 must also be ignored
        a.add(0, 2, "YYYY".toByteArray(), verified = false)
        assertEquals(4, a.total)

        // two agreeing verified chunks with total=2 DO switch
        a.add(0, 2, "pp".toByteArray())          // second vote for total=2 -> switch, adds idx0
        val done = a.add(1, 2, "qq".toByteArray())
        assertEquals(2, a.total)
        assertNotNull(done)
        assertEquals("ppqq", String(done!!, Charsets.UTF_8))
    }

    // ================= helpers =================

    /** Deterministic small hue jitter in roughly [-3, 3]. */
    private fun noise(seed: Int): Double = ((seed * 37) % 7 - 3).toDouble()

    private fun rebuild(a: Assembler): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until a.total!!) out.write(a.chunks[i]!!)
        return out.toByteArray()
    }

    /**
     * Simulate the physical layer for a whole message and run it through SymbolPipeline.
     * aMeansOne picks which observed colour ("A") carries a 1 bit, so both wire polarities
     * are exercised. Each phase is held for several frames so the vote debounce switches
     * cleanly, and the message is looped twice like the real sender.
     */
    private fun runMessage(message: String, aMeansOne: Boolean): String? {
        val bits = messageToBits(message)
        val pipe = SymbolPipeline()
        var t = 0L
        var completed: ByteArray? = null
        fun frame(sym: String?) {
            val r = pipe.feed(t, sym)
            if (r.completedMessage != null) completed = r.completedMessage
            t += 60
        }
        fun run(sym: String?, n: Int) = repeat(n) { frame(sym) }
        repeat(2) {
            run("SYNC", 5)
            for (b in bits) {
                val isA = (b == 1) == aMeansOne
                run(if (isA) "A" else "B", 5)
                run("SYNC", 5)
            }
            run("SYNC", 10)   // inter-lap gap
        }
        run("SYNC", 5)
        return completed?.let { String(it, Charsets.UTF_8) }
    }
}
