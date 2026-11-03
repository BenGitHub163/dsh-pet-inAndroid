package com.dshpet.android.voice.core

import android.content.Context
import com.dshpet.android.data.PetConfig

/**
 * 语音对话的**唯一入口**：测试页和桌宠三击都走这里，避免两套装配逻辑分叉。
 *
 * 装配顺序：
 *   1) VoiceSettingsStore（缓存：音色、资源、隐私档位）
 *   2) PetConfig（权威：DS Key / 豆包 Key 覆盖缓存）
 *   3) Key 缺失直接返回 null（调用方负责提示用户）
 */
object VoiceRuntime {

    @Volatile
    private var loop: VoiceLoop? = null

    val current: VoiceLoop? get() = loop
    val running: Boolean get() = loop != null && VoiceLoop.running

    /** 组装最终配置：DataStore 为准，缓存兜底 */
    fun buildSettings(ctx: Context): VoiceSettings {
        // **以 SharedPreferences 里的 VoiceSettings 为准**（唯一数据源，UI 改完立刻生效）；
        // PetConfig 只做老版本迁移兜底：store 里为空时才用 PetConfig 的值。
        var st = VoiceSettingsStore.get(ctx).load()
        val pet = PetConfig.get(ctx.applicationContext)
        val dk = runCatching { kotlinx.coroutines.runBlocking { pet.chatApiKey() } }.getOrDefault("")
        val bk = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoApiKey() } }.getOrDefault("")
        val rid = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoResourceId() } }.getOrDefault("")
        val vid = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoVoiceId() } }.getOrDefault("")
        if (st.deepseekKey.isBlank() && dk.isNotBlank()) st = st.copy(deepseekKey = dk)
        if (st.doubaoKey.isBlank() && bk.isNotBlank()) st = st.copy(doubaoKey = bk)
        if (st.resourceId.isBlank() && rid.isNotBlank()) st = st.copy(resourceId = rid)
        if (st.voiceId.isBlank() && vid.isNotBlank()) st = st.copy(voiceId = vid)
        return st
    }

    /** 启动（幂等）：已在跑就返回现有实例 */
    @com.dshpet.android.util.TraceLog(module = "VOICE", level = "D")
    fun ensureStarted(ctx: Context): VoiceLoop? {
        loop?.let { if (VoiceLoop.running) return it }
        val st = buildSettings(ctx)
        if (st.doubaoKey.isBlank() || st.deepseekKey.isBlank()) return null
        val l = VoiceLoop(ctx.applicationContext)
        loop = l
        l.start(st)
        return l
    }

    fun stop() {
        loop?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        loop = null
    }
}
