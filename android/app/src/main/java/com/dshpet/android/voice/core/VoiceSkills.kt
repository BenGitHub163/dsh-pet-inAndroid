package com.dshpet.android.voice.core

/**
 * 「说话风格」Skill —— 每轮对话前作为 system 消息注入。
 *
 * 为什么需要它：不做约束时，DeepSeek 会输出"段落 + 并列选项 + 括号补充"这种**写给人看的文本**，
 * TTS 念出来就是灾难（实测一句 60+ 字、三个并列选项）。所以这里把"说"和"写"分开：
 *
 *   [CORE]  所有风格共用的硬约束（长度、格式、读法、收尾）
 *   [风格]  三套人设，逐轮轮换，避免每次都一个腔调
 *
 * 三套风格都**必须在 CORE 之内**发挥，保证换风格不会换坏体验。
 */
object VoiceSkills {

    /** 三套风格共用的硬约束（**外置**：assets/prompts/skills_core.txt） */
    val CORE: String
        get() = com.dshpet.android.prompt.PromptStore.text(
            "skills_core",
            "【输出是给耳朵听的】口语、简短、禁止 markdown 与括号旁白，说完就停。",
        )
    data class Skill(
        val id: String,
        val label: String,
        /** 人设描述（不含 CORE） */
        val persona: String,
    )

    /** ① 极简口语：最稳的默认档，先结论后一句理由 */
    val CONCISE = Skill(
        id = "concise",
        label = "极简口语",
        persona = com.dshpet.android.prompt.PromptStore.text("skills_concise", """语气干净利落。
先回答问题本身，再补一句必要的理由或提醒；
不确定就直接说不确定，并给一个最简单的下一步。
不铺垫、不寒暄、不解释自己为什么这样回答。"""),
    )

    /** ② 温柔陪聊：先接住情绪，再给建议 */
    val WARM = Skill(
        id = "warm",
        label = "温柔陪聊",
        persona = com.dshpet.android.prompt.PromptStore.text("skills_warm", """语气温柔、有耐心。
先接住对方的情绪（累、烦、开心都接一句），再给一句轻的建议；
不说教、不打断、不替他做决定，语气放松，像在旁边陪着。"""),
    )

    /** ③ 俏皮搭档：轻松、有点二次元，但不油 */
    val PLAYFUL = Skill(
        id = "playful",
        label = "俏皮搭档",
        persona = com.dshpet.android.prompt.PromptStore.text("skills_playful", """语气活泼、爱开玩笑但不刻薄。
回应里带一点轻松的吐槽或者小惊讶，让对方想继续聊；
玩笑只开在事情上，不开在人身上；一次最多一个梗，不要连着抖机灵。"""),
    )

    val all: List<Skill> = listOf(CONCISE, WARM, PLAYFUL)

    /** 第 turn 轮用哪套（0,1,2 循环） */
    fun forTurn(turn: Int): Skill = all[turn.coerceAtLeast(0) % all.size]

    /** 拼成完整 system 消息 */
    fun systemFor(turn: Int): String {
        val s = forTurn(turn)
        return "【本轮说话风格：${s.label}】\n${s.persona}\n\n$CORE"
    }

    /** 沉默主动开口时用：NudgePolicy 的模板 + 同一套硬约束 */
    fun forNudge(nudgeTemplate: String): String = "$nudgeTemplate\n\n$CORE"
}
