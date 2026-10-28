package com.dshpet.android.voice.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

/**
 * 长期记忆的数据模型 + 本地存储。
 *
 * 借鉴 mem0 的 Pipeline（extract → consolidate → retrieve），但存储换成
 * **一个 JSON 文件**：桌宠场景记忆量很小（几百条），不上向量库是更合理的选择
 * ——省掉 embed 模型、省掉 100MB 依赖、断电也不会坏库。
 */
enum class MemoryType(val raw: String, val label: String) {
    PREFERENCE("preference", "偏好"),
    HABIT("habit", "习惯"),
    FACT("fact", "事实"),
    EVENT("event", "事件");

    companion object {
        fun of(raw: String?): MemoryType =
            entries.firstOrNull { it.raw.equals(raw, true) } ?: FACT
    }
}

data class MemoryItem(
    val id: String,
    val type: MemoryType,
    val text: String,
    val keywords: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    var lastUsedAt: Long = 0L,
    var hitCount: Int = 0,
    /** 重要度 0~1（模型评估 + 命中次数共同影响） */
    var importance: Double = 0.5,
    /** 置信度 0~1（用户亲口说的 > 推断出来的） */
    var confidence: Double = 0.7,
    // ---- 辅助信息：给"记忆整理"和"个性化回复"提供上下文（对齐 Generative Agents 的 memory stream 字段）----
    /** 这件事发生的时间（可能早于记录时间，例如"上周去了成都"） */
    var occurredAt: Long = 0L,
    /** 记录时的地点（城市/区域名），用于"你在北京说的"这种回忆 */
    var place: String? = null,
    var lat: Double? = null,
    var lng: Double? = null,
    /** 记录时用户正在用的应用（例如"在刷抖音时说的"） */
    var app: String? = null,
    /** 来源：voice / text */
    var source: String = "voice",
) {
    /**
     * 量化评分：用于检索排序、淘汰、以及"要不要晋升为长期"。
     *   importance 占大头，近期用过的加分，说得越肯定越可信。
     */
    fun score(now: Long): Double {
        val days = (now - maxOf(updatedAt, lastUsedAt)) / 86_400_000.0
        val recency = 1.0 / (1.0 + days / 30.0)
        val hits = hitCount.coerceAtMost(10) / 10.0
        return importance * 0.5 + recency * 0.2 + hits * 0.2 + confidence * 0.1
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.raw)
        put("text", text)
        put("keywords", JSONArray(keywords))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastUsedAt", lastUsedAt)
        put("hitCount", hitCount)
        put("importance", importance)
        put("confidence", confidence)
        put("occurredAt", occurredAt)
        put("place", place ?: "")
        put("lat", lat ?: 0.0)
        put("lng", lng ?: 0.0)
        put("app", app ?: "")
        put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject): MemoryItem = MemoryItem(
            id = o.optString("id"),
            type = MemoryType.of(o.optString("type")),
            text = o.optString("text"),
            keywords = o.optJSONArray("keywords")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList(),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            lastUsedAt = o.optLong("lastUsedAt"),
            hitCount = o.optInt("hitCount"),
            importance = o.optDouble("importance", 0.5),
            confidence = o.optDouble("confidence", 0.7),
            occurredAt = o.optLong("occurredAt", 0L),
            place = o.optString("place").takeIf { it.isNotBlank() },
            lat = o.optDouble("lat", 0.0).takeIf { it != 0.0 },
            lng = o.optDouble("lng", 0.0).takeIf { it != 0.0 },
            app = o.optString("app").takeIf { it.isNotBlank() },
            source = o.optString("source", "voice"),
        )

        fun makeId(text: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            return md.digest(normalize(text).toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
        }

        /** 归一化：只保留字母、数字、汉字（去空白/标点），用于去重和 id 生成 */
        fun normalize(s: String): String =
            s.lowercase().filter { it.isLetterOrDigit() }
    }
}

/**
 * JSON 文件存储（**Repository**）：负责持久化、去重合并、容量淘汰。
 *
 * 容量策略：超过 [MAX_ITEMS] 时，按「最久没用 + 命中少」淘汰，
 * 但**偏好/事实类保留权重大**（这两类最像"人设"，丢了最伤体验）。
 */
class MemoryStore(private val ctx: Context) {

    private val file = File(ctx.filesDir, "voice_memory.json")
    private val lock = Any()
    private var cache: MutableList<MemoryItem>? = null

    fun all(): List<MemoryItem> = synchronized(lock) { load().toList() }

    fun size(): Int = synchronized(lock) { load().size }

    /**
     * 合并写入（mem0 的 consolidate 简化版）：
     *  - 完全重复（归一化后相同）→ 只更新 updatedAt；
     *  - 语义近似（关键词重合度 ≥ 0.75）→ 用新文本覆盖，保留 createdAt/hitCount；
     *  - 否则新增。
     */
    fun upsert(item: MemoryItem): Boolean = synchronized(lock) {
        val list = load()
        val norm = MemoryItem.normalize(item.text)
        if (norm.length < 4) return false

        val sameText = list.firstOrNull { MemoryItem.normalize(it.text) == norm }
        if (sameText != null) {
            sameText.let { _ ->
                list.remove(sameText)
                list.add(sameText.copy(updatedAt = System.currentTimeMillis()))
            }
            save(list); return false
        }

        val similar = list.firstOrNull { jaccard(it.keywords.toSet(), item.keywords.toSet()) >= 0.75 }
        if (similar != null) {
            list.remove(similar)
            list.add(
                similar.copy(
                    text = item.text,
                    keywords = (similar.keywords + item.keywords).distinct().take(8),
                    updatedAt = System.currentTimeMillis(),
                )
            )
            save(list); return false
        }

        list.add(item)
        trim(list)
        save(list)
        return true
    }

    fun touch(id: String) = synchronized(lock) {
        val list = load()
        val it = list.firstOrNull { m -> m.id == id } ?: return@synchronized
        it.lastUsedAt = System.currentTimeMillis()
        it.hitCount += 1
        save(list)
    }

    /** 整表替换（记忆整理器执行完批量操作后落盘） */
    fun replaceAll(items: List<MemoryItem>) = synchronized(lock) { save(items) }

    fun clear() = synchronized(lock) {
        cache = mutableListOf()
        runCatching { file.delete() }
    }

    private fun trim(list: MutableList<MemoryItem>) {
        if (list.size <= MAX_ITEMS) return
        val now = System.currentTimeMillis()
        val sorted = list.sortedBy { m ->
            // 分数越小越先被淘汰
            val weight = when (m.type) {
                MemoryType.PREFERENCE, MemoryType.FACT -> 2.0
                MemoryType.HABIT -> 1.5
                MemoryType.EVENT -> 0.5
            }
            val days = (now - maxOf(m.updatedAt, m.lastUsedAt)) / 86_400_000.0
            (ln(1.0 + m.hitCount) + 1.0) * weight - days * 0.05
        }
        val keep = sorted.takeLast(MAX_ITEMS).toMutableList()
        list.clear()
        list.addAll(keep)
    }

    private fun load(): MutableList<MemoryItem> {
        cache?.let { return it }
        val list = mutableListOf<MemoryItem>()
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    runCatching { list.add(MemoryItem.fromJson(arr.getJSONObject(i))) }
                }
                android.util.Log.i(TAG, "loaded ${list.size} items from ${file.absolutePath}")
            } else {
                android.util.Log.i(TAG, "no memory file yet (${file.absolutePath})")
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "load failed: ${e.message}")
        }
        cache = list
        return list
    }

    private fun save(list: List<MemoryItem>) {
        cache = list.toMutableList()
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString())
            val ok = if (file.exists()) file.delete() else true
            if (!tmp.renameTo(file)) {
                // 有些设备 rename 会失败，退化成直接覆盖写
                file.writeText(arr.toString())
                tmp.delete()
            }
            android.util.Log.i(TAG, "saved ${list.size} items -> ${file.absolutePath} (${file.length()}B, deleted=$ok)")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "save failed: ${e.message}", e)
        }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    private companion object {
        const val TAG = "DshVoiceMemory"
        const val MAX_ITEMS = 300
    }
}

/**
 * 检索排序（**Strategy**：以后想换成向量检索，只替换这个类）。
 *
 * 打分 = 0.75 × 中文 bigram 余弦相似度
 *      + 0.15 × 时间衰减（越近越高，30 天半衰）
 *      + 0.10 × 命中次数（常用的说明重要）
 *
 * 为什么用 bigram：中文没有空格，分词要么上词典要么上模型；
 * 双字切分在这个量级（几百条记忆）效果足够，且零依赖、零延迟。
 */
class MemoryRetriever {

    fun retrieve(items: List<MemoryItem>, query: String, limit: Int = 4): List<MemoryItem> {
        if (items.isEmpty() || query.isBlank()) return emptyList()
        val q = bigrams(query)
        if (q.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        return items
            .map { it to score(it, q, now) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun score(item: MemoryItem, q: Set<String>, now: Long): Double {
        val doc = bigrams(item.text + " " + item.keywords.joinToString(" "))
        if (doc.isEmpty()) return 0.0
        val inter = q.intersect(doc).size
        val cosine = if (inter == 0) 0.0 else inter.toDouble() / Math.sqrt(q.size.toDouble() * doc.size)
        val days = (now - item.createdAt) / 86_400_000.0
        val recency = 1.0 / (1.0 + days / 30.0)
        val hits = (item.hitCount.coerceAtMost(5)) / 5.0
        return cosine * 0.75 + recency * 0.15 + hits * 0.10
    }

    /** 中文按双字切分，英文/数字按词切分 */
    fun bigrams(s: String): Set<String> {
        val out = HashSet<String>()
        val text = s.lowercase()
        Regex("[a-z0-9]+").findAll(text).forEach { out.add(it.value) }
        val han = text.replace(Regex("[^\\u4e00-\\u9fa5]"), "")
        for (i in 0 until han.length - 1) out.add(han.substring(i, i + 2))
        if (han.length == 1) out.add(han)
        return out
    }

    private companion object { const val MIN_SCORE = 0.06 }
}
