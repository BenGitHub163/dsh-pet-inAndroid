package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.VoiceProfile
import org.json.JSONObject
import java.util.Base64

/**
 * 豆包厂家接口：TTS 报文编解码。HTTP 由 [TtsApi] 发出。
 * 每次合成新建实例（流状态不能跨请求共享）。
 */
class DoubaoApi : TtsCodec {

    override val id: String = "volcengine"

    override fun displayName(cfg: TtsConfig): String = "豆包语音合成 2.0"

    override fun isConfigured(cfg: TtsConfig): Boolean =
        com.dshpet.android.data.KeyFactory.current().hasDoubao

    override fun authHeaders(cfg: TtsConfig): Map<String, String> {
        val keys = com.dshpet.android.data.KeyFactory.current()
        return when {
            keys.doubao.isNotBlank() -> mapOf(HEADER_KEY to keys.doubao)
            keys.doubaoAppId.isNotBlank() -> mapOf("X-Api-App-Id" to keys.doubaoAppId)
            else -> emptyMap()
        }
    }

    private var emitted = 0
    private var lastCode = Int.MIN_VALUE
    private var lastMsg = ""
    private var logId: String = "-"
    private var lineNo = 0

    override fun request(text: String, voice: VoiceProfile, cfg: TtsConfig): TtsHttpSpec {
        emitted = 0
        lastCode = Int.MIN_VALUE
        lastMsg = ""
        logId = "-"
        lineNo = 0

        val format = cfg.extra["format"]?.takeIf { it.isNotBlank() }?.lowercase() ?: "pcm"
        val sampleRate = cfg.extra["sample_rate"]?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
        val speechRate = cfg.extra["speech_rate"]?.toIntOrNull()
            ?: ((voice.speed - 1.0f) * 100).toInt().coerceIn(-50, 100)
        val loudnessRate = cfg.extra["loudness_rate"]?.toIntOrNull()
            ?: ((voice.volume - 1.0f) * 100).toInt().coerceIn(-50, 100)
        val emotion = cfg.extra["emotion"]?.takeIf { it.isNotBlank() } ?: voice.emotion
        val emotionScale = (cfg.extra["emotion_scale"]?.toIntOrNull() ?: voice.emotionScale).coerceIn(1, 5)
        val nested = (cfg.extra["body_style"] ?: "nested") == "nested"

        val audioParams = JSONObject().apply {
            put("format", format)
            put("sample_rate", sampleRate)
            put("speech_rate", speechRate)
            put("loudness_rate", loudnessRate)
            if (!emotion.isNullOrBlank()) {
                put("emotion", emotion)
                put("emotion_scale", emotionScale)
            }
        }

        val uid = cfg.extra["uid"]?.takeIf { it.isNotBlank() } ?: "dshpet"
        val body = if (nested) {
            JSONObject().apply {
                put("user", JSONObject().put("uid", uid))
                put("req_params", JSONObject().apply {
                    put("text", text)
                    put("speaker", voice.voiceId)
                    put("audio_params", audioParams)
                })
            }
        } else {
            JSONObject().apply {
                put("user", JSONObject().put("uid", uid))
                put("text", text)
                put("speaker", voice.voiceId)
                put("format", format)
                put("sample_rate", sampleRate)
                put("speech_rate", speechRate)
                put("loudness_rate", loudnessRate)
                if (!emotion.isNullOrBlank()) {
                    put("emotion", emotion)
                    put("emotion_scale", emotionScale)
                }
            }
        }

        return TtsHttpSpec(
            url = cfg.baseUrl.trim().ifBlank { TTS_URL },
            headers = buildMap {
                put("Connection", "keep-alive")
                put(HEADER_RESOURCE, cfg.resourceId.ifBlank { DEFAULT_RESOURCE })
            },
            bodyJson = body.toString(),
            stream = true,
            lineMode = true,
            pcm = format == "pcm",
        )
    }

    override fun onResponseHeaders(headers: Map<String, String>) {
        logId = headers["x-tt-logid"] ?: "-"
    }

    override fun decodeLine(line: String): ByteArray? {
        val payload = if (line.startsWith("data:")) line.removePrefix("data:").trim() else line
        if (payload.isEmpty() || payload == "[DONE]") return null

        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json == null) {
            if (lineNo++ == 0) android.util.Log.w(TAG, "非 JSON 行：${payload.take(160)}")
            return null
        }
        if (lineNo++ == 0) android.util.Log.i(TAG, "首行响应：${payload.take(160)}")

        val code = if (json.has("code")) json.optInt("code") else Int.MIN_VALUE
        lastCode = code
        lastMsg = json.optString("message").ifBlank { json.optString("msg") }

        firstBase64(json)?.let { b64 ->
            val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                android.util.Log.w(TAG, "base64 解码失败（${b64.length} 字符，logid=$logId）")
                return null
            }
            emitted++
            return bytes
        }

        if (code != Int.MIN_VALUE && code !in SUCCESS_CODES) {
            throw TtsError.Server("code=$code msg=$lastMsg logid=$logId")
        }
        return null
    }

    override fun onStreamEnd() {
        if (emitted == 0) {
            throw TtsError.Protocol("没有返回音频（code=$lastCode msg=$lastMsg logid=$logId）")
        }
    }

    private fun firstBase64(json: JSONObject): String? {
        for (k in BASE64_FIELDS) {
            val v = json.optString(k, "")
            if (v.length > 32) return v
        }
        for (k in json.keys()) {
            val o = json.optJSONObject(k) ?: continue
            for (k2 in BASE64_FIELDS) {
                val v = o.optString(k2, "")
                if (v.length > 32) return v
            }
        }
        return null
    }

    companion object {
        const val TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        const val HEADER_KEY = "X-Api-Key"
        const val HEADER_RESOURCE = "X-Api-Resource-Id"
        private const val TAG = "DshVoice"
        private const val DEFAULT_SAMPLE_RATE = 24000
        private const val DEFAULT_RESOURCE = "seed-tts-2.0"
        private val SUCCESS_CODES = setOf(0, 20000000)
        private val BASE64_FIELDS = arrayOf("data", "audio", "audio_data", "audio_b64", "chunk")
    }
}
