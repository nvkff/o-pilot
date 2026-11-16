package io.github.nvkff.opilot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.nvkff.opilot.shared.ControlPoint
import org.osmdroid.views.Projection
import kotlin.math.atan2
import kotlin.math.hypot

class MapOverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Mode { MAP, ADJUST_SCAN, EDIT_CP }
    var mode = Mode.MAP
    private var bmp: Bitmap? = null
    private val cps = mutableListOf<PointF>()
    private val matrix = Matrix()
    private val imgPaint = Paint().apply { alpha = 165; isFilterBitmap = true }
    private val cpPaint = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val cpText = Paint().apply { color = Color.YELLOW; textSize = 30f; isFakeBoldText = true; isAntiAlias = true }
    private var lx = 0f; private var ly = 0f; private var lastDist = 0f; private var lastAng = 0f; private var multi = false

    fun setMap(bitmap: Bitmap, points: List<PointF>) {
        bmp = bitmap; cps.clear(); cps.addAll(points)
        post { fitCenter(); invalidate() }
    }
    fun setOverlayAlpha(a: Int) { imgPaint.alpha = a; invalidate() }
    fun fitCenter() {
        val b = bmp ?: return; if (width == 0) return
        matrix.reset()
        val s = (width * 0.7f) / b.width
        matrix.postScale(s, s)
        matrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        invalidate()
    }
    fun setPlacement(cx: Float, cy: Float, targetWidthPx: Float) {
        val b = bmp ?: return
        matrix.reset()
        val s = targetWidthPx / b.width
        matrix.postScale(s, s)
        matrix.postTranslate(cx - targetWidthPx / 2f, cy - b.height * s / 2f)
        invalidate()
    }
    fun exportControlPoints(proj: Projection): List<ControlPoint> {
        val out = mutableListOf<ControlPoint>()
        cps.forEachIndexed { idx, p ->
            val pt = floatArrayOf(p.x, p.y)
            matrix.mapPoints(pt)
            val geo = proj.fromPixels(pt[0].toInt(), pt[1].toInt())
            out.add(ControlPoint(idx + 1, "КП ${idx + 1}", geo.latitude, geo.longitude))
        }
        return out
    }
    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawBitmap(b, matrix, imgPaint)
        cps.forEachIndexed { i, p ->
            val pt = floatArrayOf(p.x, p.y); matrix.mapPoints(pt)
            c.drawCircle(pt[0], pt[1], 18f, cpPaint)
            c.drawText("${i + 1}", pt[0] + 20f, pt[1] + 8f, cpText)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.MAP) return false
        if (mode == Mode.EDIT_CP) {
            if (e.actionMasked == MotionEvent.ACTION_UP) handleCpTap(e.x, e.y)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                multi = true; lastDist = dist(e); lastAng = ang(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !multi) {
                    matrix.postTranslate(e.x - lx, e.y - ly); lx = e.x; ly = e.y
                } else if (e.pointerCount == 2) {
                    val d = dist(e); val a = ang(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2f; val my = (e.getY(0) + e.getY(1)) / 2f
                    if (lastDist > 10f) { val sc = d / lastDist; matrix.postScale(sc, sc, mx, my) }
                    matrix.postRotate(a - lastAng, mx, my)
                    lastDist = d; lastAng = a
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 1) multi = false
        }
        return true
    }
    private fun handleCpTap(sx: Float, sy: Float) {
        val inv = Matrix(); if (!matrix.invert(inv)) return
        val bPt = floatArrayOf(sx, sy); inv.mapPoints(bPt)
        val hit = cps.indexOfFirst {
            val p = floatArrayOf(it.x, it.y); matrix.mapPoints(p)
            hypot((p[0] - sx).toDouble(), (p[1] - sy).toDouble()) < 45.0
        }
        if (hit >= 0) cps.removeAt(hit) else cps.add(PointF(bPt[0], bPt[1]))
        invalidate()
    }
    private fun dist(e: MotionEvent) = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat()
    private fun ang(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
}
