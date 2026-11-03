package com.dshpet.android.voice.providers

import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsEngines
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Breeze Blue TTS（https://docs.breezeblue.ai）。
 *
 * 走文档里的**流式**接口，延迟最低：
 *   `POST /v1/text-to-speech/{voice_id}/stream?output_format=pcm`（域名见 api/BreezeApi）
 *   header `xi-api-key: brz_...`
 *   body   `{"text":"…","model_id":"breeze-tts-2"}`
 *   → 二进制音频流（pcm = 24 kHz / mono / s16le；也可 `output_format=mp3`）
 *
 * 说明：
 *  - 一次 [synthesize] 只处理**一句话**（分句是上层的事，和豆包实现一致）；
 *  - [cancel] 会直接掐掉在途 HTTP 请求（用户打断/停止时不再计费）；
 *  - `cfg.extra` 支持：`format`(pcm/mp3)、`sample_rate`、`model_id`、`language_code`。
 */
class BreezeTtsProvider(private val cfg: TtsConfig) : TtsProvider {

    override val id: String get() = TtsEngines.ID_BREEZE
    override val displayName: String get() = "Breeze Blue"

    override fun isConfigured(): Boolean = com.dshpet.android.data.KeyFactory.current().hasBreeze

    /** HTTP 与错误映射已重构进 api/TtsApi；这里只剩"这家服务商怎么编解码"的委托 */
    private val api = com.dshpet.android.api.TtsApi(com.dshpet.android.api.BreezeApi)

    override suspend fun synthesize(
        text: String,
        voice: com.dshpet.android.voice.VoiceProfile,
        onChunk: (com.dshpet.android.voice.AudioChunk) -> Unit,
    ) {
        if (!isConfigured()) throw com.dshpet.android.voice.TtsError.NotConfigured()
        api.synthesize(text, voice, cfg, onChunk)
    }


    override fun cancel() = api.cancel()
}
