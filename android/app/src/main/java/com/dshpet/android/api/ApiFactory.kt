package com.dshpet.android.api

import android.content.Context
import com.dshpet.android.chat.ChatConfigFactory
import com.dshpet.android.chat.SseClient
import com.dshpet.android.data.PetConfig
import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.VoiceProfile
import com.dshpet.android.voice.providers.VolcengineTtsProvider

/**
 * 外部 API 的唯一入口。
 *
 * 历史：这里原来有 `ApiFactory.kt` 和 `ApiFactory2.kt` 两个文件，"2" 只是后来加东西时
 * 不想动老文件留下的后缀，内容其实分属两层。现在合成一个文件、两个对象：
 *
 * | | [ApiFactory] | [ApiTargets] |
 * |---|---|---|
 * | 谁用 | 对话 / 语音 / 自言自语等**业务代码** | 设置页「工具」面板的**界面** |
 * | 产出 | [SseClient.ChatCfg] 这类**拿来就能用**的配置对象 | [ApiTarget] 这类**可渲染的行**（名字 / Key 掩码 / 测试动作） |
 * | 依赖 | 只读配置，纯函数、非挂起 | 还要真的发请求（测试连接、查余额），是 `suspend` |
 *
 * 为什么**不**合成一个类：把"网络测试"和"业务构造"塞进同一个对象后，
 * 语音对话、自言自语这些热路径就得跟着变成 `suspend`（或者被迫持有一个带状态的工厂），
 * 得不偿失。所以保留两个职责清晰的入口，只是不再分成两个文件。
 */
object ApiFactory {

    /** DeepSeek 对话配置（返回值可直接 `.copy(...)` 微调） */
    fun chat(ctx: Context, maxTokens: Int = 2048): SseClient.ChatCfg =
        ChatConfigFactory.build(ctx, maxTokens)
}

/** 一个外部 API 的描述：名字 + Key 摘要 + 「测试连接」动作（可选附加动作） */
data class ApiTarget(
    val id: String,
    val name: String,
    val keyHint: String,
    val test: suspend () -> Result<String>,
    val extraLabel: String? = null,
    val extra: (suspend () -> Result<String>)? = null,
)

/**
 * 设置页「工具」面板的数据源：把用到的外部 API 组装成一组 [ApiTarget]。
 *
 * 面板不再硬编码"测试 DeepSeek"一个按钮，而是遍历这个列表渲染：
 * 每个 API 一行、各自一个「测试」，有附加能力的（如查余额）跟在同一行。以后加 API 只改这里。
 */
object ApiTargets {

    suspend fun build(cfg: PetConfig, appCtx: android.content.Context): List<ApiTarget> {
        val dsKey = cfg.chatApiKey()
        val dbKey = cfg.doubaoApiKey()
        val voice = cfg.doubaoVoiceId()
        val rid = cfg.doubaoResourceId()

        val deepseek = ApiTarget(
            id = "deepseek",
            name = "DeepSeek 对话",
            keyHint = mask(dsKey),
            test = {
                val chat = SseClient.ChatCfg(
                    baseUrl = cfg.chatBaseUrl(),
                    chatPath = cfg.chatPath(),
                    model = cfg.chatModel(),
                    apiKey = dsKey,
                    temperature = cfg.chatTemperature(),
                    maxTokens = cfg.chatMaxTokens(),
                    timeoutSec = cfg.chatTimeout(),
                    verifySsl = cfg.chatVerifySsl(),
                )
                runCatching {
                    kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                        SseClient.testConnection(chat) { r ->
                            if (cont.isActive) {
                                r.fold(
                                    { ok -> cont.resumeWith(Result.success(ok)) },
                                    { e -> cont.resumeWith(Result.failure(e)) },
                                )
                            }
                        }
                    }
                }
            },
            // 余额查询跟「测试」同一行（以前是工具区里一个独立按钮，看不出到底查的是谁）
            extraLabel = "余额",
            extra = {
                val key = cfg.chatApiKey()
                if (key.isBlank()) {
                    Result.failure(IllegalStateException("未配置 API Key"))
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.dshpet.android.pet.Balance.fetch(
                            baseUrl = cfg.chatBaseUrl(),
                            apiKey = key,
                            verifySsl = cfg.chatVerifySsl(),
                            timeoutSec = cfg.chatTimeout(),
                        )
                    }.map { "DeepSeek 账户余额：$it" }
                }
            },
        )

        // 语音合成按「语音 API」页里当前选的服务商来测（豆包 / Breeze / 系统），
        // 不再写死豆包，也不再读 PetConfig 里的旧 key。
        val vs = com.dshpet.android.voice.core.VoiceSettingsStore.get(appCtx).load()
        val ttsKey = if (vs.provider == com.dshpet.android.voice.core.VoiceSettings.PROVIDER_BREEZE) vs.breezeKey else vs.doubaoKey
        val tts = ApiTarget(
            id = "tts",
            name = "语音合成（" + com.dshpet.android.voice.core.VoiceSettings.providerName(vs.provider) + "）",
            keyHint = mask(ttsKey),
            test = {
                runCatching {
                    val p = com.dshpet.android.voice.VoiceTtsFactory.create(appCtx, vs)
                    var bytes = 0
                    p.synthesize("你好呀", com.dshpet.android.voice.VoiceTtsFactory.profileFor(vs)) {
                        bytes += it.pcm?.size ?: 0
                    }
                    if (bytes <= 0) error("没有返回音频") else "合成正常（" + bytes / 1024 + " KB）"
                }
            },
        )

        return listOf(deepseek, tts)
    }

    private fun mask(key: String): String = when {
        key.isBlank() -> "未配置 Key"
        key.length <= 8 -> "已配置"
        else -> key.take(4) + "…" + key.takeLast(4)
    }
}
