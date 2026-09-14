package com.apn201.blinker.core

/**
 * A single video frame in HSV, planar, row-major. Hue is on the OpenCV 0..179 scale;
 * saturation and value are 0..255. The Android layer converts YUV_420_888 to this on
 * the analyzer thread (see the app module); the synthetic harness builds it directly.
 *
 * Bytes are stored unsigned - always read through [hAt]/[sAt]/[vAt] which mask &0xFF.
 */
class HsvFrame(
    val width: Int,
    val height: Int,
    val h: ByteArray,
    val s: ByteArray,
    val v: ByteArray
) {
    init {
        val n = width * height
        require(h.size == n && s.size == n && v.size == n) { "plane size must be width*height" }
    }

    fun hAt(x: Int, y: Int): Int = h[y * width + x].toInt() and 0xFF
    fun sAt(x: Int, y: Int): Int = s[y * width + x].toInt() and 0xFF
    fun vAt(x: Int, y: Int): Int = v[y * width + x].toInt() and 0xFF

    companion object {
        /** Build from an HSV int layout for tests/harness convenience. */
        fun of(width: Int, height: Int, hsv: (x: Int, y: Int) -> Triple<Int, Int, Int>): HsvFrame {
            val n = width * height
            val h = ByteArray(n); val s = ByteArray(n); val v = ByteArray(n)
            for (y in 0 until height) for (x in 0 until width) {
                val (hh, ss, vv) = hsv(x, y)
                val i = y * width + x
                h[i] = hh.toByte(); s[i] = ss.toByte(); v[i] = vv.toByte()
            }
            return HsvFrame(width, height, h, s, v)
        }
    }
}
