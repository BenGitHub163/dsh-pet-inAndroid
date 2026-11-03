package com.dshpet.android.voice.providers

import com.dshpet.android.api.DoubaoCodec
import com.dshpet.android.api.TtsApi
import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsEngines
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile

/**
 * 火山引擎「豆包语音合成 2.0」HTTP Chunked/SSE 单向流式。
 *
 * 文档：https://docs.volcengine.com/docs/DoubaoVoice/HTTPChunkedSSEUnidirectionalStreaming-V3
 *
 * 报文（URL / 头 / body / 逐行解 base64）全部在 [DoubaoCodec]，本类只剩三件事：
 * 配没配好、把调用转给 [TtsApi]、把 cancel 转给在途 Call。
 * **这里不该再出现 `Request.Builder` / `newCall` / `X-Api-Key`。**
 *
 * 可配置项（都走 [TtsConfig.extra]）见 [DoubaoCodec] 的类注释。
 */
class VolcengineTtsProvider(private val cfg: TtsConfig) : TtsProvider {

    override val id: String get() = TtsEngines.ID_VOLCENGINE
    override val displayName: String get() = "豆包语音合成 2.0"

    override fun isConfigured(): Boolean =
        cfg.apiKey.isNotBlank() || (cfg.appId.isNotBlank() && cfg.accessKey.isNotBlank())

    /** HTTP 与错误映射已重构进 api/TtsApi；这里只剩"这家服务商怎么编解码"的委托 */
    private val api = TtsApi(DoubaoCodec())

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        if (!isConfigured()) throw TtsError.NotConfigured()
        api.synthesize(text, voice, cfg, onChunk)
    }

    /**
     * 打断。
     *
     * 这里不需要旧版本的 `cancelled` 标记：`TtsApi` 会用 `call.isCanceled()` 把中断映射成
     * [TtsError.Cancelled]，责任链据此终止整条链而不是降级到系统 TTS（否则用户点了停止，
     * 系统语音还会把同一句念一遍）。
     */
    override fun cancel() = api.cancel()
}
