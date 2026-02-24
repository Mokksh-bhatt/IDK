package com.mobileclaw.agent.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Manages drawing a transparent overlay with numbered boxes (Set-of-Mark style)
 * over interactive UI elements. This runs in the context of the Accessibility Service
 * so it has permission to draw a TYPE_ACCESSIBILITY_OVERLAY.
 */
class BoundingBoxOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private val handler = Handler(Looper.getMainLooper())

    fun showBoxes(boxes: Map<Int, Rect>) {
        handler.post {
            hideBoxesSync()
            
            overlayView = OverlayView(context, boxes)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // Draw over status/nav bars
                PixelFormat.TRANSLUCENT
            )
            
            try {
                windowManager.addView(overlayView, params)
                Log.d("BoundingBox", "Drew ${boxes.size} bounding boxes")
            } catch (e: Exception) {
                Log.e("BoundingBox", "Failed to add overlay", e)
            }
        }
    }

    fun hideBoxes() {
        handler.post { hideBoxesSync() }
    }

    private fun hideBoxesSync() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        overlayView = null
    }

    private inner class OverlayView(context: Context, val boxes: Map<Int, Rect>) : View(context) {
        
        private val boxPaint = Paint().apply {
            color = Color.parseColor("#44FFE600")
            style = Paint.Style.FILL
        }
        
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#FFFFE600")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        
        private val badgeBgPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        
        private val textPaint = Paint().apply {
            color = Color.parseColor("#FFFFE600")
            textSize = 34f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for ((id, rect) in boxes) {
                canvas.drawRect(rect, boxPaint)
                canvas.drawRect(rect, borderPaint)
                
                val text = id.toString()
                val textWidth = textPaint.measureText(text)
                val fontMetrics = textPaint.fontMetrics
                val textHeight = fontMetrics.descent - fontMetrics.ascent
                
                val padding = 6
                val bgRect = Rect(
                    rect.left,
                    rect.top,
                    (rect.left + textWidth + padding * 2).toInt(),
                    (rect.top + textHeight + padding).toInt()
                )
                
                canvas.drawRect(bgRect, badgeBgPaint)
                canvas.drawText(
                    text, 
                    rect.left + padding.toFloat(), 
                    rect.top - fontMetrics.ascent + (padding / 2f), 
                    textPaint
                )
            }
        }
    }

    companion object {
        /**
         * Draws numbered bounding boxes DIRECTLY onto a Bitmap.
         * This is critical because MediaProjection does NOT capture window overlays,
         * so the AI can only see boxes if they are baked into the screenshot itself.
         */
        fun drawBoxesOnBitmap(source: android.graphics.Bitmap, boxes: Map<Int, Rect>): android.graphics.Bitmap {
            if (boxes.isEmpty()) return source

            // Create a mutable copy to draw on
            val mutable = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)

            val boxPaint = Paint().apply {
                color = Color.parseColor("#44FFE600")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint().apply {
                color = Color.parseColor("#FFFFE600")
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val badgeBgPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            val textPaint = Paint().apply {
                color = Color.parseColor("#FFFFE600")
                textSize = 32f
                isAntiAlias = true
                isFakeBoldText = true
                textAlign = Paint.Align.LEFT
            }

            for ((id, rect) in boxes) {
                // Draw semi-transparent fill and border
                canvas.drawRect(rect, boxPaint)
                canvas.drawRect(rect, borderPaint)

                // Draw black badge with yellow number at top-left corner
                val text = id.toString()
                val tw = textPaint.measureText(text)
                val fm = textPaint.fontMetrics
                val th = fm.descent - fm.ascent
                val pad = 5

                val bgRect = Rect(
                    rect.left, rect.top,
                    (rect.left + tw + pad * 2).toInt(),
                    (rect.top + th + pad).toInt()
                )
                canvas.drawRect(bgRect, badgeBgPaint)
                canvas.drawText(text, rect.left + pad.toFloat(), rect.top - fm.ascent + (pad / 2f), textPaint)
            }

            return mutable
        }
    }
}
