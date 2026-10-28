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
    private val SYSTEM = """
        你是住在用户手机里的二次元桌宠，此刻在**自言自语**：用户没在跟你说话，你只是随口嘀咕一句。
        写一句你会小声念叨的话：可以是关于时间/天气/自己正在做的事，也可以轻轻关心用户一句。
        要求：
        1. 只输出这一句话本身，不要引号、不要旁白、不要 emoji、不要解释。
        2. 中文不超过 18 个字。
        3. 口语感，带一点性格（有点傲娇，嘴硬心软）。
        4. 不要提问式追问，不要重复用户的话，不要出现"作为 AI""我是模型"这类自我说明。
    """.trimIndent()

    private const val MAX_CHARS = 24

    /** 生成一句台词；失败返回 null（调用方回退到内置台词） */
    suspend fun generate(ctx: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = ApiFactory.chat(ctx, maxTokens = 64)
            if (cfg.apiKey.isBlank()) return@runCatching null
            val sb = StringBuilder()
            ChatClient().stream(
                cfg,
                listOf(
                    SseClient.Msg("system", SYSTEM),
                    SseClient.Msg("user", "（现在没人说话，你小声嘀咕一句）"),
                ),
            ).collect { delta -> sb.append(delta) }
            clean(sb.toString()).also {
                AppLog.log("SELFTALK", "AI 台词 raw=${sb.length} 字 →「${it ?: "(空)"}」")
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
