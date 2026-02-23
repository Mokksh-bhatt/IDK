package com.mobileclaw.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mobileclaw.agent.data.AgentAction
import com.mobileclaw.agent.data.ActionType
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11yService"
        var instance: AgentAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to events, we drive the interaction proactively
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "Accessibility Service destroyed")
    }

    /**
     * Execute a single agent action.
     */
    suspend fun executeAction(action: AgentAction, screenWidth: Int, screenHeight: Int): Boolean {
        return when (action.type) {
            ActionType.TAP -> {
                val x = action.x ?: return false
                val y = action.y ?: return false
                Log.d(TAG, "TAP at ($x, $y)")
                performTap(x.toFloat(), y.toFloat())
            }
            ActionType.LONG_PRESS -> {
                val x = action.x ?: return false
                val y = action.y ?: return false
                Log.d(TAG, "LONG_PRESS at ($x, $y)")
                performLongPress(x.toFloat(), y.toFloat())
            }
            ActionType.TYPE_TEXT -> {
                val text = action.text ?: return false
                Log.d(TAG, "TYPE_TEXT: $text")
                performTypeText(text)
            }
            ActionType.SCROLL -> {
                val direction = action.scrollDirection ?: "down"
                Log.d(TAG, "SCROLL $direction")
                performScroll(direction, screenWidth, screenHeight)
            }
            ActionType.SWIPE -> {
                val direction = action.scrollDirection ?: "up"
                Log.d(TAG, "SWIPE $direction")
                performSwipe(direction, screenWidth, screenHeight, action.swipeDuration)
            }
            ActionType.PRESS_BACK -> {
                Log.d(TAG, "PRESS_BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            ActionType.PRESS_HOME -> {
                Log.d(TAG, "PRESS_HOME")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            ActionType.PRESS_RECENTS -> {
                Log.d(TAG, "PRESS_RECENTS")
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            ActionType.OPEN_APP -> {
                val appName = action.text ?: return false
                Log.d(TAG, "OPEN_APP: $appName")
                openApp(appName)
            }
            ActionType.WAIT -> {
                Log.d(TAG, "WAIT")
                delay(1500)
                true
            }
            ActionType.TASK_COMPLETE, ActionType.TASK_FAILED -> {
                true // Handled by orchestrator
            }
        }
    }

    private suspend fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    private suspend fun performLongPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1000)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    private fun performTypeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            // Try to find any editable field
            findEditableNode(rootNode)
        } ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focusedNode.recycle()
        rootNode.recycle()
        return result
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private suspend fun performScroll(direction: String, screenWidth: Int, screenHeight: Int): Boolean {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val distance = screenHeight / 3f

        val (startX, startY, endX, endY) = when (direction) {
            "up" -> listOf(centerX, centerY + distance / 2, centerX, centerY - distance / 2)
            "down" -> listOf(centerX, centerY - distance / 2, centerX, centerY + distance / 2)
            "left" -> listOf(centerX + distance / 2, centerY, centerX - distance / 2, centerY)
            "right" -> listOf(centerX - distance / 2, centerY, centerX + distance / 2, centerY)
            else -> listOf(centerX, centerY + distance / 2, centerX, centerY - distance / 2)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    private suspend fun performSwipe(
        direction: String,
        screenWidth: Int,
        screenHeight: Int,
        duration: Long
    ): Boolean {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val distance = screenHeight / 2.5f

        val (startX, startY, endX, endY) = when (direction) {
            "up" -> listOf(centerX, centerY + distance, centerX, centerY - distance)
            "down" -> listOf(centerX, centerY - distance, centerX, centerY + distance)
            "left" -> listOf(centerX + distance, centerY, centerX - distance, centerY)
            "right" -> listOf(centerX - distance, centerY, centerX + distance, centerY)
            else -> listOf(centerX, centerY + distance, centerX, centerY - distance)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture)
    }

    private fun openApp(appName: String): Boolean {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(resolvePackageName(appName))
            ?: run {
                // Fallback: try to find via app label
                val packages = pm.getInstalledApplications(0)
                val matchingApp = packages.find {
                    pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
                }
                matchingApp?.let { pm.getLaunchIntentForPackage(it.packageName) }
            }

        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } else {
            Log.w(TAG, "Could not find app: $appName")
            false
        }
    }

    private fun resolvePackageName(appName: String): String {
        // Common app name -> package name mappings
        val known = mapOf(
            "uber" to "com.ubercab",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "subway" to "com.subway.mobile.subwayapp03",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "camera" to "com.android.camera",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "spotify" to "com.spotify.music",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "swiggy" to "in.swiggy.android",
            "zomato" to "com.application.zomato",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
        )
        return known[appName.lowercase()] ?: appName
    }

    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean {
        return suspendCoroutine { continuation ->
            val result = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled")
                    continuation.resume(false)
                }
            }, null)

            if (!result) {
                continuation.resume(false)
            }
        }
    }
}
