package com.example.voiceagent

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class AgentBrain(
    private val apiKey: String,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "AgentBrain"
        private const val MAX_STEPS = 15
        private const val MODEL_NAME = "gemini-2.0-flash"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun executeGoal(userGoal: String) {
        CoroutineScope(Dispatchers.IO).launch {
            postStatus("شروع انجام: $userGoal")
            val service = VoiceAgentAccessibilityService.instance
            if (service == null) {
                postStatus("خطا: سرویس دسترسی فعال نیست!")
                return@launch
            }

            val stepHistory = mutableListOf<String>()

            for (step in 1..MAX_STEPS) {
                postStatus("مرحله $step: بررسی وضعیت صفحه...")

                // 1. Capture screen tree
                val screenDump = service.captureCurrentScreen()

                // 2. Query Gemini
                postStatus("مرحله $step: تحلیل هوش مصنوعی...")
                val decision = queryGemini(userGoal, screenDump.summaryJson, stepHistory)

                if (decision == null) {
                    postStatus("خطا در پاسخ هوش مصنوعی. توقف.")
                    break
                }

                val thought = decision.optString("thought", "")
                val action = decision.optString("action", "done").lowercase()

                Log.d(TAG, "Step $step Decision: thought=$thought, action=$action")
                postStatus("مرحله $step: $thought")

                stepHistory.add("مرحله $step: عمل $action - $thought")

                if (action == "done") {
                    postStatus("کار با موفقیت انجام شد! ✅")
                    break
                }

                // 3. Execute action
                when (action) {
                    "click" -> {
                        val targetId = decision.optInt("target_id", -1)
                        if (targetId >= 0) {
                            val success = service.clickNode(targetId)
                            Log.d(TAG, "Click node $targetId result: $success")
                        } else {
                            val x = decision.optDouble("x", -1.0).toFloat()
                            val y = decision.optDouble("y", -1.0).toFloat()
                            if (x >= 0 && y >= 0) {
                                service.clickCoordinates(x, y)
                            }
                        }
                    }
                    "type" -> {
                        val targetId = decision.optInt("target_id", -1)
                        val textToType = decision.optString("text", "")
                        if (targetId >= 0 && textToType.isNotEmpty()) {
                            service.typeText(targetId, textToType)
                        }
                    }
                    "launch_app" -> {
                        val appName = decision.optString("app_name", "")
                        if (appName.isNotEmpty()) {
                            service.launchAppByName(appName)
                        }
                    }
                    "back" -> service.performBack()
                    "home" -> service.performHome()
                    "recents" -> service.performRecents()
                    "scroll_down" -> service.scroll(true)
                    "scroll_up" -> service.scroll(false)
                    "screenshot" -> {
                        // Fallback to vision: take screenshot and ask Gemini with image
                        var screenshotBitmap: Bitmap? = null
                        val lock = Object()
                        synchronized(lock) {
                            service.captureScreenshot { bitmap ->
                                screenshotBitmap = bitmap
                                synchronized(lock) { lock.notify() }
                            }
                            try { lock.wait(3000) } catch (e: InterruptedException) { }
                        }
                        if (screenshotBitmap != null) {
                            val visionDecision = queryGeminiVision(userGoal, screenshotBitmap!!, stepHistory)
                            if (visionDecision != null) {
                                executeVisionAction(service, visionDecision)
                            }
                        }
                    }
                }

                // Brief pause for UI transition
                delay(1200)
            }
        }
    }

    private fun executeVisionAction(service: VoiceAgentAccessibilityService, decision: JSONObject) {
        val action = decision.optString("action", "")
        if (action == "click") {
            val x = decision.optDouble("x", -1.0).toFloat()
            val y = decision.optDouble("y", -1.0).toFloat()
            if (x >= 0 && y >= 0) {
                service.clickCoordinates(x, y)
            }
        }
    }

    private suspend fun queryGemini(
        goal: String,
        screenTreeJson: String,
        history: List<String>
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val systemInstruction = """
You are an autonomous smartphone assistant operating an Android device.
User Goal: "$goal"

You receive a list of current interactive UI elements on screen with their integer 'id', type, and text.
Decide the single next action to take to make progress towards the goal.

Return strictly a single JSON object with this schema:
{
  "thought": "Brief explanation in Persian or English of why you chose this action",
  "action": "click" | "type" | "launch_app" | "back" | "home" | "recents" | "scroll_down" | "scroll_up" | "screenshot" | "done",
  "target_id": integer (required for click and type if using tree),
  "text": "string" (required for type),
  "app_name": "string" (e.g. "sms", "whatsapp", "telegram", "music" for launch_app)
}

Rules:
1. If the required app is not open yet, use "action": "launch_app".
2. If you see the button you need, use "action": "click" with its "target_id".
3. If an input field needs text, use "action": "type" with its "target_id" and the exact "text".
4. If the screen has no readable text or is custom/canvas, use "action": "screenshot" to inspect visually.
5. If the goal is completed, output "action": "done".
            """.trimIndent()

            val historyText = if (history.isEmpty()) "None" else history.joinToString("\n")
            val userContent = """
Previous Steps Taken:
$historyText

Current Screen Elements:
$screenTreeJson

What is the next action?
            """.trimIndent()

            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "$systemInstruction\n\n$userContent"))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseString = response.body?.string() ?: return@withContext null

            val rootJson = JSONObject(responseString)
            val candidateText = rootJson
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            return@withContext JSONObject(candidateText.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API query error", e)
            return@withContext null
        }
    }

    private suspend fun queryGeminiVision(
        goal: String,
        bitmap: Bitmap,
        history: List<String>
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val prompt = """
You are an autonomous smartphone assistant looking at an Android screenshot.
Goal: "$goal"
Find where to click on screen to accomplish the goal.
Respond ONLY with JSON:
{
  "thought": "explanation",
  "action": "click",
  "x": coordinate_x_pixels,
  "y": coordinate_y_pixels
}
            """.trimIndent()

            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseString = response.body?.string() ?: return@withContext null

            val rootJson = JSONObject(responseString)
            val text = rootJson
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            return@withContext JSONObject(text.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Vision error", e)
            return@withContext null
        }
    }

    private fun postStatus(msg: String) {
        mainHandler.post { onStatusUpdate(msg) }
    }
}
