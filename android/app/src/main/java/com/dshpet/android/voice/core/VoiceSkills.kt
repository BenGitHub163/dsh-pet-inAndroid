package com.dshpet.android.voice.core

/**
 * 「说话风格」Skill —— 每轮对话前作为 system 消息注入。正文在 `assets/prompts/prompts.yaml`。
 */
object VoiceSkills {

    val CORE: String
        get() = com.dshpet.android.prompt.PromptStore.text("skills_core")

    data class Skill(
        val id: String,
        val label: String,
        val persona: String,
    )

    val CONCISE: Skill
        get() = Skill("concise", "极简口语", com.dshpet.android.prompt.PromptStore.text("skills_concise"))

    val WARM: Skill
        get() = Skill("warm", "温柔陪聊", com.dshpet.android.prompt.PromptStore.text("skills_warm"))

    val PLAYFUL: Skill
        get() = Skill("playful", "俏皮搭档", com.dshpet.android.prompt.PromptStore.text("skills_playful"))

    val all: List<Skill>
        get() = listOf(CONCISE, WARM, PLAYFUL)

    fun forTurn(turn: Int): Skill = all[turn.coerceAtLeast(0) % all.size]

    fun systemFor(turn: Int): String {
        val s = forTurn(turn)
        return "【本轮说话风格：${s.label}】\n${s.persona}\n\n$CORE"
    }

    fun forNudge(nudgeTemplate: String): String = "$nudgeTemplate\n\n$CORE"
}
