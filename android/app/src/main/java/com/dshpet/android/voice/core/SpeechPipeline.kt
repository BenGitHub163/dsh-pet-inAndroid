package com.dshpet.android.voice.core

import android.util.Log
import com.dshpet.android.voice.TtsError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 播放流水线（**生产者/消费者 + 预取**）——解决"逗号/句号处停顿太久"。
 *
 * 老做法：合成一句 → 播完 → 再合成下一句。
 * 结果：每句之间都要等一次 TTS 请求往返（~600ms），听感就是频繁卡顿。
 *
 * 新做法：
 *   生产者协程：不停地合成后面的句子，塞进容量 2 的缓冲（= 预取 2 句）
 *   消费者（当前协程）：把拿到的 PCM **连续写进同一个 AudioTrack**
 * 于是句子之间只剩我们主动插入的 30~90ms 静音，而不是 600ms 的网络等待。
 *
 * 另一个细节：句子边界用 `writeSilence` 补静音，而不是 pause/flush，
 * 既避免 AudioTrack 状态切换的开销，也不会在句间留下突兀的空白。
 */
class SpeechPipeline(
    private val scope: CoroutineScope,
    private val player: SpeechPlayer,
) {

    /**
     * @param segments 文本句通道（由 SentenceSplitter 产出）
     * @param synthesize 合成一句：把 PCM 通过 onPcm 回吐（编码流请自行播放）
     *        onPcm 的第二个参数是该块的真实采样率，播放层据此校正 AudioTrack
     * @param onSegment 每句开始播出时回调 (句子, 序号)
     */
    suspend fun run(
        segments: ReceiveChannel<String>,
        session: Long,
        synthesize: suspend (text: String, onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit) -> Unit,
        onSegment: (text: String, index: Int) -> Unit = { _, _ -> },
    ) = kotlinx.coroutines.coroutineScope {
        val audio = Channel<Rendered>(capacity = PREFETCH)
        val producer = launch(Dispatchers.IO) {
            try {
                for (text in segments) {
                    if (!player.isCurrent(session)) break          // 已被打断，别再花钱合成
                    val buf = ByteArrayOutputStream()
                    var rate = SpeechPlayer.DEFAULT_RATE
                    try {
                        synthesize(text) { pcm, chunkRate ->
                            if (chunkRate > 0) rate = chunkRate
                            buf.write(pcm)
                        }
                    } catch (e: TtsError.Cancelled) {
                        break                                          // 用户停止：安静收尾，不当作失败
                    } catch (e: Throwable) {
                        Log.w(TAG, "synth failed, skip segment: ${e.message}")
                        continue
                    }
                    if (buf.size() > 0) audio.send(Rendered(text, buf.toByteArray(), rate))
                }
            } finally {
                audio.close()
            }
        }

        var started = false
        var index = 0
        var lastEnd = 0L
        val t0 = System.currentTimeMillis()
        try {
            for (rendered in audio) {
                if (!player.isCurrent(session)) break               // 打断：立刻停
                val text = rendered.text
                val pcm = rendered.pcm
                if (!started) {
                    started = true
                }
                val startAt = System.currentTimeMillis() - t0
                val gap = if (index == 0) 0L else (startAt - lastEnd).coerceAtLeast(0L)
                onSegment(text, index)
                player.prepareRate(rendered.sampleRate, session)     // 采样率跟着实际音频走
                player.writeFor(session, pcm)
                val tail = text.trimEnd().lastOrNull()
                val silence = if (tail != null && tail in SENTENCE_TAIL) SILENCE_SENTENCE_MS else SILENCE_CLAUSE_MS
                player.writeSilence(silence, session)
                lastEnd = System.currentTimeMillis() - t0
                Log.i(
                    TAG,
                    "segment#$index start=+${startAt}ms gap=${gap}ms dur=${pcm.size / 48}ms " +
                        "rate=${rendered.sampleRate} silence=${silence}ms: ${text.take(20)}"
                )
                index++
            }
        } finally {
            if (started && player.isCurrent(session)) {
                player.awaitDrain(s = session)
                player.finishSegment()
            }
            producer.cancel()
        }
    }

    /** 一句合成结果：文本 + PCM + 该 PCM 的采样率 */
    private data class Rendered(val text: String, val pcm: ByteArray, val sampleRate: Int)

    private companion object {
        const val TAG = "DshVoicePipeline"
        const val PREFETCH = 2
        const val SILENCE_SENTENCE_MS = 150
        const val SILENCE_CLAUSE_MS = 80
        val SENTENCE_TAIL = charArrayOf('。', '！', '？', '!', '?', '…', '~', '～')
    }
}
