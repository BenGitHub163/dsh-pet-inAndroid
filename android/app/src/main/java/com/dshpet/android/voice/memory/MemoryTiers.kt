package com.dshpet.android.voice.memory

import android.content.Context
import android.util.Log
import com.dshpet.android.chat.SseClient
import com.dshpet.android.voice.chat.ChatClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * 中期记忆：把"一段对话"压缩成"一个话题片段"，带 **heat（热度）**。
 *
 * 设计直接对齐 MemoryOS（EMNLP 2025）：
 *   short-term（最近几轮，内存里）→ 满了就 consolidate → mid-term（话题片段 + heat）
 *   → heat 到阈值 → 提炼进 long-term（用户档案/事实，永不遗忘）
 *
 * 为什么要有 mid-term：长期事实记不住"过程"（"我们昨天聊了戒烟，他决定先减到每天一杯"），
 * 而短期原文又太占 token。话题片段正好卡在中间：叙事性 + 便宜。
 */
data class MemorySegment(
    val id: String,
    val title: String,
    val summary: String,
    val keywords: List<String>,
    val createdAt: Long,
    var lastHitAt: Long,
    var heat: Int,
    var turnCount: Int,
    var promoted: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("summary", summary)
        put("keywords", JSONArray(keywords))
        put("createdAt", createdAt)
        put("lastHitAt", lastHitAt)
        put("heat", heat)
        put("turnCount", turnCount)
        put("promoted", promoted)
    }

    companion object {
        fun fromJson(o: JSONObject): MemorySegment = MemorySegment(
            id = o.optString("id"),
            title = o.optString("title"),
            summary = o.optString("summary"),
            keywords = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            createdAt = o.optLong("createdAt"),
            lastHitAt = o.optLong("lastHitAt"),
            heat = o.optInt("heat"),
            turnCount = o.optInt("turnCount"),
            promoted = o.optBoolean("promoted"),
        )
    }
}

/** 话题片段的持久化 + 合并（同一话题再聊到 → heat++，而不是新增一条） */
class SegmentStore(context: Context) {

    private val file = File(context.filesDir, "voice_segments.json")
    private val lock = Any()
    private var cache: MutableList<MemorySegment>? = null

    fun all(): List<MemorySegment> = synchronized(lock) { load().toList() }

    fun size(): Int = synchronized(lock) { load().size }

    /** 合并写入，返回最终落库的那条 */
    fun upsertMerge(seg: MemorySegment): MemorySegment = synchronized(lock) {
        val list = load()
        val similar = list.firstOrNull { jaccard(it.keywords.toSet(), seg.keywords.toSet()) >= 0.6 }
            ?: list.firstOrNull { jaccard(bigrams(it.title + it.summary), bigrams(seg.title + seg.summary)) >= 0.55 }

        val result = if (similar != null) {
            list.remove(similar)
            similar.copy(
                title = seg.title.ifBlank { similar.title },
                summary = seg.summary.ifBlank { similar.summary },
                keywords = (similar.keywords + seg.keywords).distinct().take(10),
                lastHitAt = System.currentTimeMillis(),
                heat = similar.heat + 1,
                turnCount = similar.turnCount + seg.turnCount,
            )
        } else {
            seg
        }
        list.add(result)
        trim(list)
        save(list)
        result
    }

    fun touch(id: String) = synchronized(lock) {
        val list = load()
        val s = list.firstOrNull { it.id == id } ?: return@synchronized
        s.heat += 1
        s.lastHitAt = System.currentTimeMillis()
        save(list)
    }

    fun markPromoted(id: String) = synchronized(lock) {
        val list = load()
        val s = list.firstOrNull { it.id == id } ?: return@synchronized
        s.promoted = true
        save(list)
    }

    fun clear() = synchronized(lock) {
        cache = mutableListOf()
        file.delete()
    }

    private fun trim(list: MutableList<MemorySegment>) {
        if (list.size <= MAX) return
        val keep = list.sortedWith(compareBy({ it.heat }, { it.lastHitAt })).takeLast(MAX).toMutableList()
        list.clear(); list.addAll(keep)
    }

    private fun load(): MutableList<MemorySegment> {
        cache?.let { return it }
        val list = mutableListOf<MemorySegment>()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) runCatching { list.add(MemorySegment.fromJson(arr.getJSONObject(i))) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "load segments failed: ${e.message}")
        }
        cache = list
        return list
    }

    private fun save(list: List<MemorySegment>) {
        cache = list.toMutableList()
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString())
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
            Log.i(TAG, "saved ${list.size} segments (${file.length()}B)")
        } catch (e: Throwable) {
            Log.e(TAG, "save segments failed: ${e.message}")
        }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    private fun bigrams(s: String): Set<String> = RETRIEVER.bigrams(s)

    private companion object {
        const val TAG = "DshVoiceSeg"
        const val MAX = 40
        val RETRIEVER = MemoryRetriever()
    }
}

/** 话题片段的检索：关键词重合 + heat + 新鲜度 */
class SegmentRetriever {
    private val retriever = MemoryRetriever()

    fun retrieve(segments: List<MemorySegment>, query: String, limit: Int = 2): List<MemorySegment> {
        if (segments.isEmpty() || query.isBlank()) return emptyList()
        val q = retriever.bigrams(query)
        val now = System.currentTimeMillis()
        return segments.map { seg ->
            val doc = retriever.bigrams(seg.title + " " + seg.summary + " " + seg.keywords.joinToString(" "))
            val inter = q.intersect(doc).size
            val cosine = if (inter == 0 || doc.isEmpty()) 0.0 else inter.toDouble() / sqrt(q.size.toDouble() * doc.size)
            val heat = (seg.heat.coerceAtMost(8)) / 8.0
            val days = (now - seg.lastHitAt) / 86_400_000.0
            val fresh = 1.0 / (1.0 + days / 14.0)
            seg to (cosine * 0.7 + heat * 0.2 + fresh * 0.1)
        }.filter { it.second >= 0.08 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}

/**
 * 短期 → 中期 → 长期 的搬运工（MemoryOS 的 Updater + MemoryOS 的 heat 阈值）。
 */
class MemoryConsolidator(

    private val chatApi: com.dshpet.android.api.ChatApi,
) {

    /** 把最近几轮对话压成一个话题片段 */
    suspend fun summarize(turns: List<SseClient.Msg>): MemorySegment? {
        if (turns.count { it.role == "user" } < 2) return null
        val dialog = turns.takeLast(10).joinToString("\n") { "${it.role}：${it.content.take(200)}" }
        val text = collect(
            listOf(
                SseClient.Msg("system", SUMMARIZE_PROMPT),
                SseClient.Msg("user", dialog),
            )
        ) ?: return null
        val json = extractJson(text, '{', '}') ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val title = o.optString("title").trim().take(20)
        val summary = o.optString("summary").trim().take(160)
        if (title.isBlank() || summary.isBlank()) return null
        val keywords = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(8) }
            ?: emptyList()
        val now = System.currentTimeMillis()
        return MemorySegment(
            id = "seg-" + now.toString(36),
            title = title,
            summary = summary,
            keywords = keywords,
            createdAt = now,
            lastHitAt = now,
            heat = 1,
            turnCount = turns.count { it.role == "user" },
            promoted = false,
        )
    }

    /** heat 到位：把话题片段里的稳定信息提炼成长期事实 */
    suspend fun promote(segment: MemorySegment): List<MemoryItem> {
        val text = collect(
            listOf(
                SseClient.Msg("system", PROMOTE_PROMPT),
                SseClient.Msg("user", "话题：${segment.title}\n摘要：${segment.summary}"),
            )
        ) ?: return emptyList()
        val arr = extractJson(text, '[', ']') ?: return emptyList()
        val json = runCatching { JSONArray(arr) }.getOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<MemoryItem>()
        for (i in 0 until json.length()) {
            val o = json.optJSONObject(i) ?: continue
            val t = o.optString("text").trim()
            if (t.length < 4 || t.length > 60) continue
            out.add(
                MemoryItem(
                    id = MemoryItem.makeId(t),
                    type = MemoryType.of(o.optString("type")),
                    text = t,
                    keywords = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            if (out.size >= 3) break
        }
        return out
    }

    private suspend fun collect(messages: List<SseClient.Msg>): String? {
        val sb = StringBuilder()
        return try {
            chatApi.stream(messages).collect { sb.append(it) }
            sb.toString().trim().ifBlank { null }
        } catch (e: Throwable) {
            Log.w(TAG, "llm call failed: ${e.message}")
            null
        }
    }

    private fun extractJson(raw: String, open: Char, close: Char): String? {
        val a = raw.indexOf(open)
        val b = raw.lastIndexOf(close)
        return if (a >= 0 && b > a) raw.substring(a, b + 1) else null
    }

    private companion object {
        const val TAG = "DshVoiceSeg"

        val SUMMARIZE_PROMPT = """
            你是「话题整理器」。把下面几轮对话压成一段可复用的话题记忆。
            只输出 JSON，不要任何解释、不要代码块：
            {"title":"8~14 字的话题标题","summary":"2~3 句、不超过 80 字：聊了什么、用户的结论或偏好、有没有待办","keywords":["词1","词2","词3"]}
            要求：写事实，不写寒暄；不要出现"用户说"这种句式堆砌；没有实质内容就输出 {}。
        """.trimIndent()

        val PROMOTE_PROMPT = """
            从下面这段话题记忆里，提炼值得长期记住的用户信息（稳定偏好 / 习惯 / 事实 / 有日期的计划）。
            只提取用户本人的，不要助手的话，不要临时情绪。
            只输出 JSON 数组，最多 3 项，不要解释：
            [{"type":"preference","text":"用户……","keywords":["词1","词2"]}]
            没有就输出 []。
        """.trimIndent()
    }
}
