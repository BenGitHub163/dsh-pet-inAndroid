package com.dshpet.android.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dshpet.android.prompt.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * **视觉观察接口**：`POST {chatBaseUrl}{chatPath}`（多模态，图片内联 data URI）。
 *
 * 重构要点：原来 `voice/vision/VisionClient` 一个类里同时做
 *   ① prompt 文案 ② 请求体拼装 ③ 发 HTTP ④ 解析响应，
 * 现在整条"请求 + 响应"链路都在这里；调用方只调 [observe] 拿一句话。
 * 提示词仍来自 assets/prompts/prompts.yaml（vision_observe）。
 */
class VisionApi(ctx: Context) {

    private val chat = ChatApi(ctx)

    suspend fun observe(images: List<ByteArray>): String? = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext null
        // 观察任务要稳定、不要发散：温度 0.3，maxTokens 200
        val cfg = chat.cfg(maxTokens = 200, temperature = 0.3)
        try {
            val content = JSONArray().apply {
                put(
                    JSONObject().put("type", "text").put(
                        "text",
                        "这是我现在的样子（连拍 ${images.size} 张，按时间顺序，最后一张最接近此刻），请观察我的状态。",
                    ),
                )
                images.forEach { jpeg ->
                    val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")),
                    )
                }
            }
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("max_tokens", 200)
                put("temperature", 0.3)
                put("stream", false)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", prompt()))
                        put(JSONObject().put("role", "user").put("content", content))
                    },
                )
            }
            val req = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + cfg.chatPath.ifBlank { "/v1/chat/completions" })
                .header("Content-Type", "application/json")
                .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            ApiClient.read(60).newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "vision http ${resp.code}: ${text.take(200)}")
                    return@withContext null
                }
                runCatching { JSONObject(text) }.getOrNull()
                    ?.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vision failed: ${e.message}")
            null
        }
    }

    suspend fun observe(jpeg: ByteArray): String? = observe(listOf(jpeg))

    private fun prompt(): String = PromptStore.text(
        "vision_observe",
        "你是情绪观察员。只输出一到两句客观观察，不评价长相、不猜身份。",
    )

    private companion object {
        const val TAG = "Dsh_VISION"
    }
}