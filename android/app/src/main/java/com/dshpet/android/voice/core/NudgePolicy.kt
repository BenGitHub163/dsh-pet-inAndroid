package com.dshpet.android.voice.core

/**
 * 沉默应对策略（**Policy 模式**）：用户一段时间没说话时，让模型主动开口。
 *
 * 关键设计（都是被"AI 太吵"坑出来的）：
 *  1. **4 套人格轮换**：轮流用不同口吻，避免每次都一句"你还在吗"；
 *  2. **间隔递增**：1.3s → 2.1s → 3.3s → 5.3s → 8.5s 封顶，越没人理越少说话；
 *  3. **有上限**：连续 4 次没人应，说一句结束语就退出，不做骚扰；
 *  4. **提示词自带上限**：一句话、20 字以内、不带 emoji、不复读——保证 TTS 快且自然。
 */
object NudgePolicy {

    /**
     * 用户沉默多久触发第一次主动开口。
     *
     * 曾经是 5s：起语音后要干等 5 秒才听到第一句，体感明显"卡住"。
     * 现在 1.3s——ASR 一轮 listen 结束（说完/静音超时）后立刻接一句，像真人插话。
     */
    const val SILENCE_THRESHOLD_MS = 1_300L

    /** 连续 N 次没人理就收工 */
    const val MAX_NUDGES = 4

    data class Template(
        val id: String,
        val label: String,
        val system: String,
    )

    /** 四套口吻（**外置**：assets/prompts/nudge_*.txt） */
    val templates: List<Template>
        get() = listOf(
            Template("clingy", "黏人撒娇", p("nudge_clingy", "轻声问一句他还在不在，不超过 18 字。")),
            Template("caring", "温柔关心", p("nudge_caring", "用一句话关心他，不施压，不超过 20 字。")),
            Template("teasing", "俏皮吐槽", p("nudge_teasing", "俏皮地吐槽一句他的沉默，不超过 18 字。")),
            Template("curious", "主动找话题", p("nudge_curious", "主动问一个具体的小问题，不超过 22 字。")),
        )

    private fun p(key: String, fallback: String): String =
        com.dshpet.android.prompt.PromptStore.text(key, fallback)
    /** 第 n 次主动开口用哪套（循环轮换，保证四种口吻都会被用到） */
    fun templateFor(nudgeIndex: Int): Template =
        templates[nudgeIndex.coerceAtLeast(0) % templates.size]

    /**
     * 第 n 次主动开口前的等待时长：1.3s → 2.1s → 3.3s → 5.3s → 8.5s 封顶
     * （1.6 倍递增，避免"越没人理越唠叨"）
     *
     * 首轮必须短（1.3s）：用户刚起语音、一句话没说，桌宠要马上搭话；
     * 之后逐轮拉长，连续被冷落时就不会一直追着问。
     */
    fun intervalMs(nudgeIndex: Int): Long {
        if (nudgeIndex <= 0) return SILENCE_THRESHOLD_MS
        var ms = SILENCE_THRESHOLD_MS.toDouble()
        repeat(nudgeIndex) { ms *= 1.6 }
        return ms.toLong().coerceAtMost(MAX_INTERVAL_MS)
    }

    /** 后续轮次的等待上限（1.3×1.6⁴≈8.5s 之后不再拉长） */
    private const val MAX_INTERVAL_MS = 8_500L

    /**
     * 静音探测窗口（毫秒）：交给 ASR 的 [com.dshpet.android.voice.asr.AsrProvider.listen] 的 `probeMs`。
     *
     * 为什么需要它：`asr.listen()` 是阻塞的，系统识别器要等你"说完 + 静音 2s"才返回；
     * 用户起语音后一个字都不说时，它会在 ~4.2s 后返回 NO_MATCH——阈值再短也只能等它。
     * 有了这个探测，1.4s 内**一点声音都没有**就直接收一轮空结果，
     * 于是"沉默多久触发主动开口"才真的等于 [SILENCE_THRESHOLD_MS] 量级。
     *
     * 安全阀：探测期间只要引擎报"开始说话"、给了中间结果、或 mic 电平抬到门限以上，
     * 探测立即作废、照旧等正常识别结果（见 SystemAsrProvider.VOICE_FLOOR_DB）。
     */
    const val PROBE_SILENCE_MS = 1_400L

    /** 连续多次没人理之后说的收尾话术（不走模型，保证一定会说） */
    /** 收尾语（**外置**：assets/prompts/nudge_farewell.txt） */
    val FAREWELL: String
        get() = com.dshpet.android.prompt.PromptStore.text(
            "nudge_farewell",
            "那我先安静待着，需要我的时候喊我一声就好。",
        )
}
