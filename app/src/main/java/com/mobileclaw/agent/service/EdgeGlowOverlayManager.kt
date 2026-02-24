package com.mobileclaw.agent.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Manages drawing a glowing border around the edge of the screen to indicate
 * when the AI agent is actively running.
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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(glowView, params)
                isShowing = true
                Log.d("EdgeGlow", "Started Edge Glow")
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
        private val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 25f // 25 pixels wide outline
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = glowColor
            paint.setShadowLayer(50f, 0f, 0f, glowColor)

            val inset = paint.strokeWidth / 2f
            canvas.drawRoundRect(
                inset, inset,
                width.toFloat() - inset, height.toFloat() - inset,
                80f, 80f, // Rounded corners for modern phones
                paint
            )
        }
    }
}
