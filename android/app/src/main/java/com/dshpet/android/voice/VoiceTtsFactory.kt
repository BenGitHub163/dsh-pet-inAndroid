package com.dshpet.android.voice

import android.content.Context
import com.dshpet.android.voice.core.VoiceSettings
import com.dshpet.android.voice.core.VoiceSettingsStore

/**
 * **语音服务商工厂**：把 [VoiceSettings] 翻译成 [TtsConfig] + [VoiceProfile]，
 * 再交给 [TtsEngines] 创建 provider。
 *
 * 为什么要这一层（解耦）：
 *  - 业务方（VoiceLoop / QuickTts / 自检按钮 / 登录前的试听）**只认 VoiceSettings**，
 *    不需要知道豆包要传 resourceId、Breeze 要传 model_id；
 *  - 新增一家服务商只需改这里 + [TtsEngines]，其它代码零改动；
 *  - **所有非测试路径都从这里取配置**，测试台（VoiceTestActivity）保持独立。
 */
object VoiceTtsFactory {

    /** 豆包的情绪/语速偏移：把"心情"叠到用户设置上 */
    fun configFor(
        s: VoiceSettings,
        moodRate: Int = 0,
        moodLoud: Int = 0,
    ): TtsConfig = when (s.provider) {
        VoiceSettings.PROVIDER_BREEZE -> TtsConfig(
            providerId = TtsEngines.ID_BREEZE,
            apiKey = s.breezeKey,
            extra = mapOf(
                "format" to "pcm",              // 流式播 PCM，省一层解码
                "sample_rate" to "24000",
                "model_id" to "breeze-tts-2",
            ),
        )

        VoiceSettings.PROVIDER_SYSTEM -> TtsConfig(providerId = TtsEngines.ID_SYSTEM)

        else -> TtsConfig(
            providerId = TtsEngines.ID_VOLCENGINE,
            apiKey = s.doubaoKey,
            resourceId = s.resourceId,
            extra = mapOf(
                "format" to s.format,
                "sample_rate" to s.sampleRate.toString(),
                "speech_rate" to (s.speechRate + moodRate).coerceIn(-50, 100).toString(),
                "loudness_rate" to (s.loudnessRate + moodLoud).coerceIn(-50, 100).toString(),
                "emotion" to s.emotion,
                "emotion_scale" to s.emotionScale.toString(),
            ),
        )
    }

    /** 音色档案：不同服务商的"音色"字段不同（豆包 voiceId / Breeze breezeVoiceId） */
    fun profileFor(s: VoiceSettings): VoiceProfile = when (s.provider) {
        VoiceSettings.PROVIDER_BREEZE -> VoiceProfile(voiceId = s.breezeVoiceId)
        else -> VoiceProfile(
            voiceId = s.voiceId,
            emotion = s.emotion.ifBlank { null },
            emotionScale = s.emotionScale,
        )
    }

    /** 创建 provider（不可用时由 [TtsEngines.createWithFallback] 自动退回系统 TTS） */
    fun create(ctx: Context, s: VoiceSettings, moodRate: Int = 0, moodLoud: Int = 0): TtsProvider =
        TtsEngines.createWithFallback(ctx, configFor(s, moodRate, moodLoud))

    /** 直接按"当前设置"创建（非测试路径的便捷入口） */
    fun createCurrent(ctx: Context, moodRate: Int = 0, moodLoud: Int = 0): TtsProvider =
        create(ctx, VoiceSettingsStore.get(ctx).load(), moodRate, moodLoud)

    /** 当前设置是否可用（缺 Key 时上层可以直接降级，不要报错） */
    fun isReady(s: VoiceSettings): Boolean = when (s.provider) {
        VoiceSettings.PROVIDER_BREEZE -> s.breezeKey.isNotBlank() && s.breezeVoiceId.isNotBlank()
        VoiceSettings.PROVIDER_SYSTEM -> true
        else -> s.doubaoKey.isNotBlank()
    }
}
