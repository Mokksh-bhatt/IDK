package com.mobileclaw.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mobileclaw.agent.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiKey: String,
    maxSteps: Int,
    captureInterval: Int,
    onApiKeyChange: (String) -> Unit,
    onMaxStepsChange: (Int) -> Unit,
    onCaptureIntervalChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    var localApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    var localMaxSteps by remember(maxSteps) { mutableStateOf(maxSteps.toString()) }
    var localInterval by remember(captureInterval) { mutableStateOf(captureInterval.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AI Configuration Section
            SettingsSection(title = "AI Configuration", icon = Icons.Outlined.SmartToy) {
                // API Key
                OutlinedTextField(
                    value = localApiKey,
                    onValueChange = {
                        localApiKey = it
                        onApiKeyChange(it)
                    },
                    label = { Text("API Key (Gemini, OpenAI, or OpenRouter)") },
                    placeholder = { Text("Paste your API key here", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                "Toggle visibility",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                        focusedContainerColor = BackgroundSurface,
                        unfocusedContainerColor = BackgroundSurface,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = PrimaryLight,
                        unfocusedLabelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    "MobileClaw auto-detects your provider \n(AIza... keys = Gemini 2.5 Flash, sk-proj-... = GPT-4o, sk-or-... keys = OpenRouter)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Secondary
                )
            }

            // Agent Behavior Section
            SettingsSection(title = "Agent Behavior", icon = Icons.Outlined.Tune) {
                OutlinedTextField(
                    value = localMaxSteps,
                    onValueChange = {
                        localMaxSteps = it
                        it.toIntOrNull()?.let { steps -> onMaxStepsChange(steps) }
                    },
                    label = { Text("Max Steps Per Task") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                        focusedContainerColor = BackgroundSurface,
                        unfocusedContainerColor = BackgroundSurface,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = PrimaryLight,
                        unfocusedLabelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("How many steps the agent can take before stopping", color = TextMuted) }
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = localInterval,
                    onValueChange = {
                        localInterval = it
                        it.toIntOrNull()?.let { ms -> onCaptureIntervalChange(ms) }
                    },
                    label = { Text("Capture Interval (ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                        focusedContainerColor = BackgroundSurface,
                        unfocusedContainerColor = BackgroundSurface,
                        cursorColor = Primary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = PrimaryLight,
                        unfocusedLabelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("Time between screen captures (lower = faster but uses more API calls)", color = TextMuted) }
                )
            }

            // About Section
            SettingsSection(title = "About", icon = Icons.Outlined.Info) {
                Text(
                    "MobileClaw v1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "An AI agent that can control your Android phone to automate complex tasks. Powered by OpenRouter VLM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BackgroundCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = PrimaryLight, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}
