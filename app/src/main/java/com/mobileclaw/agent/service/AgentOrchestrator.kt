package com.mobileclaw.agent.service

import android.util.Log
import com.mobileclaw.agent.ai.OpenRouterClient
import com.mobileclaw.agent.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The brain of the agent.
 * Runs the Observe -> Think -> Act loop.
 */
class AgentOrchestrator(
    private val aiClient: OpenRouterClient
) {
    companion object {
        private const val TAG = "AgentOrchestrator"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val previousActions = mutableListOf<String>()
    private var agentJob: Job? = null
    private var captureIntervalMs: Long = 2000L

    fun setCaptureInterval(ms: Long) { captureIntervalMs = ms }

    /**
     * Start the agent loop for a given task.
     */
    fun startTask(taskDescription: String) {
        if (_state.value.isRunning) {
            Log.w(TAG, "Agent already running")
            return
        }

        previousActions.clear()

        addMessage(ChatMessage(
            role = MessageRole.USER,
            content = taskDescription
        ))

        addMessage(ChatMessage(
            role = MessageRole.SYSTEM,
            content = "🤖 Starting task: $taskDescription"
        ))

        _state.value = AgentState(
            isRunning = true,
            currentTask = taskDescription,
            stepCount = 0,
            status = AgentStatus.THINKING
        )

        agentJob = scope.launch {
            runAgentLoop(taskDescription)
        }
    }

    /**
     * Stop the current task.
     */
    fun stopTask() {
        agentJob?.cancel()
        agentJob = null
        _state.value = _state.value.copy(
            isRunning = false,
            status = AgentStatus.IDLE
        )
        addMessage(ChatMessage(
            role = MessageRole.SYSTEM,
            content = "⏹️ Agent stopped by user"
        ))
    }

    /**
     * Pause the agent.
     */
    fun pauseTask() {
        _state.value = _state.value.copy(status = AgentStatus.PAUSED)
        addMessage(ChatMessage(
            role = MessageRole.SYSTEM,
            content = "⏸️ Agent paused"
        ))
    }

    /**
     * Resume the agent.
     */
    fun resumeTask() {
        if (_state.value.status == AgentStatus.PAUSED) {
            _state.value = _state.value.copy(status = AgentStatus.THINKING)
            addMessage(ChatMessage(
                role = MessageRole.SYSTEM,
                content = "▶️ Agent resumed"
            ))
        }
    }

    private suspend fun runAgentLoop(taskDescription: String) {
        val maxSteps = _state.value.maxSteps

        for (step in 1..maxSteps) {
            // Check if paused
            while (_state.value.status == AgentStatus.PAUSED) {
                delay(500)
            }

            // Check if still running
            if (!_state.value.isRunning) break

            _state.value = _state.value.copy(
                stepCount = step,
                status = AgentStatus.THINKING
            )

            // == OBSERVE: Capture screen ==
            val captureService = ScreenCaptureService.instance
            if (captureService == null) {
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "❌ Screen capture not available. Please grant screen recording permission."
                ))
                _state.value = _state.value.copy(isRunning = false, status = AgentStatus.FAILED)
                return
            }

            // Small delay before capture to let any animation settle
            delay(captureIntervalMs)

            val screenshot = captureService.captureScreen()
            if (screenshot == null) {
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "⚠️ Failed to capture screen, retrying..."
                ))
                delay(1000)
                continue
            }

            val screenWidth = captureService.screenWidth
            val screenHeight = captureService.screenHeight

            // == THINK: Send to AI ==
            addMessage(ChatMessage(
                role = MessageRole.AGENT,
                content = "🧠 Analyzing screen... (step $step/$maxSteps)",
                isThinking = true
            ))

            val result = aiClient.getNextAction(
                screenshot = screenshot,
                taskDescription = taskDescription,
                previousActions = previousActions,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )

            screenshot.recycle()

            if (result.isFailure) {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "❌ AI Error: $error"
                ))
                _state.value = _state.value.copy(isRunning = false, status = AgentStatus.FAILED)
                return
            }

            val response = result.getOrThrow()
            _state.value = _state.value.copy(lastThinking = response.thinking)

            // Remove the "thinking" message and replace with actual response
            removeLastThinkingMessage()

            addMessage(ChatMessage(
                role = MessageRole.AGENT,
                content = "💭 ${response.thinking}\n\n🎯 Action: ${response.action.type} ${response.action.description}"
            ))

            // == CHECK TERMINAL CONDITIONS ==
            if (response.action.type == ActionType.TASK_COMPLETE) {
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "✅ Task completed successfully!"
                ))
                _state.value = _state.value.copy(isRunning = false, status = AgentStatus.COMPLETED)
                return
            }

            if (response.action.type == ActionType.TASK_FAILED) {
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "❌ Task failed: ${response.action.description}"
                ))
                _state.value = _state.value.copy(isRunning = false, status = AgentStatus.FAILED)
                return
            }

            // == ACT: Execute the action ==
            _state.value = _state.value.copy(status = AgentStatus.ACTING)

            val accessibilityService = AgentAccessibilityService.instance
            if (accessibilityService == null) {
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "❌ Accessibility Service not enabled. Please enable it in Settings."
                ))
                _state.value = _state.value.copy(isRunning = false, status = AgentStatus.FAILED)
                return
            }

            val actionSuccess = accessibilityService.executeAction(
                response.action, screenWidth, screenHeight
            )

            val actionDescription = "${response.action.type}: ${response.action.description}"
            previousActions.add(
                if (actionSuccess) "✓ $actionDescription"
                else "✗ $actionDescription (failed)"
            )

            if (!actionSuccess) {
                addMessage(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = "⚠️ Action failed: ${response.action.description}"
                ))
            }
        }

        // Max steps reached
        addMessage(ChatMessage(
            role = MessageRole.SYSTEM,
            content = "⚠️ Maximum steps ($maxSteps) reached. Task stopped."
        ))
        _state.value = _state.value.copy(isRunning = false, status = AgentStatus.FAILED)
    }

    private fun addMessage(message: ChatMessage) {
        _chatMessages.value = _chatMessages.value + message
    }

    private fun removeLastThinkingMessage() {
        _chatMessages.value = _chatMessages.value.toMutableList().apply {
            val idx = indexOfLast { it.isThinking }
            if (idx >= 0) removeAt(idx)
        }
    }

    fun clearChat() {
        _chatMessages.value = emptyList()
        previousActions.clear()
    }

    fun destroy() {
        stopTask()
        scope.cancel()
    }
}
