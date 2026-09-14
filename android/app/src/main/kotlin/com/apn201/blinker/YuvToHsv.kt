package com.apn201.blinker

import androidx.camera.core.ImageProxy
import com.apn201.blinker.core.HsvFrame
import kotlin.math.max

/**
 * Converts a CameraX YUV_420_888 frame to an [HsvFrame] on the OpenCV 0..179 hue scale,
 * rotated to upright in the same pass. Rotating here (rather than downstream) keeps the
 * core's pixel-space constants meaningful and makes the HUD box mapping a plain uniform
 * fill-centre scale.
 *
 * Plane handling reads pixelStride/rowStride explicitly, so it works whether the device
 * delivers I420 (chroma pixelStride 1) or NV12/NV21 (chroma pixelStride 2) - a real
 * cross-device pitfall called out in the port spec.
 *
 * The YUV->RGB constants are BT.601. Exact hue is deliberately not relied upon anywhere:
 * the ColorSplitter learns whatever this conversion actually renders.
 */
object YuvToHsv {

    /**
     * Longest side of the working frame. The core's constants (blob areas, search radii,
     * cell sizes) are all in pixels and tuned around 640x480, and the port spec says higher
     * resolution is slower and gains nothing. Cameras do not always honour a requested
     * analysis size, so the conversion subsamples to this regardless of what arrives -
     * that keeps both the constants meaningful and the per-frame cost bounded.
     */
    const val TARGET_LONG_SIDE = 640

    // The analyzer is single-threaded and the frame is consumed synchronously before the
    // next one arrives, so one reusable frame is enough. Allocating three ~300 KB arrays
    // per frame here was part of what held the app at 1 fps in continuous GC.
    private var cached: HsvFrame? = null

    // Plane staging buffers. Reading pixels through ByteBuffer.get(index) on a DIRECT
    // buffer costs roughly a microsecond each on a device; at three reads per pixel that
    // alone was ~275 ms/frame (measured). Bulk-copying each plane once and indexing plain
    // byte arrays is the single biggest win in this file.
    private var yArr = ByteArray(0)
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)

    private fun stage(buf: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        buf.rewind()
        val n = buf.remaining()
        val dst = if (into.size >= n) into else ByteArray(n)
        buf.get(dst, 0, n)
        return dst
    }

    private fun frameFor(w: Int, h: Int): HsvFrame {
        val c = cached
        if (c != null && c.width == w && c.height == h) return c
        val n = w * h
        val f = HsvFrame(w, h, ByteArray(n), ByteArray(n), ByteArray(n))
        cached = f
        return f
    }

    fun convert(image: ImageProxy, rotationDegrees: Int): HsvFrame {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        yArr = stage(yP.buffer, yArr)
        uArr = stage(uP.buffer, uArr)
        vArr = stage(vP.buffer, vArr)
        val yBuf = yArr
        val uBuf = uArr
        val vBuf = vArr
        val yRow = yP.rowStride
        val uRow = uP.rowStride
        val vRow = vP.rowStride
        val uPix = uP.pixelStride
        val vPix = vP.pixelStride

        val swap = rotationDegrees == 90 || rotationDegrees == 270
        val uw = if (swap) h else w      // upright, full resolution
        val uh = if (swap) w else h
        // subsample so the working frame's long side is about TARGET_LONG_SIDE
        val step = max(1, (max(uw, uh) + TARGET_LONG_SIDE - 1) / TARGET_LONG_SIDE)
        val ow = uw / step
        val oh = uh / step
        val out = frameFor(ow, oh)
        val hOut = out.h
        val sOut = out.s
        val vOut = out.v

        var oi = 0
        for (oy in 0 until oh) {
            val uy = oy * step
            for (ox in 0 until ow) {
                val ux = ox * step
                val sx: Int
                val sy: Int
                when (rotationDegrees) {
                    90 -> { sx = uy; sy = h - 1 - ux }
                    180 -> { sx = w - 1 - ux; sy = h - 1 - uy }
                    270 -> { sx = w - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }

                val y = yBuf[sy * yRow + sx].toInt() and 0xFF
                val uvRow = (sy shr 1)
                val uvCol = (sx shr 1)
                val u = (uBuf[uvRow * uRow + uvCol * uPix].toInt() and 0xFF) - 128
                val v = (vBuf[uvRow * vRow + uvCol * vPix].toInt() and 0xFF) - 128

                // BT.601 YUV -> RGB
                var r = y + ((91881 * v) shr 16)
                var g = y - ((22554 * u + 46802 * v) shr 16)
                var b = y + ((116130 * u) shr 16)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                // RGB -> HSV (OpenCV: H 0..179, S/V 0..255)
                val maxc = if (r >= g) (if (r >= b) r else b) else (if (g >= b) g else b)
                val minc = if (r <= g) (if (r <= b) r else b) else (if (g <= b) g else b)
                val delta = maxc - minc
                val sv = if (maxc == 0) 0 else (delta * 255) / maxc
                var hue: Int
                if (delta == 0) {
                    hue = 0
                } else {
                    val hf: Float = when (maxc) {
                        r -> 60f * ((g - b).toFloat() / delta)
                        g -> 60f * (2f + (b - r).toFloat() / delta)
                        else -> 60f * (4f + (r - g).toFloat() / delta)
                    }
                    var hd = hf
                    if (hd < 0f) hd += 360f
                    hue = (hd / 2f + 0.5f).toInt()
                    if (hue > 179) hue = 179
                }

                hOut[oi] = hue.toByte()
                sOut[oi] = sv.toByte()
                vOut[oi] = maxc.toByte()
                oi++
            }
        }
        return out
    }
}
