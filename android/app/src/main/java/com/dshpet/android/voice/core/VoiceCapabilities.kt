package com.dshpet.android.voice.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * **设备能力探测** —— 多机型适配的第一层。
 *
 * 不同手机在这三件事上差异极大：
 *   1. 有没有语音识别服务（小米/华为自带；原生/海外机是 Google；部分 ROM 什么都没有）
 *   2. 有没有 TTS 引擎（很多国内 ROM 把 Pico/Google TTS 裁掉了）
 *   3. 语言包是否支持中文、是否支持离线
 *
 * 所以启动前先探测，再决定用哪条链路 + 给用户什么提示，而不是"试了报错再猜"。
 *
 * 需要 manifest 里的 <queries>（TTS_SERVICE / RecognitionService），否则 Android 11+
 * 查不到这些服务。
 */
class VoiceCapabilities private constructor(
    val sdkInt: Int,
    val asrServices: List<String>,
    val ttsServices: List<String>,
    val defaultAsrService: String?,
    val defaultTtsService: String?,
    val prefersChinese: Boolean,
    val isLowRam: Boolean,
    val isEmulator: Boolean,
) {

    val hasAsr: Boolean get() = asrServices.isNotEmpty()
    val hasTts: Boolean get() = ttsServices.isNotEmpty()

    /** ASR 可用性的一句话说明（给 UI 用） */
    fun asrSummary(): String = when {
        !hasAsr -> "无识别服务（需要装语音识别引擎）"
        defaultAsrService != null -> "识别：$defaultAsrService"
        else -> "识别：${asrServices.first()}"
    }

    fun ttsSummary(): String = when {
        !hasTts -> "无 TTS 引擎"
        defaultTtsService != null -> "TTS：$defaultTtsService"
        else -> "TTS：${ttsServices.first()}"
    }

    /**
     * 语言候选列表（**按优先级**）：口语化中文优先，再退到标准标签，最后交给引擎自己决定。
     * 有些 OEM 引擎只认 zh-Hans-CN，有些只认 cmn-Hans-CN，还有的传空串反而最稳。
     */
    fun languageCandidates(): List<String> {
        val simp = listOf("zh-CN", "zh-Hans-CN", "cmn-Hans-CN", "")
        val trad = listOf("zh-TW", "zh-Hant-TW", "cmn-Hant-TW", "zh-CN", "")
        return if (Locale.getDefault().country in setOf("TW", "HK", "MO")) trad else simp
    }

    override fun toString(): String =
        "VoiceCapabilities(api=$sdkInt, asr=$asrServices, tts=$ttsServices, lowRam=$isLowRam)"

    companion object {
        private const val TAG = "DshVoiceCaps"

        fun detect(ctx: Context): VoiceCapabilities {
            val pm = ctx.packageManager
            val asr = query(pm, Intent(RecognitionService.SERVICE_INTERFACE))
            val tts = query(pm, Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE))
            val defaultAsr = runCatching {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver, "voice_recognition_service"
                )
            }.getOrNull()
            val defaultTts = runCatching {
                android.provider.Settings.Secure.getString(
                    ctx.contentResolver, "tts_default_synth"
                )
            }.getOrNull()
            val lowRam = runCatching {
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
            }.getOrDefault(false)
            val emulator = Build.FINGERPRINT.startsWith("generic") ||
                Build.MODEL.contains("Emulator", true) || Build.HARDWARE.contains("goldfish")

            val caps = VoiceCapabilities(
                sdkInt = Build.VERSION.SDK_INT,
                asrServices = asr,
                ttsServices = tts,
                defaultAsrService = defaultAsr,
                defaultTtsService = defaultTts,
                prefersChinese = Locale.getDefault().language.startsWith("zh"),
                isLowRam = lowRam,
                isEmulator = emulator,
            )
            Log.i(TAG, caps.toString())
            return caps
        }

        private fun query(pm: PackageManager, intent: Intent): List<String> = try {
            pm.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo?.let { si -> "${si.packageName}/${si.name}" } }.distinct()
        } catch (e: Throwable) {
            Log.w(TAG, "query failed: ${e.message}")
            emptyList()
        }

        /** 系统识别是否真的可用（更严格：会走一次系统检查） */
        fun canRecognize(ctx: Context): Boolean = try {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        } catch (e: Throwable) {
            false
        }
    }
}
