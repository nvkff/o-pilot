package io.github.nvkff.opilot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.github.nvkff.opilot.shared.ControlPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

class MapOverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Mode { MAP, ADJUST_SCAN, EDIT_CP }
    var mode = Mode.MAP
    var mapViewRef: MapView? = null
    private var bmp: Bitmap? = null
    private val cps = mutableListOf<PointF>()
    var centerGeo = GeoPoint(55.7558, 37.6173)
    var widthMeters = 1800.0
    var rotationDeg = 0f
    private val matrix = Matrix()
    private val imgPaint = Paint().apply { alpha = 165; isFilterBitmap = true }
    private val cpPaint = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val cpText = Paint().apply { color = Color.YELLOW; textSize = 30f; isFakeBoldText = true; isAntiAlias = true }
    private var lx = 0f; private var ly = 0f; private var lastDist = 0f; private var lastAng = 0f; private var multi = false

    private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (mode == Mode.EDIT_CP) handleCpTap(e.x, e.y)
            return true
        }
    })

    fun setMap(bitmap: Bitmap, points: List<PointF>, center: GeoPoint) {
        bmp = bitmap; cps.clear(); cps.addAll(points); centerGeo = center; invalidate()
    }
    fun setOverlayAlpha(a: Int) { imgPaint.alpha = a; invalidate() }
    fun setPlacement(c: GeoPoint, wMeters: Double) { centerGeo = c; widthMeters = wMeters; rotationDeg = 0f; invalidate() }

    private fun rebuildMatrix() {
        val b = bmp ?: return; val proj = mapViewRef?.projection ?: return
        val dLon = widthMeters / (111320.0 * cos(Math.toRadians(centerGeo.latitude)).coerceAtLeast(0.2))
        val pL = proj.toPixels(GeoPoint(centerGeo.latitude, centerGeo.longitude - dLon / 2.0), null)
        val pR = proj.toPixels(GeoPoint(centerGeo.latitude, centerGeo.longitude + dLon / 2.0), null)
        val pC = proj.toPixels(centerGeo, null)
        val pxW = hypot((pR.x - pL.x).toDouble(), (pR.y - pL.y).toDouble()).toFloat().coerceAtLeast(10f)
        val s = pxW / b.width
        matrix.reset(); matrix.postTranslate(-b.width / 2f, -b.height / 2f); matrix.postScale(s, s)
        matrix.postRotate(rotationDeg); matrix.postTranslate(pC.x.toFloat(), pC.y.toFloat())
    }
    fun exportControlPoints(proj: org.osmdroid.views.Projection): List<ControlPoint> {
        rebuildMatrix()
        return cps.mapIndexed { idx, p ->
            val pt = floatArrayOf(p.x, p.y); matrix.mapPoints(pt)
            val g = proj.fromPixels(pt[0].toInt(), pt[1].toInt())
            ControlPoint(idx + 1, "КП ${idx + 1}", g.latitude, g.longitude)
        }
    }
    override fun onDraw(c: Canvas) {
        val b = bmp ?: return; rebuildMatrix(); c.drawBitmap(b, matrix, imgPaint)
        cps.forEachIndexed { i, p ->
            val pt = floatArrayOf(p.x, p.y); matrix.mapPoints(pt)
            c.drawCircle(pt[0], pt[1], 18f, cpPaint); c.drawText("${i + 1}", pt[0] + 20f, pt[1] + 8f, cpText)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.MAP) return false
        if (mode == Mode.EDIT_CP) { tapDetector.onTouchEvent(e); mapViewRef?.dispatchTouchEvent(e); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { multi = true; lastDist = dist(e); lastAng = ang(e) }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1) if (!multi) {
                    val proj = mapViewRef?.projection
                    if (proj != null) {
                        val pc = proj.toPixels(centerGeo, null)
                        val ng = proj.fromPixels((pc.x - (e.x - lx)).toInt(), (pc.y - (e.y - ly)).toInt())
                        centerGeo = GeoPoint(ng.latitude, ng.longitude)
                    }
                    lx = e.x; ly = e.y
                }
                if (e.pointerCount == 2) {
                    val d = dist(e); val a = ang(e)
                    if (lastDist > 10f) widthMeters = (widthMeters * (lastDist / d)).coerceIn(100.0, 25000.0)
                    rotationDeg += (a - lastAng); lastDist = d; lastAng = a
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 1) multi = false
        }
        return true
    }
    private fun handleCpTap(sx: Float, sy: Float) {
        rebuildMatrix(); val inv = Matrix(); if (!matrix.invert(inv)) return
        val bPt = floatArrayOf(sx, sy); inv.mapPoints(bPt)
        val hit = cps.indexOfFirst { val p = floatArrayOf(it.x, it.y); matrix.mapPoints(p); hypot((p[0] - sx).toDouble(), (p[1] - sy).toDouble()) < 45.0 }
        if (hit >= 0) cps.removeAt(hit) else cps.add(PointF(bPt[0], bPt[1]))
        invalidate()
    }
    private fun dist(e: MotionEvent) = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat()
    private fun ang(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
}
