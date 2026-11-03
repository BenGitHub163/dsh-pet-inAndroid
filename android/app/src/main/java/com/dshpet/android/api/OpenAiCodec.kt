package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.VoiceProfile
import org.json.JSONObject

/**
 * **OpenAI 兼容 `/v1/audio/speech`** 的报文编解码（不含 HTTP）。
 *
 * 这是事实标准，一次实现能接：OpenAI TTS、GPT-SoVITS / IndexTTS2 / CosyVoice / fish-speech、
 * openedai-speech、各种中转站。
 *
 * 线上形态：
 * ```
 * POST {baseUrl}{path}                      ← baseUrl 可以只到 host，也可以是完整 endpoint
 * Authorization: Bearer {apiKey}
 * {"model":"tts-1","input":"…","voice":"alloy","response_format":"pcm","speed":1.0}
 * → 裸音频字节流（response_format=pcm 时是 24k/mono/s16le；mp3 等交给播放层解码）
 * ```
 *
 * `cfg.extra` 支持的键：`format`（默认 pcm）、`model`（默认 tts-1）、`sample_rate`。
 *
 * 未支持：用 SSE `data: {"audio":"<base64>"}` 而不是裸字节的一些中转站
 * （旧实现同样不支持，这里保持行为一致；真要接，给 [TtsHttpSpec.lineMode] 打开并在
 * [decodeLine] 里解 base64 即可）。
 */
class OpenAiCodec : TtsCodec {

    override val id: String = "openai"

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
            headers = buildMap {
                // Content-Type 由 TtsApi 的 RequestBody 带上，这里不重复
                if (cfg.apiKey.isNotBlank()) put("Authorization", "Bearer ${cfg.apiKey}")
            },
            bodyJson = body.toString(),
            stream = true,
            lineMode = false,
            pcm = format == "pcm",
        )
    }

    /** 二进制流：服务端已经给的是成品音频，不需要再解包 */
    override fun decodeChunk(bytes: ByteArray): ByteArray? = bytes

    /** baseUrl 可能已经是完整 endpoint，也可能只到 host */
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
