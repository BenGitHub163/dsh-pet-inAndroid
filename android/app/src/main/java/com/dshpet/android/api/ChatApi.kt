package com.dshpet.android.api

import android.content.Context
import com.dshpet.android.api.ChatStream
import com.dshpet.android.data.KeyFactory
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 对话接口：域名 + 路径 + 模型 + 鉴权的唯一决策点。
 *
 * 重构前这件事被 4 处各决定一次（PetConfig 默认值 / ChatConfigFactory /
 * MainActivity 的 UI 默认值 / VoiceLoopActivity 手搓的 ChatCfg），
 * chatPath 配置甚至被忽略。现在只有这里知道怎么拼请求。
 */
class ChatApi(private val ctx: Context) {

    companion object {
        const val DEFAULT_BASE_URL = com.dshpet.android.api.ApiDefaults.CHAT_BASE_URL
        const val DEFAULT_PATH = com.dshpet.android.api.ApiDefaults.CHAT_PATH
        const val DEFAULT_MODEL = com.dshpet.android.api.ApiDefaults.CHAT_MODEL
    }

    /** 同包内的其它接口（视觉/记忆等）复用同一套端点与鉴权 */
    internal fun cfg(maxTokens: Int, temperature: Double = -1.0): ChatStream.ChatCfg {
        val ready = PetConfig.snapReady
        return ChatStream.ChatCfg(
            baseUrl = (if (ready) PetConfig.snapBaseUrl else "").ifBlank { DEFAULT_BASE_URL },
            chatPath = (if (ready) PetConfig.snapChatPath else "").ifBlank { DEFAULT_PATH },
            model = (if (ready) PetConfig.snapModel else "").ifBlank { DEFAULT_MODEL },
            apiKey = (if (ready) PetConfig.snapChatKey else "").ifBlank { KeyFactory.defaults().deepseek },
            temperature = if (temperature >= 0) temperature else if (ready) PetConfig.snapTemperature else 0.7,
            maxTokens = maxTokens,
            timeoutSec = if (ready) PetConfig.snapTimeout else 60,
            verifySsl = if (ready) PetConfig.snapVerifySsl else true,
        )
    }

    /** 流式对话：取消 Flow 即取消底层请求 */
    fun stream(messages: List<ChatStream.Msg>, maxTokens: Int = 2048): Flow<String> = callbackFlow {
        val job = ChatStream.stream(
            cfg = cfg(maxTokens),
            messages = messages,
            onDelta = { delta -> trySend(delta) },
            onError = { err -> close(ChatApiException(err)) },
            onDone = { close() },
        )
        awaitClose { job.cancel() }
    }

    /** 一次性拿完整回复（记忆整理 / 自说自话 / 看屏） */
    suspend fun complete(messages: List<ChatStream.Msg>, maxTokens: Int = 512): String =
        stream(messages, maxTokens).fold("") { acc, d -> acc + d }

    /** 连通性测试（设置页「测试」） */
    suspend fun test(timeoutSec: Int = 15): Result<String> = suspendCancellableCoroutine { cont ->
        ChatStream.testConnection(cfg(64).copy(timeoutSec = timeoutSec)) { r ->
            if (cont.isActive) cont.resume(r)
        }
    }
}

class ChatApiException(message: String) : Exception(message)