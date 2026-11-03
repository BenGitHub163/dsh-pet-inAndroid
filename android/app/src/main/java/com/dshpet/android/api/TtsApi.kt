package com.dshpet.android.api

import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
        val body = spec.bodyJson.toRequestBody(JSON)
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
                    throw errorFor(resp.code, err)
                }
                val sampleRate = cfg.extra["sample_rate"]?.toIntOrNull() ?: DEFAULT_RATE
                codec.onResponseHeaders(allHeaders(resp))

                if (spec.stream) {
                    if (spec.lineMode) readLines(resp, sampleRate, spec.pcm, onChunk)
                    else readBinary(resp, sampleRate, spec.pcm, onChunk)
                } else {
                    val all = resp.body?.bytes() ?: ByteArray(0)
                    val out = codec.decodeChunk(all) ?: all
                    if (out.isNotEmpty()) onChunk(chunkOf(out, spec.pcm, sampleRate))
                }
                codec.onStreamEnd()
                onChunk(AudioChunk(isLast = true, sampleRate = sampleRate))
            }
        } catch (e: TtsError) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (call.isCanceled()) throw TtsError.Cancelled() else throw TtsError.Network(e.message ?: "网络错误", e)
        } finally {
            activeCall.set(null)
        }
    }

    /** 行式流（SSE / NDJSON）：一行一帧，控制帧由 codec 返回 null 丢掉 */
    private fun readLines(
        resp: Response,
        sampleRate: Int,
        pcm: Boolean,
        onChunk: (AudioChunk) -> Unit,
    ) {
        val src = resp.body?.source() ?: throw TtsError.Protocol("空响应")
        while (true) {
            val line = (src.readUtf8Line() ?: break).trim()
            if (line.isEmpty()) continue
            val out = codec.decodeLine(line) ?: continue
            if (out.isNotEmpty()) onChunk(chunkOf(out, pcm, sampleRate))
        }
    }

    /** 二进制分块：PCM 直推，MP3/AAC 打上 encoded 交给播放层解码 */
    private fun readBinary(
        resp: Response,
        sampleRate: Int,
        pcm: Boolean,
        onChunk: (AudioChunk) -> Unit,
    ) {
        val src = resp.body?.source() ?: throw TtsError.Protocol("空响应")
        val buf = ByteArray(8192)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            val out = codec.decodeChunk(buf.copyOf(n))
            if (out != null && out.isNotEmpty()) onChunk(chunkOf(out, pcm, sampleRate))
        }
    }

    private fun chunkOf(bytes: ByteArray, pcm: Boolean, sampleRate: Int): AudioChunk =
        if (pcm) AudioChunk(pcm = bytes, sampleRate = sampleRate)
        else AudioChunk(encoded = bytes, sampleRate = sampleRate)

    private fun allHeaders(resp: Response): Map<String, String> =
        resp.headers.names().associate { it.lowercase() to resp.header(it).orEmpty() }

    private fun errorFor(code: Int, err: String): TtsError = when (code) {
        401, 403 -> TtsError.Auth("鉴权失败 HTTP $code $err")
        402 -> TtsError.Auth("额度不足 HTTP 402 $err")
        429 -> TtsError.RateLimited("限流 HTTP 429 $err")
        in 500..599 -> TtsError.Server("服务端错误 $code $err")
        else -> TtsError.Protocol("HTTP $code $err")
    }

    /** 打断：立刻断掉底层连接 */
    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private companion object {
        /** 各家默认都是 24k；`cfg.extra["sample_rate"]` 显式给了就用它 */
        const val DEFAULT_RATE = 24000
        val JSON = "application/json".toMediaType()
    }
}
