package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class OrderedCourse(val points: List<PointF>, val labels: List<String>, val scale: Int, val legendCount: Int)
private data class NumTok(val num: Int, val cx: Float, val cy: Float, val h: Float)

object CourseOrderer {
    private val scaleRx = Regex("1\\s*[:\\-]\\s*(\\d{4,5})")
    private val numRx = Regex("\\d+")
    private fun buildAdjAndMask(bmp: Bitmap, pts: List<PointF>): Pair<Bitmap, Array<MutableList<Int>>> {
        val rgba = Mat(); val bgr = Mat(); val hsv = Mat(); val m1 = Mat(); val m2 = Mat(); val mask = Mat(); val inv = Mat()
        Utils.bitmapToMat(bmp, rgba); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(132.0, 40.0, 55.0), Scalar(180.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(0.0, 40.0, 55.0), Scalar(10.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, mask)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val dMask = Mat(); Imgproc.dilate(mask, dMask, k)
        val cands = mutableListOf<Triple<Int, Int, Int>>()
        val minSep = bmp.width * 0.028
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val ax = pts[i].x; val ay = pts[i].y; val bx = pts[j].x; val by = pts[j].y
            val len = hypot((bx - ax).toDouble(), (by - ay).toDouble()); if (len < minSep) continue
            var blocked = false
            for (m in pts.indices) if (m != i && m != j) {
                val t = ((pts[m].x - ax) * (bx - ax) + (pts[m].y - ay) * (by - ay)) / (len * len)
                if (t in 0.12..0.88) {
                    val d = hypot((pts[m].x - (ax + t * (bx - ax))), (pts[m].y - (ay + t * (by - ay))))
                    if (d < bmp.width * 0.025) { blocked = true; break }
                }
            }
            if (blocked) continue
            var hits = 0
            for (s in 4..28) {
                val t = s / 32.0; val px = (ax + (bx - ax) * t).roundToInt().coerceIn(0, dMask.cols() - 1)
                val py = (ay + (by - ay) * t).roundToInt().coerceIn(0, dMask.rows() - 1)
                val v = dMask.get(py, px); if (v != null) if (v[0] > 0.0) hits++
            }
            if (hits >= 14) cands.add(Triple(i, j, hits))
        }
        cands.sortByDescending { it.third }
        val adj = Array(pts.size) { mutableListOf<Int>() }
        for (e in cands) if (adj[e.first].size < 2 && adj[e.second].size < 2) { adj[e.first].add(e.second); adj[e.second].add(e.first) }
        Core.bitwise_not(mask, inv); val outM = Mat(); Imgproc.cvtColor(inv, outM, Imgproc.COLOR_GRAY2RGBA)
        val cleanBmp = Bitmap.createBitmap(outM.cols(), outM.rows(), Bitmap.Config.ARGB_8888); Utils.matToBitmap(outM, cleanBmp)
        rgba.release(); bgr.release(); hsv.release(); m1.release(); m2.release(); mask.release(); dMask.release(); inv.release(); outM.release(); k.release()
        return cleanBmp to adj
    }
    private fun readTokens(client: TextRecognizer, bmp: Bitmap, sx: Float = 1f, ox: Float = 0f): Pair<Int, List<NumTok>> {
        val txt = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)))
        val sc = scaleRx.findAll(txt.text).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1000..50000 } ?: 0
        val out = mutableListOf<NumTok>()
        for (b in txt.textBlocks) for (l in b.lines) for (el in l.elements) {
            val r = el.boundingBox ?: continue; val raw = el.text.trim()
            val ms = numRx.findAll(raw).toList(); if (ms.isEmpty()) continue
            ms.forEachIndexed { idx, m ->
                val n = m.value.toIntOrNull() ?: return@forEachIndexed
                val frac = (idx + 0.5f) / ms.size
                out.add(NumTok(n, (r.left + r.width() * frac) / sx + ox, r.exactCenterY() / sx, r.height() / sx))
            }
        }
        return sc to out
    }
    private fun prepLegendCrop(src: Bitmap, x0: Int, cw: Int): Bitmap {
        val crop = Bitmap.createBitmap(src, x0, 0, cw, src.height)
        val big = Bitmap.createScaledBitmap(crop, cw * 3, src.height * 3, true); crop.recycle()
        val rgba = Mat(); val gray = Mat(); val out = Mat()
        Utils.bitmapToMat(big, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val tmp=Mat();Imgproc.threshold(gray,tmp,127.0,255.0,Imgproc.THRESH_BINARY);val br=Core.countNonZero(tmp).toDouble()/(gray.rows()*gray.cols()).coerceAtLeast(1);tmp.release();if(br<0.5)Core.bitwise_not(gray,gray)
        Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX)
        Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
        val res = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888); Utils.matToBitmap(out, res)
        big.recycle(); rgba.release(); gray.release(); out.release()
        return res
    }
    fun orderAndExtract(bmp: Bitmap, rawPts: List<PointF>, cb: (OrderedCourse) -> Unit) {
        if (rawPts.isEmpty()) { cb(OrderedCourse(emptyList(), emptyList(), 0, 0)); return }
        try {
            val (cleanMag, adj) = buildAdjAndMask(bmp, rawPts)
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val (sc1, colorToks) = readTokens(client, bmp)
            val (_, magToks) = readTokens(client, cleanMag); cleanMag.recycle()
            val cw = (bmp.width * 0.25).toInt()
            val rBig = prepLegendCrop(bmp, bmp.width - cw, cw); val lBig = prepLegendCrop(bmp, 0, cw)
            val (_, rToks) = readTokens(client, rBig, 3f, (bmp.width - cw).toFloat()); rBig.recycle()
            val (sc2, lToks) = readTokens(client, lBig, 3f, 0f); lBig.recycle()
            val legend = mutableMapOf<Int, Int>()
            for (side in listOf(rToks, lToks)) for (a in side) if (a.num in 1..60) for (b in side) if (b !== a) if (b.num in 31..299) {
                val dx = b.cx - a.cx; val dy = abs(b.cy - a.cy)
                if (dx > 4f && dx < bmp.width * 0.09f && dy < maxOf(a.h, b.h) * 0.75f) legend[a.num] = b.num
            }
            val allMap = (magToks + colorToks).filter { it.num in 1..60 }
            val cpRad = bmp.width * 0.020f; val ocrOrder = HashMap<Int, Int>(); val used = mutableSetOf<Int>()
            val pairs = mutableListOf<Triple<Int, Int, Double>>()
            rawPts.forEachIndexed { idx, p -> for (t in allMap) {
                val d = hypot((t.cx - p.x).toDouble(), (t.cy - p.y).toDouble())
                if (d in (cpRad * 0.5)..(cpRad * 4.5)) pairs.add(Triple(idx, t.num, d))
            }}
            pairs.sortBy { it.third }
            for (pr in pairs) if (!ocrOrder.containsKey(pr.first) && !used.contains(pr.second)) { ocrOrder[pr.first] = pr.second; used.add(pr.second) }
            val chain = mutableListOf<Int>(); val vis = BooleanArray(rawPts.size)
            var cur = ocrOrder.entries.minByOrNull { it.value }?.key ?: adj.indices.firstOrNull { adj[it].size == 1 } ?: 0
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
