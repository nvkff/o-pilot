package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

data class MapSnap(val bmp: Bitmap, val mPerPx: Double, val tlLat: Double, val tlLon: Double, val brLat: Double, val brLon: Double)

object LocalMapMatcher {
    fun captureMap(mapView: MapView): MapSnap? {
        val w = mapView.width; val h = mapView.height
        if (w < 100) return null
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mapView.draw(Canvas(full))
        val small = Bitmap.createScaledBitmap(full, w / 2, h / 2, true); full.recycle()
        val proj = mapView.projection
        val gTL = proj.fromPixels(0, 0); val gBR = proj.fromPixels(w, h)
        val gL = proj.fromPixels(0, h / 2); val gR = proj.fromPixels(w, h / 2)
        val mPerPx = GeoPoint(gL.latitude, gL.longitude).distanceToAsDouble(GeoPoint(gR.latitude, gR.longitude)) / (w / 2.0)
        return MapSnap(small, mPerPx, gTL.latitude, gTL.longitude, gBR.latitude, gBR.longitude)
    }
    private fun extractFeatures(bmp: Bitmap): Mat {
        val rgba = Mat(); val rgb = Mat(); val hsv = Mat(); val gray = Mat(); val water = Mat(); val edges = Mat(); val out = Mat()
        Utils.bitmapToMat(bmp, rgba)
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Core.inRange(hsv, Scalar(86.0, 40.0, 145.0), Scalar(116.0, 255.0, 255.0), water)
        Imgproc.Canny(gray, edges, 50.0, 140.0)
        Core.max(water, edges, out)
        rgba.release(); rgb.release(); hsv.release(); gray.release(); water.release(); edges.release()
        return out
    }
    fun align(snap: MapSnap, scanBmp: Bitmap, scaleDenom: Int = 0): GeoPlacement? {
        val osmFeat = extractFeatures(snap.bmp)
        val cx0 = (scanBmp.width * 0.12).toInt(); val cy0 = (scanBmp.height * 0.08).toInt()
        val cw = (scanBmp.width * 0.76).toInt(); val ch = (scanBmp.height * 0.84).toInt()
        val crop = Bitmap.createBitmap(scanBmp, cx0, cy0, cw, ch)
        val scanFeat = extractFeatures(crop); crop.recycle()
        val widths = if (scaleDenom > 0) listOf(0.20, 0.24, 0.28, 0.32, 0.36).map { it * scaleDenom } else listOf(900.0, 1200.0, 1500.0, 1800.0, 2200.0, 2600.0)
        var best: GeoPlacement? = null
        for (wM in widths) {
            val tw = ((wM * 0.76) / snap.mPerPx).toInt()
            val th = (tw * ch.toDouble() / cw).toInt()
            if (minOf(tw, th) < 30) continue
            if (tw >= osmFeat.cols()) continue
            if (th >= osmFeat.rows()) continue
            val tpl = Mat(); Imgproc.resize(scanFeat, tpl, Size(tw.toDouble(), th.toDouble()))
            val res = Mat(); Imgproc.matchTemplate(osmFeat, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal > (best?.score ?: -999.0)) {
                val mx = mm.maxLoc.x + tw / 2.0; val my = mm.maxLoc.y + th / 2.0
                val lat = snap.tlLat + (my / snap.bmp.height) * (snap.brLat - snap.tlLat)
                val lon = snap.tlLon + (mx / snap.bmp.width) * (snap.brLon - snap.tlLon)
                best = GeoPlacement(lat, lon, wM, mm.maxVal)
            }
            tpl.release(); res.release()
        }
        osmFeat.release(); scanFeat.release()
        return best
    }
}
