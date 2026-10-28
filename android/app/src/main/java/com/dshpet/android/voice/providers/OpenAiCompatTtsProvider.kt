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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容的 `/v1/audio/speech` 通用适配器。
 *
 * 这是事实标准：`response_format` 支持 mp3/opus/aac/flac/wav/pcm，`speed` 0.25~4.0，响应可流式。
 * 一次实现，能接：
 *   - OpenAI TTS
 *   - 自建 GPT-SoVITS / IndexTTS2 / CosyVoice / fish-speech（写个薄封装即可）
 *   - openedai-speech、各种中转站
 *   - MiniMax 等提供兼容层的平台
 *
 * 未编译验证。
 */
class OpenAiCompatTtsProvider(
    private val cfg: TtsConfig,
    private val client: OkHttpClient = defaultClient(),
) : TtsProvider {

    override val id: String = "openai"
    override val displayName: String = cfg.extra["display_name"] ?: "OpenAI 兼容 TTS"

    private val currentCall = AtomicReference<okhttp3.Call?>(null)

    override fun isConfigured(): Boolean = cfg.baseUrl.isNotBlank()

    override fun cancel() {
        currentCall.getAndSet(null)?.cancel()
    }

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        if (!isConfigured()) throw TtsError.NotConfigured()
        val format = cfg.extra["format"] ?: "pcm"
        val url = buildUrl()

        val body = JSONObject().apply {
            put("model", cfg.extra["model"] ?: "tts-1")
            put("input", text)
            put("voice", voice.voiceId)
            put("response_format", format)
            put("speed", voice.speed.toDouble().coerceIn(0.25, 4.0))
        }

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body.toString().toRequestBody(JSON))
            .build()

        withContext(Dispatchers.IO) {
            val call = client.newCall(req)
            currentCall.set(call)
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = resp.body?.string().orEmpty().take(300)
                    throw when (resp.code) {
                        401, 403 -> TtsError.Auth("认证失败（HTTP ${resp.code}）：$msg")
                        429 -> TtsError.RateLimited("频率超限：$msg")
                        else -> TtsError.Server("合成失败（HTTP ${resp.code}）：$msg")
                    }
                }
                val source = resp.body?.source() ?: throw TtsError.Protocol("空响应体")
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    val data = buf.copyOf(n)
                    onChunk(if (format == "pcm") AudioChunk(pcm = data) else AudioChunk(encoded = data))
                }
                onChunk(AudioChunk(isLast = true))
            }
            currentCall.set(null)
        }
    }

    /** baseUrl 可能已经是完整 endpoint，也可能只到 host */
    private fun buildUrl(): String {
        val base = cfg.baseUrl.trimEnd('/')
        val path = cfg.path.ifBlank { "/v1/audio/speech" }
        return if (base.endsWith("/audio/speech")) base
        else if (base.endsWith("/v1")) base + "/audio/speech"
        else base + if (path.startsWith("/")) path else "/$path"
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
