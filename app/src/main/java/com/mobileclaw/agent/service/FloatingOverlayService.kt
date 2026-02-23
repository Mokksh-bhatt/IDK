package com.mobileclaw.agent.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A floating overlay that shows the agent's status while it's working in other apps.
 * - IDLE: Hidden or small dot
 * - THINKING: Pulsing neon blue circle with "🧠"
 * - ACTING: Flashing neon green with "⚡"
 * - FAILED/COMPLETE: Red/Green flash then auto-hide
 * Includes a draggable bubble + tap-to-expand with a STOP button.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlay"
        var instance: FloatingOverlayService? = null
            private set

        // Colors
        private const val COLOR_THINKING = 0xFF00BFFF.toInt()   // Neon Blue
        private const val COLOR_ACTING   = 0xFF00FF88.toInt()   // Neon Green
        private const val COLOR_FAILED   = 0xFFFF3366.toInt()   // Neon Red
        private const val COLOR_COMPLETE = 0xFF00FF00.toInt()   // Bright Green
        private const val COLOR_IDLE     = 0xFF6200EE.toInt()   // Purple
        private const val COLOR_BG_DARK  = 0xDD1A1A2E.toInt()   // Dark bg

        private const val BUBBLE_SIZE = 140
        private const val EXPANDED_WIDTH = 500
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false
    private var pulseAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var expandedParams: WindowManager.LayoutParams

    // Status
    private var currentStatusText = "Idle"
    private var currentEmoji = "🤖"
    private var currentColor = COLOR_IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createBubble()
        createExpandedPanel()
    }

    private fun createBubble() {
        bubbleParams = WindowManager.LayoutParams(
            BUBBLE_SIZE,
            BUBBLE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        val bubble = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_BG_DARK)
                setStroke(4, COLOR_IDLE)
            }
            background = bg
            setPadding(0, 0, 0, 0)

            val emojiLabel = TextView(this@FloatingOverlayService).apply {
                text = "🤖"
                textSize = 28f
                gravity = Gravity.CENTER
                tag = "emoji"
            }
            addView(emojiLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Dragging + tap to expand
            var initialX = 0; var initialY = 0
            var initialTouchX = 0f; var initialTouchY = 0f
            var isDragging = false

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            isDragging = true
                            bubbleParams.x = initialX + dx.toInt()
                            bubbleParams.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(v, bubbleParams)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            toggleExpanded()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        bubbleView = bubble

        try {
            windowManager?.addView(bubble, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }
    }

    private fun createExpandedPanel() {
        expandedParams = WindowManager.LayoutParams(
            EXPANDED_WIDTH,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300 + BUBBLE_SIZE + 10
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(COLOR_BG_DARK)
                setStroke(2, COLOR_IDLE)
            }
            background = bg
            setPadding(32, 24, 32, 24)

            // Status line
            val statusLine = TextView(this@FloatingOverlayService).apply {
                text = "🤖 MobileClaw — Idle"
                setTextColor(Color.WHITE)
                textSize = 14f
                tag = "status_text"
            }
            addView(statusLine)

            // Thinking line (what the AI is doing)
            val thinkingLine = TextView(this@FloatingOverlayService).apply {
                text = ""
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 12f
                maxLines = 2
                tag = "thinking_text"
                setPadding(0, 8, 0, 8)
            }
            addView(thinkingLine)

            // STOP button
            val stopBtn = TextView(this@FloatingOverlayService).apply {
                text = "⬛ STOP AGENT"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val stopBg = GradientDrawable().apply {
                    cornerRadius = 20f
                    setColor(COLOR_FAILED)
                }
                background = stopBg
                setPadding(24, 16, 24, 16)
                setOnClickListener {
                    // Broadcast stop to orchestrator
                    val stopIntent = Intent("com.mobileclaw.agent.STOP_AGENT")
                    sendBroadcast(stopIntent)
                    updateStatus("Stopped", "⏹️", COLOR_FAILED)
                }
            }
            addView(stopBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 })
        }

        expandedView = panel
        // Don't add to window yet — only when expanded
    }

    private fun toggleExpanded() {
        if (isExpanded) {
            try {
                expandedView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            isExpanded = false
        } else {
            try {
                expandedParams.x = bubbleParams.x
                expandedParams.y = bubbleParams.y + BUBBLE_SIZE + 10
                windowManager?.addView(expandedView, expandedParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show expanded panel", e)
            }
            isExpanded = true
        }
    }

    /**
     * Called by the Orchestrator to update the overlay status.
     */
    fun updateStatus(status: String, emoji: String, color: Int) {
        currentStatusText = status
        currentEmoji = emoji
        currentColor = color

        handler.post {
            // Update bubble
            (bubbleView as? FrameLayout)?.let { frame ->
                val bg = frame.background as? GradientDrawable
                bg?.setStroke(4, color)
                val emojiView = frame.findViewWithTag<TextView>("emoji")
                emojiView?.text = emoji
            }

            // Update expanded panel
            expandedView?.findViewWithTag<TextView>("status_text")?.text = "$emoji MobileClaw — $status"

            // Animate based on state
            pulseAnimator?.cancel()
            when (status) {
                "Thinking" -> startPulse()
                "Acting" -> flashColor(color)
            }
        }
    }

    fun updateThinking(text: String) {
        handler.post {
            expandedView?.findViewWithTag<TextView>("thinking_text")?.text = text
        }
    }

    private fun startPulse() {
        pulseAnimator = ObjectAnimator.ofFloat(bubbleView, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun flashColor(color: Int) {
        (bubbleView as? FrameLayout)?.let { frame ->
            val bg = frame.background as? GradientDrawable
            bg?.setStroke(6, color)
            handler.postDelayed({
                bg?.setStroke(4, currentColor)
            }, 500)
        }
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        try { bubbleView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { if (isExpanded) expandedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubbleView = null
        expandedView = null
        instance = null
        super.onDestroy()
    }
}
