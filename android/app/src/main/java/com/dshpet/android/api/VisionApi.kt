package com.dshpet.android.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dshpet.android.prompt.PromptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视觉观察能力：拼好多模态 messages，真正发请求走 [DeepseekApi.complete]。
 */
class VisionApi(ctx: Context) {

    private val chat = ChatApi(ctx)

    suspend fun observe(images: List<ByteArray>): String? = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext null
        val req = chat.cfg(maxTokens = 200, temperature = 0.3)
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
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", prompt()))
            put(JSONObject().put("role", "user").put("content", content))
        }
        DeepseekApi.complete(req, messages).fold(
            onSuccess = { it.takeIf { t -> t.isNotBlank() } },
            onFailure = { e ->
                Log.w(TAG, "vision failed: ${e.message}")
                null
            },
        )
    }

    suspend fun observe(jpeg: ByteArray): String? = observe(listOf(jpeg))

    private fun prompt(): String = PromptStore.text("vision_observe")

    private companion object {
        const val TAG = "Dsh_VISION"
    }
}
