package io.github.nvkff.opilot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.nvkff.opilot.shared.ControlPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.math.*

class MapOverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Mode { MAP, ADJUST_SCAN, EDIT_CP, TWO_PT }
    var mode = Mode.MAP; var mapViewRef: MapView? = null; var onStatus: ((String) -> Unit)? = null; var loupeTopY = 250f
    private var bmp: Bitmap? = null; private val cps = mutableListOf<PointF>(); private var labels = listOf<String>()
    var centerGeo = GeoPoint(55.7558, 37.6173); var widthMeters = 1800.0; var rotationDeg = 0f
    private var twoPtStep = 0; private var sPt1: PointF? = null; private var mGeo1: GeoPoint? = null; private var sPt2: PointF? = null
    private val matrix = Matrix()
    private val imgPaint = Paint().apply { alpha = 165; isFilterBitmap = true }
    private val cpPaint = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val cpText = Paint().apply { color = Color.YELLOW; textSize = 30f; isFakeBoldText = true; isAntiAlias = true }
    private val pinPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL; isAntiAlias = true }
    private val loupeBorder = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val crossPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
    private var lx = 0f; private var ly = 0f; private var lastDist = 0f; private var lastAng = 0f; private var multi = false
    private var downX = 0f; private var downY = 0f; private var loupeX = 0f; private var loupeY = 0f
    private var loupeActive = false; private var isDrawingLoupe = false
    private val loupeRun = Runnable { loupeActive = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate() }
    private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (mode == Mode.EDIT_CP) handleCpTap(e.x, e.y) else if (mode == Mode.TWO_PT) handleTwoPtTap(e.x, e.y)
            return true
        }
    })
    fun setMap(bitmap: Bitmap, points: List<PointF>, center: GeoPoint, cpLabels: List<String> = emptyList()) { bmp = bitmap; cps.clear(); cps.addAll(points); labels = cpLabels; centerGeo = center; invalidate() }
    fun startTwoPt() { twoPtStep = 0; sPt1 = null; mGeo1 = null; sPt2 = null; mode = Mode.TWO_PT; onStatus?.invoke("2Т (1/4): зажми ориентир НА СКАНЕ"); invalidate() }
    fun setOverlayAlpha(a: Int) { imgPaint.alpha = a; invalidate() }
    fun setPlacement(c: GeoPoint, wMeters: Double, rot: Float = 0f) { centerGeo = c; widthMeters = wMeters; rotationDeg = rot; invalidate() }
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
            ControlPoint(idx + 1, labels.getOrNull(idx) ?: "КП ${idx + 1}", g.latitude, g.longitude)
        }
    }
    override fun onDraw(c: Canvas) {
        val b = bmp ?: return; rebuildMatrix(); c.drawBitmap(b, matrix, imgPaint)
        cps.forEachIndexed { i, p ->
            val pt = floatArrayOf(p.x, p.y); matrix.mapPoints(pt)
            c.drawCircle(pt[0], pt[1], 18f, cpPaint); c.drawText(labels.getOrNull(i) ?: "${i + 1}", pt[0] + 20f, pt[1] + 8f, cpText)
        }
        sPt1?.let { val p = floatArrayOf(it.x, it.y); matrix.mapPoints(p); c.drawCircle(p[0], p[1], 14f, pinPaint) }
        sPt2?.let { val p = floatArrayOf(it.x, it.y); matrix.mapPoints(p); c.drawCircle(p[0], p[1], 14f, pinPaint) }
        if (isDrawingLoupe) return
        if (loupeActive) {
            val sz = (width * 0.34f).coerceIn(220f, 340f); val lx0 = width - sz - 24f; val ly0 = loupeTopY
            c.save(); c.clipRect(lx0, ly0, lx0 + sz, ly0 + sz); c.drawColor(Color.BLACK)
            c.translate(lx0 + sz / 2f, ly0 + sz / 2f); c.scale(4f, 4f); c.translate(-loupeX, -loupeY)
            mapViewRef?.draw(c); isDrawingLoupe = true; draw(c); isDrawingLoupe = false; c.restore()
            c.drawRect(lx0, ly0, lx0 + sz, ly0 + sz, loupeBorder)
            c.drawLine(lx0 + sz / 2f - 24f, ly0 + sz / 2f, lx0 + sz / 2f + 24f, ly0 + sz / 2f, crossPaint)
            c.drawLine(lx0 + sz / 2f, ly0 + sz / 2f - 24f, lx0 + sz / 2f, ly0 + sz / 2f + 24f, crossPaint)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.MAP) return false
        if (mode in listOf(Mode.EDIT_CP, Mode.TWO_PT)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; loupeX = e.x; loupeY = e.y; loupeActive = false; postDelayed(loupeRun, 180L); tapDetector.onTouchEvent(e); mapViewRef?.dispatchTouchEvent(e) }
                MotionEvent.ACTION_POINTER_DOWN -> { removeCallbacks(loupeRun); loupeActive = false; invalidate(); mapViewRef?.dispatchTouchEvent(e) }
                MotionEvent.ACTION_MOVE -> if (loupeActive) { loupeX = e.x; loupeY = e.y; invalidate() } else { if (hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 18.0) removeCallbacks(loupeRun); tapDetector.onTouchEvent(e); mapViewRef?.dispatchTouchEvent(e) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(loupeRun)
                    if (loupeActive) {
                        loupeActive = false; val cEv = MotionEvent.obtain(e); cEv.action = MotionEvent.ACTION_CANCEL; mapViewRef?.dispatchTouchEvent(cEv); cEv.recycle()
                        if (e.actionMasked == MotionEvent.ACTION_UP) { if (mode == Mode.EDIT_CP) handleCpTap(e.x, e.y) else handleTwoPtTap(e.x, e.y) }
                        invalidate()
                    } else { tapDetector.onTouchEvent(e); mapViewRef?.dispatchTouchEvent(e) }
                }
                else -> mapViewRef?.dispatchTouchEvent(e)
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { multi = true; lastDist = dist(e); lastAng = ang(e) }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1) if (!multi) { mapViewRef?.projection?.let { val pc = it.toPixels(centerGeo, null); val ng = it.fromPixels((pc.x + (e.x - lx)).toInt(), (pc.y + (e.y - ly)).toInt()); centerGeo = GeoPoint(ng.latitude, ng.longitude) }; lx = e.x; ly = e.y }
                if (e.pointerCount == 2) { val d = dist(e); val a = ang(e); if (lastDist > 10f) widthMeters = (widthMeters * (d / lastDist)).coerceIn(100.0, 25000.0); rotationDeg += (a - lastAng); lastDist = d; lastAng = a }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 1) multi = false
        }
        return true
    }
    private fun handleTwoPtTap(sx: Float, sy: Float) {
        val proj = mapViewRef?.projection ?: return; val b = bmp ?: return; rebuildMatrix()
        if (twoPtStep == 0) { val inv = Matrix(); if (!matrix.invert(inv)) return; val pt = floatArrayOf(sx, sy); inv.mapPoints(pt); sPt1 = PointF(pt[0], pt[1]); twoPtStep = 1; onStatus?.invoke("2Т (2/4): ту же точку НА КАРТЕ") }
        else if (twoPtStep == 1) { val g = proj.fromPixels(sx.toInt(), sy.toInt()); mGeo1 = GeoPoint(g.latitude, g.longitude); twoPtStep = 2; onStatus?.invoke("2Т (3/4): 2-й ориентир НА СКАНЕ") }
        else if (twoPtStep == 2) { val inv = Matrix(); if (!matrix.invert(inv)) return; val pt = floatArrayOf(sx, sy); inv.mapPoints(pt); sPt2 = PointF(pt[0], pt[1]); twoPtStep = 3; onStatus?.invoke("2Т (4/4): 2-й ориентир НА КАРТЕ") }
        else if (twoPtStep == 3) {
            val b1 = sPt1 ?: return; val b2 = sPt2 ?: return; val g1 = mGeo1 ?: return; val g2 = proj.fromPixels(sx.toInt(), sy.toInt())
            val p1 = proj.toPixels(g1, null); val p2 = proj.toPixels(GeoPoint(g2.latitude, g2.longitude), null)
            val dbx = (b2.x - b1.x).toDouble(); val dby = (b2.y - b1.y).toDouble(); val dsx = (p2.x - p1.x).toDouble(); val dsy = (p2.y - p1.y).toDouble()
            val lenB = hypot(dbx, dby); val lenS = hypot(dsx, dsy); if (lenB < 10.0) return
            val sc = lenS / lenB; val dAng = atan2(dsy, dsx) - atan2(dby, dbx); val pxW = b.width * sc
            val gL = proj.fromPixels((p1.x - pxW / 2).toInt(), p1.y); val gR = proj.fromPixels((p1.x + pxW / 2).toInt(), p1.y)
            widthMeters = GeoPoint(gL.latitude, gL.longitude).distanceToAsDouble(GeoPoint(gR.latitude, gR.longitude)).coerceIn(100.0, 25000.0); rotationDeg = Math.toDegrees(dAng).toFloat()
            val vx = b.width / 2.0 - b1.x; val vy = b.height / 2.0 - b1.y
            val scx = p1.x + sc * (vx * cos(dAng) - vy * sin(dAng)); val scy = p1.y + sc * (vx * sin(dAng) + vy * cos(dAng))
            val cg = proj.fromPixels(scx.toInt(), scy.toInt()); centerGeo = GeoPoint(cg.latitude, cg.longitude)
            sPt1 = null; sPt2 = null; mode = Mode.MAP; onStatus?.invoke("Привязка по 2 точкам готова!")
        }
        invalidate()
    }
    private fun handleCpTap(sx: Float, sy: Float) {
        rebuildMatrix(); val inv = Matrix(); if (!matrix.invert(inv)) return; val bPt = floatArrayOf(sx, sy); inv.mapPoints(bPt)
        val hit = cps.indexOfFirst { val p = floatArrayOf(it.x, it.y); matrix.mapPoints(p); hypot((p[0] - sx).toDouble(), (p[1] - sy).toDouble()) < 45.0 }
        if (hit >= 0) cps.removeAt(hit) else cps.add(PointF(bPt[0], bPt[1]))
        invalidate()
    }
    private fun dist(e: MotionEvent) = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat()
    private fun ang(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
}
