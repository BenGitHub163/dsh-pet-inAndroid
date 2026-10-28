package com.dshpet.android.voice.chat

import com.dshpet.android.chat.SseClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * **Adapter 模式**：把项目里基于回调的 `SseClient.stream(...)` 适配成 `Flow<String>`。
 *
 * 好处：
 *  - 上层可以用 suspend/Flow 写顺序逻辑（分句 → 合成 → 播放），不用嵌套回调；
 *  - 取消 Flow 会自动 cancel 底层 Job（转场/打断天然正确）。
 */
class ChatClient {

    fun stream(
        cfg: SseClient.ChatCfg,
        messages: List<SseClient.Msg>,
    ): Flow<String> = callbackFlow {
        val job = SseClient.stream(
            cfg = cfg,
            messages = messages,
            onDelta = { delta -> trySend(delta) },
            onError = { err -> close(ChatException(err)) },
            onDone = { close() },
        )
        awaitClose { job.cancel() }
    }
}

class ChatException(message: String) : Exception(message)
