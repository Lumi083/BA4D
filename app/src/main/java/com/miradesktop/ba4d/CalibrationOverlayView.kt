package com.miradesktop.ba4d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CalibrationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val crossPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    var dotX: Float = 0f
    var dotY: Float = 0f
    var showDot: Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // Draw crosshair
        val crossSize = 50f
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, crossPaint)

        // Draw center circle
        canvas.drawCircle(centerX, centerY, 5f, crossPaint)

        // Draw calibration dot if active
        if (showDot) {
            canvas.drawCircle(dotX, dotY, 20f, dotPaint)
            canvas.drawText("点击这里", dotX, dotY - 30f, textPaint)
        }
    }

    fun updateDot(x: Float, y: Float) {
        dotX = x
        dotY = y
        showDot = true
        invalidate()
    }

    fun hideDot() {
        showDot = false
        invalidate()
    }
}
