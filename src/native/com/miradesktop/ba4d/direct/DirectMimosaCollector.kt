package com.miradesktop.ba4d.direct

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collect touch input events directly from an overlay window.
 * This method doesn't require special permissions but the overlay must be touchable.
 */
class DirectMimosaCollector(
    private val context: Context,
    private val onPointer: (pointerId: Int, x: Int, y: Int, pressed: Boolean) -> Unit,
    private val onBackgroundLog: (eventName: String, detail: String, x: Int, y: Int) -> Unit,
    private val fpsLimit: Int = 60
) {
    private val active = AtomicBoolean(false)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var lastEmitMs = 0L
    private val minEmitIntervalMs = (1000.0 / fpsLimit).toLong()

    // Track active pointers
    private val activePointers = mutableMapOf<Int, Pair<Int, Int>>()

    fun start() {
        if (!active.compareAndSet(false, true)) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Create a transparent overlay that captures touch events
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        overlayView = View(context).apply {
            setBackgroundColor(0x00000000) // Fully transparent
            setOnTouchListener { _, event ->
                handleTouchEvent(event)
                true
            }
        }

        windowManager?.addView(overlayView, params)
        android.util.Log.d("DirectMimosaCollector", "Overlay view created with flags: ${params.flags}")
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return

        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        windowManager = null
        activePointers.clear()
    }

    fun setTouchable(touchable: Boolean) {
        val view = overlayView ?: run {
            android.util.Log.w("DirectMimosaCollector", "setTouchable: overlayView is null")
            return
        }
        val wm = windowManager ?: run {
            android.util.Log.w("DirectMimosaCollector", "setTouchable: windowManager is null")
            return
        }

        val params = view.layoutParams as? WindowManager.LayoutParams ?: run {
            android.util.Log.w("DirectMimosaCollector", "setTouchable: layoutParams is not WindowManager.LayoutParams")
            return
        }

        android.util.Log.d("DirectMimosaCollector", "setTouchable($touchable) - current flags: ${params.flags}")

        if (touchable) {
            // Remove FLAG_NOT_TOUCHABLE to allow capturing touches
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            // Add FLAG_NOT_TOUCHABLE to let touches pass through
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        android.util.Log.d("DirectMimosaCollector", "setTouchable($touchable) - new flags: ${params.flags}")

        try {
            wm.updateViewLayout(view, params)
            android.util.Log.d("DirectMimosaCollector", "updateViewLayout succeeded")
        } catch (e: Exception) {
            android.util.Log.e("DirectMimosaCollector", "updateViewLayout failed", e)
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val now = System.currentTimeMillis()
        if (now - lastEmitMs < minEmitIntervalMs) return
        lastEmitMs = now

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val x = event.getX(pointerIndex).toInt()
                val y = event.getY(pointerIndex).toInt()

                activePointers[pointerId] = Pair(x, y)
                onPointer(pointerId, x, y, true)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i).toInt()
                    val y = event.getY(i).toInt()

                    val lastPos = activePointers[pointerId]
                    if (lastPos == null || lastPos.first != x || lastPos.second != y) {
                        activePointers[pointerId] = Pair(x, y)
                        onPointer(pointerId, x, y, true)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val x = event.getX(pointerIndex).toInt()
                val y = event.getY(pointerIndex).toInt()

                activePointers.remove(pointerId)
                onPointer(pointerId, x, y, false)
            }

            MotionEvent.ACTION_CANCEL -> {
                // Release all active pointers
                for ((pointerId, pos) in activePointers) {
                    onPointer(pointerId, pos.first, pos.second, false)
                }
                activePointers.clear()
            }
        }
    }
}
