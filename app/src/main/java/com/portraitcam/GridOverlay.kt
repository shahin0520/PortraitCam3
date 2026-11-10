package com.portraitcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlay @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val p = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 2f }
    override fun onDraw(cv: Canvas) {
        for (i in 1..2) {
            cv.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), p)
            cv.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, p)
        }
    }
}
