package com.mobileclaw.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.graphics.Rect
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
            ActionType.TAP_NODE -> {
                val nodeText = action.text ?: return false
                Log.d(TAG, "TAP_NODE: $nodeText")
                performTapNode(nodeText)
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

    // ===== SEMANTIC UI TREE =====

    /**
     * Extracts structured info about all visible UI elements from the
     * live Android accessibility tree. Returns a compact text description
     * the AI can use to identify elements and their exact screen bounds.
     */
    fun getScreenUiTree(): String {
        val root = rootInActiveWindow ?: return "[No accessibility window available]"
        val sb = StringBuilder()
        sb.appendLine("=== Screen UI Elements ===")
        try {
            extractNodes(root, sb, depth = 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UI tree", e)
            sb.appendLine("[Error reading UI tree]")
        } finally {
            root.recycle()
        }
        return sb.toString()
    }

    private fun extractNodes(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 12) return // Prevent stack overflow on deep trees

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()?.take(60) ?: ""
        val desc = node.contentDescription?.toString()?.take(60) ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked

        // Only emit nodes that have useful info
        val hasLabel = text.isNotEmpty() || desc.isNotEmpty()
        val isInteractive = isClickable || isEditable || isCheckable

        if (hasLabel || isInteractive) {
            val label = when {
                text.isNotEmpty() && desc.isNotEmpty() -> "\"$text\" ($desc)"
                text.isNotEmpty() -> "\"$text\""
                desc.isNotEmpty() -> "($desc)"
                else -> ""
            }

            val flags = buildList {
                if (isClickable) add("clickable")
                if (isEditable) add("editable")
                if (isCheckable) add(if (isChecked) "checked" else "unchecked")
            }.joinToString(",")

            val indent = "  ".repeat(depth.coerceAtMost(6))
            sb.appendLine("${indent}[$className] $label ${if (flags.isNotEmpty()) "{$flags}" else ""} bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                extractNodes(child, sb, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * Find a node by its text or content description and click it.
     * Falls back to tapping the center of the node's bounds if
     * ACTION_CLICK doesn't work.
     */
    private suspend fun performTapNode(nodeText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val target = findNodeByText(root, nodeText)
            if (target != null) {
                // Try native click first (100% accurate)
                if (target.isClickable) {
                    val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    target.recycle()
                    if (clicked) return true
                }

                // Walk up the tree to find a clickable parent
                var parent = target.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        target.recycle()
                        if (clicked) return true
                    }
                    val grandparent = parent.parent
                    parent.recycle()
                    parent = grandparent
                }

                // Last resort: tap the center of the bounds
                val bounds = Rect()
                target.getBoundsInScreen(bounds)
                target.recycle()
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f
                Log.d(TAG, "TAP_NODE fallback to gesture at ($cx, $cy)")
                return performTap(cx, cy)
            }
            Log.w(TAG, "TAP_NODE: could not find node with text '$nodeText'")
            return false
        } finally {
            root.recycle()
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val targetLower = target.lowercase()
        // Try exact match first
        val exactMatches = root.findAccessibilityNodeInfosByText(target)
        if (!exactMatches.isNullOrEmpty()) {
            return exactMatches[0] // caller is responsible for recycling
        }
        // Manual recursive search for content description matches
        return findNodeRecursive(root, targetLower)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(target) || desc.contains(target)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, target)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
