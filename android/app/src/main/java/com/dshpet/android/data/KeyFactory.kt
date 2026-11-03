package com.dshpet.android.data

import com.dshpet.android.BuildConfig

/**
 * **Key 工厂**：全 App 取 API Key 的唯一入口。
 *
 * 三层优先级（从高到低）：
 *  1. **数据库**（DataStore）：用户在设置页填的 key —— 朋友装的是"无内置 Key"版本时唯一来源；
 *  2. **构建期配置文件**：`android/keys.properties`（不进版本库）→ Gradle 注入 [BuildConfig]；
 *  3. **空串**：没有任何 key，调用方自己提示用户去填。
 *
 * 为什么不直接把 `BuildConfig.XXX` 散落到各处：
 *  - key 的"有没有配好"是个业务判断（要不要提示、要不要降级到离线话术），
 *    散落各处就会每处都要写一遍 `ifBlank { … }`，很容易漏；
 *  - 将来加第三个 key（比如另一个 TTS/视觉服务）时，只改这一个文件 + 配置文件；
 *  - 送人版（`-Pdsh.noKey`）把所有默认值都变成空串，**代码一行不用改**。
 *
 * 用法：
 * ```
 * val keys = KeyFactory.resolve(cfg)          // 需要挂起（读 DataStore）
 * keys.deepseek.usable                        // 有没有可用 key
 * KeyFactory.defaults().deepseek              // 只要构建期内置值（同步）
 * ```
 */
object KeyFactory {

    /**
     * 一组 key。用 `value class`/`data class` 包起来，避免到处传 `String` 时传错顺序
     * （deepseek 和 doubao 都是长串，混了很难查）。
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
        /** 对话能不能用 */
        val chatReady: Boolean get() = hasDeepseek
        /** 语音能不能用（合成 + 对话都要 key） */
        // 语音只要"有一个能出声的服务商"就算就绪：豆包 或 Breeze（系统 TTS 不需要 key）
        val voiceReady: Boolean get() = hasDeepseek && (hasDoubao || hasBreeze)
    }

    /** 构建期内置值（来自 `keys.properties`；送人版全空）。同步、纯函数。 */
    fun defaults(): Keys = Keys(
        deepseek = BuildConfig.DEFAULT_DEEPSEEK_KEY,
        doubao = BuildConfig.DEFAULT_DOUBAO_KEY,
        doubaoAppId = BuildConfig.DEFAULT_DOUBAO_APPID,
        breeze = BuildConfig.DEFAULT_BREEZE_KEY,
    )

    /** 这份安装包有没有内置对话 key（设置页据此显示"请填 Key"提示） */
    val hasBuiltInDeepseek: Boolean get() = defaults().hasDeepseek

    /**
     * 数据库优先、内置兜底。
     *
     * 注意：用户在设置里**清空**了 key（存成空串）时，这里会回落到内置值 ——
     * 这是原有的行为（`chatApiKey()` 注释里写过"存了空串也回退"），
     * 因为"清空"在这个界面上的语义是"恢复默认"，不是"禁用"。
     */
    suspend fun resolve(cfg: PetConfig): Keys {
        val d = defaults()
        return Keys(
            deepseek = cfg.chatApiKeyRaw().ifBlank { d.deepseek },
            doubao = cfg.doubaoApiKeyRaw().ifBlank { d.doubao },
            doubaoAppId = d.doubaoAppId,
            breeze = d.breeze,
        )
    }

    /** 只要对话 key（最常用的快捷方法） */
    suspend fun deepseek(cfg: PetConfig): String = resolve(cfg).deepseek

    /** 只要语音 key（豆包） */
    suspend fun doubao(cfg: PetConfig): String = resolve(cfg).doubao

    /** 只要 Breeze key */
    suspend fun breeze(cfg: PetConfig): String = resolve(cfg).breeze
}
