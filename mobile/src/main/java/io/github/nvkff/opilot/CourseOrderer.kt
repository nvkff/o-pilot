package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class OrderedCourse(val points: List<PointF>, val labels: List<String>, val scale: Int, val legendCount: Int)
private data class NumTok(val num: Int, val cx: Float, val cy: Float, val h: Float)

object CourseOrderer {
    private val scaleRx = Regex("1\\s*[:\\-]\\s*(\\d{4,5})")
    private fun buildMagentaAndEdges(bmp: Bitmap, pts: List<PointF>): Pair<Bitmap, Array<MutableList<Int>>> {
        val rgba = Mat(); val bgr = Mat(); val hsv = Mat(); val m1 = Mat(); val m2 = Mat(); val mask = Mat(); val inv = Mat()
        Utils.bitmapToMat(bmp, rgba); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(132.0, 40.0, 55.0), Scalar(180.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(0.0, 40.0, 55.0), Scalar(10.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, mask)
        val adj = Array(pts.size) { mutableListOf<Int>() }
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val dMask = Mat(); Imgproc.dilate(mask, dMask, k)
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val d = hypot((pts[j].x - pts[i].x).toDouble(), (pts[j].y - pts[i].y).toDouble())
            if (d < bmp.width * 0.03) continue
            var hits = 0
            for (s in 4..16) {
                val t = s / 20.0; val px = (pts[i].x + (pts[j].x - pts[i].x) * t).roundToInt().coerceIn(0, dMask.cols() - 1)
                val py = (pts[i].y + (pts[j].y - pts[i].y) * t).roundToInt().coerceIn(0, dMask.rows() - 1)
                val v = dMask.get(py, px); if (v != null) if (v[0] > 0.0) hits++
            }
            if (hits >= 8) { adj[i].add(j); adj[j].add(i) }
        }
        Core.bitwise_not(mask, inv)
        val outMat = Mat(); Imgproc.cvtColor(inv, outMat, Imgproc.COLOR_GRAY2RGBA)
        val cleanBmp = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outMat, cleanBmp)
        rgba.release(); bgr.release(); hsv.release(); m1.release(); m2.release(); mask.release(); dMask.release(); inv.release(); outMat.release(); k.release()
        return cleanBmp to adj
    }
    private fun readTokens(client: com.google.mlkit.vision.text.TextRecognizer, bmp: Bitmap, sx: Float = 1f, ox: Float = 0f): Pair<Int, List<NumTok>> {
        val txt = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)))
        val sc = scaleRx.findAll(txt.text).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1000..50000 } ?: 0
        val out = mutableListOf<NumTok>()
        for (b in txt.textBlocks) for (l in b.lines) for (el in l.elements) {
            val n = el.text.trim().toIntOrNull() ?: continue
            val r = el.boundingBox ?: continue
            out.add(NumTok(n, r.exactCenterX() / sx + ox, r.exactCenterY() / sx, r.height() / sx))
        }
        return sc to out
    }
    private fun cropUpscale(src: Bitmap, x0: Int, cw: Int): Bitmap {
        val crop = Bitmap.createBitmap(src, x0, 0, cw, src.height)
        val big = Bitmap.createScaledBitmap(crop, cw * 2, src.height * 2, true); crop.recycle(); return big
    }
    fun orderAndExtract(bmp: Bitmap, rawPts: List<PointF>, cb: (OrderedCourse) -> Unit) {
        if (rawPts.isEmpty()) { cb(OrderedCourse(emptyList(), emptyList(), 0, 0)); return }
        try {
            val (cleanMag, adj) = buildMagentaAndEdges(bmp, rawPts)
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val (sc1, _) = readTokens(client, bmp)
            val (_, mapToks) = readTokens(client, cleanMag); cleanMag.recycle()
            val cw = (bmp.width * 0.24).toInt()
            val rightBig = cropUpscale(bmp, bmp.width - cw, cw)
            val leftBig = cropUpscale(bmp, 0, cw)
            val (_, rToks) = readTokens(client, rightBig, 2f, (bmp.width - cw).toFloat()); rightBig.recycle()
            val (sc2, lToks) = readTokens(client, leftBig, 2f, 0f); leftBig.recycle()
            val legend = mutableMapOf<Int, Int>()
            for (side in listOf(rToks, lToks)) for (a in side) if (a.num in 1..60) for (b in side) if (b !== a) if (b.num in 31..299) {
                val dx = b.cx - a.cx; val dy = abs(b.cy - a.cy)
                if (dx > 4f) if (dx < bmp.width * 0.09f) if (dy < maxOf(a.h, b.h) * 0.7f) legend[a.num] = b.num
            }
            val cpRad = bmp.width * 0.020f; val ocrOrder = HashMap<Int, Int>(); val used = mutableSetOf<Int>()
            val pairs = mutableListOf<Triple<Int, Int, Double>>()
            rawPts.forEachIndexed { idx, p -> for (t in mapToks) if (t.num in 1..60) {
                val d = hypot((t.cx - p.x).toDouble(), (t.cy - p.y).toDouble())
                if (d in (cpRad * 0.6)..(cpRad * 4.5)) pairs.add(Triple(idx, t.num, d))
            }}
            pairs.sortBy { it.third }
            for (pr in pairs) if (!ocrOrder.containsKey(pr.first)) if (!used.contains(pr.second)) { ocrOrder[pr.first] = pr.second; used.add(pr.second) }
            val chain = mutableListOf<Int>(); val vis = BooleanArray(rawPts.size)
            var cur = ocrOrder.entries.minByOrNull { it.value }?.key ?: 0
            while (chain.size < rawPts.size) {
                chain.add(cur); vis[cur] = true; if (chain.size == rawPts.size) break
                val curN = ocrOrder[cur]
                var nxt = if (curN != null) ocrOrder.entries.firstOrNull { !vis[it.key] && it.value == curN + 1 }?.key else null
                if (nxt == null) nxt = adj[cur].filter { !vis[it] }.minByOrNull { ocrOrder[it] ?: 999 }
                if (nxt == null) { val p = rawPts[cur]; nxt = rawPts.indices.filter { !vis[it] }.minByOrNull { hypot((rawPts[it].x - p.x).toDouble(), (rawPts[it].y - p.y).toDouble()) } ?: 0 }
                cur = nxt
            }
            val ptsOut = chain.map { rawPts[it] }
            val lblOut = chain.indices.map { pos -> val n = pos + 1; val code = legend[n]; if (code != null) "$n ($code)" else "$n" }
            cb(OrderedCourse(ptsOut, lblOut, if (sc1 > 0) sc1 else sc2, legend.size))
        } catch (_: Exception) { cb(OrderedCourse(rawPts, rawPts.indices.map { "${it + 1}" }, 0, 0)) }
    }
}
