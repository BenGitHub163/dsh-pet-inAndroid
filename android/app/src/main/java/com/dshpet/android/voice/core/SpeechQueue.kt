package com.dshpet.android.voice.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 逐句播放队列（**生产者/消费者 + 有界缓冲做背压**）。
 *
 * 设计点：
 *  - 通道容量默认 3：模型吐字比合成快时，最多积压 3 句，既有预取效果又不会
 *    在长回复里无限缓冲（内存/费用都受控）；
 *  - `offer` 用 trySend，满了直接丢弃最旧的？——不：丢句子会漏读，所以满了就阻塞
 *    调用方（用 send）。这里给两个方法，调用方按语义选；
 *  - `cancel()` 同时关通道并取消 worker，实现"打断/切歌"。
 */
class SpeechQueue(
    private val scope: CoroutineScope,
    capacity: Int = 3,
) {
    private val channel = Channel<String>(capacity)
    private var worker: Job? = null

    /** 启动消费者：对每句话执行 [onItem]（通常是 synthesize+play） */
    fun start(onItem: suspend (String) -> Unit) {
        if (worker != null) return
        worker = scope.launch {
            for (text in channel) {
                if (text.isBlank()) continue
                onItem(text)
            }
        }
    }

    /** 非阻塞投递；通道满时返回 false（调用方可选择等待或丢弃） */
    fun tryOffer(text: String): Boolean = channel.trySend(text).isSuccess

    /** 挂起投递（有背压） */
    suspend fun offer(text: String) {
        channel.send(text)
    }

    /** 输入结束：等消费者把队列里的都播完 */
    fun closeInput() {
        channel.close()
    }

    suspend fun awaitIdle() {
        worker?.join()
    }

    fun cancel() {
        runCatching { channel.close() }
        worker?.cancel()
        worker = null
    }
}
