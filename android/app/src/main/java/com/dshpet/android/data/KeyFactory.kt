package com.dshpet.android.data

import com.dshpet.android.BuildConfig
import com.dshpet.android.voice.core.VoiceSettings

/**
 * **Key 工厂**：全 App 取 API Key 的唯一入口。
 *
 * 谁负责什么：
 *  - **制造 / 读取**：只经过本类。[api/] 自己调 [current]，业务代码**不要把 key 当参数传入接口**；
 *  - **落盘**：三份 key 都写 [PetConfig]（DataStore）；旧版 Breeze 可能还在 VoiceSettings prefs，[reload] 会迁过来。
 *
 * 优先级（从高到低）：用户填写 → 构建期 `keys.properties` → 空串。
 */
object KeyFactory {

    /**
     * 一组 key。用 data class 包起来，避免到处传 `String` 时把 deepseek / doubao 弄反。
     */
    data class Keys(
        /** DeepSeek 对话（`sk-…`） */
        val deepseek: String = "",
        /** 豆包 / 火山引擎语音 */
        val doubao: String = "",
        /** 豆包 AppID（可选，按 resourceId 模式走时留空） */
        val doubaoAppId: String = "",
        /** Breeze Blue 语音合成（`brz_…`） */
        val breeze: String = "",
    ) {
        val hasDeepseek: Boolean get() = deepseek.isNotBlank()
        val hasDoubao: Boolean get() = doubao.isNotBlank()
        val hasBreeze: Boolean get() = breeze.isNotBlank()
        val chatReady: Boolean get() = hasDeepseek
        val voiceReady: Boolean get() = hasDeepseek && (hasDoubao || hasBreeze)
    }

    @Volatile
    private var snap: Keys = defaults()

    /** 构建期内置值（来自 `keys.properties`；送人版 `-Pdsh.noKey` 全空）。 */
    fun defaults(): Keys = Keys(
        deepseek = BuildConfig.DEFAULT_DEEPSEEK_KEY,
        doubao = BuildConfig.DEFAULT_DOUBAO_KEY,
        doubaoAppId = BuildConfig.DEFAULT_DOUBAO_APPID,
        breeze = BuildConfig.DEFAULT_BREEZE_KEY,
    )

    /** 当前生效的 key。api/ 与「是否配好了」的判断都读这个。 */
    fun current(): Keys = snap

    val hasBuiltInDeepseek: Boolean get() = defaults().hasDeepseek

    /**
     * 写入内存快照。空串回落到构建期内置值（设置页「清空」= 恢复默认，不是禁用）。
     */
    fun publish(keys: Keys) {
        val d = defaults()
        snap = Keys(
            deepseek = keys.deepseek.ifBlank { d.deepseek },
            doubao = keys.doubao.ifBlank { d.doubao },
            doubaoAppId = keys.doubaoAppId.ifBlank { d.doubaoAppId },
            breeze = keys.breeze.ifBlank { d.breeze },
        )
    }

    fun publish(
        deepseek: String? = null,
        doubao: String? = null,
        doubaoAppId: String? = null,
        breeze: String? = null,
    ) {
        val c = current()
        publish(
            Keys(
                deepseek = deepseek ?: c.deepseek,
                doubao = doubao ?: c.doubao,
                doubaoAppId = doubaoAppId ?: c.doubaoAppId,
                breeze = breeze ?: c.breeze,
            ),
        )
    }

    /**
     * 从 DataStore 重建快照。[voice] 仅作旧版 prefs 迁移（DataStore 为空时才读）。
     */
    suspend fun reload(cfg: PetConfig, voice: VoiceSettings? = null) {
        val d = defaults()
        val c = current()
        publish(
            Keys(
                deepseek = cfg.chatApiKeyRaw().ifBlank { voice?.deepseekKey ?: c.deepseek }.ifBlank { d.deepseek },
                doubao = cfg.doubaoApiKeyRaw().ifBlank { voice?.doubaoKey ?: c.doubao }.ifBlank { d.doubao },
                doubaoAppId = d.doubaoAppId,
                breeze = cfg.breezeApiKeyRaw().ifBlank { voice?.breezeKey ?: c.breeze }.ifBlank { d.breeze },
            ),
        )
    }

    suspend fun resolve(cfg: PetConfig): Keys {
        reload(cfg)
        return current()
    }

    suspend fun deepseek(cfg: PetConfig): String = resolve(cfg).deepseek
    suspend fun doubao(cfg: PetConfig): String = resolve(cfg).doubao
    suspend fun breeze(cfg: PetConfig): String = resolve(cfg).breeze
}
