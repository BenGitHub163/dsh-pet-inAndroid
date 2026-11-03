package com.dshpet.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * **Breeze Blue 接口**：域名 + 鉴权（`xi-api-key`）都关在这里。
 *
 * 现状：`voice/BreezeVoices` 里还留着一份 URL 与请求拼装，属于历史债；
 * 本类成为唯一出口后，`BreezeVoices` 只负责"名字缓存"，不再碰网络。
 */
object BreezeApi {

    private const val BASE = "https://api.breeze.blue"

    /** `GET /v1/voices` → (voiceId, name) */
    suspend fun listVoices(apiKey: String, language: String = "zh"): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$BASE/v1/voices?language_code=$language&page_size=100")
                .addHeader("xi-api-key", apiKey)
                .get()
                .build()
            ApiClient.read(30).newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val arr = JSONObject(body).optJSONArray("voices") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("voice_id")
                    if (id.isBlank()) null else id to o.optString("name").ifBlank { "未命名音色" }
                }
            }
        }

    /** `POST /v1/text-to-speech/{voiceId}/stream` 的地址（由 TTS 接口层使用） */
    fun ttsStreamUrl(voiceId: String, format: String = "pcm"): String =
        "$BASE/v1/text-to-speech/$voiceId/stream?output_format=$format"

    /** 鉴权头名（TTS 接口层复用） */
    const val HEADER_KEY = "xi-api-key"
}