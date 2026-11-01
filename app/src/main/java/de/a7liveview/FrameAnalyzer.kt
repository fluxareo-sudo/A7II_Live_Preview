package de.a7liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs

class AnalysisResult(
    val frame: Bitmap,
    val overlay: Bitmap?,
    val histogram: IntArray?,
)

/** Berechnet Focus Peaking, Zebra und Luma-Histogramm für ein Live-View-Bild. */
class FrameAnalyzer {

    @Volatile var peaking = true
    @Volatile var zebra = true
    @Volatile var histogram = true

    /** Zebra-Schwelle auf der Luma-Skala 0..255 (235 entspricht etwa 92 %). */
    @Volatile var zebraLevel = 235

    /** Schwelle für die Kantenstärke beim Peaking. Kleiner = empfindlicher. */
    @Volatile var peakingThreshold = 40

    private val zebraColor = Color.argb(180, 255, 60, 60)
    private val peakingColor = Color.rgb(0, 255, 90)

    fun analyze(jpeg: ByteArray): AnalysisResult? {
        val frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null

        val doPeaking = peaking
        val doZebra = zebra
        val doHistogram = histogram
        if (!doPeaking && !doZebra && !doHistogram) {
            return AnalysisResult(frame, null, null)
        }

        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        val hist = IntArray(64)
        for (i in pixels.indices) {
            val c = pixels[i]
            val y = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
            luma[i] = y
            hist[y shr 2]++
        }

        var overlay: Bitmap? = null
        if (doPeaking || doZebra) {
            val out = IntArray(w * h)

            if (doZebra) {
                val level = zebraLevel
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (luma[i] >= level && ((x + y) / 4) % 2 == 0) out[i] = zebraColor
                    }
                }
            }

            if (doPeaking) {
                val threshold = peakingThreshold
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val i = y * w + x
                        val gx = abs(luma[i + 1] - luma[i - 1])
                        val gy = abs(luma[i + w] - luma[i - w])
                        if (gx + gy > threshold) out[i] = peakingColor
                    }
                }
            }

            overlay = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }

        return AnalysisResult(frame, overlay, if (doHistogram) hist else null)
    }
}
