package com.dshpet.android.api

import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.atomic.AtomicReference

/**
 * **TTS 接口**：唯一发请求的地方。
 *
 * 分工：
 *  - [TtsCodec] 只负责"这家服务商的报文长什么样、流怎么切"；
 *  - 本类负责 URL/头/body 落地、超时、HTTP 状态码 → [TtsError] 映射、取消（cancel 底层 Call）。
 * 于是 provider 不再需要 OkHttpClient，也不该出现域名与鉴权头。
 */
class TtsApi(private val codec: TtsCodec) {

    private val activeCall = AtomicReference<okhttp3.Call?>(null)

    suspend fun synthesize(
        text: String,
        voice: VoiceProfile,
        cfg: TtsConfig,
        onChunk: (AudioChunk) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        val spec = codec.request(text, voice, cfg)
        val body = spec.bodyJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(spec.url)
            .apply { spec.headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body)
            .build()

        val call = ApiClient.read(60).newCall(req)
        activeCall.set(call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    throw when (resp.code) {
                        401, 403 -> TtsError.Auth("鉴权失败 HTTP ${resp.code}")
                        402 -> TtsError.Auth("额度不足 HTTP 402 $err")
                        429 -> TtsError.RateLimited("限流 HTTP 429 $err")
                        in 500..599 -> TtsError.Server("服务端错误 ${resp.code} $err")
                        else -> TtsError.Protocol("HTTP ${resp.code} $err")
                    }
                }
                val sampleRate = cfg.extra["sample_rate"]?.toIntOrNull() ?: 24000
                if (!spec.stream) {
                    val all = resp.body?.bytes() ?: ByteArray(0)
                    val out = codec.decodeChunk(all) ?: all
                    if (out.isNotEmpty()) onChunk(AudioChunk(pcm = out, sampleRate = sampleRate))
                    return@use
                }
                val src = resp.body?.source() ?: throw TtsError.Protocol("空响应")
                val buf = ByteArray(8192)
                while (!src.exhausted()) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    val raw = buf.copyOf(n)
                    val out = codec.decodeChunk(raw)
                    if (out != null && out.isNotEmpty()) onChunk(AudioChunk(pcm = out, sampleRate = sampleRate))
                }
            }
        } catch (e: TtsError) {
            throw e
        } catch (e: Throwable) {
            if (call.isCanceled()) throw TtsError.Cancelled() else throw TtsError.Network(e.message ?: "网络错误", e)
        } finally {
            activeCall.set(null)
        }
    }

    /** 打断：立刻断掉底层连接 */
    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }
}