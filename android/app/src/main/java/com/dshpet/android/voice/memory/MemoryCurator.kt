package com.dshpet.android.voice.memory

import android.content.Context
import android.util.Log
import com.dshpet.android.chat.SseClient
import com.dshpet.android.voice.chat.ChatClient
import kotlinx.coroutines.flow.collect
import org.json.JSONArray

/**
 * 记忆"自省"（**Curator**）——按时间/规模触发，让模型评估并修订整批记忆。
 *
 * 为什么需要：
 *  抽取是一条一条发生的，天然会攒出**重复、过时、互相矛盾**的条目；
 *  再叠加"用户只是随口一说"的低价值记忆，检索时噪音会越来越大。
 *
 * 做法（对齐 mem0 的 consolidate 思路）：
 *  把当前记忆压成紧凑清单（id + 类型 + 文本 + 命中次数 + 重要度）交给模型，
 *  模型只允许输出三种操作：update（改写/调整重要度）、merge（合并同类）、delete（删除过时）。
 *  我们本地执行这些操作 —— 模型不直接改文件，**可审计、可回滚**。
 */
class MemoryCurator(
    private val chat: ChatClient,
    private val cfgProvider: () -> SseClient.ChatCfg,
    context: Context,
) {

    private val prefs = context.getSharedPreferences("voice_memory_meta", Context.MODE_PRIVATE)

    var lastReviewAt: Long
        get() = prefs.getLong(KEY_LAST_REVIEW, 0L)
        private set(v) = prefs.edit().putLong(KEY_LAST_REVIEW, v).apply()

    var turnsSinceReview: Int
        get() = prefs.getInt(KEY_TURNS, 0)
        private set(v) = prefs.edit().putInt(KEY_TURNS, v).apply()

    fun onTurn() {
        turnsSinceReview += 1
    }

    /** 触发策略：距上次 ≥24h，或攒了 ≥40 轮，或条目 ≥40 条 */
    fun shouldReview(itemCount: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (itemCount < 8) return false
        val byTime = now - lastReviewAt >= REVIEW_INTERVAL_MS
        val byTurns = turnsSinceReview >= REVIEW_TURNS
        val bySize = itemCount >= REVIEW_ITEMS
        return byTime || byTurns || bySize
    }

    fun markReviewed() {
        lastReviewAt = System.currentTimeMillis()
        turnsSinceReview = 0
    }

    data class Op(val op: String, val ids: List<String>, val text: String?, val importance: Double?)

    data class Result(val updated: Int, val merged: Int, val deleted: Int) {
        val touched: Int get() = updated + merged + deleted
    }

    /** 让模型给出一批修订操作 */
    suspend fun plan(items: List<MemoryItem>): List<Op> {
        val fmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
        val list = items.sortedByDescending { it.importance }.take(MAX_SEND).joinToString("\n") { m ->
            val when_ = fmt.format(java.util.Date(if (m.occurredAt > 0) m.occurredAt else m.createdAt))
            val where_ = m.place?.takeIf { it.isNotBlank() } ?: "-"
            val app_ = m.app?.takeIf { it.isNotBlank() } ?: "-"
            "- id=${m.id} | ${m.type.raw} | 命中${m.hitCount} | 重要度${"%.1f".format(m.importance)} " +
                "| 发生$when_ | 地点$where_ | 应用$app_ | ${m.text}"
        }
        val messages = listOf(
            SseClient.Msg("system", PROMPT),
            SseClient.Msg("user", "现有记忆：\n$list"),
        )
        Log.i(TAG, "plan: asking model about ${items.size} items (sent ${minOf(items.size, MAX_SEND)})")
        val sb = StringBuilder()
        runCatching { chat.stream(cfgProvider(), messages).collect { sb.append(it) } }
            .onFailure { Log.w(TAG, "plan failed: ${it.message}"); return emptyList() }
        val raw = sb.toString()
        Log.i(TAG, "plan: reply ${raw.length} chars: ${raw.take(500).replace("\n", " ")}")

        val objects = parseObjects(raw)
        Log.i(TAG, "plan: parsed ${objects.size} json objects")
        val ops = ArrayList<Op>()
        for (o in objects) {
            val op = o.optString("op").lowercase().trim()
            val ids = when {
                o.has("ids") -> o.optJSONArray("ids")?.let { ja -> (0 until ja.length()).map { ja.optString(it) } } ?: emptyList()
                o.has("id") -> listOf(o.optString("id"))
                else -> emptyList()
            }.filter { it.isNotBlank() }
            if (ids.isEmpty() || op !in setOf("update", "merge", "delete")) {
                Log.w(TAG, "plan: skip op='$op' ids=$ids")
                continue
            }
            ops.add(
                Op(
                    op = op,
                    ids = ids,
                    text = o.optString("text").takeIf { it.isNotBlank() },
                    importance = if (o.has("importance")) o.optDouble("importance", 0.5) else null,
                )
            )
        }
        return ops
    }

    /**
     * 容错解析：先按数组解析；失败就逐行找 {...}（模型偶尔会包代码块、加解释、少括号）。
     */
    private fun parseObjects(raw: String): List<org.json.JSONObject> {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val out = ArrayList<org.json.JSONObject>()

        val a = cleaned.indexOf('[')
        val b = cleaned.lastIndexOf(']')
        if (a >= 0 && b > a) {
            val parsed = runCatching { JSONArray(cleaned.substring(a, b + 1)) }.getOrNull()
            if (parsed != null) {
                for (i in 0 until parsed.length()) parsed.optJSONObject(i)?.let { out.add(it) }
                if (out.isNotEmpty()) return out
            }
        }
        // 退化：逐行 / 逐个花括号对象
        Regex("\\{[^{}]*\\}").findAll(cleaned).forEach { m ->
            runCatching { out.add(org.json.JSONObject(m.value)) }
        }
        return out
    }

    /** 本地执行操作（纯函数式：输入旧列表，返回新列表 + 统计） */
    fun apply(items: List<MemoryItem>, ops: List<Op>): Pair<List<MemoryItem>, Result> {
        val map = LinkedHashMap<String, MemoryItem>()
        items.forEach { map[it.id] = it }
        var updated = 0
        var merged = 0
        var deleted = 0
        val now = System.currentTimeMillis()

        for (op in ops) {
            when (op.op) {
                "update" -> {
                    val id = op.ids.first()
                    val old = map[id] ?: continue
                    val text = op.text ?: old.text
                    map[id] = old.copy(
                        text = text,
                        updatedAt = now,
                        importance = (op.importance ?: old.importance).coerceIn(0.0, 1.0),
                    )
                    updated++
                }
                "delete" -> {
                    var hit = false
                    op.ids.forEach { if (map.remove(it) != null) hit = true }
                    if (hit) deleted++
                }
                "merge" -> {
                    val olds = op.ids.mapNotNull { map[it] }
                    if (olds.size < 2) continue
                    val text = op.text ?: olds.first().text
                    olds.forEach { map.remove(it.id) }
                    val base = olds.first()
                    map[base.id] = base.copy(
                        text = text,
                        keywords = olds.flatMap { it.keywords }.distinct().take(10),
                        updatedAt = now,
                        importance = (op.importance ?: olds.maxOf { it.importance }).coerceIn(0.0, 1.0),
                        hitCount = olds.sumOf { it.hitCount },
                        confidence = olds.maxOf { it.confidence },
                    )
                    merged++
                }
            }
        }
        return map.values.toList() to Result(updated, merged, deleted)
    }

    companion object {
        private const val TAG = "DshVoiceCurator"
        private const val KEY_LAST_REVIEW = "last_review_at"
        private const val KEY_TURNS = "turns_since_review"
        private const val REVIEW_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val REVIEW_TURNS = 40
        private const val REVIEW_ITEMS = 40
        private const val MAX_SEND = 60

        private val PROMPT = """
            你是「记忆整理员」。下面是桌宠记住的用户信息清单（每条有 id、类型、命中次数、重要度）。
            请只做三件事，让这份记忆更干净、更有用：
            1. update：把表述改得更准确/更短，或调整重要度 importance（0~1）。
               —— 用户亲口说过、长期有效的 → 0.8 以上；猜测出来的、一次性的 → 0.2~0.4。
            2. merge：把说的是同一件事的多条合并成一条（给出新的 text）。
            3. delete：删掉过时的、和别的条目矛盾的、明显没价值（一次性状态）的。

            规则：
            - 不要新增条目；不要改动与你判断无关的条目。
            - **可以借助「发生时间 / 地点 / 应用」判断两条是否同一件事**：
              同一天、同一地点、同一个应用里的两条，很可能是同一件事，应该 merge。
            - 时间久远且从未被用过的（命中0、重要度低）优先 delete。
            - 最多输出 8 个操作；没有可改的就输出 []。
            - 只输出 JSON 数组，不要任何解释、不要代码块；你的输出**不会被朗读**，也不该出现任何面向用户的措辞。

            格式示例：
            [{"op":"update","id":"a1b2","text":"用户喜欢不加糖的美式","importance":0.9},
             {"op":"merge","ids":["c3","d4"],"text":"用户每天早上喝一杯美式","importance":0.8},
             {"op":"delete","id":"e5"}]
        """.trimIndent()
    }
}
