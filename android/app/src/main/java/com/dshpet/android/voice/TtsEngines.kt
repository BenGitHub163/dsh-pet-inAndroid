package com.dshpet.android.voice

import android.content.Context
import com.dshpet.android.voice.providers.BreezeTtsProvider
import com.dshpet.android.voice.providers.OpenAiCompatTtsProvider
import com.dshpet.android.voice.providers.SystemTtsProvider
import com.dshpet.android.voice.providers.VolcengineTtsProvider

/**
 * Provider 注册表：**换平台的唯一入口**。
 *
 * 新增一个平台只要三步：
 *   1. 写 `providers/XxxTtsProvider : TtsProvider`（实现 synthesize/cancel/isConfigured）；
 *   2. 在下面 `when` 里加一行；
 *   3. 设置页把一个选项塞进 `voice_provider` 这个 DataStore 键。
 *
 * 上层（SpeechQueue / ReplyReader / 播放器）完全不需要改。
 */
object TtsEngines {

    const val ID_SYSTEM = "system"
    const val ID_VOLCENGINE = "volcengine"
    const val ID_OPENAI = "openai"
    const val ID_BREEZE = "breeze"

    /** 设置页展示用的清单（id -> 名称） */
    val catalog: List<Pair<String, String>> = listOf(
        ID_VOLCENGINE to "豆包语音合成 2.0（火山引擎）",
        ID_BREEZE to "Breeze Blue（国外 TTS）",
        ID_OPENAI to "OpenAI 兼容 / 自建服务",
        ID_SYSTEM to "系统语音合成（兜底）",
    )

    @com.dshpet.android.util.TraceLog(module = "TTS", level = "D")
    fun create(ctx: Context, cfg: TtsConfig): TtsProvider = when (cfg.providerId) {
        ID_VOLCENGINE -> VolcengineTtsProvider(cfg)
        ID_OPENAI -> OpenAiCompatTtsProvider(cfg)
        ID_BREEZE -> BreezeTtsProvider(cfg)
        else -> SystemTtsProvider(ctx)
    }

    /**
     * 按 id 造**编解码器**（不含 HTTP）：
     * 试听台这类"只发一次请求、自己管 AudioTrack"的地方，直接用
     * `TtsApi(codec(id)!!).synthesize(...)`，不必再造一个 provider。
     * 系统 TTS 没有编解码器，返回 null（它走 Android 的 TextToSpeech，没有报文）。
     */
    fun codec(providerId: String): com.dshpet.android.api.TtsCodec? = when (providerId) {
        ID_VOLCENGINE -> com.dshpet.android.api.DoubaoCodec()
        ID_OPENAI -> com.dshpet.android.api.OpenAiCodec()
        ID_BREEZE -> com.dshpet.android.api.BreezeCodec()
        else -> null
    }

    /**
     * 带降级的创建：provider 不可用（没配置/初始化失败）时自动退回系统 TTS。
     * 上层只需要捕获 [TtsError]，不需要自己判断 provider 是否可用。
     */
    fun createWithFallback(ctx: Context, cfg: TtsConfig): TtsProvider {
        val primary = create(ctx, cfg)
        return if (primary.isConfigured()) primary else SystemTtsProvider(ctx)
    }
}
