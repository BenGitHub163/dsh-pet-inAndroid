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
        var st = VoiceSettingsStore(ctx).load()
        val pet = PetConfig.get(ctx.applicationContext)
        val dk = runCatching { kotlinx.coroutines.runBlocking { pet.chatApiKey() } }.getOrDefault("")
        val bk = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoApiKey() } }.getOrDefault("")
        val rid = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoResourceId() } }.getOrDefault("")
        val vid = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoVoiceId() } }.getOrDefault("")
        if (dk.isNotBlank()) st = st.copy(deepseekKey = dk)
        if (bk.isNotBlank()) st = st.copy(doubaoKey = bk)
        if (rid.isNotBlank()) st = st.copy(resourceId = rid)
        if (vid.isNotBlank()) st = st.copy(voiceId = vid)
        return st
    }

    /** 启动（幂等）：已在跑就返回现有实例 */
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
