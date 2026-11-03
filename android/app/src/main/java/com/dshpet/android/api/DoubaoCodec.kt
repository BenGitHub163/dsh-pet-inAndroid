package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.VoiceProfile
import org.json.JSONObject
import java.util.Base64

/**
 * **火山引擎「豆包语音合成 2.0」HTTP 单向流式**的报文编解码（不含 HTTP）。
 *
 * 文档：https://docs.volcengine.com/docs/DoubaoVoice/HTTPChunkedSSEUnidirectionalStreaming-V3
 * 域名与头名见 [DoubaoApi]。
 *
 * 线上形态：
 * ```
 * POST https://openspeech.bytedance.com/api/v3/tts/unidirectional
 * X-Api-Resource-Id: seed-tts-2.0
 * X-Api-Key: …            （或 X-Api-App-Id + X-Api-Access-Key）
 * {"user":{"uid":"…"},"req_params":{"text":"…","speaker":"…","audio_params":{…}}}
 * → 一行一帧的 JSON：{"code":0,"data":"<base64 音频>"}
 * ```
 *
 * `cfg.extra` 支持的键：
 *   format        pcm | mp3 | wav | ogg_opus   （默认 pcm：不用解码、延迟最低）
 *   sample_rate   16000 | 24000 | 48000        （默认 24000）
 *   speech_rate   -50..100                     （0=正常，100=2 倍速）
 *   loudness_rate -50..100                     （0=正常音量）
 *   emotion       happy/sad/angry/...          （不传=不带情绪）
 *   emotion_scale 1..5                         （情绪强度，火山是非线性的）
 *   body_style    nested | flat                （默认 nested=官方文档形态）
 *   resource_id   见 [TtsConfig.resourceId]：seed-tts-2.0 等
 */
class DoubaoCodec : TtsCodec {

    override val id: String = "volcengine"

    // ---- 单次请求的流状态（在 request() 里重置）----
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

        val format = formatOf(cfg)
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

        val body = if (nested) {
            JSONObject().apply {
                put("user", JSONObject().put("uid", uid(cfg)))
                put("req_params", JSONObject().apply {
                    put("text", text)
                    put("speaker", voice.voiceId)
                    put("audio_params", audioParams)
                })
            }
        } else {
            // 扁平形态（部分开源客户端/账号在用）。注意：text 必须带，否则服务端只回
            // code=3001/400 之类的"缺少 text"错误（旧版本这里漏了，切到 flat 必定失败）。
            JSONObject().apply {
                put("user", JSONObject().put("uid", uid(cfg)))
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
            url = cfg.baseUrl.trim().ifBlank { DoubaoApi.TTS_URL },
            headers = buildMap {
                put("Connection", "keep-alive")
                put(DoubaoApi.HEADER_RESOURCE, cfg.resourceId.ifBlank { DEFAULT_RESOURCE })
                if (cfg.apiKey.isNotBlank()) {
                    put(DoubaoApi.HEADER_KEY, cfg.apiKey)
                } else {
                    put("X-Api-App-Id", cfg.appId)
                    put("X-Api-Access-Key", cfg.accessKey)
                }
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

        // 没有音频的"确认/汇总"行：0 和 20000000 都是成功码（不同接口版本不同）
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

    /** 兼容不同返回形态：data / audio / audio_data / audio_b64 / chunk（含嵌套一层） */
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

    private fun formatOf(cfg: TtsConfig): String =
        cfg.extra["format"]?.takeIf { it.isNotBlank() }?.lowercase() ?: "pcm"

    private fun uid(cfg: TtsConfig): String = cfg.extra["uid"]?.takeIf { it.isNotBlank() } ?: "dshpet"

    private companion object {
        const val TAG = "DshVoice"
        const val DEFAULT_SAMPLE_RATE = 24000
        const val DEFAULT_RESOURCE = "seed-tts-2.0"
        /** 火山的成功码不止一个：分块接口用 0，汇总/单向接口用 20000000 */
        val SUCCESS_CODES = setOf(0, 20000000)
        val BASE64_FIELDS = arrayOf("data", "audio", "audio_data", "audio_b64", "chunk")
    }
}
