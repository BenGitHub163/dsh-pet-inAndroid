package com.dshpet.android.api

import android.content.Context
import com.dshpet.android.voice.vision.VisionClient

/**
 * **视觉观察接口**：`POST {chatBaseUrl}{chatPath}`（多模态，图片内联 data URI）。
 *
 * 之前「看我一眼」在 VoiceLoopActivity 里自己拼了一份 ChatCfg（域名/模型/key 全写死），
 * 等于绕过用户在设置里配置的端点。现在统一走这里：
 * 端点与 key 来自 [ChatApi.cfg]，提示词来自 assets/prompts/vision_observe.txt。
 */
class VisionApi(ctx: Context) {

    private val chat = ChatApi(ctx)
    private val client = VisionClient()

    /** 看一组照片，返回一句客观观察；失败返回 null（不抛） */
    suspend fun observe(images: List<ByteArray>): String? {
        if (images.isEmpty()) return null
        // 观察任务要稳定、不要发散：温度固定 0.3，maxTokens 200
        val cfg = chat.cfg(maxTokens = 200, temperature = 0.3)
        return runCatching { client.observe(cfg, images) }.getOrNull()
    }

    suspend fun observe(jpeg: ByteArray): String? = observe(listOf(jpeg))
}