package com.dshpet.android.voice.providers

import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 火山引擎「豆包语音合成 2.0」HTTP Chunked/SSE 单向流式。
 *
 * 文档：https://docs.volcengine.com/docs/DoubaoVoice/HTTPChunkedSSEUnidirectionalStreaming-V3
 *
 * 支持的配置（都走 [TtsConfig.extra]，便于界面上直接暴露）：
 *   format        pcm | mp3 | wav | ogg_opus   （默认 pcm：不用解码、延迟最低）
 *   sample_rate   16000 | 24000 | 48000        （默认 24000）
 *   speech_rate   -50..100                     （0=正常，100=2 倍速）
 *   loudness_rate -50..100                     （0=正常音量）
 *   emotion       happy/sad/angry/...          （不传=不带情绪）
 *   emotion_scale 1..5                         （情绪强度，火山是非线性的）
 *   body_style    nested | flat                （默认 nested=官方文档形态；flat 兼容部分账号/SDK）
 *   resource_id   见 [TtsConfig.resourceId]：seed-tts-2.0 等
 */
class VolcengineTtsProvider(
    private val cfg: TtsConfig,
    private val client: OkHttpClient = defaultClient(),
) : TtsProvider {

    override val id: String = "volcengine"
    override val displayName: String = "豆包语音合成 2.0"

    private val currentCall = AtomicReference<okhttp3.Call?>(null)

    /**
     * 是否被主动取消。之所以要这个标记：OkHttp 的 `call.cancel()` 只会让 `execute()` 抛
     * `IOException("Canceled")`，责任链会把它当成"普通网络失败"从而降级到系统 TTS——
     * 结果就是用户按了停止，系统语音又把这句话念一遍。
     */
    private val cancelled = AtomicBoolean(false)

    override fun isConfigured(): Boolean =
        cfg.apiKey.isNotBlank() || (cfg.appId.isNotBlank() && cfg.accessKey.isNotBlank())

    override fun cancel() {
        cancelled.set(true)
        currentCall.getAndSet(null)?.cancel()
    }

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        if (!isConfigured()) throw TtsError.NotConfigured()
        cancelled.set(false)

        val format = cfg.extra["format"] ?: "pcm"
        val sampleRate = cfg.extra["sample_rate"]?.toIntOrNull() ?: 24000
        val speechRate = cfg.extra["speech_rate"]?.toIntOrNull()
            ?: ((voice.speed - 1.0f) * 100).toInt().coerceIn(-50, 100)
        val loudnessRate = cfg.extra["loudness_rate"]?.toIntOrNull()
            ?: ((voice.volume - 1.0f) * 100).toInt().coerceIn(-50, 100)
        val emotion = cfg.extra["emotion"]?.takeIf { it.isNotBlank() } ?: voice.emotion
        val emotionScale = cfg.extra["emotion_scale"]?.toIntOrNull() ?: voice.emotionScale
        val nested = (cfg.extra["body_style"] ?: "nested") == "nested"

        val audioParams = JSONObject().apply {
            put("format", format)
            put("sample_rate", sampleRate)
            put("speech_rate", speechRate)
            put("loudness_rate", loudnessRate)
            if (!emotion.isNullOrBlank()) {
                put("emotion", emotion)
                put("emotion_scale", emotionScale.coerceIn(1, 5))
            }
        }

        val body = if (nested) {
            JSONObject().apply {
                put("user", JSONObject().put("uid", cfg.extra["uid"] ?: "dshpet"))
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
                put("user", JSONObject().put("uid", cfg.extra["uid"] ?: "dshpet"))
                put("text", text)
                put("speaker", voice.voiceId)
                put("format", format)
                put("sample_rate", sampleRate)
                put("speech_rate", speechRate)
                put("loudness_rate", loudnessRate)
                if (!emotion.isNullOrBlank()) {
                    put("emotion", emotion)
                    put("emotion_scale", emotionScale.coerceIn(1, 5))
                }
            }
        }

        val builder = Request.Builder()
            .url(cfg.baseUrl.ifBlank { DEFAULT_URL })
            .header("Content-Type", "application/json")
            .header("Connection", "keep-alive")
            .header("X-Api-Resource-Id", cfg.resourceId.ifBlank { "seed-tts-2.0" })
            .post(body.toString().toRequestBody(JSON))
        if (cfg.apiKey.isNotBlank()) {
            builder.header("X-Api-Key", cfg.apiKey)
        } else {
            builder.header("X-Api-App-Id", cfg.appId)
            builder.header("X-Api-Access-Key", cfg.accessKey)
        }

        withContext(Dispatchers.IO) {
            val call = client.newCall(builder.build())
            currentCall.set(call)
            try {
                call.execute().use { resp ->
                    val logId = resp.header("X-Tt-Logid") ?: "-"
                    if (!resp.isSuccessful) {
                        val msg = resp.body?.string().orEmpty().take(400)
                        throw when (resp.code) {
                            401, 403 -> TtsError.Auth("认证失败（HTTP ${resp.code}，logid=$logId）：$msg")
                            429 -> TtsError.RateLimited("频率超限（logid=$logId）：$msg")
                            else -> TtsError.Server("合成失败（HTTP ${resp.code}，logid=$logId）：$msg")
                        }
                    }
                    val source = resp.body?.source() ?: throw TtsError.Protocol("空响应体（logid=$logId）")
                    val isPcm = format == "pcm"
                    var emitted = 0
                    var lastCode = Int.MIN_VALUE
                    var lastMsg = ""
                    var lineNo = 0
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line()?.trim().orEmpty()
                        if (line.isEmpty()) continue
                        val payload = if (line.startsWith("data:")) line.removePrefix("data:").trim() else line
                        if (payload == "[DONE]") break
                        val json = runCatching { JSONObject(payload) }.getOrNull()
                        if (json == null) {
                            if (lineNo++ == 0) android.util.Log.w(TAG, "非 JSON 行：${payload.take(160)}")
                            continue
                        }
                        if (lineNo++ == 0) android.util.Log.i(TAG, "首行响应：${payload.take(160)}")
                        val code = if (json.has("code")) json.optInt("code") else Int.MIN_VALUE
                        val msg = json.optString("message").ifBlank { json.optString("msg") }
                        lastCode = code; lastMsg = msg

                        val b64 = firstBase64(json)
                        if (b64 != null) {
                            val bytes = Base64.getDecoder().decode(b64)
                            onChunk(
                                if (isPcm) AudioChunk(pcm = bytes, sampleRate = sampleRate)
                                else AudioChunk(encoded = bytes, sampleRate = sampleRate)
                            )
                            emitted++
                            continue
                        }
                        // 没有音频的"确认/汇总"行：0 和 20000000 都是成功码（不同接口版本不同）
                        if (code != Int.MIN_VALUE && code !in SUCCESS_CODES) {
                            throw TtsError.Server("code=$code msg=$msg logid=$logId")
                        }
                    }
                    if (emitted == 0) {
                        throw TtsError.Protocol("没有返回音频（code=$lastCode msg=$lastMsg logid=$logId）")
                    }
                    onChunk(AudioChunk(isLast = true, sampleRate = sampleRate))
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (cancelled.get() || call.isCanceled()) throw TtsError.Cancelled()
                throw e
            } finally {
                currentCall.set(null)
            }
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

    companion object {
        private const val TAG = "DshVoice"
        const val DEFAULT_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        /** 火山的成功码不止一个：分块接口用 0，汇总/单向接口用 20000000 */
        private val SUCCESS_CODES = setOf(0, 20000000)
        private val JSON = "application/json".toMediaType()
        private val BASE64_FIELDS = arrayOf("data", "audio", "audio_data", "audio_b64", "chunk")

fun defaultClient(): OkHttpClient = com.dshpet.android.api.ApiClient.stream()
    }
}
