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
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1alpha/models"
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
You are MobileClaw, an AI agent controlling an Android phone. You receive:
1. A SCREENSHOT with YELLOW NUMBERED BOXES over interactive elements
2. A UI TREE listing each box ID + text label + bounds

ACTIONS:
- TAP_NODE_ID: Tap box by ID. {"type":"TAP_NODE_ID","nodeId":5,"description":"tap search"}
- TYPE_TEXT: Type text. {"type":"TYPE_TEXT","text":"Mom","description":"type contact name"}
- SCROLL: {"type":"SCROLL","scrollDirection":"down","description":"scroll to see more"}
- OPEN_APP: {"type":"OPEN_APP","text":"WhatsApp","description":"launch app"}
- PRESS_BACK, PRESS_HOME, WAIT, TASK_COMPLETE, TASK_FAILED

STRATEGY - take the SHORTEST path:
1. OPEN_APP if the target app is not on screen.
2. If the contact/item is ALREADY VISIBLE in the list, TAP it directly. Do NOT search if it's already on screen.
3. If it's NOT visible, tap the search icon, TYPE_TEXT the name, then tap the result.
4. SCROLL only if search is unavailable.
5. WAIT if the screen is loading.

CRITICAL BEHAVIOR RULES:
- Read the UI TREE text labels FIRST. Match labels to the task before looking at the screenshot.
- ALWAYS use TAP_NODE_ID. Never guess x,y coordinates.
- MESSAGING: When told to "message" someone, open their chat and type the specified message. If no specific message is mentioned, type "Hi". If there's an existing conversation, just type in the text field at the bottom.
- CALLS: When a call is active (ringing or connected), do NOT tap the end/red button. Just report TASK_COMPLETE. The user asked to MAKE the call, not end it.
- Do NOT navigate to call tabs, profiles, or settings unless explicitly asked. Go directly to the chat or dial.
- If an action fails, try a DIFFERENT approach. Never repeat the same failed action.
- 1 sentence thinking max.

JSON only: {"action":{"type":"...","nodeId":5,"text":"...","scrollDirection":"...","description":"..."},"thinking":"...","confidence":0.9}
""".trimIndent()

    suspend fun getNextAction(
        screenshot: Bitmap,
        taskDescription: String,
        previousActions: List<String> = emptyList(),
        screenWidth: Int,
        screenHeight: Int,
        uiTree: String = "",
        memoryContext: String = ""
    ): Result<AgentResponse> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val base64Image = bitmapToBase64(screenshot)
            val provider = detectProvider()

            val historyContext = if (previousActions.isNotEmpty()) {
                "\n\nPrevious actions (do NOT repeat failed ones):\n" + previousActions.takeLast(8).joinToString("\n") { "- $it" }
            } else ""

            val uiTreeSection = if (uiTree.isNotBlank()) "\n\nUI TREE (ID -> label -> bounds):\n$uiTree" else ""
            val memorySection = if (memoryContext.isNotBlank()) "\n\nUSER MEMORY (context for this task):\n$memoryContext" else ""

            val userPromptText = "Task: $taskDescription\nScreen: ${screenWidth}x${screenHeight}$historyContext$uiTreeSection$memorySection\nRespond with JSON only."

            val modelsToTry = when (provider) {
                // gemini-2.0-flash-exp was deprecated and throws 404 on v1beta. Switched to v1alpha to support stable 2.0/2.5 flash.
                Provider.GEMINI -> listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash")
                Provider.OPENROUTER -> listOf("google/gemini-2.0-flash-001", "google/gemini-2.0-flash-lite-preview-02-05", "google/gemini-1.5-pro", "openai/gpt-4o-mini")
                Provider.OPENAI -> listOf("gpt-4o-mini", "gpt-4o")
            }

            var successfulResponse: AgentResponse? = null
            val fallbackErrors = mutableListOf<String>()

            for (model in modelsToTry) {
                try {
                    val (request, providerName) = when (provider) {
                        Provider.GEMINI -> buildGeminiRequest(base64Image, userPromptText, model) to "Gemini"
                        Provider.OPENROUTER -> buildOpenAiCompatibleRequest(base64Image, userPromptText, model, OPENROUTER_URL) to "OpenRouter"
                        Provider.OPENAI -> buildOpenAiCompatibleRequest(base64Image, userPromptText, model, OPENAI_URL) to "OpenAI"
                    }

                    Log.d(TAG, "Trying model: $model via $providerName")

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: throw Exception("Empty response from $providerName")

                    if (!response.isSuccessful) {
                        Log.w(TAG, "Model $model failed (${response.code}): $responseBody")
                        throw Exception("$providerName error (${response.code}): ${extractErrorMessage(responseBody)}")
                    }

                    val agentResponse = when (provider) {
                        Provider.GEMINI -> parseGeminiResponse(responseBody)
                        Provider.OPENROUTER, Provider.OPENAI -> parseOpenAiCompatibleResponse(responseBody, providerName)
                    }
                    
                    // IF we reach here without exceptions thrown by parsing, we found a working model!
                    successfulResponse = agentResponse
                    break 

                } catch (e: Exception) {
                    Log.w(TAG, "Fallback triggered for $model: ${e.message}")
                    fallbackErrors.add("[$model] ${e.message}")
                }
            }

            if (successfulResponse != null) {
                return@withContext Result.success(successfulResponse!!)
            } else {
                return@withContext Result.failure(Exception("All fallback models failed:\n" + fallbackErrors.joinToString("\n")))
            }

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
                put("temperature", 0.4)
                put("topP", 0.95)
                put("maxOutputTokens", 500)
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
                                put("detail", "low")
                            })
                        }
                    }
                }
            }
            put("temperature", 0.2)
            put("max_tokens", 300)
        }

        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            // Removing HTTP-Referer entirely because OpenRouter / OpenAI aggressively flags
            // keys as "leaked" if they originate from github.com URLs.
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
        // 1280px balances readability vs token cost (~3x cheaper than 1920)
        val maxDim = 1280
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
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseManually(text: String): AgentResponse {
        try {
            val jsonElement = json.parseToJsonElement(text).jsonObject

            val thinking = jsonElement["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
            val confidence = jsonElement["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.5f

            val actionObj = jsonElement["action"]?.jsonObject ?: throw Exception("No action object")

            val actionTypeStr = actionObj["type"]?.jsonPrimitive?.contentOrNull ?: "WAIT"
            val actionType = try {
                ActionType.valueOf(actionTypeStr)
            } catch (e: Exception) {
                ActionType.WAIT
            }

            val action = AgentAction(
                type = actionType,
                x = actionObj["x"]?.jsonPrimitive?.intOrNull,
                y = actionObj["y"]?.jsonPrimitive?.intOrNull,
                text = actionObj["text"]?.jsonPrimitive?.contentOrNull,
                nodeId = actionObj["nodeId"]?.jsonPrimitive?.intOrNull,
                description = actionObj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                scrollDirection = actionObj["scrollDirection"]?.jsonPrimitive?.contentOrNull,
            )

            return AgentResponse(thinking, action, confidence)
        } catch (e: Exception) {
            Log.e(TAG, "Manual JSON parse also failed, attempting regex fallback: ${e.message}")
            
            // Regex fallback for completely broken/truncated JSON
            val typeStr = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "WAIT"
            val actionType = try { ActionType.valueOf(typeStr) } catch(ex: Exception) { ActionType.WAIT }
            val description = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "Fallback extracted action"
            val textVal = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val nodeId = Regex("\"nodeId\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val scrollDir = Regex("\"scrollDirection\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val thinking = Regex("\"thinking\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "Extracted via regex due to malformed JSON"
            
            val action = AgentAction(
                type = actionType,
                text = textVal,
                nodeId = nodeId,
                scrollDirection = scrollDir,
                description = description
            )
            
            return AgentResponse(thinking, action, 0.5f)
        }
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
