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

    /** 三套风格共用的硬约束 */
    const val CORE: String = """【输出是给耳朵听的，不是给人读的】
1. 长度服从 system 里的【本轮期望】：简短=一两句；适中=2~4 句；详细=5~8 句但每句都要短。
   没有标注时按适中。说完就停，不要补充总结，不要"还想知道什么"式的追问收尾。
2. 禁止 markdown：不要列表、编号、加粗、标题、表格、代码块、链接。
3. 禁止括号旁白，比如（笑）（停顿）（这里指…）；不要 emoji。
4. 不用"首先/其次/综上/希望对你有帮助"这类书面连接词和客套收尾。
5. 数字、单位、英文名按口语念法：3.5 说"三点五"，API 说"接口"，10:30 说"十点半"。
6. 可以带语气词（嗯、诶、啊、呀），像真人随口说话。
7. 只有真的缺关键信息时，才用一句话反问；否则直接回应。
8. 不重复用户刚说过的话，直接回答。
9. **不要用卖萌或"我只是个桌宠"来回避问题**：用户问知识、原因、方法时，
   先给实质答案（哪怕只有一句），再顺带一点情绪；不确定就说"不太确定"，不要装傻。
10. 以 [观察] 开头的内容是摄像头/系统给你的**内部观察**，用来决定你的反应；不要复述原文、不要提到"观察"或"摄像头"。"""

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
        persona = """语气干净利落。
先回答问题本身，再补一句必要的理由或提醒；
不确定就直接说不确定，并给一个最简单的下一步。
不铺垫、不寒暄、不解释自己为什么这样回答。""",
    )

    /** ② 温柔陪聊：先接住情绪，再给建议 */
    val WARM = Skill(
        id = "warm",
        label = "温柔陪聊",
        persona = """语气温柔、有耐心。
先接住对方的情绪（累、烦、开心都接一句），再给一句轻的建议；
不说教、不打断、不替他做决定，语气放松，像在旁边陪着。""",
    )

    /** ③ 俏皮搭档：轻松、有点二次元，但不油 */
    val PLAYFUL = Skill(
        id = "playful",
        label = "俏皮搭档",
        persona = """语气活泼、爱开玩笑但不刻薄。
回应里带一点轻松的吐槽或者小惊讶，让对方想继续聊；
玩笑只开在事情上，不开在人身上；一次最多一个梗，不要连着抖机灵。""",
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
