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
    apiKeyRaw: String
) {
    private var apiKey: String = apiKeyRaw.trim()

    companion object {
        private const val TAG = "AIClient"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    }

    enum class Provider { GEMINI, OPENROUTER, OPENAI }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun updateApiKey(key: String) { apiKey = key.trim() }

    /**
     * Auto-detect provider from the API key format.
     */
    private fun detectProvider(): Provider {
        return when {
            apiKey.startsWith("sk-or-") -> Provider.OPENROUTER
            apiKey.startsWith("sk-proj-") || (apiKey.startsWith("sk-") && !apiKey.startsWith("sk-or-")) -> Provider.OPENAI
            else -> Provider.GEMINI // Default to Gemini for Google AI keys
        }
    }

    private val systemPrompt = """
You are MobileClaw, an expert AI agent that controls an Android phone. You receive a screenshot AND a structured accessibility tree listing every UI element on screen with its label, type, and pixel bounds.

## CRITICAL: THINK BEFORE ACTING
Before EVERY action, you MUST:
1. Identify EXACTLY which app/screen you are currently on from the UI tree and screenshot
2. Determine if this is the CORRECT screen for the current step of the task
3. If you are on the WRONG screen or lost, use OPEN_APP or PRESS_HOME to recover

## Available Actions
- TAP_NODE: **PREFERRED** — Click a UI element by its text label. Put the label text in "text". This uses Android's native click and is 100% accurate. ALWAYS use this when you can see the element in the UI tree.
- TAP: Tap at (x, y) pixel coordinates. Only use as a LAST RESORT when TAP_NODE cannot identify the element.
- LONG_PRESS: Long press at (x, y)
- TYPE_TEXT: Type text into the currently focused input. The field MUST already be focused (tap it first). Put the text in the "text" field.
- SCROLL: Scroll the screen. Use scrollDirection: "up", "down", "left", or "right"
- SWIPE: Swipe gesture. Use scrollDirection for direction.
- PRESS_BACK: Press Android back button. Use to dismiss popups, go back one screen.
- PRESS_HOME: Go to Android home screen. Use to RESET if lost.
- PRESS_RECENTS: Open recent apps overview
- OPEN_APP: Launch an app by name. Put the app name in "text". ALWAYS prefer this over finding icons.
- WAIT: Wait for loading/transitions to finish.
- TASK_COMPLETE: Task is FULLY done. Verify visually first.
- TASK_FAILED: Cannot complete. Use if stuck or going in circles.

## Response Format
Respond with ONLY a JSON object. No markdown, no code fences.
{
  "thinking": "I see [screen]. The UI tree shows [element]. I will TAP_NODE on [label].",
  "action": {
    "type": "TAP_NODE",
    "x": null,
    "y": null,
    "text": "Search",
    "description": "Tapping the Search button",
    "scrollDirection": null
  },
  "confidence": 0.95
}

## STRICT Rules
1. ALWAYS prefer TAP_NODE over TAP. Only use TAP if the element has no text label in the UI tree.
2. For opening apps, ALWAYS use OPEN_APP.
3. LOOP DETECTION: If repeating similar actions, immediately TASK_FAILED.
4. If a popup/dialog appears, handle it first.
5. If confidence < 50%, use WAIT or re-examine.
6. NEVER repeat a failed action. Try a different approach.
7. Be CONCISE — max 2 sentences in thinking.
8. Verify success visually before TASK_COMPLETE.
""".trimIndent()

    suspend fun getNextAction(
        screenshot: Bitmap,
        taskDescription: String,
        previousActions: List<String> = emptyList(),
        screenWidth: Int,
        screenHeight: Int,
        uiTree: String = ""
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

            val uiTreeSection = if (uiTree.isNotBlank()) {
                "\n\nUI Accessibility Tree (use this to identify elements):\n$uiTree"
            } else ""

            val userPromptText = """
Task: $taskDescription
Screen dimensions: ${screenWidth}x${screenHeight} pixels
$historyContext
$uiTreeSection

Analyze the screenshot AND the UI tree. Use TAP_NODE with the element's text label when possible. Respond with ONLY a JSON object.
""".trimIndent()

            val (request, providerName) = when (provider) {
                Provider.GEMINI -> buildGeminiRequest(base64Image, userPromptText, "gemini-2.5-flash") to "Gemini"
                Provider.OPENROUTER -> buildOpenAiCompatibleRequest(base64Image, userPromptText, "google/gemini-2.5-flash", OPENROUTER_URL) to "OpenRouter"
                Provider.OPENAI -> buildOpenAiCompatibleRequest(base64Image, userPromptText, "gpt-4o", OPENAI_URL) to "OpenAI"
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
                Provider.OPENROUTER, Provider.OPENAI -> parseOpenAiCompatibleResponse(responseBody, providerName)
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

    // ===== OPENAI / OPENROUTER COMPATIBLE API =====

    private fun buildOpenAiCompatibleRequest(base64Image: String, userPromptText: String, model: String, url: String): Request {
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
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/MobileClaw")
            .addHeader("X-Title", "MobileClaw Agent")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseOpenAiCompatibleResponse(responseBody: String, providerName: String): AgentResponse {
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
            Log.w(TAG, "$providerName parse failed, trying manual: ${e.message}")
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
