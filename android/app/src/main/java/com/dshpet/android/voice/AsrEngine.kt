package com.dshpet.android.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 用系统 SpeechRecognizer 做一句话识别（不用额外 key）。
 *
 * 实测这台小米上有 com.xiaomi.mibrain.speech/.asr.AsrService，且是默认识别服务。
 * 注意：Android 11+ 必须在 manifest 里声明
 *   <queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>
 * 否则 isRecognitionAvailable() 会返回 false。
 */
class AsrEngine(private val ctx: Context) {

    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(ctx)
    } catch (e: Throwable) {
        false
    }

    /**
     * 监听一次。识别完（或出错/超时）返回。
     * @param onPartial 中间结果（用于 UI 实时显示"正在听到…"）
     */
    suspend fun listenOnce(
        lang: String = "zh-CN",
        timeoutMs: Long = 20000,
        onPartial: (String) -> Unit = {},
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var recognizer: SpeechRecognizer? = null
        var timeoutRunnable: Runnable? = null

        fun finish(r: Result<String>) {
            if (finished) return
            finished = true
            timeoutRunnable?.let { main.removeCallbacks(it) }
            main.post {
                try { recognizer?.destroy() } catch (_: Throwable) {}
                recognizer = null
            }
            if (cont.isActive) cont.resume(r)
        }

        cont.invokeOnCancellation { finish(Result.failure(AsrError(-1, "cancelled"))) }

        main.post {
            try {
                val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
                recognizer = rec
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (t.isNotBlank()) onPartial(t)
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finish(Result.success(list?.firstOrNull().orEmpty()))
                    }

                    override fun onError(error: Int) {
                        finish(Result.failure(AsrError(error, describe(error))))
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
                }
                rec.startListening(intent)
                timeoutRunnable = Runnable { finish(Result.failure(AsrError(-2, "识别超时"))) }
                main.postDelayed(timeoutRunnable!!, timeoutMs)
            } catch (e: Throwable) {
                finish(Result.failure(AsrError(-3, "启动识别失败：${e.message}")))
            }
        }
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清（再说一次）"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没检测到说话"
        else -> "识别错误 code=$code"
    }
}

class AsrError(val code: Int, message: String) : Exception(message)
