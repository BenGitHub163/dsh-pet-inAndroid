package com.dshpet.android.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dshpet.android.voice.providers.SystemTtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * **测试专用**广播接收器：验证"合成 → 播放"整条链路。
 *
 *   # 1) 系统 TTS（不用 key，先验证播放链路）
 *   adb shell am broadcast -a com.dshpet.android.VOICE_TEST \
 *       --es provider system --es text "今天也要认真工作呀。"
 *
 *   # 2) 豆包
 *   adb shell am broadcast -a com.dshpet.android.VOICE_TEST \
 *       --es provider volcengine --es apiKey ak-xxxx --es resourceId seed-tts-2.0 \
 *       --es voiceId zh_female_sajiaoxuemei_uranus_bigtts --es text "今天也要认真工作呀。"
 *
 *   # 3) 日志
 *   adb logcat -s DshVoice
 *
 * ⚠️ 开发自测用，正式发版前应删除（manifest 里 exported=true）。
 */
class VoiceTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val providerId = intent.getStringExtra("provider") ?: TtsEngines.ID_SYSTEM
        val text = intent.getStringExtra("text") ?: "今天也要认真工作呀。"
        val voiceId = intent.getStringExtra("voiceId") ?: DEFAULT_VOICE
        val extraKey = intent.getStringExtra("apiKey").orEmpty()
        if (extraKey.isNotBlank()) {
            if (providerId == TtsEngines.ID_BREEZE) {
                com.dshpet.android.data.KeyFactory.publish(breeze = extraKey)
            } else {
                com.dshpet.android.data.KeyFactory.publish(doubao = extraKey)
            }
        }
        val cfg = TtsConfig(
            providerId = providerId,
            baseUrl = intent.getStringExtra("baseUrl").orEmpty(),
            path = intent.getStringExtra("path").orEmpty(),
            resourceId = intent.getStringExtra("resourceId").orEmpty(),
            appId = intent.getStringExtra("appId").orEmpty(),
            accessKey = intent.getStringExtra("accessKey").orEmpty(),
            extra = mapOf(
                "format" to (intent.getStringExtra("format") ?: "pcm"),
                "sample_rate" to intent.getIntExtra("sampleRate", 24000).toString(),
            ),
        )
        val voice = VoiceProfile(
            voiceId = voiceId,
            speed = intent.getFloatExtra("speed", 1.0f),
            emotion = intent.getStringExtra("emotion"),
        )

        scope.launch {
            val t0 = System.currentTimeMillis()
            val sink = AudioSink(appCtx)
            var pcmBytes = 0
            var chunks = 0
            var provider: TtsProvider? = null
            try {
                provider = TtsEngines.create(appCtx, cfg)
                Log.i(TAG, "TEST start provider=${provider.id} configured=${provider.isConfigured()} text=$text")
                provider.synthesize(text, voice) { chunk ->
                    chunks++
                    chunk.pcm?.let { pcm ->
                        if (pcmBytes == 0) sink.begin(chunk.sampleRate)
                        sink.writePcm(pcm)
                        pcmBytes += pcm.size
                        if (chunks == 1) Log.i(TAG, "首包 ${System.currentTimeMillis() - t0}ms, ${pcm.size} bytes")
                    }
                    chunk.encoded?.let { enc ->
                        Log.i(TAG, "收到编码音频 ${enc.size} bytes（临时文件播放）")
                        sink.playEncoded(enc)
                    }
                }
                if (pcmBytes > 0) {
                    Thread.sleep(AudioSink.pcmDurationMs(pcmBytes, 24000).coerceAtMost(20000))
                } else {
                    sink.awaitDone()
                }
                Log.i(TAG, "TEST OK chunks=$chunks pcmBytes=$pcmBytes cost=${System.currentTimeMillis() - t0}ms")
            } catch (e: Throwable) {
                Log.e(TAG, "TEST FAIL ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                sink.release()
                (provider as? SystemTtsProvider)?.release()
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DshVoice"
        private const val DEFAULT_VOICE = "zh_female_sajiaoxuemei_uranus_bigtts"
    }
}
