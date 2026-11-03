package com.dshpet.android.voice.providers

import com.dshpet.android.api.OpenAiApi
import com.dshpet.android.api.TtsApi
import com.dshpet.android.voice.AudioChunk
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.TtsEngines
import com.dshpet.android.voice.TtsError
import com.dshpet.android.voice.TtsProvider
import com.dshpet.android.voice.VoiceProfile

/**
 * OpenAI 兼容的 `/v1/audio/speech` 通用适配器。
 *
 * 一次实现，能接：
 *   - OpenAI TTS
 *   - 自建 GPT-SoVITS / IndexTTS2 / CosyVoice / fish-speech（写个薄封装即可）
 *   - openedai-speech、各种中转站
 *   - MiniMax 等提供兼容层的平台
 *
 * 报文在 [OpenAiApi]，HTTP 在 [TtsApi]。本类只剩三件事：
 * 配没配好、把调用转给 [TtsApi]、把 cancel 转给在途 Call。
 * **这里不该再出现 `Request.Builder` / `newCall` / `Bearer`。**
 */
class OpenAiCompatTtsProvider(private val cfg: TtsConfig) : TtsProvider {

    override val id: String get() = TtsEngines.ID_OPENAI
    override val displayName: String get() = cfg.extra["display_name"] ?: "OpenAI 兼容 TTS"

    override fun isConfigured(): Boolean = cfg.baseUrl.isNotBlank()

    /** HTTP 与错误映射已重构进 api/TtsApi；这里只剩"这家服务商怎么编解码"的委托 */
    private val api = TtsApi(OpenAiApi())

    override suspend fun synthesize(text: String, voice: VoiceProfile, onChunk: (AudioChunk) -> Unit) {
        if (!isConfigured()) throw TtsError.NotConfigured()
        api.synthesize(text, voice, cfg, onChunk)
    }

    override fun cancel() = api.cancel()
}
