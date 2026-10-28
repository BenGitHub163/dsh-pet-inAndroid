package com.dshpet.android.voice.providers

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统 TTS（Android TextToSpeech）——**兜底实现**。
 *
 * 作用：没配 key、401/403、断网、或者 provider 报错时，至少"能出声"。
 * 实现方式：合成到临时 WAV 文件 → 读出字节 → 当一个 encoded chunk 交给播放层
 *          （播放层用 MediaPlayer/ExoPlayer 播 WAV 即可，不用自己解 PCM）。
 *
 * 未编译验证。
 */
class SystemTtsProvider(
    private val ctx: Context,
    private val cfg: TtsConfig? = null,
) : TtsProvider {

    override val id: String = "system"
    override val displayName: String = "系统语音合成（兜底）"

    private var engine: TextToSpeech? = null
    private var initSignal = CompletableDeferred<Int>()

    private suspend fun ensureEngine(): TextToSpeech {
        engine?.let { if (initSignal.isCompleted && initSignal.getCompleted() == TextToSpeech.SUCCESS) return it }
        return withContext(Dispatchers.Main) {
            if (initSignal.isCompleted) initSignal = CompletableDeferred()   // 上次失败 → 允许重试
            val created = TextToSpeech(ctx) { status ->
                if (!initSignal.isCompleted) initSignal.complete(status)
            }
            engine = created
            val status = kotlinx.coroutines.withTimeoutOrNull(6000) { initSignal.await() }
                ?: throw TtsError.Server("系统 TTS 初始化超时（6s）")
            if (status != TextToSpeech.SUCCESS) {
                throw TtsError.Server("系统 TTS 引擎初始化失败（status=$status，可检查系统设置里的“文字转语音”引擎）")
            }
            created
        }
    }

    override fun isConfigured(): Boolean = true

    override fun cancel() {
        engine?.stop()
    }

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        val tts = ensureEngine()
        withContext(Dispatchers.Main) {
            tts.language = Locale.SIMPLIFIED_CHINESE
            tts.setSpeechRate(voice.speed.coerceIn(0.5f, 2.0f))
            tts.setPitch(1.0f)
        }
        val out = File(ctx.cacheDir, "sys_tts_${System.nanoTime()}.wav")
        val utteranceId = "dshpet-${System.nanoTime()}"

        suspendCancellableCoroutine<Unit> { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("兼容旧 API")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resumeWithException(TtsError.Server("系统 TTS 合成失败"))
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) cont.resumeWithException(TtsError.Server("系统 TTS 合成失败 code=$errorCode"))
                }

                /** cancel() → engine.stop() 会走这里；不接住的话协程会一直挂着 */
                override fun onStop(id: String?, interrupted: Boolean) {
                    if (id == utteranceId && cont.isActive) cont.resumeWithException(TtsError.Cancelled())
                }
            })
            val params = Bundle()
            val r = tts.synthesizeToFile(text, params, out, utteranceId)
            if (r != TextToSpeech.SUCCESS) {
                if (cont.isActive) cont.resumeWithException(TtsError.Server("synthesizeToFile 返回 $r"))
            }
        }

        try {
            val bytes = withContext(Dispatchers.IO) { out.readBytes() }
            onChunk(AudioChunk(encoded = bytes, isLast = true))
        } finally {
            runCatching { out.delete() }
        }
    }

    fun release() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
