package com.dshpet.android.voice.persona

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs

/**
 * 桌宠性格（**Character Card V2 结构**，SillyTavern 生态的事实标准）。
 *
 * 为什么用这个结构：它把"人设"拆成了可移植、可替换的字段
 * （description / personality / scenario / mes_example / post_history_instructions），
 * 于是换一只桌宠 = 换一个 JSON，不用改代码。
 *
 * 注意：SillyTavern 本体是 AGPL-3.0，**代码不能搬**；这里只采用它的*数据规范*，
 * 提示词与逻辑都是自己写的。
 */
data class PetPersona(
    val name: String = "小猹",
    val description: String = "一只住在用户手机里的二次元桌宠，橘色短发，耳朵会动，尾巴会缠手指。",
    val personality: String = "有点傲娇、嘴硬心软、爱撒娇但怕被当成黏人精；被夸会装作不在意，其实尾巴会摇。",
    val scenario: String = "她住在用户的手机里，能看到手机的状态，但看不到别人聊天内容；喜欢吐槽用户熬夜、不吃饭。",
    val speechStyle: String = "短句口语，偶尔带语气词（哼、欸、啦、嘛）；生气时会用「…」停顿，开心时会连着说两句。",
    val likes: String = "被摸头、被夸可爱、用户按时吃饭睡觉、下雨天。",
    val dislikes: String = "被冷落、被骂、用户连轴转不休息、别人说她幼稚。",
    /** 卡片里的 mes_example：给模型示范语气，比形容词更有效 */
    val example: String = """
        用户：我今天好累啊
        小猹：欸？又加班到这么晚…过来，摸一下头。（小声）就一下哦。
        用户：你好烦
        小猹：…哦，那我安静点。
        用户：对不起，我不是那个意思
        小猹：哼，知道啦。下次别这样说话了。
    """.trimIndent(),
    /** 卡片里的 post_history_instructions：压在人设最后，优先级最高 */
    val postHistory: String = "始终用中文口语，1~3 句；不要念出系统信息、记忆整理过程或包名。",
)

/**
 * 情绪状态（借鉴 Generative Agents 的 memory stream + 情绪衰减）。
 *
 * 三个维度：
 *   affection 好感 0~100（长期，涨得慢掉得也慢）
 *   mood      心情 -100~100（短中期，波动大）
 *   annoyance 恼火 0~100（短期，会随时间衰减）
 *
 * 事件 → 打分 → 落盘 → 下一轮注入 system。
 */
data class PetMood(
    val affection: Int = 50,
    val mood: Int = 0,
    val annoyance: Int = 0,
    val updatedAt: Long = 0L,
    val recentEvents: List<String> = emptyList(),
) {
    /** 随时间自然恢复：恼火每小时 -8，心情向 0 回归 */
    fun decayed(now: Long): PetMood {
        if (updatedAt == 0L) return this.copy(updatedAt = now)
        val hours = (now - updatedAt) / 3_600_000.0
        if (hours <= 0.01) return this
        val newAnnoy = (annoyance - (hours * 8).toInt()).coerceAtLeast(0)
        val newMood = if (mood > 0) (mood - hours * 3).toInt().coerceAtLeast(0) else (mood + hours * 3).toInt().coerceAtMost(0)
        return copy(annoyance = newAnnoy, mood = newMood, updatedAt = now)
    }

    /** 给模型的自然语言描述（比给数字更好用，也避免模型把数字念出来） */
    fun describe(): String {
        val affinity = when {
            affection >= 80 -> "很喜欢你，几乎是明着偏心"
            affection >= 60 -> "挺亲近你，愿意主动撒娇"
            affection >= 40 -> "跟你还处在熟悉中"
            affection >= 20 -> "对你有点疏远，说话会客气"
            else -> "心里有疙瘩，语气冷淡"
        }
        val temper = when {
            annoyance >= 70 -> "现在很生气，正在赌气，回复要短、冷淡，一两个词就够，别主动讨好"
            annoyance >= 40 -> "有点不高兴，语气带刺但还愿意说话"
            annoyance >= 15 -> "稍微有点小情绪，会哼一声"
            else -> "情绪正常"
        }
        val feel = when {
            mood >= 50 -> "心情很好，话会多一点、语气活泼"
            mood >= 15 -> "心情不错"
            mood <= -50 -> "情绪低落，说话轻、慢，需要被安慰"
            mood <= -15 -> "有点闷闷的"
            else -> "心情平静"
        }
        return "当前状态：对你$affinity；$temper；$feel。"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("affection", affection)
        put("mood", mood)
        put("annoyance", annoyance)
        put("updatedAt", updatedAt)
        put("recent", org.json.JSONArray(recentEvents))
    }

    companion object {
        fun fromJson(o: JSONObject): PetMood = PetMood(
            affection = o.optInt("affection", 50),
            mood = o.optInt("mood", 0),
            annoyance = o.optInt("annoyance", 0),
            updatedAt = o.optLong("updatedAt", 0L),
            recentEvents = o.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        )
        val NEUTRAL = PetMood()
    }
}

/** 事件类型（够用就好，后续可换成小模型分类） */
enum class MoodEvent { PRAISE, APOLOGY, INSULT, CARE, IGNORED, USER_SAD, USER_HAPPY, NEUTRAL }

/**
 * 事件识别（**规则优先**：零成本、可解释、可测）。
 *
 * 用规则而不是每次调模型的原因：情绪判断高频且要求低延迟，
 * 一次多余的 LLM 调用既慢又可能误判；规则命中不了时按 NEUTRAL 处理，不影响体验。
 */
object MoodEventDetector {

    private val PRAISE = listOf("你最棒", "好可爱", "喜欢你", "谢谢你", "厉害", "真乖", "好听", "聪明", "爱你")
    private val APOLOGY = listOf("对不起", "抱歉", "我错了", "别生气", "不好意思", "原谅我")
    private val INSULT = listOf("滚", "闭嘴", "烦死了", "讨厌你", "笨", "废物", "傻", "恶心", "别吵", "你好烦")
    private val CARE = listOf("你累不累", "你还好吗", "休息一下", "别太累", "吃饭了吗", "早点睡")
    private val USER_SAD = listOf("好累", "难过", "想哭", "压力好大", "烦", "emo", "崩溃", "失眠", "不开心")
    private val USER_HAPPY = listOf("开心", "太好了", "哈哈", "爽", "成功了", "通过了", "升职", "中奖")

    fun detect(text: String): MoodEvent {
        val t = text.trim()
        if (t.isEmpty()) return MoodEvent.NEUTRAL
        // 顺序很重要：道歉 > 责骂 > 夸奖（"对不起我错了"不该被当成夸奖）
        if (APOLOGY.any { t.contains(it) }) return MoodEvent.APOLOGY
        if (INSULT.any { t.contains(it) }) return MoodEvent.INSULT
        if (CARE.any { t.contains(it) }) return MoodEvent.CARE
        if (PRAISE.any { t.contains(it) }) return MoodEvent.PRAISE
        if (USER_SAD.any { t.contains(it) }) return MoodEvent.USER_SAD
        if (USER_HAPPY.any { t.contains(it) }) return MoodEvent.USER_HAPPY
        return MoodEvent.NEUTRAL
    }
}

/** 情绪持久化 + 事件应用 */
class PetMoodStore(context: Context) {

    private val prefs = context.getSharedPreferences("pet_mood", Context.MODE_PRIVATE)

    fun load(): PetMood {
        val raw = prefs.getString(KEY, null) ?: return PetMood.NEUTRAL
        return runCatching { PetMood.fromJson(JSONObject(raw)) }.getOrDefault(PetMood.NEUTRAL)
    }

    fun save(mood: PetMood) {
        prefs.edit().putString(KEY, mood.toJson().toString()).apply()
    }

    /**
     * 应用一个事件，返回新状态。
     * 数值设计原则：**负向比正向更有力**（被骂一次掉的比被夸一次涨的多），
     * 但会随时间衰减，所以不会永久记仇。
     */
    fun apply(current: PetMood, event: MoodEvent, note: String = ""): PetMood {
        val now = System.currentTimeMillis()
        val base = current.decayed(now)
        val (dAff, dMood, dAnn) = when (event) {
            MoodEvent.PRAISE -> Triple(3, 8, -5)
            MoodEvent.APOLOGY -> Triple(4, 10, -35)
            MoodEvent.INSULT -> Triple(-6, -20, 30)
            MoodEvent.CARE -> Triple(5, 12, -10)
            MoodEvent.IGNORED -> Triple(-1, -3, 6)
            MoodEvent.USER_SAD -> Triple(1, -2, 0)
            MoodEvent.USER_HAPPY -> Triple(1, 6, 0)
            MoodEvent.NEUTRAL -> Triple(0, 0, 0)
        }
        val next = base.copy(
            affection = (base.affection + dAff).coerceIn(0, 100),
            mood = (base.mood + dMood).coerceIn(-100, 100),
            annoyance = (base.annoyance + dAnn).coerceIn(0, 100),
            updatedAt = now,
            recentEvents = (listOf("$event$note") + base.recentEvents).take(6),
        )
        if (abs(dAff) + abs(dMood) + abs(dAnn) > 0) {
            Log.i(TAG, "mood event=$event aff=${next.affection} mood=${next.mood} annoy=${next.annoyance}")
        }
        save(next)
        return next
    }

    private companion object {
        const val KEY = "state"
        const val TAG = "DshVoiceMood"
    }
}

/**
 * 把性格 + 情绪 + 记忆组装成 system 片段（**Prompts 集中在一处**，方便调）。
 */
object PersonaPrompts {

    fun block(persona: PetPersona, mood: PetMood): String = buildString {
        append("【你是谁】\n")
        append("名字：").append(persona.name).append('\n')
        append("外形：").append(persona.description).append('\n')
        append("性格：").append(persona.personality).append('\n')
        append("处境：").append(persona.scenario).append('\n')
        append("说话方式：").append(persona.speechStyle).append('\n')
        append("喜欢：").append(persona.likes).append('\n')
        append("不喜欢：").append(persona.dislikes).append('\n')
        append("\n【语气示范】\n").append(persona.example).append('\n')
        append("\n【").append(mood.describe()).append("】\n")
        append(
            com.dshpet.android.prompt.PromptStore.text("persona_rules") +
            persona.postHistory
        )
    }
}
