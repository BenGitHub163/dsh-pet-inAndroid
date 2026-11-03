package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.VoiceProfile
import org.json.JSONObject

/** Breeze Blue 的报文编解码（不含 HTTP）。 */
class BreezeCodec : TtsCodec {
    override val id: String = "breeze"

    override fun request(text: String, voice: VoiceProfile, cfg: TtsConfig): TtsHttpSpec {
        val voiceId = voice.voiceId.ifBlank { throw com.dshpet.android.voice.TtsError.Protocol("Breeze 音色 ID 为空") }
        val format = (cfg.extra["format"] ?: "pcm").lowercase()
        val body = JSONObject()
            .put("text", text)
            .put("model_id", cfg.extra["model_id"] ?: "breeze-tts-2")
            .apply { cfg.extra["language_code"]?.takeIf { it.isNotBlank() }?.let { put("language_code", it) } }
            .toString()
        return TtsHttpSpec(
            url = BreezeApi.ttsStreamUrl(voiceId, format),
            headers = mapOf(BreezeApi.HEADER_KEY to cfg.apiKey),
            bodyJson = body,
            stream = true,
        )
    }

    override fun decodeChunk(bytes: ByteArray): ByteArray? = bytes
}