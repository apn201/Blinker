package com.apn201.blinker.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Everything the UI needs about one processed frame. The persistent state (assembler,
 * decoders, colour fit, tracker) lives on the [Receiver] and is read directly for the HUD.
 */
data class FrameResult(
    val cls: String?,             // "A", "B", "SYNC", or null this frame
    val box: Box?,                // lock box to draw, if any
    val locked: Boolean,
    val active: Boolean,          // activity gate trusts this location
    val hue: Double?,
    val sat: Double,
    val v: Double,
    val swing: Double,
    val flickerArea: Int,
    val peakSat: Int,
    val statesSeen: Int,
    val reason: String,
    val completedMessage: ByteArray?,   // non-null iff a verified message just completed
    val chunkVerified: Boolean,         // a chunk finished and passed CRC this frame
    val reacquired: Boolean
)

/**
 * The full optical-link receiver, one frame at a time. Direct port of find_and_read() +
 * the receive() loop body in pc_receiver.py, minus the OpenCV capture/render (that is the
 * Android layer's job). Feed it HSV frames with monotonically increasing timestamps.
 *
 * Performance: every large buffer is allocated once per frame SIZE and reused, and the
 * expensive stages (threshold, morphological close, connected components) run only inside
 * the region that is actually flickering. Detection is change-first, so that region is
 * small in a normal scene and empty in a static one.
 */
class Receiver {
    companion object {
        const val VAL_MIN = 42
        const val BLOB_MIN_SAT = 85
        const val MAX_AREA_FRAC = 0.12
        const val FLICKER_MIN_AREA = 150
        const val SHAPE_ASPECT_MAX = 1.9
        const val SHAPE_MIN_EXTENT = 0.5
        const val CLOSE_K = 7
        const val REACQUIRE_MS = 6000L
        const val PEAK_STEP = 4       // peakSat is HUD-only; sample it rather than sweep

    }

    private var flicker = FlickerMap()
    private var tracker = Tracker()
    var colors = ColorSplitter()
        private set
    private var reader = BoxReader()
    private var activity = ActivityGate()
    private val satAdapter = SatAdapter()
    val pipeline = SymbolPipeline()

    private var lastBox: Box? = null
    private var lastSymbolMs: Long? = null

    // ---- reusable buffers, sized to the frame ----
    private var bufW = 0
    private var bufH = 0
    private var flickBuf = BooleanArray(0)
    private var maskBuf = BooleanArray(0)
    private var closedBuf = BooleanArray(0)
    private var tmpA = BooleanArray(0)
    private var tmpB = BooleanArray(0)
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)
    private var comp = IntArray(0)
    private var coreIdx = IntArray(0)
    private var subA = BooleanArray(0)
    private var subB = BooleanArray(0)
    private var subC = BooleanArray(0)
    private val satHist = IntArray(256)
    private val found = ArrayList<Candidate>(8)

    // Per-stage timings in ms, for profiling the pipeline on a real device.
    var msFlicker = 0.0; private set
    var msPeak = 0.0; private set
    var msMask = 0.0; private set
    var msDetect = 0.0; private set
    var msRead = 0.0; private set

    fun timingSummary(): String =
        "flick %.0f peak %.0f mask %.0f detect %.0f read %.0f"
            .format(msFlicker, msPeak, msMask, msDetect, msRead)

    val assembler get() = pipeline.assembler
    fun activeDecoder(): Decoder = pipeline.activeDecoder()
    val symCount get() = pipeline.symCount
    val polarity get() = pipeline.polarity
    val chunksOk get() = pipeline.chunksOk
    val chunksSeen get() = pipeline.chunksSeen
    fun satMin(): Double = satAdapter.satMin

    private fun ensureBuffers(w: Int, h: Int) {
        if (w == bufW && h == bufH) return
        val n = w * h
        flickBuf = BooleanArray(n)
        maskBuf = BooleanArray(n)
        closedBuf = BooleanArray(n)
        tmpA = BooleanArray(n)
        tmpB = BooleanArray(n)
        visited = BooleanArray(n)
        stack = IntArray(n)
        comp = IntArray(n)
        coreIdx = IntArray(n)
        subA = BooleanArray(n)
        subB = BooleanArray(n)
        subC = BooleanArray(n)
        bufW = w
        bufH = h
    }

    /** Process one frame. Returns per-frame telemetry and any decode events. */
    fun process(frame: HsvFrame, nowMs: Long): FrameResult {
        ensureBuffers(frame.width, frame.height)
        val tf = System.nanoTime()
        flicker.update(frame)
        msFlicker = (System.nanoTime() - tf) / 1e6
        val fr = findAndRead(frame, nowMs)
        val cls = fr.cls
        satAdapter.update(cls != null, fr.peakSat)

        // The dark phase is a genuine state; a location that alternates lit/dark is by
        // definition blinking, which is what proves it is a transmitter.
        if (cls == "SYNC" && lastBox != null) {
            activity.observe(lastBox!!, "SYNC", nowMs, 0.0)
            val (trusted, nStates, _) = activity.evidence(lastBox!!, nowMs)
            fr.active = trusted
            fr.statesSeen = nStates
        }
        val active = fr.active
        val decoderCls = if (active) cls else null
        if (cls != null && !active) fr.reason = "static? ${fr.statesSeen} state(s)"

        pipeline.countRaw(cls)
        if (cls == "A" || cls == "B") lastSymbolMs = nowMs

        // Data has stopped: throw the front-end away and re-acquire from scratch. The
        // assembler (the message so far) is kept.
        var reacquired = false
        val ls = lastSymbolMs
        if (ls != null && (nowMs - ls) > REACQUIRE_MS) {
            tracker.reset(); colors.reset(); reader.reset()
            activity = ActivityGate()
            flicker = FlickerMap()
            pipeline.reset()
            lastBox = null
            lastSymbolMs = null
            reacquired = true
        }

        val feed = pipeline.feed(nowMs, decoderCls)
        if (feed.verifiedChunk) colors.freeze()

        return FrameResult(
            cls = cls,
            box = fr.box ?: lastBox,
            locked = tracker.hasLock(nowMs),
            active = active,
            hue = fr.hue,
            sat = fr.sat,
            v = fr.v,
            swing = fr.swing,
            flickerArea = fr.flickerArea,
            peakSat = fr.peakSat,
            statesSeen = fr.statesSeen,
            reason = fr.reason,
            completedMessage = feed.completedMessage,
            chunkVerified = feed.verifiedChunk,
            reacquired = reacquired
        )
    }

    fun fullReset() {
        flicker = FlickerMap(); tracker = Tracker(); colors = ColorSplitter()
        reader = BoxReader(); activity = ActivityGate(); satAdapter.reset()
        pipeline.reset()
        lastBox = null; lastSymbolMs = null
    }

    // ---- mutable per-frame telemetry the private pipeline fills in ----
    private class Tel {
        var cls: String? = null
        var box: Box? = null
        var hue: Double? = null
        var sat = 0.0
        var v = 0.0
        var swing = 0.0
        var flickerArea = 0
        var peakSat = 0
        var active = false
        var statesSeen = 0
        var reason = ""
    }

    private fun findAndRead(frame: HsvFrame, nowMs: Long): Tel {
        val W = frame.width
        val H = frame.height
        val tel = Tel()
        val tp = System.nanoTime()
        tel.peakSat = peakSat(frame)
        msPeak = (System.nanoTime() - tp) / 1e6

        val tm = System.nanoTime()
        val haveMask = flicker.maskInto(flickBuf, W, H)
        msMask = (System.nanoTime() - tm) / 1e6
        if (!haveMask) {
            tel.reason = "measuring flicker..."
            tel.box = lastBox
            return tel
        }
        tel.flickerArea = flicker.area

        val td = System.nanoTime()
        found.clear()
        val rx0 = flicker.roiX0; val ry0 = flicker.roiY0
        val rx1 = flicker.roiX1; val ry1 = flicker.roiY1
        if (rx1 >= rx0 && ry1 >= ry0) {
            // Expand the working region so the close operation behaves as it would on the
            // whole frame, then clamp to the frame.
            val x0 = max(0, rx0 - CLOSE_K); val y0 = max(0, ry0 - CLOSE_K)
            val x1 = min(W - 1, rx1 + CLOSE_K); val y1 = min(H - 1, ry1 + CLOSE_K)

            java.util.Arrays.fill(maskBuf, false)
            java.util.Arrays.fill(closedBuf, false)
            java.util.Arrays.fill(visited, false)

            val satLo = max(40, min(satAdapter.satMin.toInt(), BLOB_MIN_SAT))
            for (y in y0..y1) {
                val row = y * W
                for (x in x0..x1) {
                    val i = row + x
                    if (!flickBuf[i]) continue
                    val s = frame.s[i].toInt() and 0xFF
                    if (s < satLo) continue
                    if ((frame.v[i].toInt() and 0xFF) < VAL_MIN) continue
                    maskBuf[i] = true
                }
            }
            closeBoxRegion(maskBuf, closedBuf, tmpA, tmpB, W, H, CLOSE_K, x0, y0, x1, y1)

            val frameArea = (W * H).toDouble()
            scanComponents(closedBuf, W, H, visited, stack, comp, x0, y0, x1, y1) {
                minX, minY, maxX, maxY, count, pixels ->
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (count >= FLICKER_MIN_AREA && count <= frameArea * MAX_AREA_FRAC &&
                    bw >= 10 && bh >= 10 &&
                    max(bw, bh).toDouble() / min(bw, bh) <= SHAPE_ASPECT_MAX &&
                    count.toDouble() / (bw * bh) >= SHAPE_MIN_EXTENT
                ) {
                    val k = max(1, (min(bw, bh) * 0.2).toInt()) or 1
                    var m = blobCoreInto(pixels, count, minX, minY, bw, bh, W, k, subA, subB, subC, coreIdx)
                    var src = coreIdx
                    if (m < 25) { src = pixels; m = count }
                    if (m >= 25) {
                        var sinS = 0.0; var cosS = 0.0; var satSum = 0.0; var valSum = 0.0
                        for (i in 0 until m) {
                            val p = src[i]
                            val rad = (frame.h[p].toInt() and 0xFF) * 2.0 * Math.PI / 180.0
                            sinS += sin(rad); cosS += cos(rad)
                            satSum += frame.s[p].toInt() and 0xFF
                            valSum += frame.v[p].toInt() and 0xFF
                        }
                        var deg = Math.toDegrees(atan2(sinS / m, cosS / m)) % 360.0
                        if (deg < 0) deg += 360.0
                        val hue = deg / 2.0
                        val sat = satSum / m
                        val v = valSum / m
                        if (sat >= BLOB_MIN_SAT && (!colors.ready() || colors.matches(hue))) {
                            var closeness = 0.0
                            if (colors.ready()) closeness = max(0.0, 30.0 - colors.distanceToNearest(hue))
                            val score = closeness * 2.0 + sat * 0.03 + v * 0.01
                            found.add(Candidate(score, hue, sat, v, Box(minX, minY, bw, bh)))
                        }
                    }
                }
            }
        }

        msDetect = (System.nanoTime() - td) / 1e6

        val chosen = tracker.update(found, nowMs)

        // ---- LOCKED: read the bit off the box itself, ignore contours ----
        if (tracker.hasLock(nowMs)) {
            val box = tracker.box()!!
            lastBox = box
            val tr = System.nanoTime()
            val r = reader.read(frame, box)
            msRead = (System.nanoTime() - tr) / 1e6
            tel.box = box
            tel.v = r.vtop
            tel.swing = r.swing
            if (r.lit == null) {
                tel.reason = "settling (swing ${"%.0f".format(r.swing)})"
                return tel
            }
            if (!r.lit) {
                tel.reason = "dark (separator)"
                tel.active = true
                tel.cls = "SYNC"
                return tel
            }
            tracker.touch(nowMs)
            val name = colors.classify(r.hue, nowMs)
            colors.observe(r.hue)
            activity.observe(box, name ?: "LIT", nowMs, r.vtop)
            val (trusted, nStates, _) = activity.evidence(box, nowMs)
            tel.active = trusted
            tel.statesSeen = nStates
            tel.hue = r.hue
            tel.sat = r.sat
            tel.reason = if (name == null) "calibrating colours" else ""
            tel.cls = name
            return tel
        }

        if (chosen == null) {
            val hasLock = tracker.hasLock(nowMs)
            tel.reason = if (hasLock) "dark (separator)" else "acquiring lock..."
            tel.box = tracker.box() ?: lastBox
            if (!hasLock) return tel
            tel.active = true
            tel.cls = "SYNC"
            return tel
        }

        val box = tracker.box() ?: chosen.box
        lastBox = box
        tel.box = box
        val name = colors.classify(chosen.hue, nowMs)
        activity.observe(box, name ?: "LIT", nowMs, chosen.v)
        val (trusted, nStates, _) = activity.evidence(box, nowMs)
        tel.active = trusted
        tel.statesSeen = nStates
        colors.observe(chosen.hue)
        tel.hue = chosen.hue
        tel.sat = chosen.sat
        tel.v = chosen.v
        if (name == null) {
            val hasLock = tracker.hasLock(nowMs)
            tel.reason = if (hasLock) "outlier colour - treating as dark" else "calibrating"
            if (hasLock) {
                tel.active = true
                tel.cls = "SYNC"
            }
            return tel
        }
        tel.cls = name
        return tel
    }

    /**
     * 99th percentile of saturation among bright pixels, else the max. Mirrors peak_sat.
     * Uses a 256-bin histogram: the first version built and sorted a boxed Integer list of
     * every bright pixel, which alone was enough to hold a device at 1 fps.
     */
    private fun peakSat(frame: HsvFrame): Int {
        java.util.Arrays.fill(satHist, 0)
        var maxSat = 0
        var bright = 0
        val n = frame.width * frame.height
        // Sampled every PEAK_STEP pixels: this value is HUD telemetry only. SatAdapter's
        // adaptation path uses cellSatHint (never supplied), so peakSat never feeds the
        // saturation floor - a full-frame pass for it was pure cost.
        var i = 0
        while (i < n) {
            val s = frame.s[i].toInt() and 0xFF
            if (s > maxSat) maxSat = s
            if ((frame.v[i].toInt() and 0xFF) > 70) {
                satHist[s]++
                bright++
            }
            i += PEAK_STEP
        }
        if (bright <= 50) return maxSat
        val target = ((bright - 1) * 99) / 100
        var acc = 0
        for (s in 0..255) {
            acc += satHist[s]
            if (acc > target) return s
        }
        return maxSat
    }
}
