package com.dshpet.android.voice

import android.content.Context
import com.dshpet.android.api.BreezeApi
import com.dshpet.android.api.DoubaoApi
import com.dshpet.android.api.OpenAiApi
import com.dshpet.android.api.TtsApi
import com.dshpet.android.api.TtsCodec
import com.dshpet.android.voice.providers.SystemTtsProvider

/**
 * TTS 注册表：云端厂家走 [TtsApi]，系统 TTS 留在本模块。
 */
object TtsEngines {

    const val ID_SYSTEM = "system"
    const val ID_VOLCENGINE = "volcengine"
    const val ID_OPENAI = "openai"
    const val ID_BREEZE = "breeze"

    val catalog: List<Pair<String, String>> = listOf(
        ID_VOLCENGINE to "豆包语音合成 2.0（火山引擎）",
        ID_BREEZE to "Breeze Blue（国外 TTS）",
        ID_OPENAI to "OpenAI 兼容 / 自建服务",
        ID_SYSTEM to "系统语音合成（兜底）",
    )

    @com.dshpet.android.util.TraceLog(module = "TTS", level = "D")
    fun create(ctx: Context, cfg: TtsConfig): TtsProvider = when (cfg.providerId) {
        ID_VOLCENGINE -> TtsApi(DoubaoApi(), cfg)
        ID_OPENAI -> TtsApi(OpenAiApi(), cfg)
        ID_BREEZE -> TtsApi(BreezeApi, cfg)
        else -> SystemTtsProvider(ctx)
    }

    fun codec(providerId: String): TtsCodec? = when (providerId) {
        ID_VOLCENGINE -> DoubaoApi()
        ID_OPENAI -> OpenAiApi()
        ID_BREEZE -> BreezeApi
        else -> null
    }

    fun createWithFallback(ctx: Context, cfg: TtsConfig): TtsProvider {
        val primary = create(ctx, cfg)
        return if (primary.isConfigured()) primary else SystemTtsProvider(ctx)
    }
}
