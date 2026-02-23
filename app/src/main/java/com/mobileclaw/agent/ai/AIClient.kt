package com.mobileclaw.agent.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.mobileclaw.agent.data.AgentAction
import com.mobileclaw.agent.data.AgentResponse
import com.mobileclaw.agent.data.ActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Unified AI client that supports both:
 * - Gemini Direct API (Google AI Studio keys starting with "AIza...")
 * - OpenRouter API (keys starting with "sk-or-...")
 *
 * Auto-detects which provider to use based on the API key format.
 */
class AIClient(
    private var apiKey: String
) {
    companion object {
        private const val TAG = "AIClient"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    enum class Provider { GEMINI, OPENROUTER }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun updateApiKey(key: String) { apiKey = key }

    /**
     * Auto-detect provider from the API key format.
     */
    private fun detectProvider(): Provider {
        return when {
            apiKey.startsWith("sk-or-") -> Provider.OPENROUTER
            else -> Provider.GEMINI // Default to Gemini for Google AI keys
        }
    }

    private val systemPrompt = """
You are MobileClaw, an expert AI agent that controls an Android phone. You receive screenshots and must decide one action at a time.

## CRITICAL: THINK BEFORE ACTING
Before EVERY action, you MUST:
1. Identify EXACTLY which app/screen you are currently on (e.g., "I am on the Uber home screen", "I am on the Android home screen")
2. Determine if this is the CORRECT screen for the current step of the task
3. If you are on the WRONG screen or lost, use OPEN_APP or PRESS_HOME to recover — do NOT randomly tap

## Available Actions
- TAP: Tap at (x, y). You MUST be very precise. Aim for the exact CENTER of the button/element.
- LONG_PRESS: Long press at (x, y)
- TYPE_TEXT: Type text into the currently focused input. The field MUST already be focused (tap it first). Put the text in the "text" field.
- SCROLL: Scroll the screen. Use scrollDirection: "up", "down", "left", or "right"
- SWIPE: Swipe gesture. Use scrollDirection for direction.
- PRESS_BACK: Press Android back button. Use to dismiss popups, go back one screen.
- PRESS_HOME: Go to Android home screen. Use this to RESET if you are lost or on the wrong app.
- PRESS_RECENTS: Open recent apps overview
- OPEN_APP: Launch an app by name. Put the app name in "text" (e.g., "Uber", "WhatsApp", "Chrome"). ALWAYS prefer this over trying to find an app icon on the home screen.
- WAIT: Wait for a loading screen, animation, or transition to finish before acting.
- TASK_COMPLETE: The user's task is FULLY done. Only use when you can visually confirm success.
- TASK_FAILED: You cannot complete the task. Use this if you are stuck, going in circles, or encounter an error you cannot recover from.

## Response Format
Respond with ONLY a JSON object. No markdown, no code fences, no extra text.
{
  "thinking": "I see [describe screen]. I am on [app name]. To complete the task I need to [next step]. I will [action] because [reason].",
  "action": {
    "type": "TAP",
    "x": 540,
    "y": 960,
    "text": null,
    "description": "Tapping the search button",
    "scrollDirection": null
  },
  "confidence": 0.85
}

## STRICT Rules (violations waste money)
1. NEVER tap blindly. If you cannot clearly see a button or element, use SCROLL or WAIT first.
2. For opening apps, ALWAYS use OPEN_APP with the app name instead of hunting for icons on the home screen.
3. LOOP DETECTION: Look at your previous actions. If you see yourself repeating similar actions (e.g., tapping the same area, opening and closing the same app), immediately use TASK_FAILED with a description of why you're stuck.
4. After tapping, expect the screen to change. If the next screenshot looks identical, your tap likely missed. Try different coordinates or a different approach.
5. Coordinates are in PIXELS matching the screenshot dimensions. The screenshot may be scaled, so use coordinates relative to the image you see.
6. If a popup, dialog, or permission prompt appears, handle it first (accept/dismiss) before continuing the task.
7. If you are less than 50% confident in your action, use WAIT or re-examine the screen instead of guessing.
8. NEVER repeat a failed action more than once. If it failed, try a completely different approach.
9. Be CONCISE in your thinking — max 2 sentences.
10. When the task is done, verify visually (e.g., you see a confirmation screen) before using TASK_COMPLETE.
""".trimIndent()

    suspend fun getNextAction(
        screenshot: Bitmap,
        taskDescription: String,
        previousActions: List<String> = emptyList(),
        screenWidth: Int,
        screenHeight: Int
    ): Result<AgentResponse> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val base64Image = bitmapToBase64(screenshot)
            val provider = detectProvider()

            val historyContext = if (previousActions.isNotEmpty()) {
                "\n\nPrevious actions taken:\n" + previousActions.takeLast(5).joinToString("\n") { "- $it" }
            } else ""

            val userPromptText = """
Task: $taskDescription
Screen dimensions: ${screenWidth}x${screenHeight} pixels
$historyContext

Analyze the screenshot and decide your next action. Respond with ONLY a JSON object.
""".trimIndent()

            val (request, providerName) = when (provider) {
                Provider.GEMINI -> buildGeminiRequest(base64Image, userPromptText, "gemini-2.5-flash") to "Gemini"
                Provider.OPENROUTER -> buildOpenRouterRequest(base64Image, userPromptText, "google/gemini-2.5-flash") to "OpenRouter"
            }

            Log.d(TAG, "Using provider: $providerName")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response from $providerName")

            if (!response.isSuccessful) {
                Log.e(TAG, "API Error: $responseBody")
                throw Exception("$providerName error (${response.code}): ${extractErrorMessage(responseBody)}")
            }

            val agentResponse = when (provider) {
                Provider.GEMINI -> parseGeminiResponse(responseBody)
                Provider.OPENROUTER -> parseOpenRouterResponse(responseBody)
            }
            Result.success(agentResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting next action", e)
            Result.failure(e)
        }
    }

    // ===== GEMINI DIRECT API =====

    private fun buildGeminiRequest(base64Image: String, userPromptText: String, model: String): Request {
        val url = "$GEMINI_BASE_URL/$model:generateContent?key=$apiKey"

        val requestBody = buildJsonObject {
            put("system_instruction", buildJsonObject {
                putJsonArray("parts") {
                    addJsonObject { put("text", systemPrompt) }
                }
            })
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        }
                        addJsonObject {
                            put("text", userPromptText)
                        }
                    }
                }
            }
            put("generationConfig", buildJsonObject {
                put("temperature", 0.1)
                put("topP", 0.95)
                put("maxOutputTokens", 1024)
                put("responseMimeType", "application/json")
            })
        }

        return Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseGeminiResponse(responseBody: String): AgentResponse {
        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
        val candidates = jsonResponse["candidates"]?.jsonArray
            ?: throw Exception("No candidates in Gemini response")

        val content = candidates[0].jsonObject["content"]?.jsonObject
            ?: throw Exception("No content in candidate")

        val parts = content["parts"]?.jsonArray
            ?: throw Exception("No parts in content")

        val textPart = parts[0].jsonObject["text"]?.jsonPrimitive?.content
            ?: throw Exception("No text in part")

        val cleanJson = textPart
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        return try {
            json.decodeFromString<AgentResponse>(cleanJson)
        } catch (e: Exception) {
            Log.w(TAG, "Gemini parse failed, trying manual: ${e.message}")
            parseManually(cleanJson)
        }
    }

    // ===== OPENROUTER API =====

    private fun buildOpenRouterRequest(base64Image: String, userPromptText: String, model: String): Request {
        val requestBody = buildJsonObject {
            put("model", model)
            put("response_format", buildJsonObject {
                put("type", "json_object")
            })
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", userPromptText)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$base64Image")
                                put("detail", "auto")
                            })
                        }
                    }
                }
            }
            put("temperature", 0.1)
            put("max_tokens", 1024)
        }

        return Request.Builder()
            .url(OPENROUTER_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/MobileClaw")
            .addHeader("X-Title", "MobileClaw Agent")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseOpenRouterResponse(responseBody: String): AgentResponse {
        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
        val choices = jsonResponse["choices"]?.jsonArray
            ?: throw Exception("No choices in response")

        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw Exception("No message in choice")

        val textPart = message["content"]?.jsonPrimitive?.content
            ?: throw Exception("No content in message")

        val cleanJson = textPart
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        return try {
            json.decodeFromString<AgentResponse>(cleanJson)
        } catch (e: Exception) {
            Log.w(TAG, "OpenRouter parse failed, trying manual: ${e.message}")
            parseManually(cleanJson)
        }
    }

    // ===== SHARED UTILS =====

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 1024
        val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseManually(text: String): AgentResponse {
        val jsonElement = json.parseToJsonElement(text).jsonObject

        val thinking = jsonElement["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
        val confidence = jsonElement["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.5f

        val actionObj = jsonElement["action"]?.jsonObject
            ?: throw Exception("No action object found in response")

        val typeStr = actionObj["action"]?.jsonPrimitive?.content ?: actionObj["type"]?.jsonPrimitive?.content
            ?: throw Exception("No action type found")

        val actionType = try {
            ActionType.valueOf(typeStr)
        } catch (e: Exception) {
            ActionType.WAIT
        }

        val action = AgentAction(
            type = actionType,
            x = actionObj["x"]?.jsonPrimitive?.intOrNull,
            y = actionObj["y"]?.jsonPrimitive?.intOrNull,
            text = actionObj["text"]?.jsonPrimitive?.contentOrNull,
            description = actionObj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            scrollDirection = actionObj["scrollDirection"]?.jsonPrimitive?.contentOrNull,
        )

        return AgentResponse(
            thinking = thinking,
            action = action,
            confidence = confidence
        )
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val error = jsonObj["error"]?.jsonObject
            error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
        } catch (e: Exception) {
            body.take(200)
        }
    }
}
