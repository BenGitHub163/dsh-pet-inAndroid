package com.dshpet.android.chat

import android.content.Context
import com.dshpet.android.data.PetConfig

/**
 * 唯一的对话连接工厂：语音对话与快速对话都从这里取 ChatCfg，
 * 参数来源、兜底值、超时/SSL 策略完全一致（避免两条路径行为分叉）。
 */
object ChatConfigFactory {

    private inline fun <T> safe(noinline block: suspend () -> T, def: T): T =
        runCatching { kotlinx.coroutines.runBlocking { block() } }.getOrDefault(def)

    fun build(ctx: Context, maxTokens: Int = 2048): SseClient.ChatCfg {
        // 优先用内存快照（零阻塞）；快照未就绪时才回退到 suspend 读取
        if (PetConfig.snapReady) {
            return SseClient.ChatCfg(
                baseUrl = PetConfig.snapBaseUrl.ifBlank { "https://api.deepseek.com" },
                chatPath = "/v1/chat/completions",
                model = PetConfig.snapModel.ifBlank { "deepseek-chat" },
                apiKey = PetConfig.snapChatKey.ifBlank { com.dshpet.android.data.KeyFactory.defaults().deepseek },
                temperature = PetConfig.snapTemperature,
                maxTokens = maxTokens,
                timeoutSec = PetConfig.snapTimeout,
                verifySsl = PetConfig.snapVerifySsl,
            )
        }
        val c = PetConfig.get(ctx.applicationContext)
        android.util.Log.i("DshChat", "factory keyLen=" + safe({ c.chatApiKey() }, "").length)
        android.util.Log.i("DshChat", "factory keyLen=" + safe({ c.chatApiKey() }, "").length)
        return SseClient.ChatCfg(
            baseUrl = safe({ c.chatBaseUrl() }, "").ifBlank { "https://api.deepseek.com" },
            chatPath = "/v1/chat/completions",
            model = safe({ c.chatModel() }, "").ifBlank { "deepseek-chat" },
            // 首次读取 DataStore 可能还没热起来（runBlocking 在主线程可能抛异常被 safe 吞成空串），
            // 空串会导致请求不带 Authorization → 服务端回 401 governor。这里必须有硬兜底。
            apiKey = safe({ c.chatApiKey() }, "").ifBlank { com.dshpet.android.data.KeyFactory.defaults().deepseek },
            temperature = safe({ c.chatTemperature() }, 1.0),
            maxTokens = maxTokens,
            timeoutSec = 60,
            verifySsl = true,
        )
    }
}
