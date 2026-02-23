package com.mobileclaw.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * A floating bubble overlay that shows the agent status
 * and allows quick pause/stop while the agent is working in other apps.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlay"
        var instance: FloatingOverlayService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingBubble()
    }

    private fun createFloatingBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val container = FrameLayout(this).apply {
            val bubble = ImageView(this@FloatingOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_view)
                setPadding(24, 24, 24, 24)
                setBackgroundColor(0xFF6200EE.toInt())
                alpha = 0.85f
            }

            addView(bubble, FrameLayout.LayoutParams(120, 120))

            // Make draggable
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(this, params)
                        true
                    }
                    else -> false
                }
            }
        }

        floatingView = container

        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
        }
    }

    override fun onDestroy() {
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
        instance = null
        super.onDestroy()
    }
}
