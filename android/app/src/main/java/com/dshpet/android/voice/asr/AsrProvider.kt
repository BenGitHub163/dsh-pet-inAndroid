package com.dshpet.android.voice.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.dshpet.android.voice.core.VoiceCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ASR 抽象（**Strategy 模式**）。
 *
 * 现在只有系统识别一种实现；将来接"豆包流式 ASR / Whisper / 离线 sherpa"时，
 * 只需要再写一个实现类，上层（VoiceLoop）完全不用改。
 */
interface AsrProvider {
    val id: String
    val displayName: String
    fun isAvailable(): Boolean

    /**
     * 听一句并返回识别文本。
     * @param probeMs >0 时开启"静音探测"：这么多毫秒内**一点声音都没有**（引擎既没报"开始说话"、
     *   中间结果为空、mic 电平也没起来）就直接返回空串，不再傻等引擎自己的断句超时。
     *   用途见 [com.dshpet.android.voice.core.NudgePolicy]：用户起语音后一直不说话时，
     *   要在 1.x 秒内让桌宠主动开口，而不是等系统识别器 4 秒后返回 NO_MATCH。
     *   只要这期间听到任何声音（或电平抬起来），探测就自动放弃、正常等识别结果——不会打断用户说话。
     * @param onPartial 中间结果（用于"正在听到…"）
     */
    suspend fun listen(
        locale: String = "zh-CN",
        timeoutMs: Long = 20000,
        probeMs: Long = 0L,
        onPartial: (String) -> Unit = {},
    ): Result<String>

    /** 释放底层引擎（语音会话结束时调用；实现可复用引擎，不必每次 listen 都重建） */
    fun release() {}
}

class AsrError(val code: Int, message: String) : Exception(message)

/**
 * 系统 SpeechRecognizer 实现（免费、无需 key，覆盖小米/华为/Google 等自带引擎的机型）。
 *
 * 多机型适配点：
 *  1. **语言标签回退链**：不同 OEM 引擎认的标签不同（zh-CN / zh-Hans-CN / cmn-Hans-CN / 空串）；
 *  2. **离线优先**：低内存/弱网设备第二次尝试加 EXTRA_PREFER_OFFLINE；
 *  3. **错误分类**：权限/忙/没听清 直接返回；语言不支持/服务端错误 才换标签重试；
 *  4. 每次用完 `destroy()`，避免某些 ROM 上识别器状态粘住。
 */
class SystemAsrProvider(
    private val ctx: Context,
    private val caps: VoiceCapabilities,
) : AsrProvider {

    override val id: String = "system"
    override val displayName: String = "系统语音识别"

    /**
     * 复用的识别器。
     *
     * 旧实现是"每句话 createSpeechRecognizer → destroy"，在国产 ROM 上这笔开销不小
     * （300~800ms），而且连着建/销毁时引擎容易返回 ERROR_RECOGNIZER_BUSY/ERROR_CLIENT，
     * 听起来就是"收音断断续续"。现在整个会话只建一次，每次说话前 cancel + 重设 listener。
     */
    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(ctx)
    } catch (e: Throwable) {
        false
    }

    override fun release() {
        val r = recognizer ?: return
        recognizer = null
        Handler(Looper.getMainLooper()).post { runCatching { r.destroy() } }
    }

    /** 引擎状态可能已经脏了（忙/客户端错误），丢掉它，下次重建 */
    private fun dropRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        Handler(Looper.getMainLooper()).post { runCatching { r.destroy() } }
    }

    override suspend fun listen(
        locale: String,
        timeoutMs: Long,
        probeMs: Long,
        onPartial: (String) -> Unit,
    ): Result<String> {
        if (!isAvailable()) return Result.failure(AsrError(ERR_NO_SERVICE, "系统没有可用的语音识别服务"))

        val candidates = buildList {
            if (locale.isNotBlank()) add(locale)
            addAll(caps.languageCandidates())
        }.distinct()

        var last: Result<String>? = null
        candidates.forEachIndexed { index, lang ->
            val preferOffline = index > 0 && (caps.isLowRam || caps.isEmulator)
            val r = listenOnce(lang, preferOffline, timeoutMs, probeMs, onPartial)
            if (r.isSuccess) return r
            last = r
            val err = r.exceptionOrNull() as? AsrError
            val retryable = err != null && err.code in RETRYABLE
            Log.i(TAG, "listen(lang='$lang', offline=$preferOffline) failed: ${err?.message}; retryable=$retryable")
            if (!retryable) return r
        }
        return last ?: Result.failure(AsrError(ERR_UNKNOWN, "识别失败"))
    }

    private suspend fun listenOnce(
        lang: String,
        preferOffline: Boolean,
        timeoutMs: Long,
        probeMs: Long,
        onPartial: (String) -> Unit,
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var timer: Runnable? = null
        var probe: Runnable? = null

        // ---- 静音探测状态：任何一个信号出现，就说明"有人在说话"，探测立刻作废 ----
        var speechHeard = false
        var peakRms = -100f

        fun finish(r: Result<String>) {
            if (finished) return
            finished = true
            timer?.let { main.removeCallbacks(it) }
            probe?.let { main.removeCallbacks(it) }
            if (cont.isActive) cont.resume(r)
        }

        cont.invokeOnCancellation { finish(Result.failure(AsrError(ERR_CANCELLED, "已取消"))) }

        main.post {
            try {
                // 复用同一个识别器（见 recognizer 的注释）；只有它挂了才重建
                val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() { speechHeard = true }
                    override fun onRmsChanged(rmsdB: Float) { if (rmsdB > peakRms) peakRms = rmsdB }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val t = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (t.isNotBlank()) {
                            speechHeard = true
                            onPartial(t)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val t = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        finish(Result.success(t))
                    }

                    override fun onError(error: Int) {
                        // 已经收尾（例如静音探测主动 cancel）之后的回调一律忽略，
                        // 否则会白丢一个本来还能复用的识别器。
                        if (finished) return
                        // 这两种说明引擎状态已经脏了（常见于"上一句还没彻底收尾又开始下一句"），
                        // 留着它下一句还会继续报同样的错 —— 直接丢掉，下次重建。
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_CLIENT
                        ) {
                            dropRecognizer()
                        }
                        finish(Result.failure(AsrError(error, describe(error))))
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    if (lang.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    // 断句别太急：1200ms 会在你换气时就把句子切走，听感上就是"断断续续"。
                    // 2000ms 更接近真人对话的停顿，仍能在说完后 2 秒内出结果。
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                    // 说完后不要立刻把音量判定为结束（部分引擎会因此截断最后一个字）
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                }
                runCatching { r.cancel() }        // 清掉上一轮可能残留的会话
                r.startListening(intent)
                timer = Runnable { finish(Result.failure(AsrError(ERR_TIMEOUT, "识别超时"))) }
                main.postDelayed(timer!!, timeoutMs)
                if (probeMs > 0L) {
                    val p = Runnable {
                        if (speechHeard || peakRms >= VOICE_FLOOR_DB) {
                            Log.i(TAG, "静音探测作废（有人说话：speech=$speechHeard peakRms=$peakRms）")
                            return@Runnable
                        }
                        Log.i(TAG, "静音探测：${probeMs}ms 内没有任何声音 → 结束这一轮（空结果）")
                        // 先 finish 再 cancel：finish 之后 onError 会被忽略，识别器还能留着复用
                        finish(Result.success(""))
                        runCatching { r.cancel() }
                    }
                    probe = p
                    main.postDelayed(p, probeMs)
                }
            } catch (e: Throwable) {
                dropRecognizer()
                finish(Result.failure(AsrError(ERR_START, "启动识别失败：${e.message}")))
            }
        }
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错（可能被其他应用占用）"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没检测到说话"
        12 -> "引擎不支持该语言"          // ERROR_LANGUAGE_NOT_SUPPORTED (API 31+)
        13 -> "语言包不可用"              // ERROR_LANGUAGE_UNAVAILABLE
        else -> "识别错误（code=$code）"
    }

    companion object {
        private const val TAG = "DshVoiceAsr"
        const val ERR_NO_SERVICE = -10
        const val ERR_TIMEOUT = -11
        const val ERR_START = -12
        const val ERR_CANCELLED = -13
        const val ERR_UNKNOWN = -14

        /** 这些错误换语言标签重试有意义 */
        private val RETRYABLE = setOf(
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_NETWORK,      // 弱网时换离线优先再试一次
            12, 13,
        )

        /**
         * 静音探测的"有人说话"门限（dB）。
         * [RecognitionListener.onRmsChanged] 的范围大约 -2..10，安静房间常在 0 附近、
         * 说话时基本都会越过这个值。宁可漏判（继续等引擎自己的断句，行为与以前一致），
         * 也不要在用户正说话时把这一轮掐掉。
         */
        private const val VOICE_FLOOR_DB = 4.0f
    }
}
