package com.dshpet.android.voice.core

import android.content.Context
import android.util.Log
import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest

/**
 * **责任链 + 重试**：按顺序尝试多个 TTS 通道，任何一环失败自动降级。
 *
 * 规则（都来自实际踩坑）：
 *  1. 未配置（没 key）的通道直接跳过，不浪费时间；
 *  2. `Auth`（401/403）/`NotConfigured` → 立刻换下一家，不重试（重试也没用）；
 *  3. `RateLimited` / `Network` → 退避重试（最多 2 次），仍失败再换下一家；
 *  4. **已经吐过音频再失败 → 不降级**（否则用户会听到同一句读两遍），直接抛错；
 *  5. 最后一环通常是系统 TTS，保证"无论如何能出声"。
 */
class TtsChain(
    private val providers: List<TtsProvider>,
    private val retriesPerProvider: Int = 2,
) : TtsProvider {

    override val id: String = "chain"
    override val displayName: String = "TTS 链路"

    override fun isConfigured(): Boolean = providers.any { it.isConfigured() }

    override fun cancel() {
        providers.forEach { runCatching { it.cancel() } }
    }

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        val usable = providers.filter { it.isConfigured() }
        if (usable.isEmpty()) throw TtsError.NotConfigured()

        var last: Throwable? = null
        for (provider in usable) {
            var emitted = 0
            for (attempt in 1..retriesPerProvider) {
                try {
                    provider.synthesize(text, voice) { chunk ->
                        if (chunk.pcm != null || chunk.encoded != null) emitted++
                        onChunk(chunk)
                    }
                    if (provider.id != "chain") Log.d(TAG, "tts ok via ${provider.id}")
                    return
                } catch (e: Throwable) {
                    last = e
                    if (e is TtsError.Cancelled) throw e          // 主动取消：不重试、也不降级
                    val kind = e.javaClass.simpleName
                    Log.w(TAG, "tts fail via ${provider.id} attempt=$attempt kind=$kind msg=${e.message}")
                    if (emitted > 0) throw e                      // 已经出过声，不能重来
                    val retryable = e is TtsError.RateLimited || e is TtsError.Network || e is TtsError.Server
                    if (!retryable) break                          // Auth/NotConfigured → 换下一家
                    if (attempt < retriesPerProvider) delay(300L * attempt)
                }
            }
        }
        throw last ?: TtsError.NotConfigured()
    }

    companion object { private const val TAG = "DshVoiceChain" }
}

/**
 * **装饰器**：给任意 TTS 通道加"句子级磁盘缓存"。
 *
 * 为什么值得做：桌宠的"自言自语 / 点击台词"是固定短句，命中率极高；
 * 命中时延迟从 600ms 降到 <10ms，而且不再计费。
 */
class CachingTtsProvider(
    ctx: Context,
    private val delegate: TtsProvider,
    /**
     * 期望的采样率（= 设置的 sample_rate）。只用于两件事：
     *  1. 参与缓存文件名，**改采样率自然会 miss**，避免拿 24k 的缓存按 48k 播（变调）；
     *  2. 命中时把这个值写回 [AudioChunk.sampleRate]，交给播放层建轨。
     */
    private val expectedRate: Int = SpeechPlayer.DEFAULT_RATE,
    private val maxBytes: Long = 20L * 1024 * 1024,
) : TtsProvider {

    override val id: String = "${delegate.id}+cache"
    override val displayName: String = delegate.displayName

    private val dir = File(ctx.cacheDir, "tts_cache").apply { mkdirs() }

    override fun isConfigured(): Boolean = delegate.isConfigured()
    override fun cancel() = delegate.cancel()

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        if (text.length > MAX_CACHEABLE_LEN) return delegate.synthesize(text, voice, onChunk)

        val key = sha1("${delegate.id}|$text|${voice.voiceId}|${voice.speed}|${voice.emotion}|${voice.emotionScale}")
        val file = File(dir, "$key-$expectedRate.pcm")

        if (file.exists() && file.length() > 0) {
            runCatching {
                onChunk(AudioChunk(pcm = file.readBytes(), sampleRate = expectedRate))
                onChunk(AudioChunk(isLast = true, sampleRate = expectedRate))
                Log.d(TAG, "cache hit ${file.name} (${file.length()}B)")
                return
            }
        }

        val buffer = java.io.ByteArrayOutputStream()
        var onlyPcm = true
        var actualRate = expectedRate
        delegate.synthesize(text, voice) { chunk ->
            if (chunk.pcm != null && onlyPcm) {
                if (chunk.sampleRate > 0) actualRate = chunk.sampleRate
                if (buffer.size() + chunk.pcm.size <= MAX_CACHEABLE_LEN_BYTES) buffer.write(chunk.pcm)
            } else if (chunk.encoded != null) {
                onlyPcm = false       // 编码流（mp3/wav）不进缓存，避免解码器差异
            }
            onChunk(chunk)
        }
        if (onlyPcm && buffer.size() > 0) {
            runCatching {
                // 用实际采样率命名：万一 provider 没按设置给，也不会被下一次以错误速率播放
                File(dir, "$key-$actualRate.pcm").writeBytes(buffer.toByteArray())
                trim()
            }
        }
    }

    /** 超限就按最后修改时间删最旧的 */
    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "DshVoiceCache"
        private const val MAX_CACHEABLE_LEN = 200          // 超过 200 字不缓存（长回复不重复）
        private const val MAX_CACHEABLE_LEN_BYTES = 2 * 1024 * 1024
    }
}
