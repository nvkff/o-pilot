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
    fun fetchWays(lat: Double, lon: Double, rDeg: Double = 0.018): List<OsmWay> {
        val s = lat - rDeg; val n = lat + rDeg
        val lonR = rDeg / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val w = lon - lonR; val e = lon + lonR
        val q = "[out:json][timeout:20];(way[\"highway\"]($s,$w,$n,$e);way[\"natural\"=\"water\"]($s,$w,$n,$e);way[\"waterway\"]($s,$w,$n,$e););out geom;"
        val conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 25000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val els = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<OsmWay>()
        for (i in 0 until els.length()) {
            val el = els.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            val t = if (tags.has("highway")) 1 else 2
            val pts = ArrayList<Pair<Double, Double>>(geom.length())
            for (j in 0 until geom.length()) { val g = geom.getJSONObject(j); pts.add(g.getDouble("lat") to g.getDouble("lon")) }
            if (pts.size >= 2) out.add(OsmWay(t, pts))
        }
        return out
    }

    fun autoAlign(scanBmp: Bitmap, cLat: Double, cLon: Double, ways: List<OsmWay>): GeoPlacement? {
        if (ways.isEmpty()) return null
        val areaM = 3600.0; val gridSz = 900; val mPerPx = areaM / gridSz
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
        val scanRgba = Mat(); val scanGray = Mat(); val scanMask = Mat()
        Utils.bitmapToMat(scanBmp, scanRgba); Imgproc.cvtColor(scanRgba, scanGray, Imgproc.COLOR_RGBA2GRAY)
        Core.bitwise_not(scanGray, scanMask); Imgproc.threshold(scanMask, scanMask, 90.0, 255.0, Imgproc.THRESH_BINARY)
        scanRgba.release(); scanGray.release()
        var best: GeoPlacement? = null
        for (wM in listOf(900.0, 1200.0, 1500.0, 1800.0, 2200.0, 2600.0)) {
            val tw = (wM / mPerPx).toInt()
            val th = (tw * scanBmp.height.toDouble() / scanBmp.width).toInt()
            if (tw < 40  th < 40  tw >= gridSz  th >= gridSz) continue
            val tpl = Mat(); Imgproc.resize(scanMask, tpl, Size(tw.toDouble(), th.toDouble()))
            val res = Mat(gridSz - th + 1, gridSz - tw + 1, CvType.CV_32FC1)
            Imgproc.matchTemplate(osmGray, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            if (best == null  mm.maxVal > best.score) {
                val cx = mm.maxLoc.x + tw / 2.0; val cy = mm.maxLoc.y + th / 2.0
                val lon = minLon + (cx / gridSz) * dLon
                val lat = minLat + ((gridSz - cy) / gridSz) * dLat
                best = GeoPlacement(lat, lon, wM, mm.maxVal)
            }
            tpl.release(); res.release()
        }
        osmGray.release(); scanMask.release()
        return best
    }
}
        var best: GeoPlacement? = null
        for (wM in listOf(900.0, 1200.0, 1500.0, 1800.0, 2200.0, 2600.0)) {
            val tw = (wM / mPerPx).toInt()
            val th = (tw * scanBmp.height.toDouble() / scanBmp.width).toInt()
            if (tw < 40 || th < 40 || tw >= gridSz || th >= gridSz) continue
            val tpl = Mat(); Imgproc.resize(scanMask, tpl, Size(tw.toDouble(), th.toDouble()))
            val res = Mat(gridSz - th + 1, gridSz - tw + 1, CvType.CV_32FC1)
            Imgproc.matchTemplate(osmGray, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            if (best == null || mm.maxVal > best.score) {
                val cx = mm.maxLoc.x + tw / 2.0; val cy = mm.maxLoc.y + th / 2.0
                val lon = minLon + (cx / gridSz) * dLon
                val lat = minLat + ((gridSz - cy) / gridSz) * dLat
                best = GeoPlacement(lat, lon, wM, mm.maxVal)
            }
            tpl.release(); res.release()
        }
        osmGray.release(); scanMask.release()
        return best
    }
}
