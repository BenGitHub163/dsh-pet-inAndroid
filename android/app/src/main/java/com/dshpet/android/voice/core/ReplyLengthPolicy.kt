package com.dshpet.android.voice.core

/**
 * 回复长度自适应（**Policy 模式**）。
 *
 * 问题：固定"≤60 字"会让"为什么/讲讲/怎么办"这种明确的深入请求也只能得到一句话；
 * 反过来不设限又会变成念文章。所以按**用户这句话的意图**给三档，并把意图写进 system。
 *
 * 判定依据（可解释、可调，不额外消耗 token）：
 *  - 出现"为什么/怎么/讲讲/举例/区别…" → 详细
 *  - 用户自己说了一长段（≥40 字）        → 详细（他愿意聊）
 *  - "嗯/好的/在吗/晚安"这类极短应答       → 简短
 *  - 其余                              → 适中
 */
enum class ReplyLength { SHORT, NORMAL, DETAILED }

object ReplyLengthPolicy {

    private val DETAIL_WORDS = listOf(
        "详细", "为什么", "怎么", "如何", "讲讲", "说说", "解释", "举例", "比如", "具体",
        "展开", "流程", "步骤", "区别", "比较", "原理", "推荐", "怎么办", "有什么", "介绍",
        "分析", "原因", "方案", "教我", "帮我写", "规划", "建议",
    )

    private val SHORT_WORDS = listOf(
        "嗯", "哦", "好的", "在吗", "谢谢", "哈哈", "晚安", "早安", "收到", "好呀", "可以", "行",
    )

    fun hintFor(userText: String, lastTurnWasDetailed: Boolean = false): ReplyLength {
        val t = userText.trim()
        if (t.isEmpty()) return ReplyLength.NORMAL
        if (t.length <= 4 && SHORT_WORDS.any { t.contains(it) }) return ReplyLength.SHORT
        if (DETAIL_WORDS.any { t.contains(it) }) return ReplyLength.DETAILED
        if (t.length >= 40) return ReplyLength.DETAILED
        if (lastTurnWasDetailed && t.length >= 12) return ReplyLength.DETAILED   // 追问延续详细
        return ReplyLength.NORMAL
    }

    /** 写进 system 的一句话（模型据此决定说多长） */
    fun systemLine(length: ReplyLength): String = when (length) {
        ReplyLength.SHORT -> com.dshpet.android.prompt.PromptStore.text("reply_short")
        ReplyLength.NORMAL -> com.dshpet.android.prompt.PromptStore.text("reply_normal")
        ReplyLength.DETAILED -> com.dshpet.android.prompt.PromptStore.text("reply_detailed")
    }

    fun maxTokens(length: ReplyLength): Int = when (length) {
        ReplyLength.SHORT -> 160
        ReplyLength.NORMAL -> 320
        ReplyLength.DETAILED -> 800
    }

    /** 分句粒度：越长的话，单块可以越大，减少拼接痕迹 */
    fun chunkSize(length: ReplyLength): Int = when (length) {
        ReplyLength.SHORT -> 40
        ReplyLength.NORMAL -> 52
        ReplyLength.DETAILED -> 72
    }

    fun label(length: ReplyLength): String = when (length) {
        ReplyLength.SHORT -> "简短"
        ReplyLength.NORMAL -> "适中"
        ReplyLength.DETAILED -> "详细"
    }
}
