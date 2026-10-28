package com.dshpet.android.voice.memory

import android.content.Context
import android.util.Log
import com.dshpet.android.chat.SseClient
import com.dshpet.android.voice.chat.ChatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 记录一条记忆时的"现场信息"（时间/地点/正在用的应用/来源）。
 * 这些字段不参与"记什么"，但参与**怎么整理、怎么回忆**：
 * 例如"你在北京说过"、"上周三在抖音刷到的时候"。
 */
data class MemoryContext(
    val occurredAt: Long = System.currentTimeMillis(),
    val place: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val app: String? = null,
    val source: String = "voice",
)

/**
 * 记忆提取器（mem0 的 "extract" 阶段）。
 *
 * 每轮对话结束后**异步**跑一次，不阻塞说话；
 * 用很短的 system 提示词把对话压成 JSON 事实，再交给 [MemoryStore] 合并。
 * 失败就静默放弃——记忆是增强功能，不能因为它影响对话。
 */
class MemoryExtractor(
    private val chat: ChatClient,
    private val cfgProvider: () -> SseClient.ChatCfg,
) {

    suspend fun extract(userText: String, answer: String, ctx: MemoryContext = MemoryContext()): List<MemoryItem> {
        if (userText.length < 4) return emptyList()
        val messages = listOf(
            SseClient.Msg("system", SYSTEM),
            SseClient.Msg("user", "用户：$userText\n助手：${answer.take(300)}"),
        )
        val sb = StringBuilder()
        runCatching {
            chat.stream(cfgProvider(), messages).collect { sb.append(it) }
        }.onFailure { Log.w(TAG, "extract call failed: ${it.message}") }
        return parse(sb.toString(), ctx)
    }

    private fun parse(raw: String, ctx: MemoryContext): List<MemoryItem> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val json = raw.substring(start, end + 1)
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<MemoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            if (text.length < 4 || text.length > 60) continue
            val type = MemoryType.of(o.optString("type"))
            val keywords = o.optJSONArray("keywords")?.let { k ->
                (0 until k.length()).mapNotNull { idx -> k.optString(idx).takeIf { it.isNotBlank() } }.take(8)
            } ?: emptyList()
            // 模型可以说"昨天/上周"，用 daysAgo 折算成发生时间
            val daysAgo = o.optInt("daysAgo", 0).coerceIn(0, 3650)
            out.add(
                MemoryItem(
                    id = MemoryItem.makeId(text),
                    type = type,
                    text = text,
                    keywords = keywords,
                    createdAt = now,
                    updatedAt = now,
                    occurredAt = now - daysAgo * 86_400_000L,
                    place = ctx.place,
                    lat = ctx.lat,
                    lng = ctx.lng,
                    app = ctx.app,
                    source = ctx.source,
                    confidence = if (daysAgo > 0) 0.6 else 0.7,
                )
            )
            if (out.size >= 3) break
        }
        return out
    }

    companion object {
        private const val TAG = "DshVoiceMemory"

        private val SYSTEM = """
            你是「记忆提取器」，只做一件事：从对话里挑出值得长期记住的用户信息。

            只提取这四类：
            - preference：稳定的喜好/厌恶（食物、音乐、作息、说话方式…）
            - habit：反复出现的行为（每天跑步、周一开会…）
            - fact：关于用户的事实（职业、城市、家人、宠物、设备…）
            - event：有日期的计划或重要经历

            绝对不要提取：
            - 临时状态或情绪（"我现在好累""今天有点烦"）
            - 提问本身、助手说的话、通用知识、客套话
            - 一次性、明天就没意义的细节

            输出格式：严格 JSON 数组，最多 3 项，不要任何解释文字、不要 markdown 代码块：
            [{"type":"preference","text":"用户喜欢喝不加糖的美式","keywords":["咖啡","美式","不加糖"],"daysAgo":0}]

            text 用第三人称，以「用户」开头，一句话，12~30 字。
            daysAgo：这句话提到的事情发生在几天前（今天=0，"昨天"=1，"上周"=7；说不清就 0）。
            没有值得记的就输出 []。
        """.trimIndent()
    }
}

/**
 * 记忆门面（**Facade**）——VoiceLoop 只跟它打交道。
 *
 *   retrieve(query)  读出与当前话题相关的记忆（并累加命中次数）
 *   promptBlock()    拼成一小段 system 提示
 *   rememberAsync()  一轮结束后异步抽取并入库
 */
class MemoryManager(
    context: Context,
    private val chat: ChatClient,
    private val cfgProvider: () -> SseClient.ChatCfg,
) {
    private val store = MemoryStore(context.applicationContext)
    private val retriever = MemoryRetriever()
    private val extractor = MemoryExtractor(chat, cfgProvider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extracting = AtomicBoolean(false)

    // ---- 分层记忆：中期话题片段 + 长期事实（MemoryOS 三层结构的本地实现）----
    private val segments = SegmentStore(context.applicationContext)
    private val segmentRetriever = SegmentRetriever()
    private val consolidator = MemoryConsolidator(chat, cfgProvider)
    private val consolidating = AtomicBoolean(false)

    // ---- 记忆自省：量化 + 定期让模型评估修订 ----
    private val curator = MemoryCurator(chat, cfgProvider, context.applicationContext)
    private val curating = AtomicBoolean(false)

    /** 整理完成后回调（用于把结果反馈到界面，而不是静默） */
    var onCurated: ((String) -> Unit)? = null

    fun lastReviewAt(): Long = curator.lastReviewAt

    /** 每轮调一次（只累加计数，开销可忽略） */
    fun onTurn() = curator.onTurn()

    /** 到点就整理：24h / 40 轮 / 40 条，任一满足；force=true 立即执行 */
    fun curateAsync(force: Boolean = false) {
        if (!curating.compareAndSet(false, true)) {
            Log.i(TAG, "curate: skipped (already running)")
            return
        }
        scope.launch {
            try {
                val items = store.all()
                Log.i(TAG, "curate start force=$force items=${items.size}")
                if (!force && !curator.shouldReview(items.size)) {
                    Log.i(TAG, "curate: not due yet")
                    return@launch
                }
                val ops = kotlinx.coroutines.withTimeoutOrNull(25_000) { curator.plan(items) } ?: emptyList()
                if (ops.isEmpty()) {
                    curator.markReviewed()
                    Log.i(TAG, "curate: nothing to change (${items.size} items)")
                    return@launch
                }
                val (newItems, result) = curator.apply(items, ops)
                store.replaceAll(newItems)
                curator.markReviewed()
                val summary = "整理：更新${result.updated} 合并${result.merged} 删除${result.deleted}" +
                    "（${items.size}→${newItems.size} 条）"
                Log.i(TAG, "curate: ops=${ops.size} $summary")
                onCurated?.invoke(summary)
            } catch (e: Throwable) {
                Log.w(TAG, "curate failed: ${e.message}")
            } finally {
                curating.set(false)
            }
        }
    }

    /** 一次检索的结果：长期事实 + 近期话题 */
    data class Retrieval(val facts: List<MemoryItem>, val segments: List<MemorySegment>) {
        val isEmpty: Boolean get() = facts.isEmpty() && segments.isEmpty()
        val size: Int get() = facts.size + segments.size
    }

    fun count(): Int = store.size()
    fun all(): List<MemoryItem> = store.all()
    fun clear() = store.clear()

    fun segmentCount(): Int = segments.size()
    fun allSegments(): List<MemorySegment> = segments.all()
    fun clearSegments() = segments.clear()

    /** 三层一起检索（长期事实 + 中期话题） */
    fun retrieveTiered(query: String): Retrieval {
        val facts = retrieve(query, 4)
        val segs = try {
            segmentRetriever.retrieve(segments.all(), query, 2).also { hits -> hits.forEach { segments.touch(it.id) } }
        } catch (e: Throwable) {
            Log.w(TAG, "retrieve segments failed: ${e.message}")
            emptyList()
        }
        return Retrieval(facts, segs)
    }

    /** 短期 → 中期：把最近几轮压成话题片段；heat 够了再提炼进长期 */
    fun consolidateAsync(turns: List<SseClient.Msg>) {
        if (!consolidating.compareAndSet(false, true)) return
        scope.launch {
            try {
                val seg = consolidator.summarize(turns) ?: return@launch
                val merged = segments.upsertMerge(seg)
                Log.i(TAG, "segment: ${merged.title} heat=${merged.heat} turns=${merged.turnCount}")
                if (merged.heat >= HEAT_THRESHOLD && !merged.promoted) {
                    val facts = consolidator.promote(merged)
                    var added = 0
                    facts.forEach { if (store.upsert(it)) added++ }
                    segments.markPromoted(merged.id)
                    Log.i(TAG, "promote heat=${merged.heat}: facts=${facts.size} added=$added")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "consolidate failed: ${e.message}")
            } finally {
                consolidating.set(false)
            }
        }
    }

    /** 分层记忆的提示词块 */
    fun promptBlock(r: Retrieval): String {
        if (r.isEmpty) return ""
        val sb = StringBuilder()
        if (r.facts.isNotEmpty()) {
            sb.append(promptBlock(r.facts))
        }
        if (r.segments.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("【最近聊过的话题（中期记忆）】\n")
            r.segments.take(2).forEach { sb.append("- ").append(it.title).append("：").append(it.summary).append('\n') }
            sb.append("使用要求：只在相关时自然带出（例如「上次你说到戒烟…」），不要显得在翻记录。")
        }
        return sb.toString()
    }

    /** 按当前这句话检索相关记忆 */
    fun retrieve(query: String, limit: Int = 4): List<MemoryItem> {
        if (query.isBlank()) return emptyList()
        return try {
            val hits = retriever.retrieve(store.all(), query, limit)
            hits.forEach { store.touch(it.id) }
            hits
        } catch (e: Throwable) {
            Log.w(TAG, "retrieve failed: ${e.message}")
            emptyList()
        }
    }

    /** 拼成 system 提示块（控制长度，避免挤占 token） */
    fun promptBlock(items: List<MemoryItem>): String {
        if (items.isEmpty()) return ""
        val lines = items.take(4).joinToString("\n") { "- ${it.text}" }
        return """
            【关于用户的长期记忆（按相关度挑选）】
            $lines

            使用要求：只在和当前话题相关时自然带出，像老朋友记得那样顺口提一句；
            不要逐条复述，不要说"根据我的记忆/记录显示"，不要主动罗列。
        """.trimIndent()
    }

    /** 一轮结束后异步抽取（同一个时刻只跑一个，避免并发写文件） */
    fun rememberAsync(userText: String, answer: String, ctx: MemoryContext = MemoryContext()) {
        if (!extracting.compareAndSet(false, true)) return
        scope.launch {
            try {
                val items = extractor.extract(userText, answer, ctx)
                var added = 0
                items.forEach { if (store.upsert(it)) added++ }
                if (items.isNotEmpty()) {
                    Log.i(TAG, "remember: extracted=${items.size} added=$added total=${store.size()} place=${ctx.place} app=${ctx.app}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "remember failed: ${e.message}")
            } finally {
                extracting.set(false)
            }
        }
    }

    private companion object {
        const val TAG = "DshVoiceMemory"

        /** 话题片段的 heat 到 5 就提炼成长期事实（对齐 MemoryOS 的默认阈值） */
        const val HEAT_THRESHOLD = 5
    }
}
