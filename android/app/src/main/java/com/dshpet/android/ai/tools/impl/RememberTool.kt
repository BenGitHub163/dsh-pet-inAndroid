package com.dshpet.android.ai.tools.impl

import android.content.Context
import com.dshpet.android.ai.tools.InvalidArgsException
import com.dshpet.android.ai.tools.RiskLevel
import com.dshpet.android.ai.tools.Schema
import com.dshpet.android.ai.tools.Tool
import com.dshpet.android.ai.tools.ToolCall
import com.dshpet.android.ai.tools.ToolContext
import com.dshpet.android.ai.tools.ToolError
import com.dshpet.android.ai.tools.ToolResult
import com.dshpet.android.ai.tools.ToolSpec
import com.dshpet.android.ai.tools.optionalEnum
import com.dshpet.android.ai.tools.optionalStringList
import com.dshpet.android.ai.tools.requireString
import com.dshpet.android.ai.tools.toolJson
import com.dshpet.android.voice.memory.MemoryItem
import com.dshpet.android.voice.memory.MemoryRetriever
import com.dshpet.android.voice.memory.MemoryStore
import com.dshpet.android.voice.memory.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `remember`：把一条**用户明确要求记住**的事实写进长期记忆。
 *
 * 与自动抽取（`MemoryManager.rememberAsync`，每轮异步跑）的关系：自动抽取靠模型判断"这句话值不值得记"，
 * 会漏；本工具解决"用户说了'记住'就必须记住"的确定性。两者写的是同一个 `voice_memory.json`，
 * `MemoryStore.upsert` 自带去重合并，所以 R4-B 的 OQ-12 选 A（允许重复写，靠 upsert 兜底）。
 *
 * 三道闸（R4-B：记忆是"中风险"，边界在执行侧而不是靠模型自觉）：
 *  1. 长度：归一化后 <4 字拒收（`text_too_short`，对齐 `MemoryStore.upsert` 的既有语义）；
 *  2. 敏感内容：密码/验证码/银行卡/证件号/API Key 一律拒收（正则初筛，见 [RememberPolicy]）；
 *  3. 只增不改：本工具不删除、不覆盖已有记忆（`upsert` 的合并行为除外）。
 */
class RememberTool(
    /** 便于单测注入替身；默认每次按 `appContext` 取同一个文件（`MemoryStore` 内部有缓存） */
    private val storeFactory: (Context) -> MemoryStore = { MemoryStore(it) },
) : Tool {

    override val idempotent: Boolean = true

    override val spec: ToolSpec = ToolSpec(
        name = "remember",
        description = "用户明确要求你记住某件事时调用。只写用户本人的稳定偏好、习惯、事实或有日期的计划；" +
            "不写寒暄、不写你自己说过的话、不写密码和证件号。",
        parameters = Schema.obj(
            "text" to Schema.str("一条事实，4 到 60 字，尽量以具体信息开头", maxLength = RememberPolicy.MAX_LENGTH),
            "type" to Schema.str(
                "记忆类型：preference 偏好 / habit 习惯 / fact 事实 / event 有日期的事件",
                enum = listOf("preference", "habit", "fact", "event"),
            ),
            "keywords" to Schema.strArray("最多 6 个检索关键词，可省略", maxItems = 6),
            required = listOf("text"),
        ),
        risk = RiskLevel.MEDIUM,
    )

    override suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult {
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started

        val args = call.args()
        val text = try {
            args.requireString("text", RememberPolicy.MAX_LENGTH)
        } catch (e: InvalidArgsException) {
            // 长度问题给专门的 code，模型据此换成"太短了，说全一点"
            val code = if (e.message?.contains("缺少") == true) "invalid_args" else "text_too_short"
            return ToolResult.failure(call.id, ToolError.INVALID_ARGS, code, elapsedMs = elapsed())
        }
        when (RememberPolicy.reject(text)) {
            RememberPolicy.Rejection.TOO_SHORT ->
                return ToolResult.failure(call.id, ToolError.INVALID_ARGS, "text_too_short", elapsedMs = elapsed())

            RememberPolicy.Rejection.SENSITIVE ->
                return ToolResult.failure(call.id, ToolError.DENIED, "sensitive_blocked", elapsedMs = elapsed())

            null -> Unit
        }

        val type = args.optionalEnum("type", RememberPolicy.TYPE_VALUES, "fact")
        val keywords = args.optionalStringList("keywords", 6)

        return withContext(Dispatchers.IO) {
            val store = storeFactory(ctx.appContext)
            val norm = MemoryItem.normalize(text)
            val existed = runCatching {
                store.all().any { MemoryItem.normalize(it.text) == norm }
            }.getOrElse {
                return@withContext ToolResult.failure(call.id, ToolError.EXEC_FAILED, "store_failed", elapsedMs = elapsed())
            }

            val now = System.currentTimeMillis()
            val item = MemoryItem(
                id = MemoryItem.makeId(text),
                type = MemoryType.of(type),
                text = text,
                keywords = keywords.ifEmpty { RememberPolicy.deriveKeywords(text) },
                createdAt = now,
                updatedAt = now,
                // 用户亲口要求记的：重要度与置信度都给高，检索时排前面
                importance = 0.8,
                confidence = 0.95,
                source = "tool",
            )
            val created = runCatching { store.upsert(item) }.getOrElse { e ->
                ctx.log(TAG, "upsert failed: ${e.message}")
                return@withContext ToolResult.failure(call.id, ToolError.EXEC_FAILED, "store_failed", elapsedMs = elapsed())
            }

            val action = when {
                existed -> "unchanged"
                created -> "created"
                else -> "updated" // 与已有条目语义近似，被 upsert 合并
            }
            ctx.log(TAG, "remember action=$action id=${item.id} len=${text.length}")
            ToolResult.success(
                callId = call.id,
                content = toolJson("ok" to true, "action" to action, "id" to item.id),
                elapsedMs = elapsed(),
            )
        }
    }

    private companion object {
        const val TAG = "DshToolRemember"
    }
}

/**
 * `remember` 的**可判定策略**（纯函数，不碰 Context/文件，便于 JVM 单测）。
 *
 * 敏感内容这块宁可误拒：桌宠记错一条无所谓，把密码写进 `voice_memory.json`
 * （用户可导出、会被拼进 system 提示）是真事故。
 */
internal object RememberPolicy {

    const val MIN_LENGTH = 4

    /** 与 SPEC §4.4 的 `text` 上限一致；`MemoryStore` 容量只有 300 条，长文本纯属浪费 */
    const val MAX_LENGTH = 60

    val TYPE_VALUES = setOf("preference", "habit", "fact", "event")

    enum class Rejection { TOO_SHORT, SENSITIVE }

    /** 命中即拒收：都是"看起来像凭据"的形状，正常生活事实不会长成这样 */
    private val SENSITIVE_RULES: List<Regex> = listOf(
        Regex("""\d[ \-]?\d{3}[ \-]?\d{4}[ \-]?\d{4}[ \-]?\d{4,7}"""),          // 银行卡 16~19 位（可带空格/短横）
        Regex("""\b\d{17}[\dXx]\b"""),                                          // 身份证 18 位
        Regex("""\b\d{15}\b"""),                                                // 老身份证 15 位
        Regex("""(密码|口令|pin码|password|passwd|pwd)""", RegexOption.IGNORE_CASE),
        Regex("""(验证码|校验码|短信码|动态码|verification code)""", RegexOption.IGNORE_CASE),
        Regex("""(银行卡|卡号|信用卡|借记卡|储蓄卡|cvv)""", RegexOption.IGNORE_CASE),
        Regex("""(身份证|护照号|社保卡|公积金账号)"""),
        Regex("""\bsk-[A-Za-z0-9_\-]{12,}"""),                                  // OpenAI 风格 key
        Regex("""(api[_\- ]?key|access[_\- ]?key|secret|token)\s*[:=：]""", RegexOption.IGNORE_CASE),
        Regex("""\b[A-Za-z0-9_\-]{32,}\b"""),                                   // 裸长串，多半是 token
    )

    fun reject(text: String): Rejection? {
        if (MemoryItem.normalize(text).length < MIN_LENGTH) return Rejection.TOO_SHORT
        if (SENSITIVE_RULES.any { it.containsMatchIn(text) }) return Rejection.SENSITIVE
        return null
    }

    /** 模型没给关键词时，用中文 bigram 兜底（与检索器同一套切分，保证能被检索到） */
    fun deriveKeywords(text: String, limit: Int = 4): List<String> =
        MemoryRetriever().bigrams(text).filter { it.length >= 2 }.take(limit).toList()
}
