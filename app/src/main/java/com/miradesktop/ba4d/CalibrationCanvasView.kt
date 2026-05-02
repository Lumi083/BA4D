package com.miradesktop.ba4d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CalibrationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val crossPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val crosses = mutableListOf<CrossData>()
    private val handler = Handler(Looper.getMainLooper())

    var onTouchCallback: ((x: Int, y: Int) -> Unit)? = null

    private data class CrossData(
        val x: Float,
        val y: Float,
        val timestamp: Long
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y

            // Add cross at touch location
            val crossData = CrossData(x, y, System.currentTimeMillis())
            crosses.add(crossData)
            invalidate()

            // Remove after 3 seconds
            handler.postDelayed({
                crosses.remove(crossData)
                invalidate()
            }, 3000)

            // Notify callback
            onTouchCallback?.invoke(x.toInt(), y.toInt())

            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw all crosses
        for (cross in crosses) {
            val size = 25f
            canvas.drawLine(cross.x - size, cross.y, cross.x + size, cross.y, crossPaint)
            canvas.drawLine(cross.x, cross.y - size, cross.x, cross.y + size, crossPaint)
            canvas.drawCircle(cross.x, cross.y, 4f, crossPaint)
        }
    }

    fun clearCrosses() {
        crosses.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
