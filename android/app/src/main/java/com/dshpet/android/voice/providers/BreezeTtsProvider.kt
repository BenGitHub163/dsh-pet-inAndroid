package com.dshpet.android.voice.providers

import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsEngines
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Breeze Blue TTS（https://docs.breezeblue.ai）。
 *
 * 走文档里的**流式**接口，延迟最低：
 *   `POST https://api.breeze.blue/v1/text-to-speech/{voice_id}/stream?output_format=pcm`
 *   header `xi-api-key: brz_...`
 *   body   `{"text":"…","model_id":"breeze-tts-2"}`
 *   → 二进制音频流（pcm = 24 kHz / mono / s16le；也可 `output_format=mp3`）
 *
 * 说明：
 *  - 一次 [synthesize] 只处理**一句话**（分句是上层的事，和豆包实现一致）；
 *  - [cancel] 会直接掐掉在途 HTTP 请求（用户打断/停止时不再计费）；
 *  - `cfg.extra` 支持：`format`(pcm/mp3)、`sample_rate`、`model_id`、`language_code`。
 */
class BreezeTtsProvider(private val cfg: TtsConfig) : TtsProvider {

    override val id: String get() = TtsEngines.ID_BREEZE
    override val displayName: String get() = "Breeze Blue"

    @Volatile
    private var call: Call? = null

    override fun isConfigured(): Boolean = cfg.apiKey.isNotBlank()

    override suspend fun synthesize(
        text: String,
        voice: VoiceProfile,
        onChunk: (AudioChunk) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw TtsError.NotConfigured()
        if (text.isBlank()) return@withContext
        val voiceId = voice.voiceId.ifBlank { throw TtsError.Protocol("Breeze 音色 ID 为空") }
        val format = (cfg.extra["format"] ?: "pcm").lowercase()
        val body = JSONObject()
            .put("text", text)
            .put("model_id", cfg.extra["model_id"] ?: "breeze-tts-2")
            .apply {
                cfg.extra["language_code"]?.takeIf { it.isNotBlank() }?.let { put("language_code", it) }
            }
            .toString()
        val url = "https://api.breeze.blue/v1/text-to-speech/$voiceId/stream?output_format=$format"
        val req = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", cfg.apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val c = client.newCall(req)
        call = c
        try {
            c.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    throw when (resp.code) {
                        401, 403 -> TtsError.Auth("Breeze 鉴权失败 HTTP ${resp.code}")
                        429 -> TtsError.RateLimited("Breeze 限流 HTTP 429 $err")
                        402 -> TtsError.Auth("Breeze 额度不足 $err")
                        in 500..599 -> TtsError.Server("Breeze ${resp.code} $err")
                        else -> TtsError.Protocol("Breeze HTTP ${resp.code} $err")
                    }
                }
                val src = resp.body?.source() ?: throw TtsError.Protocol("Breeze 空响应")
                val sampleRate = cfg.extra["sample_rate"]?.toIntOrNull() ?: 24000
                val buf = ByteArray(8192)
                while (!src.exhausted()) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    val bytes = buf.copyOf(n)
                    if (format == "pcm") {
                        onChunk(AudioChunk(pcm = bytes, sampleRate = sampleRate))
                    } else {
                        onChunk(AudioChunk(encoded = bytes, sampleRate = sampleRate))
                    }
                }
            }
        } catch (e: TtsError) {
            throw e
        } catch (e: Throwable) {
            if (c.isCanceled()) throw TtsError.Cancelled() else throw TtsError.Network(e.message ?: "网络错误", e)
        } finally {
            call = null
        }
    }

    override fun cancel() {
        runCatching { call?.cancel() }
        call = null
    }

    private companion object {
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
