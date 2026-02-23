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

class OpenRouterClient(
    private var apiKey: String,
    private var modelName: String = "openai/gpt-4o"
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    }

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
    fun updateModel(model: String) { modelName = model }

    private val systemPrompt = """
You are MobileClaw, an AI agent that controls an Android phone to complete tasks for the user.

You can see the phone's screen via a screenshot. Based on what you see, you must decide your next action.

## Available Actions
- TAP: Tap at coordinates (x, y) on screen
- LONG_PRESS: Long press at coordinates (x, y)
- TYPE_TEXT: Type text into the currently focused input field
- SCROLL: Scroll in a direction ("up", "down", "left", "right")
- SWIPE: Swipe gesture from center in a direction
- PRESS_BACK: Press the Android back button
- PRESS_HOME: Press the Android home button
- PRESS_RECENTS: Open recent apps
- WAIT: Wait before taking the next action (use when a page is loading)
- OPEN_APP: Open an app by name (provide the app name in "text" field)
- TASK_COMPLETE: The task is finished successfully
- TASK_FAILED: The task cannot be completed

## Response Format
You MUST respond with ONLY a valid JSON object matching the JSON schema. Do not enclose in markdown blocks. Example:
{
  "thinking": "Brief explanation of what you see and why you chose this action",
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

## Rules
1. Always analyze the screenshot carefully before deciding
2. Coordinates are in PIXELS relative to the screenshot dimensions
3. Be precise with tap coordinates - aim for the CENTER of buttons/links
4. If a page is loading, use WAIT action
5. If you're stuck or going in circles, use TASK_FAILED
6. When the task is done, use TASK_COMPLETE
7. For TYPE_TEXT, the input field must already be focused (tap it first)
8. Keep your thinking brief but clear
9. Never repeat the same failed action more than 2 times
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

            val historyContext = if (previousActions.isNotEmpty()) {
                "\n\nPrevious actions taken:\n" + previousActions.takeLast(5).joinToString("\n") { "- $it" }
            } else ""

            val userPromptText = """
Task: $taskDescription
Screen dimensions: ${screenWidth}x${screenHeight} pixels
$historyContext

Analyze the screenshot and decide your next action. Respond with ONLY a JSON object.
""".trimIndent()

            val requestBody = buildJsonObject {
                put("model", modelName)
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

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://github.com/MobileClaw") 
                .addHeader("X-Title", "MobileClaw Agent")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response from OpenRouter API")

            if (!response.isSuccessful) {
                Log.e(TAG, "API Error: $responseBody")
                throw Exception("API error (${response.code}): ${extractErrorMessage(responseBody)}")
            }

            val agentResponse = parseApiResponse(responseBody)
            Result.success(agentResponse)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting next action", e)
            Result.failure(e)
        }
    }

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

    private fun parseApiResponse(responseBody: String): AgentResponse {
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
            Log.w(TAG, "Failed to parse structured response, attempting manual parse: ${e.message}")
            parseManually(cleanJson)
        }
    }

    private fun parseManually(text: String): AgentResponse {
        val jsonElement = json.parseToJsonElement(text).jsonObject

        val thinking = jsonElement["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
        val confidence = jsonElement["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.5f

        val actionObj = jsonElement["action"]?.jsonObject
            ?: throw Exception("No action object found in response")

        val typeStr = actionObj["action"]?.jsonPrimitive?.content ?: actionObj["type"]?.jsonPrimitive?.content
            ?: throw Exception("No action type found in actionObj: $actionObj")

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
