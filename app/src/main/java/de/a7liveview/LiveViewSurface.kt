package de.a7liveview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.sqrt

/** Zeigt das Live-View-Bild mit Overlay (Peaking/Zebra) und Histogramm. */
class LiveViewSurface(context: Context) : View(context) {

    @Volatile private var result: AnalysisResult? = null

    private val dst = RectF()
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint() // ohne Filter, damit Peaking/Zebra scharf bleiben
    private val histBg = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val histBar = Paint().apply { color = Color.argb(220, 255, 255, 255) }

    fun show(r: AnalysisResult) {
        result = r
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val r = result ?: return

        val bw = r.frame.width.toFloat()
        val bh = r.frame.height.toFloat()
        val scale = minOf(width / bw, height / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        dst.set(left, top, left + dw, top + dh)

        canvas.drawBitmap(r.frame, null, dst, framePaint)
        r.overlay?.let { canvas.drawBitmap(it, null, dst, overlayPaint) }
        r.histogram?.let { drawHistogram(canvas, it) }
    }

    private fun drawHistogram(canvas: Canvas, hist: IntArray) {
        val d = resources.displayMetrics.density
        val boxW = 200f * d
        val boxH = 80f * d
        val margin = 8f * d

        val right = dst.right - margin
        val left = right - boxW
        val top = dst.top + margin
        val bottom = top + boxH
        canvas.drawRect(left, top, right, bottom, histBg)

        var max = 1
        for (v in hist) if (v > max) max = v
        val barW = boxW / hist.size
        for (i in hist.indices) {
            val norm = sqrt(hist[i].toDouble() / max).toFloat()
            val barH = norm * (boxH - 2f * d)
            canvas.drawRect(left + i * barW, bottom - barH, left + (i + 1) * barW, bottom, histBar)
        }
    }
}
