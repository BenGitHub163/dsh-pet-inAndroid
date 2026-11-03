package com.dshpet.android.voice.core

import android.content.Context
import com.dshpet.android.data.KeyFactory
import com.dshpet.android.data.PetConfig

/**
 * 语音对话的**唯一入口**：测试页和桌宠三击都走这里，避免两套装配逻辑分叉。
 *
 * Key 一律从 [KeyFactory] 取，不在这里拼、也不往 TTS/Chat 接口里传。
 */
object VoiceRuntime {

    @Volatile
    private var loop: VoiceLoop? = null

    val current: VoiceLoop? get() = loop
    val running: Boolean get() = loop != null && VoiceLoop.running

    /** 组装最终配置：音色等来自 VoiceSettings；Key 只作 UI 回显，真正发请求走 KeyFactory */
    fun buildSettings(ctx: Context): VoiceSettings {
        var st = VoiceSettingsStore.get(ctx).load()
        val pet = PetConfig.get(ctx.applicationContext)
        val rid = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoResourceId() } }.getOrDefault("")
        val vid = runCatching { kotlinx.coroutines.runBlocking { pet.doubaoVoiceId() } }.getOrDefault("")
        if (st.resourceId.isBlank() && rid.isNotBlank()) st = st.copy(resourceId = rid)
        if (st.voiceId.isBlank() && vid.isNotBlank()) st = st.copy(voiceId = vid)
        return st
    }

    /** 启动（幂等）：已在跑就返回现有实例 */
    @com.dshpet.android.util.TraceLog(module = "VOICE", level = "D")
    fun ensureStarted(ctx: Context): VoiceLoop? {
        loop?.let { if (VoiceLoop.running) return it }
        val st = buildSettings(ctx)
        if (!KeyFactory.current().hasDeepseek) return null
        if (!com.dshpet.android.voice.VoiceTtsFactory.isReady(st)) return null
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
