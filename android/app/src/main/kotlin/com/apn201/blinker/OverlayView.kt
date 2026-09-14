package com.apn201.blinker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.apn201.blinker.core.ChunkStatus
import com.apn201.blinker.core.RichSegment
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the lock box, the telemetry HUD and the decoded message over the camera preview.
 * Laid out for a portrait phone screen:
 *
 *  - all sizes are density-scaled (dp), never raw pixels
 *  - the telemetry text is auto-shrunk until the longest line fits the screen width, so
 *    nothing ever runs off the edge
 *  - each panel is sized to the text it actually contains, so no line lands half on the
 *    panel and half on the camera image
 *  - the message wraps across lines instead of running off to the right
 *  - status bar and the bottom control bar are kept clear via insets
 *
 * The field set is the PC receiver's HUD (ANDROID_PORT_SPEC section 4) - every field has
 * caught a real bug, so they are all kept, just tightened for a narrow screen.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        val GREEN = Color.rgb(60, 230, 90)
        val BLUE = Color.rgb(110, 150, 255)
        val AMBER = Color.rgb(255, 180, 40)
        val CYAN = Color.rgb(80, 220, 220)
        val GRAY = Color.rgb(200, 200, 200)
        val RED = Color.rgb(255, 90, 90)
        const val MAX_MSG_LINES = 6
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    @Volatile private var state: HudState? = null
    private var topInset = 0
    private var bottomInset = 0
    private var bottomBarHeight = 0

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = dp(0.7f)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 0, 0, 0) }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)

    fun setSystemInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        postInvalidateOnAnimation()
    }

    fun setBottomBarHeight(h: Int) {
        bottomBarHeight = h
        postInvalidateOnAnimation()
    }

    fun update(s: HudState) {
        state = s
        postInvalidateOnAnimation()
    }

    private fun symbolColor(cls: String?): Int = when (cls) {
        "A" -> GREEN
        "B" -> BLUE
        "SYNC" -> AMBER
        else -> GRAY
    }

    private fun statusColor(st: ChunkStatus): Int = when (st) {
        ChunkStatus.VERIFIED -> GREEN
        ChunkStatus.TENTATIVE -> AMBER
        ChunkStatus.MISSING -> GRAY
    }

    /** Shrink [paint] until the widest line fits [maxWidth]. */
    private fun fitTextSize(paint: Paint, lines: List<String>, maxWidth: Float, maxSp: Float, minSp: Float) {
        var size = dp(maxSp)
        val floor = dp(minSp)
        while (size > floor) {
            paint.textSize = size
            val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            if (widest <= maxWidth) return
            size -= dp(0.5f)
        }
        paint.textSize = floor
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val pad = dp(8f)
        val contentW = vw - 2 * pad

        drawLockBox(canvas, s, vw, vh)
        val hudBottom = drawHud(canvas, s, vw, pad, contentW)
        drawMessage(canvas, s, vw, vh, pad, contentW, hudBottom)
    }

    private fun drawLockBox(canvas: Canvas, s: HudState, vw: Float, vh: Float) {
        val b = s.box ?: return
        if (s.srcW <= 0 || s.srcH <= 0) return
        // PreviewView uses FILL_CENTER, so map with a uniform scale + centre offset.
        val scale = max(vw / s.srcW, vh / s.srcH)
        val dx = (vw - s.srcW * scale) / 2f
        val dy = (vh - s.srcH * scale) / 2f
        val rect = RectF(
            b.x * scale + dx, b.y * scale + dy,
            (b.x + b.w) * scale + dx, (b.y + b.h) * scale + dy
        )
        boxPaint.color = symbolColor(s.cls)
        if (s.locked && s.active) {
            boxPaint.strokeWidth = dp(2.5f)
            boxPaint.pathEffect = null
        } else {
            boxPaint.strokeWidth = dp(1.5f)
            boxPaint.pathEffect = dash
        }
        canvas.drawRect(rect, boxPaint)
        if (s.cls != null && s.active) {
            for (r in 1 until 5) {
                val fy = rect.top + r * rect.height() / 5f
                canvas.drawLine(rect.left, fy, rect.right, fy, gridPaint)
                val fx = rect.left + r * rect.width() / 5f
                canvas.drawLine(fx, rect.top, fx, rect.bottom, gridPaint)
            }
        }
    }

    /** Draws the telemetry panel at the top; returns its bottom edge. */
    private fun drawHud(canvas: Canvas, s: HudState, vw: Float, pad: Float, contentW: Float): Float {
        val lines = buildHudLines(s)
        fitTextSize(hudPaint, lines.map { it.first }, contentW, 13f, 7.5f)
        val lineH = hudPaint.textSize * 1.35f
        val top = topInset + pad / 2f
        val panelH = pad + lines.size * lineH + pad / 2f
        canvas.drawRoundRect(
            RectF(pad / 2f, top, vw - pad / 2f, top + panelH), dp(6f), dp(6f), panelPaint
        )
        var y = top + pad + hudPaint.textSize * 0.85f
        for ((text, colr) in lines) {
            hudPaint.color = colr
            canvas.drawText(text, pad, y, hudPaint)
            y += lineH
        }
        return top + panelH
    }

    private fun buildHudLines(s: HudState): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val status = if (s.locked) "LOCKED" else "SEARCHING"
        val fpsWarn = if (s.fps in 1..14) " !LOW" else ""
        out.add("OPTICAL  $status  ${s.fps}fps$fpsWarn" to
                if (s.fps in 1..14) RED else if (s.locked) GREEN else AMBER)

        val rate = s.msPerBit?.let {
            "RATE ${it.toInt()}ms/bit (%.2f b/s)".format(1000.0 / it)
        } ?: "RATE measuring..."
        out.add(rate to CYAN)

        out.add("RES ${s.srcW}x${s.srcH}  ${s.frameMs}ms/frame" to GRAY)
        out.add("COL ${s.colorsDesc}" to GRAY)
        out.add("BOX v${s.boxV.toInt()} sw${s.boxSwing.toInt()}   SIG ${s.cls ?: "-"} st${s.statesSeen}"
                to symbolColor(s.cls))

        val bal = if (s.symA + s.symB > 0)
            "${(100.0 * min(s.symA, s.symB) / (s.symA + s.symB)).toInt()}%" else "-"
        out.add("SYM A${s.symA} B${s.symB} S${s.symSync}  bal $bal" to CYAN)
        out.add("BUF ${s.bufferBits}/40 bits${s.totalBits} hdr${s.hdrTries} chk${s.chunkTries}" to CYAN)

        val lh = s.lastHeader
        if (lh == null) {
            out.add("HDR none yet" to GRAY)
        } else {
            out.add("HDR i${lh.idx} t${lh.total} l${lh.length} pl:${yn(lh.plausible)} crc:${yn(lh.crcOk)}"
                    to if (lh.crcOk) GREEN else AMBER)
        }

        val q = if (s.chunksSeen > 0) "${(100.0 * s.chunksOk / s.chunksSeen).toInt()}%" else "--"
        out.add("PROG ${s.progress}  ok ${s.chunksOk}/${s.chunksSeen} $q  pol ${s.polarity ?: "-"}" to GRAY)

        if (s.reason.isNotEmpty()) out.add(s.reason to AMBER)
        return out
    }

    private fun yn(b: Boolean) = if (b) "y" else "n"

    private fun drawMessage(
        canvas: Canvas, s: HudState, vw: Float, vh: Float,
        pad: Float, contentW: Float, hudBottom: Float
    ) {
        if (s.segments.isEmpty()) return

        msgPaint.textSize = dp(17f)
        var charW = msgPaint.measureText("0")
        var maxChars = ((contentW) / charW).toInt()
        // if the message is short, keep it big; otherwise shrink a little so more fits
        if (maxChars < 16) {
            msgPaint.textSize = dp(13f)
            charW = msgPaint.measureText("0")
            maxChars = max(8, (contentW / charW).toInt())
        }

        var lines = wrapSegments(s.segments, maxChars)
        if (lines.size > MAX_MSG_LINES) lines = lines.subList(0, MAX_MSG_LINES)

        labelPaint.textSize = dp(10f)
        val lineH = msgPaint.textSize * 1.3f
        val labelH = labelPaint.textSize * 1.5f
        val panelH = pad + labelH + lines.size * lineH + pad / 2f

        val bottomLimit = vh - max(bottomBarHeight.toFloat(), bottomInset.toFloat()) - pad / 2f
        val top = max(hudBottom + pad / 2f, bottomLimit - panelH)

        canvas.drawRoundRect(
            RectF(pad / 2f, top, vw - pad / 2f, top + panelH), dp(6f), dp(6f), panelPaint
        )

        labelPaint.color = GRAY
        canvas.drawText(
            "MESSAGE  ${s.progress}   green=verified  amber=unverified  grey=missing",
            pad, top + pad + labelPaint.textSize * 0.6f, labelPaint
        )

        var y = top + pad + labelH + msgPaint.textSize * 0.85f
        for (line in lines) {
            var x = pad
            for ((text, colr) in line) {
                msgPaint.color = colr
                canvas.drawText(text, x, y, msgPaint)
                x += msgPaint.measureText(text)
            }
            y += lineH
        }
    }

    /** Wrap the coloured runs to [maxChars] per line, preserving each run's colour. */
    private fun wrapSegments(segs: List<RichSegment>, maxChars: Int): List<List<Pair<String, Int>>> {
        val lines = ArrayList<List<Pair<String, Int>>>()
        var cur = ArrayList<Pair<String, Int>>()
        var used = 0
        for (seg in segs) {
            val colr = statusColor(seg.status)
            var t = seg.text.replace('\n', ' ')
            while (t.isNotEmpty()) {
                val room = maxChars - used
                if (room <= 0) {
                    lines.add(cur); cur = ArrayList(); used = 0
                    continue
                }
                val take = min(room, t.length)
                cur.add(t.substring(0, take) to colr)
                used += take
                t = t.substring(take)
                if (used >= maxChars) {
                    lines.add(cur); cur = ArrayList(); used = 0
                }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }
}
