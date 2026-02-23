package com.mobileclaw.agent.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileclaw.agent.data.*
import com.mobileclaw.agent.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    agentState: AgentState,
    chatMessages: List<ChatMessage>,
    isAccessibilityEnabled: Boolean,
    isScreenCaptureActive: Boolean,
    onSendTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
    onClearChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    quickTasks: List<QuickTask>
) {
    var inputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Animated logo
                        PulsatingDot(isRunning = agentState.isRunning)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "MobileClaw",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when (agentState.status) {
                                    AgentStatus.IDLE -> "Ready"
                                    AgentStatus.THINKING -> "Thinking..."
                                    AgentStatus.ACTING -> "Acting..."
                                    AgentStatus.WAITING -> "Waiting..."
                                    AgentStatus.COMPLETED -> "Completed ✓"
                                    AgentStatus.FAILED -> "Failed ✗"
                                    AgentStatus.PAUSED -> "Paused"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (agentState.status) {
                                    AgentStatus.COMPLETED -> Success
                                    AgentStatus.FAILED -> Error
                                    AgentStatus.THINKING, AgentStatus.ACTING -> Secondary
                                    else -> TextSecondary
                                }
                            )
                        }
                    }
                },
                actions = {
                    if (agentState.isRunning) {
                        // Step counter
                        Text(
                            "${agentState.stepCount}/${agentState.maxSteps}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onClearChat) {
                        Icon(Icons.Outlined.DeleteSweep, "Clear", tint = TextSecondary)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, "Settings", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark
                )
            )
        },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Setup banner (show if services not ready)
            if (!isAccessibilityEnabled || !isScreenCaptureActive) {
                SetupBanner(
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isScreenCaptureActive = isScreenCaptureActive,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onRequestScreenCapture = onRequestScreenCapture
                )
            }

            // Chat messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (chatMessages.isEmpty()) {
                    item {
                        WelcomeCard(quickTasks = quickTasks, onSelectTask = { task ->
                            inputText = task
                        })
                    }
                }

                items(chatMessages, key = { it.id }) { message ->
                    ChatBubble(message)
                }
            }

            // Quick tasks row (only show when idle)
            if (!agentState.isRunning && chatMessages.isNotEmpty()) {
                QuickTasksRow(quickTasks) { task ->
                    onSendTask(task.prompt)
                }
            }

            // Input / Controls bar
            Surface(
                color = BackgroundCard,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 8.dp
            ) {
                if (agentState.isRunning) {
                    // Running controls
                    RunningControls(
                        agentState = agentState,
                        onStop = onStopTask,
                        onPause = onPauseTask,
                        onResume = onResumeTask
                    )
                } else {
                    // Text input
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "Tell me what to do...",
                                    color = TextMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Primary,
                                unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                                focusedContainerColor = BackgroundSurface,
                                unfocusedContainerColor = BackgroundSurface,
                                cursorColor = Primary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(20.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (inputText.isNotBlank()) {
                                        onSendTask(inputText.trim())
                                        inputText = ""
                                        focusManager.clearFocus()
                                    }
                                }
                            ),
                            maxLines = 3
                        )

                        Spacer(Modifier.width(12.dp))

                        // Send button
                        FilledIconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onSendTask(inputText.trim())
                                    inputText = ""
                                    focusManager.clearFocus()
                                }
                            },
                            modifier = Modifier.size(52.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Primary,
                                contentColor = TextPrimary
                            ),
                            enabled = inputText.isNotBlank() && isAccessibilityEnabled && isScreenCaptureActive
                        ) {
                            Icon(Icons.Filled.PlayArrow, "Start Task", modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsatingDot(isRunning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (isRunning) 0.3f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .size((16 * scale).dp)
            .clip(CircleShape)
            .background(
                if (isRunning) Secondary.copy(alpha = alpha)
                else Success.copy(alpha = 0.8f)
            )
    )
}

@Composable
private fun SetupBanner(
    isAccessibilityEnabled: Boolean,
    isScreenCaptureActive: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestScreenCapture: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Warning.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Warning, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Setup Required", fontWeight = FontWeight.SemiBold, color = Warning)
            }
            Spacer(Modifier.height(12.dp))

            if (!isAccessibilityEnabled) {
                SetupItem(
                    icon = Icons.Outlined.TouchApp,
                    label = "Enable Accessibility Service",
                    description = "Allows the agent to tap and interact with apps",
                    onClick = onOpenAccessibilitySettings
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!isScreenCaptureActive) {
                SetupItem(
                    icon = Icons.Outlined.ScreenshotMonitor,
                    label = "Enable Screen Capture",
                    description = "Allows the agent to see the screen",
                    onClick = onRequestScreenCapture
                )
            }
        }
    }
}

@Composable
private fun SetupItem(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(BackgroundSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
    }
}

@Composable
private fun WelcomeCard(quickTasks: List<QuickTask>, onSelectTask: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gradient title
        Text(
            "🤖",
            fontSize = 64.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "What can I do for you?",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "I can interact with any app on your phone.\nJust tell me what you need!",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        // Quick task suggestions
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickTasks.forEach { task ->
                QuickTaskCard(task) { onSelectTask(task.prompt) }
            }
        }
    }
}

@Composable
private fun QuickTaskCard(task: QuickTask, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = BackgroundCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, Primary.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(task.icon, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Filled.ArrowForward, null, tint = PrimaryLight, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .then(
                    if (message.isThinking) Modifier.border(
                        1.dp, Secondary.copy(alpha = 0.3f),
                        RoundedCornerShape(16.dp)
                    ) else Modifier
                ),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isUser -> UserBubble
                    isSystem -> SystemBubble
                    else -> AgentBubble
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser && !isSystem) {
                    Text(
                        "Agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecondaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSystem) TextSecondary else TextPrimary
                )
            }
        }
    }
}

@Composable
private fun QuickTasksRow(tasks: List<QuickTask>, onSelect: (QuickTask) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(tasks) { task ->
            AssistChip(
                onClick = { onSelect(task) },
                label = { Text(task.title, style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Text(task.icon, fontSize = 16.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = BackgroundSurface,
                    labelColor = TextSecondary
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = Primary.copy(alpha = 0.2f)
                )
            )
        }
    }
}

@Composable
private fun RunningControls(
    agentState: AgentState,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Current task name
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                agentState.currentTask,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Step ${agentState.stepCount} of ${agentState.maxSteps}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(Modifier.width(12.dp))

        // Pause/Resume
        FilledTonalIconButton(
            onClick = {
                if (agentState.status == AgentStatus.PAUSED) onResume() else onPause()
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = SecondaryContainer,
                contentColor = SecondaryLight
            )
        ) {
            Icon(
                if (agentState.status == AgentStatus.PAUSED) Icons.Filled.PlayArrow
                else Icons.Filled.Pause,
                contentDescription = "Pause/Resume"
            )
        }

        Spacer(Modifier.width(8.dp))

        // Stop
        FilledTonalIconButton(
            onClick = onStop,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Error.copy(alpha = 0.15f),
                contentColor = Error
            )
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop")
        }
    }
}
