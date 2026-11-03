package com.dshpet.android.voice.core

import android.content.Context
import com.dshpet.android.api.ApiFactory
import com.dshpet.android.chat.SseClient
import com.dshpet.android.util.AppLog
import com.dshpet.android.voice.chat.ChatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * 「自说自话」的 **AI 版台词**：用户配了 DeepSeek 接口时，让模型现场写一句，
 * 比固定的几句循环播放自然得多（不会一直"好女孩……"）。
 *
 * 设计要点：
 *  1. **字数硬限制**：提示词里写死"不超过 18 字"，代码再兜一层截断（见 [clean]）——
 *     模型不听话时的兜底比事后调提示词可靠；
 *  2. **不污染语音对话历史**：这里是独立的一次性请求，不走 `VoiceLoop`；
 *  3. 任何失败（没 Key / 网络 / 空回复）都返回 null，调用方回退到内置台词，**不让桌宠变哑巴**。
 */
object SelfTalkAi {

    /**
     * 提示词（改这里就能调整"嘀咕"的风格）。
     * 注意别写成"请帮我写一句…"这种任务口吻，模型容易输出解释；直接描述角色与约束。
     */
    private val SYSTEM: String
        get() = com.dshpet.android.prompt.PromptStore.text(
            "selftalk",
            "你是桌宠，现在自言自语一句，不超过 18 字，只输出这句话。",
        )


    private const val MAX_CHARS = 24

    /** 生成一句台词；失败返回 null（调用方回退到内置台词） */
    suspend fun generate(ctx: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val text = ApiFactory.chat(ctx).complete(
                listOf(
                    SseClient.Msg("system", SYSTEM),
                    SseClient.Msg("user", "（现在没人说话，你小声嘀咕一句）"),
                ),
                maxTokens = 64,
            )
            clean(text).also {
                AppLog.log("SELFTALK", "AI 台词 raw=${text.length} 字 →「${it ?: "(空)"}」")
            }
        }.getOrElse { e ->
            AppLog.log("SELFTALK", "AI 台词失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 清洗：去掉换行/引号/前缀，只留第一句，超长截断 */
    internal fun clean(raw: String): String? {
        var t = raw.replace("\r", " ").replace("\n", " ").trim()
        if (t.isBlank()) return null
        // 模型偶尔会带上"小猹："这种前缀（只在冒号前是很短的名字时才剥掉）
        val colon = t.indexOf('：')
        if (colon in 1..6) t = t.substring(colon + 1).trim()
        t = t.trim().trim('"', '\'', '“', '”', '「', '」', '『', '』', '*', '-', ' ')
        if (t.isBlank()) return null
        val end = t.indexOfFirst { it in "。！？!?" }
        if (end in 1 until MAX_CHARS) t = t.substring(0, end + 1)
        if (t.length > MAX_CHARS) t = t.take(MAX_CHARS)
        return t.trim().ifBlank { null }
    }
}
