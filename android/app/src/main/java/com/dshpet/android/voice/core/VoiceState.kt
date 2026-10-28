package com.dshpet.android.voice.core

/**
 * 语音对话的状态机（**State 模式**）。
 *
 * 为什么用密封类而不是 boolean 组合：
 *  - 编译期就能穷举所有分支，UI 不会漏状态；
 *  - 每个状态只携带它需要的字段（Thinking 不需要 current 句子，Speaking 需要）；
 *  - 避免"isListening && isSpeaking"这种非法组合。
 */
sealed interface VoiceState {
    /** 空闲，未启动 */
    object Idle : VoiceState

    /** 正在听（partial 是识别中间结果，可能为空） */
    data class Listening(val partial: String = "") : VoiceState

    /** 识别完成，正在等模型回复（answer 是已经收到的部分文本） */
    data class Thinking(val userText: String, val answer: String = "") : VoiceState

    /** 正在朗读（current 是当前这一句） */
    data class Speaking(val userText: String, val answer: String, val current: String) : VoiceState

    /** 出错；recoverable=true 表示会自动重试 */
    data class Failed(val reason: String, val recoverable: Boolean = true) : VoiceState
}

/** 事件流（**Observer 模式**）：UI 只订阅，不反向调用逻辑层 */
sealed interface VoiceEvent {
    data class UserSaid(val text: String) : VoiceEvent
    data class AssistantSaid(val text: String) : VoiceEvent
    data class Segment(val text: String) : VoiceEvent
    data class Error(val message: String, val fatal: Boolean = false) : VoiceEvent
    data class Info(val message: String) : VoiceEvent
}
