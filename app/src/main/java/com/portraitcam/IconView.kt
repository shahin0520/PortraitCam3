package com.portraitcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Samsung-স্টাইলের পাতলা সাদা আইকন (কোডে আঁকা, তাই ফাইল-ভুলের ঝুঁকি নেই)
class IconView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    enum class Kind { SETTINGS, FLASH, TIMER, RATIO, GRID, FILTER, FLIP }

    var kind: Kind = Kind.SETTINGS
        set(v) { field = v; invalidate() }
    var active: Boolean = false
        set(v) { field = v; invalidate() }
    var caption: String = ""
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    private fun arrow(canvas: Canvas, px: Float, py: Float, tx: Float, ty: Float, size: Float) {
        val nx = -ty
        val ny = tx
        path.reset()
        path.moveTo(px + tx * size, py + ty * size)
        path.lineTo(px - tx * size * 0.3f + nx * size * 0.8f, py - ty * size * 0.3f + ny * size * 0.8f)
        path.lineTo(px - tx * size * 0.3f - nx * size * 0.8f, py - ty * size * 0.3f - ny * size * 0.8f)
        path.close()
        canvas.drawPath(path, fill)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        val col = if (active) 0xFFFFD60A.toInt() else Color.WHITE
        stroke.color = col
        fill.color = col
        txt.color = col
        stroke.strokeWidth = 1.8f * d
        val w = width.toFloat()
        val h = height.toFloat()
        val capH = if (caption.isEmpty()) 0f else 13f * d
        val cx = w / 2f
        val cy = (h - capH) / 2f
        val r = min(w * 0.5f, (h - capH) * 0.5f).coerceAtMost(11f * d)
        when (kind) {
            Kind.SETTINGS -> {
                canvas.drawCircle(cx, cy, r * 0.42f, stroke)
                for (i in 0 until 8) {
                    val ang = (i * 45.0) * Math.PI / 180.0
                    val ca = cos(ang).toFloat()
                    val sa = sin(ang).toFloat()
                    canvas.drawLine(cx + ca * r * 0.62f, cy + sa * r * 0.62f, cx + ca * r * 0.95f, cy + sa * r * 0.95f, stroke)
                }
                canvas.drawCircle(cx, cy, r * 0.16f, fill)
            }
            Kind.FLASH -> {
                path.reset()
                path.moveTo(cx + r * 0.15f, cy - r)
                path.lineTo(cx - r * 0.55f, cy + r * 0.1f)
                path.lineTo(cx - r * 0.05f, cy + r * 0.1f)
                path.lineTo(cx - r * 0.15f, cy + r)
                path.lineTo(cx + r * 0.55f, cy - r * 0.2f)
                path.lineTo(cx + r * 0.05f, cy - r * 0.2f)
                path.close()
                canvas.drawPath(path, if (active) fill else stroke)
            }
            Kind.TIMER -> {
                val ccy = cy + r * 0.12f
                canvas.drawCircle(cx, ccy, r * 0.78f, stroke)
                canvas.drawLine(cx - r * 0.25f, cy - r * 0.85f, cx + r * 0.25f, cy - r * 0.85f, stroke)
                canvas.drawLine(cx, ccy, cx + r * 0.35f, ccy - r * 0.38f, stroke)
            }
            Kind.RATIO -> {
                rect.set(cx - r * 0.78f, cy - r, cx + r * 0.78f, cy + r)
                canvas.drawRoundRect(rect, 3f * d, 3f * d, stroke)
                txt.textSize = 8f * sp
                canvas.drawText("3:4", cx, cy + 3f * d, txt)
            }
            Kind.GRID -> {
                rect.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
                canvas.drawRoundRect(rect, 3f * d, 3f * d, stroke)
                val a = r * 0.9f / 3f
                canvas.drawLine(cx - a, cy - r * 0.9f, cx - a, cy + r * 0.9f, stroke)
                canvas.drawLine(cx + a, cy - r * 0.9f, cx + a, cy + r * 0.9f, stroke)
                canvas.drawLine(cx - r * 0.9f, cy - a, cx + r * 0.9f, cy - a, stroke)
                canvas.drawLine(cx - r * 0.9f, cy + a, cx + r * 0.9f, cy + a, stroke)
            }
            Kind.FILTER -> {
                canvas.drawCircle(cx, cy - r * 0.38f, r * 0.52f, stroke)
                canvas.drawCircle(cx - r * 0.42f, cy + r * 0.3f, r * 0.52f, stroke)
                canvas.drawCircle(cx + r * 0.42f, cy + r * 0.3f, r * 0.52f, stroke)
            }
            Kind.FLIP -> {
                val rr = r * 0.8f
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                canvas.drawArc(rect, 200f, 130f, false, stroke)
                canvas.drawArc(rect, 20f, 130f, false, stroke)
                var e = 330.0 * Math.PI / 180.0
                arrow(canvas, cx + cos(e).toFloat() * rr, cy + sin(e).toFloat() * rr, (-sin(e)).toFloat(), cos(e).toFloat(), r * 0.4f)
                e = 150.0 * Math.PI / 180.0
                arrow(canvas, cx + cos(e).toFloat() * rr, cy + sin(e).toFloat() * rr, (-sin(e)).toFloat(), cos(e).toFloat(), r * 0.4f)
            }
        }
        if (caption.isNotEmpty()) {
            txt.textSize = 9f * sp
            canvas.drawText(caption, cx, h - 3f * d, txt)
        }
    }
}
