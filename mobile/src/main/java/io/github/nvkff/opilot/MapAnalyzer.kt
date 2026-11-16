package io.github.nvkff.opilot

import android.graphics.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class AnalysisResult(val alignedBitmap: Bitmap, val previewBitmap: Bitmap, val controlPoints: List<PointF>, val rotationDeg: Float)

object MapAnalyzer {
    fun analyze(srcBitmap: Bitmap): AnalysisResult {
        val rgba = Mat()
        Utils.bitmapToMat(srcBitmap, rgba)
        val deg = detectNorthAngle(rgba)
        val alignedMat = if (abs(deg) >= 1f) rotateMat(rgba, -deg.toDouble()) else rgba.clone()
        rgba.release()
        val cps = detectMagentaCps(alignedMat)
        val alignedBmp = Bitmap.createBitmap(alignedMat.cols(), alignedMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(alignedMat, alignedBmp)
        alignedMat.release()
        return AnalysisResult(alignedBmp, drawMarkers(alignedBmp, cps), cps, deg)
    }
    private fun detectNorthAngle(rgba: Mat): Float {
        val gray = Mat(); val edges = Mat(); val lines = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 100, rgba.rows() * 0.16, 20.0)
        val bins = IntArray(61)
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0)
            var deg = Math.toDegrees(atan2(l[2] - l[0], -(l[3] - l[1])))
            if (deg > 90.0) deg -= 180.0
            if (deg <= -90.0) deg += 180.0
            val idx = deg.roundToInt() + 30
            if (idx in 0..60) bins[idx]++
        }
        gray.release(); edges.release(); lines.release()
        var bestIdx = 30
        for (i in bins.indices) if (bins[i] > bins[bestIdx]) bestIdx = i
        return if (bins[bestIdx] >= 3) (bestIdx - 30).toFloat() else 0f
    }
    private fun rotateMat(src: Mat, angleDeg: Double): Mat {
        val dst = Mat()
        val m = Imgproc.getRotationMatrix2D(org.opencv.core.Point(src.cols() / 2.0, src.rows() / 2.0), angleDeg, 1.0)
        Imgproc.warpAffine(src, dst, m, Size(src.cols().toDouble(), src.rows().toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
        m.release(); return dst
    }
    private fun detectMagentaCps(rgba: Mat): List<PointF> {
        val bgr = Mat(); val hsv = Mat(); val m1 = Mat(); val m2 = Mat(); val mask = Mat(); val blur = Mat(); val circles = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(132.0, 40.0, 55.0), Scalar(180.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(0.0, 40.0, 55.0), Scalar(10.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, mask)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(mask, mask, k); Imgproc.GaussianBlur(mask, blur, Size(5.0, 5.0), 1.4)
        val minR = (rgba.cols() * 0.009).toInt().coerceAtLeast(10); val maxR = (rgba.cols() * 0.030).toInt().coerceAtLeast(25)
        Imgproc.HoughCircles(blur, circles, Imgproc.HOUGH_GRADIENT, 1.2, (rgba.cols() * 0.024).coerceAtLeast(26.0), 100.0, 13.5, minR, maxR)
        val raw = mutableListOf<Triple<Float, Float, Double>>()
        for (i in 0 until circles.cols()) {
            val c = circles.get(0, i); val cx = c[0]; val cy = c[1]; val r = c[2]
            var ringHits = 0; var innerHits = 0
            for (a in 0 until 24) {
                val ang = a * Math.PI / 12.0; var hit = false
                for (dr in listOf(-1.5, 0.0, 1.5)) {
                    val px = (cx + (r + dr) * cos(ang)).roundToInt().coerceIn(0, mask.cols() - 1)
                    val py = (cy + (r + dr) * sin(ang)).roundToInt().coerceIn(0, mask.rows() - 1)
                    val v = mask.get(py, px); if (v != null) if (v[0] > 0.0) hit = true
                }
                if (hit) ringHits++
            }
            for (a in 0 until 8) {
                val ang = a * Math.PI / 4.0
                val px = (cx + r * 0.4 * cos(ang)).roundToInt().coerceIn(0, mask.cols() - 1)
                val py = (cy + r * 0.4 * sin(ang)).roundToInt().coerceIn(0, mask.rows() - 1)
                val v = mask.get(py, px); if (v != null) if (v[0] > 0.0) innerHits++
            }
            if (ringHits >= 10) if (innerHits <= 4) raw.add(Triple(cx.toFloat(), cy.toFloat(), r))
        }
        bgr.release(); hsv.release(); m1.release(); m2.release(); mask.release(); blur.release(); circles.release(); k.release()
        if (raw.size < 4) return raw.map { PointF(it.first, it.second) }
        val medR = raw.map { it.third }.sorted()[raw.size / 2]
        return raw.filter { abs(it.third - medR) <= medR * 0.25 }.map { PointF(it.first, it.second) }
    }
    private fun drawMarkers(bmp: Bitmap, pts: List<PointF>): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val canvas = Canvas(out)
        val pc = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = (bmp.width * 0.005f).coerceAtLeast(4f); isAntiAlias = true }
        val ptPaint = Paint().apply { color = Color.GREEN; textSize = (bmp.width * 0.024f).coerceAtLeast(28f); isFakeBoldText = true; isAntiAlias = true }
        val rad = (bmp.width * 0.020f).coerceAtLeast(18f)
        pts.forEachIndexed { i, p -> canvas.drawCircle(p.x, p.y, rad, pc); canvas.drawText("${i + 1}", p.x + rad + 4f, p.y + 8f, ptPaint) }
        return out
    }
}
