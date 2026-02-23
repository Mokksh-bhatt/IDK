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
        
        // Semi-transparent neon yellow for the box background
        private val boxPaint = Paint().apply {
            color = Color.parseColor("#44FFE600")
            style = Paint.Style.FILL
        }
        
        // Bright solid yellow for the borders
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#FFFFE600")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        
        // Solid black background for the ID badge
        private val badgeBgPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        
        // Solid yellow text for the ID
        private val textPaint = Paint().apply {
            color = Color.parseColor("#FFFFE600")
            textSize = 34f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Draw all boxes
            for ((id, rect) in boxes) {
                // Draw box
                canvas.drawRect(rect, boxPaint)
                canvas.drawRect(rect, borderPaint)
                
                // Draw ID badge at top-left
                val text = id.toString()
                val textWidth = textPaint.measureText(text)
                val fontMetrics = textPaint.fontMetrics
                val textHeight = fontMetrics.descent - fontMetrics.ascent
                
                // Ensure badge fits within screen and provides padding
                val padding = 6
                val bgRect = Rect(
                    rect.left,
                    rect.top,
                    (rect.left + textWidth + padding * 2).toInt(),
                    (rect.top + textHeight + padding).toInt()
                )
                
                canvas.drawRect(bgRect, badgeBgPaint)
                
                // Draw numeric text centered inside the badge
                canvas.drawText(
                    text, 
                    rect.left + padding.toFloat(), 
                    rect.top - fontMetrics.ascent + (padding / 2f), 
                    textPaint
                )
            }
        }
    }
}
