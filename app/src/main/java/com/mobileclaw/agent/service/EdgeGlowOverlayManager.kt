package com.mobileclaw.agent.service

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Draws a glowing neon border around the physical screen edges
 * while the AI agent is actively running.
 * Uses TYPE_ACCESSIBILITY_OVERLAY (no permission popup needed from within AccessibilityService).
 */
class EdgeGlowOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var glowView: GlowView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isShowing = false

    fun showGlow(color: Int) {
        handler.post {
            if (isShowing && glowView != null) {
                glowView?.glowColor = color
                glowView?.invalidate()
                return@post
            }

            hideGlowSync()

            glowView = GlowView(context, color)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(glowView, params)
                isShowing = true
                Log.d("EdgeGlow", "Edge Glow started with color: ${Integer.toHexString(color)}")
            } catch (e: Exception) {
                Log.e("EdgeGlow", "Failed to add Edge Glow overlay", e)
            }
        }
    }

    fun hideGlow() {
        handler.post { hideGlowSync() }
    }

    private fun hideGlowSync() {
        if (!isShowing) return
        glowView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        glowView = null
        isShowing = false
    }

    private inner class GlowView(context: Context, var glowColor: Int) : View(context) {

        // Multiple stroke passes for a convincing glow
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 18f
        }
        private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        init {
            // CRITICAL: setShadowLayer only works on software-rendered layers
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Outer glow (wide, transparent)
            outerPaint.color = Color.argb(60, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            outerPaint.setShadowLayer(40f, 0f, 0f, glowColor)
            canvas.drawRoundRect(18f, 18f, w - 18f, h - 18f, 60f, 60f, outerPaint)

            // Mid glow (narrower, more visible)
            midPaint.color = Color.argb(130, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            midPaint.setShadowLayer(20f, 0f, 0f, glowColor)
            canvas.drawRoundRect(10f, 10f, w - 10f, h - 10f, 55f, 55f, midPaint)

            // Inner core (thin, bright)
            innerPaint.color = glowColor
            innerPaint.setShadowLayer(8f, 0f, 0f, glowColor)
            canvas.drawRoundRect(5f, 5f, w - 5f, h - 5f, 50f, 50f, innerPaint)
        }
    }
}
