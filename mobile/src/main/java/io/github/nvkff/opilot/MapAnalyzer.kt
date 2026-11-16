package io.github.nvkff.opilot

import android.graphics.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class AnalysisResult(val alignedBitmap: Bitmap, val previewBitmap: Bitmap, val controlPoints: List<PointF>, val rotationDeg: Float, val medRadius: Float)

object MapAnalyzer {
    fun analyze(srcBitmap: Bitmap, ocrNums: List<OcrNumber> = emptyList(), legend: Map<Int, Int> = emptyMap()): Pair<AnalysisResult, List<String>> {
        val rgba = Mat(); Utils.bitmapToMat(srcBitmap, rgba)
        val deg = detectNorthAngle(rgba)
        val alignedMat = if (abs(deg) >= 1f) rotateMat(rgba, -deg.toDouble()) else rgba.clone()
        rgba.release()
        val (cps, medR) = detectAndOrderCps(alignedMat, ocrNums)
        val alignedBmp = Bitmap.createBitmap(alignedMat.cols(), alignedMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(alignedMat, alignedBmp); alignedMat.release()
        val labels = cps.indices.map { i -> val seq = i + 1; val code = legend[seq]; if (code != null) "$seq ($code)" else "$seq" }
        return AnalysisResult(alignedBmp, drawMarkers(alignedBmp, cps, labels), cps, deg, medR) to labels
    }
    private fun detectNorthAngle(rgba: Mat): Float {
        val gray = Mat(); val edges = Mat(); val lines = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 100, rgba.rows() * 0.16, 20.0)
        val bins = IntArray(61)
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0); var deg = Math.toDegrees(atan2(l[2] - l[0], -(l[3] - l[1])))
            if (deg > 90.0) deg -= 180.0; if (deg <= -90.0) deg += 180.0
            val idx = deg.roundToInt() + 30; if (idx in 0..60) bins[idx]++
        }
        gray.release(); edges.release(); lines.release()
        var best = 30; for (i in bins.indices) if (bins[i] > bins[best]) best = i
        return if (bins[best] >= 3) (best - 30).toFloat() else 0f
    }
    private fun rotateMat(src: Mat, angleDeg: Double): Mat {
        val dst = Mat(); val m = Imgproc.getRotationMatrix2D(org.opencv.core.Point(src.cols() / 2.0, src.rows() / 2.0), angleDeg, 1.0)
        Imgproc.warpAffine(src, dst, m, Size(src.cols().toDouble(), src.rows().toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
        m.release(); return dst
    }
    private fun detectAndOrderCps(rgba: Mat, ocrNums: List<OcrNumber>): Pair<List<PointF>, Float> {
        val bgr = Mat(); val hsv = Mat(); val m1 = Mat(); val m2 = Mat(); val mask = Mat(); val blur = Mat(); val circles = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(132.0, 40.0, 55.0), Scalar(180.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(0.0, 40.0, 55.0), Scalar(9.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, mask)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(mask, mask, k); Imgproc.GaussianBlur(mask, blur, Size(5.0, 5.0), 1.4)
        val minR = (rgba.cols() * 0.009).toInt().coerceAtLeast(10); val maxR = (rgba.cols() * 0.030).toInt().coerceAtLeast(25)
        Imgproc.HoughCircles(blur, circles, Imgproc.HOUGH_GRADIENT, 1.2, (rgba.cols() * 0.025).coerceAtLeast(28.0), 100.0, 13.0, minR, maxR)
        val raw = mutableListOf<Triple<Float, Float, Double>>()
        for (i in 0 until circles.cols()) {
            val c = circles.get(0, i); val cx = c[0]; val cy = c[1]; val r = c[2]; var rh = 0; var ih = 0
            for (a in 0 until 24) {
                val ang = a * Math.PI / 12.0; var hit = false
                for (dr in listOf(-1.5, 0.0, 1.5)) {
                    val px = (cx + (r + dr) * cos(ang)).roundToInt().coerceIn(0, mask.cols() - 1)
                    val py = (cy + (r + dr) * sin(ang)).roundToInt().coerceIn(0, mask.rows() - 1)
                    val v = mask.get(py, px); if (v != null) if (v[0] > 0.0) hit = true
                }
                if (hit) rh++
            }
            for (a in 0 until 8) {
                val ang = a * Math.PI / 4.0
                val px = (cx + r * 0.4 * cos(ang)).roundToInt().coerceIn(0, mask.cols() - 1)
                val py = (cy + r * 0.4 * sin(ang)).roundToInt().coerceIn(0, mask.rows() - 1)
                val v = mask.get(py, px); if (v != null) if (v[0] > 0.0) ih++
            }
            if (rh >= 11) if (ih <= 4) raw.add(Triple(cx.toFloat(), cy.toFloat(), r))
        }
        val medR = if (raw.isEmpty()) 25f else raw.map { it.third }.sorted()[raw.size / 2].toFloat()
        val pts = mutableListOf<PointF>()
        for (it in raw) if (abs(it.third - medR) <= medR * 0.28) {
            val p = PointF(it.first, it.second)
            if (pts.none { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < medR * 1.8 }) pts.add(p)
        }
        val ordered = orderByLinesAndOcr(pts, mask, medR, ocrNums)
        bgr.release(); hsv.release(); m1.release(); m2.release(); mask.release(); blur.release(); circles.release(); k.release()
        return ordered to medR
    }
    private fun orderByLinesAndOcr(pts: List<PointF>, mask: Mat, medR: Float, ocrNums: List<OcrNumber>): List<PointF> {
        val n = pts.size; if (n <= 1) return pts
        val ocrIdx = IntArray(n) { -1 }
        pts.forEachIndexed { i, p ->
            val best = ocrNums.minByOrNull { hypot((it.pos.x - p.x).toDouble(), (it.pos.y - p.y).toDouble()) }
            if (best != null) if (hypot((best.pos.x - p.x).toDouble(), (best.pos.y - p.y).toDouble()) < medR * 4.2) ocrIdx[i] = best.num
        }
        val adj = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = hypot((pts[j].x - pts[i].x).toDouble(), (pts[j].y - pts[i].y).toDouble())
            if (d < medR * 2.2) continue
            var hits = 0
            for (s in 1..20) {
                val t = 0.12 + 0.76 * (s / 21.0)
                val sx = (pts[i].x + (pts[j].x - pts[i].x) * t).roundToInt()
                val sy = (pts[i].y + (pts[j].y - pts[i].y) * t).roundToInt()
                var ok = false
                for (dx in -2..2) for (dy in -2..2) {
                    val v = mask.get((sy + dy).coerceIn(0, mask.rows() - 1), (sx + dx).coerceIn(0, mask.cols() - 1))
                    if (v != null) if (v[0] > 0.0) ok = true
                }
                if (ok) hits++
            }
            if (hits >= 11) { adj[i].add(j); adj[j].add(i) }
        }
        val vis = BooleanArray(n); val chains = mutableListOf<List<Int>>()
        val starts = (0 until n).sortedBy { if (adj[it].size == 1) 0 else 1 }
        for (st in starts) if (!vis[st]) {
            val chain = mutableListOf<Int>(); var cur: Int? = st
            while (cur != null) {
                vis[cur] = true; chain.add(cur)
                cur = adj[cur].firstOrNull { !vis[it] }
            }
            val paired = chain.mapNotNull { idx -> if (ocrIdx[idx] > 0) idx to ocrIdx[idx] else null }
            if (paired.size >= 2) if (paired.first().second > paired.last().second) chain.reverse()
            chains.add(chain)
        }
        return chains.sortedBy { c -> c.mapNotNull { if (ocrIdx[it] > 0) ocrIdx[it] else null }.minOrNull() ?: 999 }.flatten().map { pts[it] }
    }
    private fun drawMarkers(bmp: Bitmap, pts: List<PointF>, labels: List<String>): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true); val canvas = Canvas(out)
        val pc = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = (bmp.width * 0.005f).coerceAtLeast(4f); isAntiAlias = true }
        val ptPaint = Paint().apply { color = Color.GREEN; textSize = (bmp.width * 0.022f).coerceAtLeast(26f); isFakeBoldText = true; isAntiAlias = true }
        val rad = (bmp.width * 0.020f).coerceAtLeast(18f)
        pts.forEachIndexed { i, p -> canvas.drawCircle(p.x, p.y, rad, pc); canvas.drawText(labels.getOrElse(i) { "${i + 1}" }, p.x + rad + 4f, p.y + 8f, ptPaint) }
        return out
    }
}
