package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Breeze 厂家接口：音色列表 + TTS 报文。合成 HTTP 由 [TtsApi] 发出。
 */
object BreezeApi : TtsCodec {

    private const val BASE = "https://api.breeze.blue"
    const val HEADER_KEY = "xi-api-key"

    override val id: String = "breeze"

    suspend fun listVoices(language: String = "zh"): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val apiKey = com.dshpet.android.data.KeyFactory.current().breeze
            if (apiKey.isBlank()) error("未配置 Breeze Key")
            val req = Request.Builder()
                .url("$BASE/v1/voices?language_code=$language&page_size=100")
                .addHeader(HEADER_KEY, apiKey)
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

    fun ttsStreamUrl(voiceId: String, format: String = "pcm"): String =
        "$BASE/v1/text-to-speech/$voiceId/stream?output_format=$format"

    override fun request(text: String, voice: VoiceProfile, cfg: TtsConfig): TtsHttpSpec {
        val voiceId = voice.voiceId.ifBlank { throw com.dshpet.android.voice.TtsError.Protocol("Breeze 音色 ID 为空") }
        val format = (cfg.extra["format"] ?: "pcm").lowercase()
        val body = JSONObject()
            .put("text", text)
            .put("model_id", cfg.extra["model_id"] ?: "breeze-tts-2")
            .apply { cfg.extra["language_code"]?.takeIf { it.isNotBlank() }?.let { put("language_code", it) } }
            .toString()
        return TtsHttpSpec(
            url = ttsStreamUrl(voiceId, format),
            headers = emptyMap(),
            bodyJson = body,
            stream = true,
            pcm = format == "pcm",
        )
    }

    override fun decodeChunk(bytes: ByteArray): ByteArray? = bytes
}
