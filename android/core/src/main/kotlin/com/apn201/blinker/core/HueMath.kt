package com.apn201.blinker.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hue is on the OpenCV 0..179 scale everywhere in this project (so the reference
 * constants transfer). Hue wraps at 180, so plain averaging is wrong near the wrap
 * point - these mirror the vector-based helpers in pc_receiver.py.
 */

private const val DEG2RAD = Math.PI / 180.0

/** Circular mean of hues on the 0..179 scale. Mirrors circular_mean_hue(). */
fun circularMeanHue(hues: FloatArray): Double {
    if (hues.isEmpty()) return 0.0
    var sSum = 0.0
    var cSum = 0.0
    for (h in hues) {
        val rad = h.toDouble() * 2.0 * DEG2RAD
        sSum += sin(rad)
        cSum += cos(rad)
    }
    val n = hues.size
    var mean = Math.toDegrees(atan2(sSum / n, cSum / n)) % 360.0
    if (mean < 0) mean += 360.0
    return mean / 2.0
}

fun circularMeanHue(hues: List<Double>): Double {
    val arr = FloatArray(hues.size) { hues[it].toFloat() }
    return circularMeanHue(arr)
}

/** Tightness of a hue cluster, 0 = identical. Wrap-safe. Mirrors circular_spread(). */
fun circularSpread(hues: FloatArray): Double {
    if (hues.size < 2) return 0.0
    var sSum = 0.0
    var cSum = 0.0
    for (h in hues) {
        val rad = h.toDouble() * 2.0 * DEG2RAD
        sSum += sin(rad)
        cSum += cos(rad)
    }
    val n = hues.size
    var r = hypot(sSum / n, cSum / n)
    r = min(1.0, max(1e-9, r))
    return Math.toDegrees(sqrt(max(0.0, -2.0 * ln(r)))) / 2.0
}

/** Shortest distance between two hues on the 0..179 wheel. Mirrors hue_distance(). */
fun hueDistance(a: Double, b: Double): Double {
    val d = abs(a - b)
    return min(d, 180.0 - d)
}

/** Signed a-b in OpenCV hue units, result in -90..90. Mirrors circular_delta(). */
fun circularDelta(a: Double, b: Double): Double {
    return ((a - b + 90.0).mod(180.0)) - 90.0
}
