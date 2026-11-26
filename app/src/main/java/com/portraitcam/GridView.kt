package com.portraitcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val p = Paint().apply { color = 0x66FFFFFF; strokeWidth = 2f }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
    }
}
