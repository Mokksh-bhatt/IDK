package com.mobileclaw.agent.data

import kotlinx.serialization.Serializable

/**
 * Represents a single action the AI agent wants to perform on the device.
 */
@Serializable
data class AgentAction(
    val type: ActionType,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val nodeId: Int? = null,
    val description: String = "",
    val scrollDirection: String? = null, // "up", "down", "left", "right"
    val swipeDuration: Long = 300L
)

@Serializable
enum class ActionType {
    TAP,
    TAP_NODE,
    TAP_NODE_ID,
    LONG_PRESS,
    TYPE_TEXT,
    SCROLL,
    SWIPE,
    PRESS_BACK,
    PRESS_HOME,
    PRESS_RECENTS,
    WAIT,
    OPEN_APP,
    TASK_COMPLETE,
    TASK_FAILED
}

/**
 * Represents the AI agent's reasoning + action response.
 */
@Serializable
data class AgentResponse(
    val thinking: String = "",
    val action: AgentAction,
    val confidence: Float = 0.0f
)

/**
 * A single message in the chat history between user and agent.
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isThinking: Boolean = false
)

enum class MessageRole {
    USER, AGENT, SYSTEM
}

/**
 * Overall state of the agent.
 */
data class AgentState(
    val isRunning: Boolean = false,
    val currentTask: String = "",
    val stepCount: Int = 0,
    val maxSteps: Int = 50,
    val lastThinking: String = "",
    val status: AgentStatus = AgentStatus.IDLE
)

enum class AgentStatus {
    IDLE,
    THINKING,
    ACTING,
    WAITING,
    COMPLETED,
    FAILED,
    PAUSED
}

/**
 * Pre-defined quick tasks the user can trigger with one tap.
 */
data class QuickTask(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val prompt: String
)
