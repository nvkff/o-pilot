package io.github.nvkff.opilot

import android.graphics.*
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.net.*
import kotlin.math.cos

data class OsmWay(val type: Int, val pts: List<Pair<Double, Double>>)
data class GeoPlacement(val centerLat: Double, val centerLon: Double, val widthMeters: Double, val score: Double)

object OverpassGeoref {
    fun fetchWays(lat: Double, lon: Double, rDeg: Double = 0.013): List<OsmWay> {
        val s = lat - rDeg; val n = lat + rDeg
        val lonR = rDeg / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val w = lon - lonR; val e = lon + lonR
        val q = "[out:json][timeout:12];(way[\"highway\"=\"primary\"]($s,$w,$n,$e);way[\"highway\"=\"secondary\"]($s,$w,$n,$e);way[\"highway\"=\"tertiary\"]($s,$w,$n,$e);way[\"highway\"=\"unclassified\"]($s,$w,$n,$e);way[\"highway\"=\"track\"]($s,$w,$n,$e);way[\"railway\"]($s,$w,$n,$e);way[\"natural\"=\"water\"]($s,$w,$n,$e););out geom;"
        val urls = listOf("https://overpass-api.de/api/interpreter", "https://lz4.overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")
        var body: String? = null; var lastErr = "Network error"
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 15000
                    setRequestProperty("User-Agent", "O-Pilot/1.0")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (conn.responseCode == 200) { body = conn.inputStream.bufferedReader().readText(); break } else lastErr = "HTTP ${conn.responseCode}"
            } catch (ex: Exception) { lastErr = ex.message ?: "Err" }
        }
        if (body == null) throw RuntimeException(lastErr)
        val els = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<OsmWay>()
        for (i in 0 until els.length()) {
            val el = els.getJSONObject(i); val tags = el.optJSONObject("tags") ?: continue; val geom = el.optJSONArray("geometry") ?: continue
            val t = if (tags.optString("natural") == "water") 2 else 1
            val pts = ArrayList<Pair<Double, Double>>(geom.length())
            for (j in 0 until geom.length()) { val g = geom.getJSONObject(j); pts.add(g.getDouble("lat") to g.getDouble("lon")) }
            if (pts.size >= 2) out.add(OsmWay(t, pts))
        }
        return out
    }

    fun autoAlign(scanBmp: Bitmap, cLat: Double, cLon: Double, ways: List<OsmWay>, scaleDenom: Int = 0): GeoPlacement? {
        if (ways.isEmpty()) return null
        val areaM = 2800.0; val gridSz = 700; val mPerPx = areaM / gridSz
        val dLat = areaM / 111320.0
        val dLon = areaM / (111320.0 * cos(Math.toRadians(cLat)).coerceAtLeast(0.2))
        val minLat = cLat - dLat / 2.0; val minLon = cLon - dLon / 2.0
        val osmBmp = Bitmap.createBitmap(gridSz, gridSz, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(osmBmp).apply { drawColor(Color.BLACK) }
        val pRoad = Paint().apply { color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE }
        val pWater = Paint().apply { color = Color.LTGRAY; strokeWidth = 4f; style = Paint.Style.STROKE }
        for (way in ways) {
            val p = if (way.type == 1) pRoad else pWater
            for (i in 0 until way.pts.size - 1) {
                val x1 = ((way.pts[i].second - minLon) / dLon * gridSz).toFloat()
                val y1 = (gridSz - (way.pts[i].first - minLat) / dLat * gridSz).toFloat()
                val x2 = ((way.pts[i + 1].second - minLon) / dLon * gridSz).toFloat()
                val y2 = (gridSz - (way.pts[i + 1].first - minLat) / dLat * gridSz).toFloat()
                canvas.drawLine(x1, y1, x2, y2, p)
            }
        }
        val osmGray = Mat(); val osmRgba = Mat()
        Utils.bitmapToMat(osmBmp, osmRgba); Imgproc.cvtColor(osmRgba, osmGray, Imgproc.COLOR_RGBA2GRAY)
        osmRgba.release(); osmBmp.recycle()
        val scanRgba = Mat(); val scanGray = Mat(); val scanEdges = Mat()
        Utils.bitmapToMat(scanBmp, scanRgba); Imgproc.cvtColor(scanRgba, scanGray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.Canny(scanGray, scanEdges, 60.0, 160.0); scanRgba.release(); scanGray.release()
        val widths = if (scaleDenom > 0) listOf(0.20, 0.24, 0.28, 0.32, 0.36).map { it * scaleDenom } else listOf(900.0, 1200.0, 1500.0, 1800.0, 2200.0, 2500.0)
        var best: GeoPlacement? = null
        for (wM in widths) {
            val tw = (wM / mPerPx).toInt(); val th = (tw * scanBmp.height.toDouble() / scanBmp.width).toInt()
            if (minOf(tw, th) < 40) continue
            if (maxOf(tw, th) >= gridSz) continue
            val tpl = Mat(); Imgproc.resize(scanEdges, tpl, Size(tw.toDouble(), th.toDouble()))
            val res = Mat(); Imgproc.matchTemplate(osmGray, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal > (best?.score ?: -999.0)) {
                val cx = mm.maxLoc.x + tw / 2.0; val cy = mm.maxLoc.y + th / 2.0
                best = GeoPlacement(minLat + ((gridSz - cy) / gridSz) * dLat, minLon + (cx / gridSz) * dLon, wM, mm.maxVal)
            }
            tpl.release(); res.release()
        }
        osmGray.release(); scanEdges.release()
        return best
    }
}
