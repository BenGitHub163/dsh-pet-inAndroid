package com.dshpet.android.voice.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    // ---------------- 语音服务商 & 音色参数（「语音 API」二级页） ----------------

    /** 语音服务商：`volcengine`（豆包/火山）| `breeze`（Breeze Blue）| `system`（系统 TTS） */
    val provider: String = PROVIDER_VOLCENGINE,
    /** 豆包情绪：空串 = 不带情绪；happy/sad/angry/surprised/fear/hate/excited/neutral */
    val emotion: String = "",
    /** 豆包情绪强度 1..5（火山是非线性的） */
    val emotionScale: Int = 4,
    /** 语速 -50..100（0 = 正常，100 ≈ 2 倍） */
    val speechRate: Int = 0,
    /** 音量 -50..100（0 = 正常） */
    val loudnessRate: Int = 0,
    /** Breeze Blue API Key（brz_ 开头） */
    val breezeKey: String = "",
    /** Breeze Blue 音色 ID（voc_ 开头） */
    val breezeVoiceId: String = "",
) {
    companion object {
        const val DEFAULT_VOICE = "zh_female_sajiaoxuemei_uranus_bigtts"

        const val PROVIDER_VOLCENGINE = "volcengine"
        const val PROVIDER_BREEZE = "breeze"
        const val PROVIDER_SYSTEM = "system"

        fun providerName(id: String): String = when (id) {
            PROVIDER_VOLCENGINE -> "豆包（火山引擎）"
            PROVIDER_BREEZE -> "Breeze Blue"
            PROVIDER_SYSTEM -> "系统 TTS"
            else -> id
        }
    }
}

class VoiceSettingsStore(private val ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    /**
     * **唯一的数据源（响应式）**：设置页改一下，桌宠那边的 [com.dshpet.android.voice.core.VoiceLoop]
     * 立刻收到，不需要"重启语音/重进页面"。
     *
     * 联动与解耦：
     *  - 联动 = 所有消费方（VoiceLoop / 设置页摘要 / 试听）都订阅这条 flow；
     *  - 解耦 = 谁都不直接调用谁，只依赖 flow 与 [save]。
     */
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<VoiceSettings> = _flow.asStateFlow()

    init {
        // 别的进程/旧代码直接改 prefs 时也能同步（SharedPreferences 是进程内广播）
        prefs.registerOnSharedPreferenceChangeListener { _, _ ->
            runCatching { _flow.value = load() }
        }
    }

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
            provider = prefs.getString(KEY_PROVIDER, d.provider) ?: d.provider,
            emotion = prefs.getString(KEY_EMOTION, d.emotion) ?: d.emotion,
            emotionScale = prefs.getInt(KEY_EMOTION_SCALE, d.emotionScale),
            speechRate = prefs.getInt(KEY_SPEECH_RATE, d.speechRate),
            loudnessRate = prefs.getInt(KEY_LOUDNESS_RATE, d.loudnessRate),
            breezeKey = prefs.getString(KEY_BREEZE_KEY, d.breezeKey) ?: d.breezeKey,
            breezeVoiceId = prefs.getString(KEY_BREEZE_VOICE, d.breezeVoiceId) ?: d.breezeVoiceId,
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
            .putString(KEY_PROVIDER, s.provider)
            .putString(KEY_EMOTION, s.emotion)
            .putInt(KEY_EMOTION_SCALE, s.emotionScale)
            .putInt(KEY_SPEECH_RATE, s.speechRate)
            .putInt(KEY_LOUDNESS_RATE, s.loudnessRate)
            .putString(KEY_BREEZE_KEY, s.breezeKey)
            .putString(KEY_BREEZE_VOICE, s.breezeVoiceId)
            .apply()
        // 立刻推给订阅者（apply() 是异步落盘，listener 可能晚一拍）
        _flow.value = s
        // 老代码（QuickTts / API 测试 / VoiceRuntime 的兜底）读的是 PetConfig，这里保持一致
        SYNC_SCOPE.launch {
            runCatching {
                val pet = com.dshpet.android.data.PetConfig.get(appCtx)
                pet.setDoubaoApiKey(s.doubaoKey)
                pet.setDoubaoResourceId(s.resourceId)
                pet.setDoubaoVoiceId(s.voiceId)
            }
        }
    }

    companion object {
        @Volatile
        private var inst: VoiceSettingsStore? = null

        /** 进程内单例：UI 和 Service 用同一个实例，才能共享同一条 flow */
        fun get(ctx: Context): VoiceSettingsStore =
            inst ?: synchronized(this) {
                inst ?: VoiceSettingsStore(ctx.applicationContext).also { inst = it }
            }

        private val SYNC_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        const val KEY_PROVIDER = "tts_provider"
        const val KEY_EMOTION = "tts_emotion"
        const val KEY_EMOTION_SCALE = "tts_emotion_scale"
        const val KEY_SPEECH_RATE = "tts_speech_rate"
        const val KEY_LOUDNESS_RATE = "tts_loudness_rate"
        const val KEY_BREEZE_KEY = "breeze_key"
        const val KEY_BREEZE_VOICE = "breeze_voice_id"
    }
}
