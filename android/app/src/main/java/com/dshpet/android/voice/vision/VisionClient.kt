package com.dshpet.android.voice.vision

import android.util.Base64
import android.util.Log
import com.dshpet.android.chat.SseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 视觉观察（**Adapter**）：把一帧照片变成一句"客观观察"，再交给桌宠做反应。
 *
 * 实测（2026-10）：DeepSeek `deepseek-chat` 已支持 `image_url` 多模态输入，
 * 官方当前可用的模型名是 `deepseek-flash` / `deepseek-v4-pro`。
 *
 * 设计要点：
 *  - 这里**只做客观描述**，不带人设——人设留给 VoiceLoop，避免"观察"和"说话"混在一起；
 *  - 提示词禁止评价长相/年龄/性别/背景，只要"状态"；
 *  - 图片走 data URI 直接内联，不落盘、不上传到第三方存储。
 */
class VisionClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun observe(cfg: SseClient.ChatCfg, jpeg: ByteArray): String? =
        observe(cfg, listOf(jpeg))

    /**
     * 多张一起看（"看我一眼"现在是连拍 3 张）。
     *
     * 所有帧塞进同一个请求：模型能对比"刚才/现在"，比只给一张更容易抓住表情变化，
     * 也顺带抵消单张糊掉的风险（有一张清楚就够）。
     */
    suspend fun observe(cfg: SseClient.ChatCfg, images: List<ByteArray>): String? = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext null
        try {
            val content = JSONArray().apply {
                put(
                    JSONObject().put("type", "text").put(
                        "text",
                        "这是我现在的样子（连拍 ${images.size} 张，按时间顺序，最后一张最接近此刻），请观察我的状态。",
                    )
                )
                images.forEach { jpeg ->
                    val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                    )
                }
            }
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("max_tokens", 200)
                put("temperature", 0.3)
                put("stream", false)
                put(
                    "messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", PROMPT))
                        put(JSONObject().put("role", "user").put("content", content))
                    }
                )
            }

            val req = Request.Builder()
                .url(endpoint(cfg))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "vision http ${resp.code}: ${text.take(200)}")
                    return@withContext null
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vision failed: ${e.message}")
            null
        }
    }

    private fun endpoint(cfg: SseClient.ChatCfg): String {
        val base = cfg.baseUrl.trimEnd('/')
        val path = cfg.chatPath.ifBlank { "/v1/chat/completions" }
        return "$base$path"
    }

    private companion object {
        const val TAG = "DshVoiceVision"
        val PROMPT = """
            你是情绪观察员。看照片判断用户此刻的状态，只输出一到两句客观观察。
            可以说：是否疲惫（眼神/姿态）、是否在笑、是否皱眉、是否看着屏幕、精神好不好。
            禁止：评价长相、猜年龄性别、描述背景或穿着、做任何身份推断。
            格式示例：用户看起来有点疲惫，眼睛半睁，没在笑。
        """.trimIndent()
    }
}
