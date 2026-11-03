package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.VoiceProfile
import org.json.JSONObject

/**
 * OpenAI 兼容 `/v1/audio/speech`。HTTP 由 [TtsApi] 发出，鉴权从 KeyFactory 注入。
 */
class OpenAiApi : TtsCodec {

    override val id: String = "openai"

    override fun displayName(cfg: TtsConfig): String =
        cfg.extra["display_name"] ?: "OpenAI 兼容 TTS"

    override fun isConfigured(cfg: TtsConfig): Boolean =
        cfg.baseUrl.isNotBlank() && com.dshpet.android.data.KeyFactory.current().hasDeepseek

    override fun authHeaders(cfg: TtsConfig): Map<String, String> {
        val key = com.dshpet.android.data.KeyFactory.current().deepseek
        return if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")
    }

    override fun request(text: String, voice: VoiceProfile, cfg: TtsConfig): TtsHttpSpec {
        if (cfg.baseUrl.isBlank()) throw TtsError.NotConfigured()
        val format = cfg.extra["format"]?.takeIf { it.isNotBlank() }?.lowercase() ?: "pcm"
        val body = JSONObject().apply {
            put("model", cfg.extra["model"] ?: "tts-1")
            put("input", text)
            put("voice", voice.voiceId)
            put("response_format", format)
            put("speed", voice.speed.toDouble().coerceIn(0.25, 4.0))
        }
        return TtsHttpSpec(
            url = endpoint(cfg),
            headers = emptyMap(),
            bodyJson = body.toString(),
            stream = true,
            lineMode = false,
            pcm = format == "pcm",
        )
    }

    override fun decodeChunk(bytes: ByteArray): ByteArray? = bytes

    private fun endpoint(cfg: TtsConfig): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw TtsError.NotConfigured()
        val path = cfg.path.ifBlank { DEFAULT_PATH }
        return when {
            base.endsWith("/audio/speech") -> base
            base.endsWith("/v1") -> "$base/audio/speech"
            else -> base + if (path.startsWith("/")) path else "/$path"
        }
    }

    private companion object {
        const val DEFAULT_PATH = "/v1/audio/speech"
    }
}
