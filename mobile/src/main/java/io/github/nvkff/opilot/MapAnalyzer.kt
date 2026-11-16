package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

data class AnalysisResult(
    val alignedBitmap: Bitmap,
    val previewBitmap: Bitmap,
    val controlPoints: List<PointF>,
    val rotationDeg: Float
)

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
        val previewBmp = drawMarkers(alignedBmp, cps)
        return AnalysisResult(alignedBmp, previewBmp, cps, deg)
    }

    private fun detectNorthAngle(rgba: Mat): Float {
        val gray = Mat()
        val edges = Mat()
        val lines = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val minLen = rgba.rows() * 0.16
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 100, minLen, 20.0)
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
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angleDeg, 1.0)
        Imgproc.warpAffine(src, dst, m, Size(src.cols().toDouble(), src.rows().toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
        m.release()
        return dst
    }

    private fun detectMagentaCps(rgba: Mat): List<PointF> {
        val bgr = Mat()
        val hsv = Mat()
        val mask = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(130.0, 55.0, 50.0), Scalar(175.0, 255.0, 255.0), mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
        val minSz = rgba.cols() * 0.015
        val maxSz = rgba.cols() * 0.095
        val rawPts = mutableListOf<PointF>()
        for (c in contours) {
            val r = Imgproc.boundingRect(c)
            val ratio = r.width.toDouble() / r.height.coerceAtLeast(1)
            if (r.width >= minSz && r.width <= maxSz && r.height >= minSz && r.height <= maxSz && ratio in 0.65..1.45) {
                val area = Imgproc.contourArea(c)
                val c2f = MatOfPoint2f(*c.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                c2f.release()
                if (peri > 0.0 && (4.0 * Math.PI * area / (peri * peri)) >= 0.45) {
                    rawPts.add(PointF(r.x + r.width / 2f, r.y + r.height / 2f))
                }
            }
            c.release()
        }
        bgr.release(); hsv.release(); mask.release(); kernel.release(); hierarchy.release()
        return deduplicate(rawPts, rgba.cols() * 0.025f)
    }

    private fun deduplicate(pts: List<PointF>, minDist: Float): List<PointF> {
        val res = mutableListOf<PointF>()
        for (p in pts) {
            if (res.none { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < minDist }) res.add(p)
        }
        return res
    }

    private fun drawMarkers(bmp: Bitmap, pts: List<PointF>): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val pCircle = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = (bmp.width * 0.005f).coerceAtLeast(4f); isAntiAlias = true }
        val pText = Paint().apply { color = Color.GREEN; textSize = (bmp.width * 0.025f).coerceAtLeast(28f); isFakeBoldText = true; isAntiAlias = true }
        val rad = (bmp.width * 0.022f).coerceAtLeast(18f)
        pts.forEachIndexed { i, pt ->
            canvas.drawCircle(pt.x, pt.y, rad, pCircle)
            canvas.drawText("${i + 1}", pt.x + rad + 4f, pt.y + 8f, pText)
        }
        return out
    }
}
