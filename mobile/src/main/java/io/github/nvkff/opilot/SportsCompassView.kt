package io.github.nvkff.opilot

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class SportsCompassView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var headingDeg = 0f
    var targetBearingDeg = 0f
    var hasTarget = false
    var isTaken = false
    private val ringP = Paint().apply { color = 0xFF2D333B.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val bgP = Paint().apply { color = 0xFF161B22.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val tickP = Paint().apply { color = 0xFF6E7681.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val nP = Paint().apply { color = 0xFFFF5252.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
    private val cardP = Paint().apply { color = 0xFF8B949E.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
    private val arrowP = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val arrowPath = Path()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = (minOf(width, height) * 0.44f).coerceAtLeast(40f)
        c.drawCircle(cx, cy, r, bgP); c.drawCircle(cx, cy, r, ringP)
        c.save(); c.rotate(-headingDeg, cx, cy)
        for (d in 0 until 360 step 15) {
            val rad = Math.toRadians(d.toDouble()); val len = if (d % 90 == 0) 16f else 8f
            c.drawLine((cx + (r - len) * sin(rad)).toFloat(), (cy - (r - len) * cos(rad)).toFloat(), (cx + r * sin(rad)).toFloat(), (cy - r * cos(rad)).toFloat(), tickP)
        }
        c.drawText("С", cx, cy - r + 38f, nP); c.drawText("Ю", cx, cy + r - 20f, cardP)
        c.drawText("В", cx + r - 28f, cy + 8f, cardP); c.drawText("З", cx - r + 28f, cy + 8f, cardP)
        c.restore()
        if (hasTarget) {
            c.save(); c.rotate(targetBearingDeg - headingDeg, cx, cy)
            arrowP.color = if (isTaken) 0xFF2EA043.toInt() else 0xFFFF9800.toInt()
            arrowPath.reset()
            arrowPath.moveTo(cx, cy - r * 0.72f); arrowPath.lineTo(cx + r * 0.16f, cy + r * 0.28f)
            arrowPath.lineTo(cx, cy + r * 0.12f); arrowPath.lineTo(cx - r * 0.16f, cy + r * 0.28f); arrowPath.close()
            c.drawPath(arrowPath, arrowP); c.restore()
        }
        c.drawCircle(cx, cy, 8f, tickP)
    }
}
