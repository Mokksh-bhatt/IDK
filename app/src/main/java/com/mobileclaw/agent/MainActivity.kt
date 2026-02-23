package com.mobileclaw.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mobileclaw.agent.ai.OpenRouterClient
import com.mobileclaw.agent.data.*
import com.mobileclaw.agent.service.*
import com.mobileclaw.agent.ui.screens.HomeScreen
import com.mobileclaw.agent.ui.screens.SettingsScreen
import com.mobileclaw.agent.ui.theme.MobileClawTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: com.mobileclaw.agent.data.PreferencesManager
    private lateinit var aiClient: OpenRouterClient
    private lateinit var orchestrator: AgentOrchestrator

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.setMediaProjectionResult(result.resultCode, result.data!!)
            val intent = Intent(this, ScreenCaptureService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "Screen capture enabled!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val quickTasks = listOf(
        QuickTask(
            id = "uber",
            title = "Book an Uber",
            description = "Open Uber and book a ride to your destination",
            icon = "🚗",
            prompt = "Open Uber and book a ride from my current location"
        ),
        QuickTask(
            id = "subway",
            title = "Order Subway",
            description = "Order your favorite sandwich from Subway",
            icon = "🥪",
            prompt = "Open the Subway app and order my usual sandwich for delivery"
        ),
        QuickTask(
            id = "whatsapp_reply",
            title = "Reply on WhatsApp",
            description = "Check and reply to new WhatsApp messages",
            icon = "💬",
            prompt = "Open WhatsApp and check for new messages, then reply to any unread ones"
        ),
        QuickTask(
            id = "maps",
            title = "Navigate Home",
            description = "Open Google Maps and start navigation home",
            icon = "🗺️",
            prompt = "Open Google Maps and start navigation to my home address"
        ),
        QuickTask(
            id = "music",
            title = "Play Music",
            description = "Open Spotify and play your liked songs",
            icon = "🎵",
            prompt = "Open Spotify and play my Liked Songs playlist on shuffle"
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferencesManager = PreferencesManager(this)
        aiClient = OpenRouterClient("") // API key set later from prefs
        orchestrator = AgentOrchestrator(aiClient)

        // Load saved settings
        lifecycleScope.launch {
            val savedKey = preferencesManager.apiKey.first()
            val savedModel = preferencesManager.modelName.first()
            val savedInterval = preferencesManager.captureInterval.first()
            aiClient.updateApiKey(savedKey)
            aiClient.updateModel(savedModel)
            orchestrator.setCaptureInterval(savedInterval.toLong())
        }

        setContent {
            MobileClawTheme {
                val navController = rememberNavController()

                // Observe state
                val agentState by orchestrator.state.collectAsState()
                val chatMessages by orchestrator.chatMessages.collectAsState()

                // Settings state
                var apiKey by remember { mutableStateOf("") }
                var modelName by remember { mutableStateOf("openai/gpt-4o") }
                var maxSteps by remember { mutableIntStateOf(50) }
                var captureInterval by remember { mutableIntStateOf(2000) }

                // Service states
                var isAccessibilityEnabled by remember { mutableStateOf(false) }
                var isScreenCaptureActive by remember { mutableStateOf(false) }

                // Load prefs into compose state
                LaunchedEffect(Unit) {
                    preferencesManager.apiKey.collect { apiKey = it }
                }
                LaunchedEffect(Unit) {
                    preferencesManager.modelName.collect { modelName = it }
                }
                LaunchedEffect(Unit) {
                    preferencesManager.maxSteps.collect { maxSteps = it }
                }
                LaunchedEffect(Unit) {
                    preferencesManager.captureInterval.collect { captureInterval = it }
                }

                // Check service states periodically
                LaunchedEffect(agentState) {
                    isAccessibilityEnabled = AgentAccessibilityService.isRunning()
                    isScreenCaptureActive = ScreenCaptureService.isRunning()
                }

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        // Re-check when returning to home
                        LaunchedEffect(Unit) {
                            isAccessibilityEnabled = AgentAccessibilityService.isRunning()
                            isScreenCaptureActive = ScreenCaptureService.isRunning()
                        }

                        HomeScreen(
                            agentState = agentState,
                            chatMessages = chatMessages,
                            isAccessibilityEnabled = isAccessibilityEnabled,
                            isScreenCaptureActive = isScreenCaptureActive,
                            onSendTask = { task ->
                                orchestrator.startTask(task)
                            },
                            onStopTask = { orchestrator.stopTask() },
                            onPauseTask = { orchestrator.pauseTask() },
                            onResumeTask = { orchestrator.resumeTask() },
                            onClearChat = { orchestrator.clearChat() },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onRequestScreenCapture = { requestScreenCapture() },
                            onOpenAccessibilitySettings = { openAccessibilitySettings() },
                            quickTasks = quickTasks
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            apiKey = apiKey,
                            modelName = modelName,
                            maxSteps = maxSteps,
                            captureInterval = captureInterval,
                            onApiKeyChange = { key ->
                                lifecycleScope.launch {
                                    preferencesManager.setApiKey(key)
                                    aiClient.updateApiKey(key)
                                }
                            },
                            onModelNameChange = { model ->
                                lifecycleScope.launch {
                                    preferencesManager.setModelName(model)
                                    aiClient.updateModel(model)
                                }
                            },
                            onMaxStepsChange = { steps ->
                                lifecycleScope.launch {
                                    preferencesManager.setMaxSteps(steps)
                                }
                            },
                            onCaptureIntervalChange = { ms ->
                                lifecycleScope.launch {
                                    preferencesManager.setCaptureInterval(ms)
                                    orchestrator.setCaptureInterval(ms.toLong())
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Toast.makeText(
            this,
            "Find \"MobileClaw\" in the list and enable it",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        orchestrator.destroy()
        super.onDestroy()
    }
}
