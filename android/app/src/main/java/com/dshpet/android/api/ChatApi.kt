package com.dshpet.android.api

import android.content.Context
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 对话能力：业务只调 [stream] / [complete] / [test] / [listModels]。
 * HTTP 与鉴权在 [DeepseekApi]。
 */
class ChatApi(@Suppress("unused") private val ctx: Context) {

    companion object {
        const val DEFAULT_BASE_URL = DeepseekApi.CHAT_BASE_URL
        const val DEFAULT_PATH = DeepseekApi.CHAT_PATH
        const val DEFAULT_MODEL = DeepseekApi.CHAT_MODEL

        fun normalizeEndpoint(baseUrl: String, chatPath: String = DeepseekApi.CHAT_PATH): String =
            DeepseekApi.normalizeEndpoint(baseUrl, chatPath)
    }

    data class Msg(
        val role: String,
        val content: String,
        val reasoningContent: String? = null,
        val toolCalls: String? = null,
        val toolCallId: String? = null,
    )

    internal fun cfg(maxTokens: Int, temperature: Double = -1.0): DeepseekApi.ChatReq {
        val ready = PetConfig.snapReady
        return DeepseekApi.ChatReq(
            baseUrl = (if (ready) PetConfig.snapBaseUrl else "").ifBlank { DEFAULT_BASE_URL },
            chatPath = (if (ready) PetConfig.snapChatPath else "").ifBlank { DEFAULT_PATH },
            model = (if (ready) PetConfig.snapModel else "").ifBlank { DEFAULT_MODEL },
            temperature = if (temperature >= 0) temperature else if (ready) PetConfig.snapTemperature else 0.7,
            maxTokens = maxTokens,
            timeoutSec = if (ready) PetConfig.snapTimeout else 60,
            verifySsl = if (ready) PetConfig.snapVerifySsl else true,
        )
    }

    fun stream(messages: List<Msg>, maxTokens: Int = 2048): Flow<String> = callbackFlow {
        val job = DeepseekApi.startStream(
            req = cfg(maxTokens),
            messages = messages,
            onDelta = { delta -> trySend(delta) },
            onError = { err -> close(ChatApiException(err)) },
            onDone = { close() },
        )
        awaitClose { job.cancel() }
    }

    suspend fun complete(messages: List<Msg>, maxTokens: Int = 512): String =
        stream(messages, maxTokens).fold("") { acc, d -> acc + d }

    suspend fun test(timeoutSec: Int = 15): Result<String> = suspendCancellableCoroutine { cont ->
        DeepseekApi.testConnection(cfg(64).copy(timeoutSec = timeoutSec)) { r ->
            if (cont.isActive) cont.resume(r)
        }
    }

    fun listModels(baseUrl: String, verifySsl: Boolean = true, timeoutSec: Int = 15): Result<List<String>> =
        DeepseekApi.listModels(baseUrl, verifySsl, timeoutSec)
}

class ChatApiException(message: String) : Exception(message)
