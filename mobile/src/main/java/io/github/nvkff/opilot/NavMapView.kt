package io.github.nvkff.opilot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class NavMapView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var bmp: Bitmap? = null; private var proj: MapProject? = null
    var activeIdx = 0; var customBx = -1f; var customBy = -1f
    var gpsLat: Double? = null; var gpsLon: Double? = null; var showGps = true
    var onSelectCp: ((Int) -> Unit)? = null
    var onSelectPoint: ((Float, Float) -> Unit)? = null
    private val mat = Matrix()
    private val imgP = Paint().apply { isFilterBitmap = true }
    private val pPend = Paint().apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val pAct = Paint().apply { color = 0xFFFF9800.toInt(); style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
    private val pDone = Paint().apply { color = 0xFF2EA043.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
    private val pTxt = Paint().apply { color = Color.YELLOW; textSize = 30f; isFakeBoldText = true; isAntiAlias = true }
    private val pGps = Paint().apply { color = 0xFF2196F3.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val pGpsW = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
    private var lx = 0f; private var ly = 0f; private var lastD = 0f; private var multi = false
    private val tapDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
    })
    fun setData(b: Bitmap, p: MapProject) { bmp = b; proj = p; post { fit(); invalidate() } }
    fun fit() {
        val b = bmp ?: return; if (width == 0) return; mat.reset()
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        mat.postScale(s, s); mat.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f); invalidate()
    }
    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF0D1117.toInt()); val b = bmp ?: return; val pr = proj ?: return
        c.drawBitmap(b, mat, imgP)
        pr.cps.forEachIndexed { i, cp ->
            val pt = floatArrayOf(cp.bx, cp.by); mat.mapPoints(pt)
            val paint = if (customBx < 0f && i == activeIdx) pAct else if (cp.taken) pDone else pPend
            val rad = if (customBx < 0f && i == activeIdx) 24f else 18f
            c.drawCircle(pt[0], pt[1], rad, paint); c.drawText(cp.label, pt[0] + 24f, pt[1] + 8f, pTxt)
        }
        if (customBx >= 0f) { val pt = floatArrayOf(customBx, customBy); mat.mapPoints(pt); c.drawCircle(pt[0], pt[1], 22f, pAct); c.drawText("Цель", pt[0] + 24f, pt[1] + 8f, pTxt) }
        if (showGps) gpsLat?.let { la -> gpsLon?.let { lo ->
            val gp = MapProjectStorage.latLonToPixel(pr, b.width, b.height, la, lo)
            val pt = floatArrayOf(gp.x, gp.y); mat.mapPoints(pt)
            c.drawCircle(pt[0], pt[1], 13f, pGps); c.drawCircle(pt[0], pt[1], 13f, pGpsW)
        }}
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        tapDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { multi = true; lastD = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat() }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1) if (!multi) { mat.postTranslate(e.x - lx, e.y - ly); lx = e.x; ly = e.y }
                if (e.pointerCount == 2) { val d = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat(); if (lastD > 10f) { val sc = d / lastD; mat.postScale(sc, sc, (e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f) }; lastD = d }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 1) multi = false
        }
        return true
    }
    private fun handleTap(sx: Float, sy: Float) {
        val pr = proj ?: return; val inv = Matrix(); if (!mat.invert(inv)) return
        val bp = floatArrayOf(sx, sy); inv.mapPoints(bp)
        val hit = pr.cps.indexOfFirst { val p = floatArrayOf(it.bx, it.by); mat.mapPoints(p); hypot((p[0] - sx).toDouble(), (p[1] - sy).toDouble()) < 55.0 }
        if (hit >= 0) { customBx = -1f; onSelectCp?.invoke(hit) } else { customBx = bp[0]; customBy = bp[1]; onSelectPoint?.invoke(bp[0], bp[1]) }
        invalidate()
    }
}
