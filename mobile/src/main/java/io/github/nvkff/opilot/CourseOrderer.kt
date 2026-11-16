package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class OrderedCourse(val points: List<PointF>, val labels: List<String>, val scale: Int, val legendCount: Int)
private data class NumTok(val num: Int, val cx: Float, val cy: Float, val h: Float)

object CourseOrderer {
    private val scaleRx = Regex("1\\s*[:\\-]\\s*(\\d{4,5})")
    private fun detectEdges(bmp: Bitmap, pts: List<PointF>): Array<MutableList<Int>> {
        val adj = Array(pts.size) { mutableListOf<Int>() }
        if (pts.size < 2) return adj
        val rgba = Mat(); val bgr = Mat(); val hsv = Mat(); val m1 = Mat(); val m2 = Mat(); val mask = Mat()
        Utils.bitmapToMat(bmp, rgba); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(132.0, 40.0, 55.0), Scalar(180.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(0.0, 40.0, 55.0), Scalar(10.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, mask)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)); Imgproc.dilate(mask, mask, k)
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val d = hypot((pts[j].x - pts[i].x).toDouble(), (pts[j].y - pts[i].y).toDouble())
            if (d < bmp.width * 0.03) continue
            var hits = 0
            for (s in 4..16) {
                val t = s / 20.0; val px = (pts[i].x + (pts[j].x - pts[i].x) * t).roundToInt().coerceIn(0, mask.cols() - 1)
                val py = (pts[i].y + (pts[j].y - pts[i].y) * t).roundToInt().coerceIn(0, mask.rows() - 1)
                val v = mask.get(py, px); if (v != null) if (v[0] > 0.0) hits++
            }
            if (hits >= 8) { adj[i].add(j); adj[j].add(i) }
        }
        rgba.release(); bgr.release(); hsv.release(); m1.release(); m2.release(); mask.release(); k.release()
        return adj
    }
    fun orderAndExtract(bmp: Bitmap, rawPts: List<PointF>, cb: (OrderedCourse) -> Unit) {
        if (rawPts.isEmpty()) { cb(OrderedCourse(emptyList(), emptyList(), 0, 0)); return }
        val adj = detectEdges(bmp, rawPts)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { txt ->
                val scale = scaleRx.findAll(txt.text).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1000..50000 } ?: 0
                val toks = mutableListOf<NumTok>()
                for (b in txt.textBlocks) for (l in b.lines) for (el in l.elements) {
                    val n = el.text.trim().toIntOrNull() ?: continue
                    val r = el.boundingBox ?: continue
                    toks.add(NumTok(n, r.exactCenterX(), r.exactCenterY(), r.height().toFloat()))
                }
                val legend = mutableMapOf<Int, Int>(); val legToks = mutableSetOf<NumTok>()
                val candidates = mutableListOf<Triple<NumTok, NumTok, Float>>()
                for (a in toks) if (a.num in 1..60) for (b in toks) if (b !== a) if (b.num in 31..299) {
                    val dx = b.cx - a.cx; val dy = abs(b.cy - a.cy)
                    if (dx > 5f) if (dx < bmp.width * 0.09f) if (dy < maxOf(a.h, b.h) * 0.65f) candidates.add(Triple(a, b, a.cx))
                }
                if (candidates.size >= 3) {
                    val medX = candidates.map { it.third }.sorted()[candidates.size / 2]
                    for (c in candidates) if (abs(c.third - medX) < bmp.width * 0.05f) {
                        legend[c.first.num] = c.second.num; legToks.add(c.first); legToks.add(c.second)
                    }
                }
                val mapToks = toks.filter { it !in legToks }.filter { it.num in 1..60 }
                val cpRadius = bmp.width * 0.020f
                val ocrOrder = HashMap<Int, Int>(); val usedNums = mutableSetOf<Int>()
                val pairs = mutableListOf<Triple<Int, Int, Double>>()
                rawPts.forEachIndexed { idx, p ->
                    for (t in mapToks) {
                        val d = hypot((t.cx - p.x).toDouble(), (t.cy - p.y).toDouble())
                        if (d in (cpRadius * 0.6)..(cpRadius * 4.5)) pairs.add(Triple(idx, t.num, d))
                    }
                }
                pairs.sortBy { it.third }
                for (pr in pairs) if (!ocrOrder.containsKey(pr.first)) if (!usedNums.contains(pr.second)) { ocrOrder[pr.first] = pr.second; usedNums.add(pr.second) }
                val chain = mutableListOf<Int>(); val vis = BooleanArray(rawPts.size)
                var cur = ocrOrder.entries.minByOrNull { it.value }?.key ?: 0
                while (chain.size < rawPts.size) {
                    chain.add(cur); vis[cur] = true
                    if (chain.size == rawPts.size) break
                    val curNum = ocrOrder[cur]
                    var nxt = if (curNum != null) ocrOrder.entries.firstOrNull { !vis[it.key] && it.value == curNum + 1 }?.key else null
                    if (nxt == null) nxt = adj[cur].filter { !vis[it] }.minByOrNull { ocrOrder[it] ?: 999 }
                    if (nxt == null) {
                        val p = rawPts[cur]
                        nxt = rawPts.indices.filter { !vis[it] }.minByOrNull { hypot((rawPts[it].x - p.x).toDouble(), (rawPts[it].y - p.y).toDouble()) } ?: 0
                    }
                    cur = nxt
                }
                val ptsOut = chain.map { rawPts[it] }
                val lblOut = chain.mapIndexed { pos, idx -> val n = ocrOrder[idx] ?: (pos + 1); val code = legend[n]; if (code != null) "$n ($code)" else "$n" }
                cb(OrderedCourse(ptsOut, lblOut, scale, legend.size))
            }
            .addOnFailureListener { cb(OrderedCourse(rawPts, rawPts.indices.map { "${it + 1}" }, 0, 0)) }
    }
}
