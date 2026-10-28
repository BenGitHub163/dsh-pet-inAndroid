package com.dshpet.android.voice.core

import android.content.Context

/**
 * 语音相关的用户配置（**Repository 模式**）。
 *
 * 现在用 SharedPreferences（测试期够用、读写同步、无需协程）；
 * 迁到正式功能时把实现换成 PetConfig 的 DataStore 即可，上层只依赖这个类。
 */
data class VoiceSettings(
    val doubaoKey: String = com.dshpet.android.data.KeyFactory.defaults().doubao,
    val deepseekKey: String = com.dshpet.android.data.KeyFactory.defaults().deepseek,
    val voiceId: String = DEFAULT_VOICE,
    val resourceId: String = "seed-tts-2.0",
    val format: String = "pcm",
    val sampleRate: Int = 24000,
    val locale: String = "zh-CN",
    val autoplay: Boolean = true,
    val historyLimit: Int = 20,
    /**
     * 隐私档位（决定桌宠"能知道多少"）：
     *  0 = 关闭：不提供应用/位置，只给时间和电量
     *  1 = 基础（默认）：允许知道正在用哪个应用、大致城市
     *  2 = 详细：可以结合应用与停留情况主动关心（仍读不到任何应用内容）
     */
    val privacyLevel: Int = 1,
) {
    companion object {
        const val DEFAULT_VOICE = "zh_female_sajiaoxuemei_uranus_bigtts"
    }
}

class VoiceSettingsStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    fun load(): VoiceSettings {
        val d = VoiceSettings()
        return VoiceSettings(
            doubaoKey = prefs.getString(KEY_DOUBAO, d.doubaoKey) ?: d.doubaoKey,
            deepseekKey = prefs.getString(KEY_DEEPSEEK, d.deepseekKey) ?: d.deepseekKey,
            voiceId = prefs.getString(KEY_VOICE, d.voiceId) ?: d.voiceId,
            resourceId = prefs.getString(KEY_RESOURCE, d.resourceId) ?: d.resourceId,
            format = prefs.getString(KEY_FORMAT, d.format) ?: d.format,
            sampleRate = prefs.getInt(KEY_RATE, d.sampleRate),
            locale = prefs.getString(KEY_LOCALE, d.locale) ?: d.locale,
            autoplay = prefs.getBoolean(KEY_AUTOPLAY, d.autoplay),
            historyLimit = prefs.getInt(KEY_HISTORY, d.historyLimit),
            privacyLevel = prefs.getInt(KEY_PRIVACY, d.privacyLevel),
        )
    }

    fun save(s: VoiceSettings) {
        prefs.edit()
            .putString(KEY_DOUBAO, s.doubaoKey)
            .putString(KEY_DEEPSEEK, s.deepseekKey)
            .putString(KEY_VOICE, s.voiceId)
            .putString(KEY_RESOURCE, s.resourceId)
            .putString(KEY_FORMAT, s.format)
            .putInt(KEY_RATE, s.sampleRate)
            .putString(KEY_LOCALE, s.locale)
            .putBoolean(KEY_AUTOPLAY, s.autoplay)
            .putInt(KEY_HISTORY, s.historyLimit)
            .putInt(KEY_PRIVACY, s.privacyLevel)
            .apply()
    }

    private companion object {
        const val KEY_DOUBAO = "doubao_key"
        const val KEY_DEEPSEEK = "deepseek_key"
        const val KEY_VOICE = "voice_id"
        const val KEY_RESOURCE = "resource_id"
        const val KEY_FORMAT = "format"
        const val KEY_RATE = "sample_rate"
        const val KEY_LOCALE = "locale"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_HISTORY = "history_limit"
        const val KEY_PRIVACY = "privacy_level"
    }
}
