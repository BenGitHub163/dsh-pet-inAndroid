package com.dshpet.android.voice.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.delay

/**
 * 播放器（**对象复用 + 精确排空**）。
 *
 * 相比"每句 new 一个 AudioTrack"：
 *  - 复用同一个 AudioTrack，省掉每句 30~80ms 的创建/预热开销；
 *  - 用 `playbackHeadPosition` 判断"真的播完了"，而不是 sleep(估算时长)；
 *  - 统一管理音频焦点（播报时压低别人的音乐，结束归还）。
 *
 * 线程模型：所有方法都在同一个协程（IO）里调用，不做内部同步。
 */
class SpeechPlayer(private val ctx: Context) {

    private var track: AudioTrack? = null
    private var sampleRate = DEFAULT_RATE
    private var startHead = 0
    private var framesWritten = 0
    private var focusRequest: AudioFocusRequest? = null

    /**
     * 会话令牌（**Generation / Epoch 模式**）：
     * 每次开口 +1，旧的播放任务拿到旧令牌后所有写入都会被丢弃。
     * 这样"被打断后还在播上一条"就不可能发生——不需要任何锁。
     */
    private var session = 0L

    fun currentSession(): Long = session

    fun isCurrent(s: Long): Boolean = s == session

    /** 开始一次新的说话（打断上一次，并丢掉上一轮缓冲里残留的音频） */
    fun startSession(rate: Int = DEFAULT_RATE): Long {
        session++
        ensureTrack(rate)
        runCatching { track?.pause(); track?.flush() }
        framesWritten = 0
        startHead = track?.playbackHeadPosition ?: 0
        requestFocus()
        runCatching { track?.play() }
        return session
    }

    /** 打断：令牌失效 + 立刻静音 + 归还焦点 */
    fun abort() {
        session++
        runCatching { track?.pause(); track?.flush() }
        framesWritten = 0
        abandonFocus()
    }

    private val am: AudioManager? = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 开始一段播放（同一段内可多次 write） */
    fun begin(rate: Int = DEFAULT_RATE) {
        ensureTrack(rate)
        framesWritten = 0
        startHead = track?.playbackHeadPosition ?: 0
        requestFocus()
        runCatching { track?.play() }
    }

    fun write(pcm: ByteArray) {
        writeFor(session, pcm)
    }

    /**
     * 让当前会话按 [rate] 播放（在写入第一块 PCM 之前调用）。
     *
     * 为什么需要它：不同 provider / 不同设置的采样率不一样（豆包支持 16k/24k/48k），
     * 而 AudioTrack 的采样率在创建时就固定了。旧实现一律按 24000 建轨，于是把设置改成
     * 16k/48k 后会**变调变慢/变快**。这里在同一会话内首次遇到不同采样率时重建轨道，
     * 并重置播放头基准——只在第一块音频之前触发，不会打断正在播的内容。
     */
    fun prepareRate(rate: Int, s: Long = session) {
        if (s != session) return
        if (rate <= 0 || rate == sampleRate) return
        Log.i(TAG, "sampleRate switch $sampleRate -> $rate")
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null                      // 强制 ensureTrack 重建
        ensureTrack(rate)
        framesWritten = 0
        startHead = track?.playbackHeadPosition ?: 0
        runCatching { track?.play() }
    }

    /** 带令牌的写入：令牌过期（已被打断）就直接丢弃 */
    fun writeFor(s: Long, pcm: ByteArray) {
        if (s != session) return
        val t = track ?: return
        if (pcm.isEmpty()) return
        try {
            val n = t.write(pcm, 0, pcm.size)
            if (n > 0) framesWritten += n / 2
        } catch (e: Throwable) {
            Log.w(TAG, "write failed: ${e.message}")
        }
    }

    /** 在两句之间插一小段静音（比 pause/flush 自然得多，也避免 AudioTrack 重建） */
    fun writeSilence(ms: Int, s: Long = session) {
        if (ms <= 0) return
        val zeros = ByteArray((sampleRate * 2 * ms) / 1000)
        writeFor(s, zeros)
    }

    /** 等到这段的最后一个采样真正播出（带超时保护） */
    suspend fun awaitDrain(timeoutMs: Long = 60_000, s: Long = session) {
        if (s != session) return
        val t = track ?: return
        val target = framesWritten
        if (target <= 0) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val played = t.playbackHeadPosition - startHead   // int 回绕在这儿天然成立
            if (played >= target) {
                // 多留 120ms：TTS 句尾的弱音（气口/尾音）常被"刚好播完"切掉
                delay(120)
                return
            }
            delay(12)
        }
        Log.w(TAG, "awaitDrain timeout (played=${t.playbackHeadPosition - startHead}, target=$target)")
    }

    /** 一段结束：暂停并清空缓冲，但保留 AudioTrack 实例 */
    fun finishSegment() {
        runCatching { track?.pause(); track?.flush() }
    }

    fun stop() {
        runCatching { track?.pause(); track?.flush() }
        abandonFocus()
    }

    fun release() {
        stop()
        runCatching { track?.release() }
        track = null
    }

    /**
     * 播一段"编码好的音频"（系统 TTS 返回的 WAV / 云端返回的 MP3）。
     * 阻塞到播完；只用于降级通道，正常情况下走 PCM 直推。
     */
    fun playEncodedBlocking(bytes: ByteArray, ext: String = "wav") {
        var file: java.io.File? = null
        var mp: android.media.MediaPlayer? = null
        try {
            file = java.io.File.createTempFile("tts_", ".$ext", ctx.cacheDir).also { it.writeBytes(bytes) }
            mp = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            val deadline = System.currentTimeMillis() + 30_000
            while (mp.isPlaying && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "playEncoded failed: ${e.message}")
        } finally {
            runCatching { mp?.release() }
            runCatching { file?.delete() }
        }
    }

    private fun ensureTrack(rate: Int) {
        val existing = track
        if (existing != null && sampleRate == rate) return
        runCatching { existing?.release() }
        sampleRate = rate
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = if (minBuf > 0) minBuf * 2 else rate / 10
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        Log.i(TAG, "AudioTrack created rate=$rate buf=$buf")
    }

    private fun requestFocus() {
        val mgr = am ?: return
        if (focusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stop()
                }
            }
            .build()
        focusRequest = req
        runCatching { mgr.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val mgr = am ?: return
        focusRequest?.let { runCatching { mgr.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    companion object {
        private const val TAG = "DshVoicePlayer"
        const val DEFAULT_RATE = 24000

        /** 16bit 单声道：每秒字节数 = rate*2 → 毫秒 = bytes/(rate*2)*1000 */
        fun pcmDurationMs(bytes: Int, rate: Int = DEFAULT_RATE): Long =
            if (rate <= 0) 0 else (bytes.toLong() * 1000L) / (rate * 2L)
    }
}
