package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

data class MapSnap(val bmp: Bitmap, val mPerPx: Double, val tlLat: Double, val tlLon: Double, val brLat: Double, val brLon: Double)
data class LocalPlacement(val centerLat: Double, val centerLon: Double, val widthMeters: Double, val rotDeg: Float, val score: Double)

object LocalMapMatcher {
    fun captureMap(mapView: MapView): MapSnap? {
        val w = mapView.width; val h = mapView.height; if (w < 100) return null
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); mapView.draw(Canvas(full))
        val small = Bitmap.createScaledBitmap(full, w / 2, h / 2, true); full.recycle()
        val proj = mapView.projection; val gTL = proj.fromPixels(0, 0); val gBR = proj.fromPixels(w, h)
        val gL = proj.fromPixels(0, h / 2); val gR = proj.fromPixels(w, h / 2)
        val mPerPx = GeoPoint(gL.latitude, gL.longitude).distanceToAsDouble(GeoPoint(gR.latitude, gR.longitude)) / (w / 2.0)
        return MapSnap(small, mPerPx, gTL.latitude, gTL.longitude, gBR.latitude, gBR.longitude)
    }
    private fun extractWaterAndRoads(bmp: Bitmap, isScan: Boolean): Mat {
        val rgba = Mat(); val rgb = Mat(); val hsv = Mat(); val water = Mat(); val openField = Mat(); val out = Mat()
        Utils.bitmapToMat(bmp, rgba); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        Core.inRange(hsv, Scalar(86.0, 40.0, 145.0), Scalar(116.0, 255.0, 255.0), water)
        if (isScan) Core.inRange(hsv, Scalar(18.0, 50.0, 140.0), Scalar(35.0, 255.0, 255.0), openField)
        else Core.inRange(hsv, Scalar(0.0, 0.0, 225.0), Scalar(180.0, 35.0, 255.0), openField)
        Core.addWeighted(water, 0.75, openField, 0.25, 0.0, out)
        Imgproc.GaussianBlur(out, out, Size(5.0, 5.0), 1.5)
        rgba.release(); rgb.release(); hsv.release(); water.release(); openField.release()
        return out
    }
    fun align(snap: MapSnap, scanBmp: Bitmap, scaleDenom: Int = 0): LocalPlacement? {
        val osmFeat = extractWaterAndRoads(snap.bmp, false)
        val cx0 = (scanBmp.width * 0.14).toInt(); val cy0 = (scanBmp.height * 0.10).toInt()
        val cw = (scanBmp.width * 0.72).toInt(); val ch = (scanBmp.height * 0.80).toInt()
        val crop = Bitmap.createBitmap(scanBmp, cx0, cy0, cw, ch)
        val scanFeat = extractWaterAndRoads(crop, true); crop.recycle()
        val widths = if (scaleDenom > 0) listOf(0.22, 0.26, 0.30, 0.34).map { it * scaleDenom } else listOf(1200.0, 1600.0, 2000.0, 2400.0)
        val angles = listOf(-15f, -12f, -9f, -6f, -3f, 0f, 3f, 6f, 9f, 12f, 15f)
        var best: LocalPlacement? = null
        for (wM in widths) {
            val tw = ((wM * 0.72) / snap.mPerPx).toInt(); val th = (tw * ch.toDouble() / cw).toInt()
            if (minOf(tw, th) < 30) continue
            if (tw >= osmFeat.cols()) continue
            if (th >= osmFeat.rows()) continue
            val baseTpl = Mat(); Imgproc.resize(scanFeat, baseTpl, Size(tw.toDouble(), th.toDouble()))
            val center = org.opencv.core.Point(tw / 2.0, th / 2.0)
            for (ang in angles) {
                val m = Imgproc.getRotationMatrix2D(center, -ang.toDouble(), 1.0)
                val rotTpl = Mat(); Imgproc.warpAffine(baseTpl, rotTpl, m, Size(tw.toDouble(), th.toDouble()))
                val res = Mat(); Imgproc.matchTemplate(osmFeat, rotTpl, res, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(res)
                if (mm.maxVal > (best?.score ?: -999.0)) {
                    val mx = mm.maxLoc.x + tw / 2.0; val my = mm.maxLoc.y + th / 2.0
                    val lat = snap.tlLat + (my / snap.bmp.height) * (snap.brLat - snap.tlLat)
                    val lon = snap.tlLon + (mx / snap.bmp.width) * (snap.brLon - snap.tlLon)
                    best = LocalPlacement(lat, lon, wM, ang, mm.maxVal)
                }
                m.release(); rotTpl.release(); res.release()
            }
            baseTpl.release()
        }
        osmFeat.release(); scanFeat.release(); return best
    }
}
