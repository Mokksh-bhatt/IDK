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

    var boundingBoxManager: BoundingBoxOverlayManager? = null
    var edgeGlowManager: EdgeGlowOverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        boundingBoxManager = BoundingBoxOverlayManager(this)
        edgeGlowManager = EdgeGlowOverlayManager(this)
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
            ActionType.TAP_NODE_ID -> {
                val nodeId = action.nodeId ?: return false
                Log.d(TAG, "TAP_NODE_ID: $nodeId")
                performTapNodeId(nodeId)
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
            "amazon music" to "com.amazon.mp3",
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

    // ===== SEMANTIC UI TREE & OVERLAY =====

    private var nextNodeId = 1
    val interactiveNodes = mutableMapOf<Int, AccessibilityNodeInfo>()
    val interactiveNodeBounds = mutableMapOf<Int, Rect>()

    /**
     * Extracts structured info about all visible UI elements from the
     * live Android accessibility tree. Returns a compact text description.
     * Also populates [interactiveNodes] and [interactiveNodeBounds] for the BoundingBox overlay.
     */
    fun getScreenUiTree(): String {
        // Cleanup old nodes
        interactiveNodes.values.forEach { try { it.recycle() } catch (e: Exception) {} }
        interactiveNodes.clear()
        interactiveNodeBounds.clear()
        nextNodeId = 1

        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        try {
            extractNodes(root, sb, depth = 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting UI tree", e)
        } finally {
            root.recycle()
        }
        val result = sb.toString()
        return if (result.length > 3000) result.take(3000) + "\n[truncated]" else result
    }

    private fun extractNodes(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 12 || sb.length > 3000) return

        val text = node.text?.toString()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isLongClickable = node.isLongClickable
        val hasClickAction = node.actionList?.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) == true

        val hasLabel = text.isNotEmpty() || desc.isNotEmpty()
        val isInteractive = isClickable || isEditable || isLongClickable || hasClickAction

        // Even if an interactive node does NOT have a label, we want to extract it
        // so we can draw a numbered bounding box over it.
        if (isInteractive) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            // Filter out tiny or invisible nodes
            if (bounds.width() > 10 && bounds.height() > 10 && bounds.top >= 0) {
                val id = nextNodeId++
                interactiveNodes[id] = AccessibilityNodeInfo.obtain(node)
                interactiveNodeBounds[id] = bounds

                val label = if (text.isNotEmpty()) text else desc
                val flag = if (isEditable) "edit" else "btn"
                
                // Only put it in the text UI tree if it has a label, to save tokens.
                // Or we can include all so the AI knows what the IDs correspond to.
                val safeLabel = if (label.isNotEmpty()) "\"$label\"" else "\"unlabeled\""
                sb.appendLine("[$id] $flag $safeLabel [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
            }
        } // End of isInteractive check

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

    /**
     * Click a node by its cached integer ID assigned during getScreenUiTree().
     */
    private suspend fun performTapNodeId(nodeId: Int): Boolean {
        val target = interactiveNodes[nodeId]
        if (target == null) {
            Log.w(TAG, "TAP_NODE_ID: could not find node with id $nodeId")
            return false
        }

        try {
            // Try native click first
            if (target.isClickable) {
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }

            // Walk up the tree to find a clickable parent
            var parent = target.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    if (clicked) return true
                }
                val grandparent = parent.parent
                parent.recycle()
                parent = grandparent
            }

            // Last resort: tap the center of the stored bounds
            val bounds = interactiveNodeBounds[nodeId]
            if (bounds != null) {
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f
                Log.d(TAG, "TAP_NODE_ID fallback to gesture at ($cx, $cy)")
                return performTap(cx, cy)
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error tapping node ID: $nodeId", e)
            return false
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
