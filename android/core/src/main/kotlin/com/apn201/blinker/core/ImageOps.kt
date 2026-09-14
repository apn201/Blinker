package com.apn201.blinker.core

import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin image primitives, so the core needs no OpenCV and unit tests can drive it
 * with synthetic frames (README records this choice).
 *
 * Everything here is ALLOCATION-FREE per call: the caller supplies the destination and
 * scratch buffers and reuses them across frames. The first version of this file allocated
 * fresh arrays (and boxed Integer collections) every frame, which pinned a real device at
 * 1 fps in continuous GC. The port spec's "no allocation churn per frame: reuse buffers"
 * is not a style note.
 *
 * All ops take an inclusive region (x0,y0)-(x1,y1) so work can be confined to the part of
 * the frame that is actually flickering. Pixels outside the region are left untouched, so
 * callers clear the buffers once per frame.
 *
 * A rectangular structuring element is separable, so box morphology is two 1-D passes:
 * O(w*h*k) rather than O(w*h*k^2).
 */

/** Box dilation, (k x k) rectangular kernel, confined to an inclusive region. */
fun dilateBoxRegion(
    src: BooleanArray, dst: BooleanArray, tmp: BooleanArray,
    w: Int, h: Int, k: Int, x0: Int, y0: Int, x1: Int, y1: Int
) {
    val r = max(0, k / 2)
    // horizontal pass
    for (y in y0..y1) {
        val row = y * w
        for (x in x0..x1) {
            var on = false
            var xi = max(0, x - r)
            val xe = min(w - 1, x + r)
            while (xi <= xe) { if (src[row + xi]) { on = true; break }; xi++ }
            tmp[row + x] = on
        }
    }
    // vertical pass
    for (y in y0..y1) {
        val row = y * w
        for (x in x0..x1) {
            var on = false
            var yi = max(0, y - r)
            val ye = min(h - 1, y + r)
            while (yi <= ye) { if (tmp[yi * w + x]) { on = true; break }; yi++ }
            dst[row + x] = on
        }
    }
}

/** Box erosion, (k x k) rectangular kernel. Outside the frame counts as background. */
fun erodeBoxRegion(
    src: BooleanArray, dst: BooleanArray, tmp: BooleanArray,
    w: Int, h: Int, k: Int, x0: Int, y0: Int, x1: Int, y1: Int
) {
    val r = max(0, k / 2)
    for (y in y0..y1) {
        val row = y * w
        for (x in x0..x1) {
            var allOn = true
            if (x - r < 0 || x + r > w - 1) {
                tmp[row + x] = false
                continue
            }
            var xi = x - r
            val xe = x + r
            while (xi <= xe) { if (!src[row + xi]) { allOn = false; break }; xi++ }
            tmp[row + x] = allOn
        }
    }
    for (y in y0..y1) {
        val row = y * w
        for (x in x0..x1) {
            var allOn = true
            if (y - r < 0 || y + r > h - 1) {
                dst[row + x] = false
                continue
            }
            var yi = y - r
            val ye = y + r
            while (yi <= ye) { if (!tmp[yi * w + x]) { allOn = false; break }; yi++ }
            dst[row + x] = allOn
        }
    }
}

/** Morphological close (dilate then erode). Needs two scratch buffers. */
fun closeBoxRegion(
    src: BooleanArray, dst: BooleanArray, tmpA: BooleanArray, tmpB: BooleanArray,
    w: Int, h: Int, k: Int, x0: Int, y0: Int, x1: Int, y1: Int
) {
    dilateBoxRegion(src, tmpA, tmpB, w, h, k, x0, y0, x1, y1)
    erodeBoxRegion(tmpA, dst, tmpB, w, h, k, x0, y0, x1, y1)
}

/**
 * 8-connected component scan over a region, allocation-free.
 *
 * [visited], [stack] and [comp] are caller-owned scratch arrays of at least w*h entries;
 * [visited] must be cleared by the caller before each frame. For each component found,
 * [onBlob] is invoked with its bounding box and its pixel indices in [comp] (valid only
 * for the duration of the call, so the callback must not retain the array).
 */
inline fun scanComponents(
    mask: BooleanArray, w: Int, h: Int,
    visited: BooleanArray, stack: IntArray, comp: IntArray,
    x0: Int, y0: Int, x1: Int, y1: Int,
    onBlob: (minX: Int, minY: Int, maxX: Int, maxY: Int, count: Int, pixels: IntArray) -> Unit
) {
    for (sy in y0..y1) {
        for (sx in x0..x1) {
            val start = sy * w + sx
            if (!mask[start] || visited[start]) continue
            var sp = 0
            var cn = 0
            stack[sp++] = start
            visited[start] = true
            var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
            while (sp > 0) {
                val p = stack[--sp]
                comp[cn++] = p
                val px = p % w
                val py = p / w
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                var dy = -1
                while (dy <= 1) {
                    val ny = py + dy
                    if (ny in 0 until h) {
                        var dx = -1
                        while (dx <= 1) {
                            val nx = px + dx
                            if ((dx != 0 || dy != 0) && nx >= 0 && nx < w) {
                                val np = ny * w + nx
                                if (mask[np] && !visited[np]) {
                                    visited[np] = true
                                    stack[sp++] = np
                                }
                            }
                            dx++
                        }
                    }
                    dy++
                }
            }
            onBlob(minX, minY, maxX, maxY, cn, comp)
        }
    }
}

/**
 * Erode one blob to its interior and write the surviving global pixel indices into [out].
 * Mirrors the per-contour erode in find_and_read that shrinks a blob before reading its
 * colour, so the reading comes from the LEDs and not the blob's edge. Returns the count.
 */
fun blobCoreInto(
    pixels: IntArray, count: Int, minX: Int, minY: Int, bw: Int, bh: Int,
    w: Int, k: Int, subA: BooleanArray, subB: BooleanArray, subC: BooleanArray, out: IntArray
): Int {
    val n = bw * bh
    java.util.Arrays.fill(subA, 0, n, false)
    for (i in 0 until count) {
        val p = pixels[i]
        val x = p % w - minX
        val y = p / w - minY
        subA[y * bw + x] = true
    }
    erodeBoxRegion(subA, subB, subC, bw, bh, k, 0, 0, bw - 1, bh - 1)
    var m = 0
    for (y in 0 until bh) {
        for (x in 0 until bw) {
            if (subB[y * bw + x]) out[m++] = (y + minY) * w + (x + minX)
        }
    }
    return m
}
