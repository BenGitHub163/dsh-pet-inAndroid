package com.dshpet.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * 音频输出：PCM �?AudioTrack 直推（延迟最低），编码流（WAV/MP3）落临时文件�?MediaPlayer 播�? *
 * 只做"播一�?，队�?预取/打断在上层（SpeechQueue）实现�? */
class AudioSink(private val ctx: Context) {

    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var sampleRate = 24000
    private var tempFile: File? = null

    fun begin(sampleRate: Int = 24000) {
        this.sampleRate = sampleRate
        if (track == null) {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = if (minBuf > 0) minBuf * 2 else sampleRate
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
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track?.play()
        }
    }

    /** 喂一�?PCM�?6bit 单声道） */
    fun writePcm(bytes: ByteArray) {
        val t = track ?: return
        try {
            t.write(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            Log.w(TAG, "writePcm failed: ${e.message}")
        }
    }

    /** 播一段编码音频（WAV/MP3）。阻塞到播完，方便测试�?*/
    fun playEncoded(bytes: ByteArray, ext: String = "wav") {
        val f = File.createTempFile("tts_", ".$ext", ctx.cacheDir)
        f.writeBytes(bytes)
        tempFile = f
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Throwable) {
            Log.w(TAG, "playEncoded failed: ${e.message}")
        }
    }

    /** 等当前播放结束（测试用；生产环境用回�?队列�?*/
    fun awaitDone(timeoutMs: Long = 15000) {
        val mp = player ?: run {
            Thread.sleep(300)
            release()
            return
        }
        val end = System.currentTimeMillis() + timeoutMs
        while (mp.isPlaying && System.currentTimeMillis() < end) {
            Thread.sleep(50)
        }
        release()
    }

    fun release() {
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        tempFile?.delete()
        tempFile = null
    }

    companion object {
        private const val TAG = "DshVoice" // touch 6

        /** 按当前音量算个提示音外的粗略时长（毫秒），用�?AudioTrack 收尾 */
        fun pcmDurationMs(bytes: Int, sampleRate: Int): Long =
            (bytes / 2L).toFloat().div(sampleRate).times(1000).toLong()

        @Suppress("unused")
        fun audioManager(ctx: Context): AudioManager =
            ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
}
