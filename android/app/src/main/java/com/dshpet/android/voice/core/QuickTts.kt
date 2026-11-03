package com.dshpet.android.voice.core

import android.content.Context
import com.dshpet.android.util.AppLog
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.TtsEngines
import com.dshpet.android.voice.VoiceProfile
import com.dshpet.android.voice.providers.VolcengineTtsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「顺口念一句」：用**当前语音设置**（豆包）把一段短文本念出来，播完就结束。
 *
 * 为什么不复用 [VoiceLoop]：那条链路是"听 → 想 → 说"的完整会话（带 ASR、记忆、情绪、打断令牌），
 * 用来念一句自说自话属于杀鸡用牛刀，而且会跟真正的语音对话抢麦克风/音频焦点。
 * 这里只做"合成 → 播放 → 释放"，一次性的。
 *
 * 返回 false 的三种情况（调用方只需要知道"没念成"）：
 *  - 没配豆包 Key；
 *  - 合成失败（网络/鉴权）；
 *  - 没有拿到任何音频。
 */
object QuickTts {

    /** 念一句话；true = 真的播完了 */
    @com.dshpet.android.util.TraceLog(module = "SELFTALK", level = "D")
    suspend fun speak(ctx: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        val t = text.trim()
        if (t.isEmpty()) return@withContext false

        // **和「语音 API」页保持同一份配置**：provider 选谁就用谁（豆包 / Breeze / 系统）
        val settings = runCatching { VoiceRuntime.buildSettings(ctx) }.getOrNull()
            ?: return@withContext false
        if (!com.dshpet.android.voice.VoiceTtsFactory.isReady(settings)) return@withContext false

        val player = SpeechPlayer(ctx)
        var provider: TtsProvider? = null
        try {
            val p = com.dshpet.android.voice.VoiceTtsFactory.create(ctx, settings)
            provider = p
            val rate = if (settings.provider == VoiceSettings.PROVIDER_VOLCENGINE) settings.sampleRate else 24000
            val session = player.startSession(rate)
            var bytes = 0
            p.synthesize(t, com.dshpet.android.voice.VoiceTtsFactory.profileFor(settings)) { chunk ->
                chunk.pcm?.let { pcm ->
                    player.prepareRate(chunk.sampleRate, session)
                    player.writeFor(session, pcm)
                    bytes += pcm.size
                }
                // 编码流（mp3/wav）走临时文件播放，阻塞到播完
                chunk.encoded?.let { enc -> player.playEncodedBlocking(enc) }
            }
            if (bytes > 0) player.awaitDrain(s = session)
            AppLog.log("SELFTALK", "念台词 ${t.length} 字 pcm=${bytes}B ok=${bytes > 0}")
            bytes > 0
        } catch (e: Throwable) {
            AppLog.log("SELFTALK", "念台词失败：${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            runCatching { provider?.cancel() }
            runCatching { player.release() }
        }
    }
}
