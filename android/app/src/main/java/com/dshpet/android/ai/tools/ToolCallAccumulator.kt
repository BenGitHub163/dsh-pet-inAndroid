package com.dshpet.android.ai.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一帧 `delta.tool_calls[]` 里我们关心的字段，**已经拆成可判空的原生类型**。
 *
 * 为什么要单独一层：`optString` 会把 `arguments:null` 变成字符串 `"null"`、把缺失的
 * `name` 变成 `""`，直接在上面做累加逻辑就是坑 3/4/7 的来源。这里用 null 明确区分
 * "字段缺失/JSON null" 与 "给了空串"。
 */
data class ToolCallFrame(
    /** null = 缺 `index` 字段（个别网关），由累加器沿用上一次的序号 */
    val index: Int?,
    /** null = 缺字段或 JSON null；`""` 表示模型/网关明确给了空串 */
    val id: String?,
    /** `function` 对象是否存在（LiteLLM/vLLM 会先发只有 id 的帧） */
    val hasFunction: Boolean,
    val name: String?,
    /** null = 缺字段或 JSON null（DeepSeek 尾部哨兵就是这个形态） */
    val arguments: String?,
)

/**
 * **SSE 分片累加器**：把 `delta.tool_calls[]` 的碎片拼回完整的工具调用。
 *
 * 契约（RESEARCH.md §R2-A，13 条坑里最要命的 4 条）：
 *  1. `index` 是"第几个调用"，**不保证连续、不保证从 0 开始**，单帧可含多个 index → 用 Map，不用 List；
 *  2. `id` / `name` **只取首个非空**，后续帧给 `""` 或重复全名都不能覆盖；
 *  3. `arguments` 是字符串拼接（每帧本身是合法 JSON，转义已在传输层解开）；
 *  4. 四条脏帧必须丢：尾部哨兵（id 空 + arguments null）、`function` 缺失、`arguments:null`
 *     写成 `"null"`、`name` 每帧重复。
 *
 * **纯类**：不引用 `Context`、`android.util.Log`、OkHttp，便于 JVM 单测（TESTPLAN §2）。
 * 需要诊断信息时读 [warnings]，由调用方决定怎么记。
 */
class ToolCallAccumulator {

    private class Partial(val index: Int) {
        var id: String = ""
        var name: String = ""
        val args: StringBuilder = StringBuilder()
    }

    private val state = LinkedHashMap<Int, Partial>()
    private var lastIndex = 0

    /** 丢弃/异常帧的说明（最多留 [MAX_WARNINGS] 条，避免日志爆炸） */
    val warnings = mutableListOf<String>()

    /** 被接受的帧数 / 被丢弃的脏帧数，供测试和日志统计 */
    var accepted = 0
        private set
    var dropped = 0
        private set

    /** 喂一帧（原生字段形态） */
    fun onFrame(frame: ToolCallFrame) {
        // 规则 4-①：尾部哨兵/空帧 —— id 空 + name 空 + 没有 arguments，整帧丢弃
        if (frame.id.isNullOrBlank() && frame.name.isNullOrBlank() && frame.arguments == null) {
            dropped++
            warn("丢弃空帧（哨兵或无内容）index=${frame.index}")
            return
        }

        // 规则 1：缺 index 时沿用上一次（不要一律归到 0）
        val index = frame.index ?: lastIndex
        lastIndex = index
        val partial = state.getOrPut(index) { Partial(index) }
        accepted++

        // 规则 2（id）：只接受首个非空
        val id = frame.id?.trim().orEmpty()
        if (id.isNotEmpty()) {
            when {
                partial.id.isEmpty() -> partial.id = id
                partial.id != id -> warn("index=$index 出现不同 id（保留 ${partial.id}，忽略 $id）")
            }
        }

        // 规则 2（name）：首个非空；网关重发全名 → 以更长的那个为准；否则保留首个
        val name = frame.name?.trim().orEmpty()
        if (name.isNotEmpty()) {
            partial.name = when {
                partial.name.isEmpty() -> name
                partial.name == name -> partial.name
                name.startsWith(partial.name) -> name
                else -> partial.name
            }
        }

        // 规则 3（arguments）：逐帧拼接；`arguments:null` 已在 frameOf 里变成 null，这里不会拼进 "null"
        val fragment = frame.arguments
        if (!fragment.isNullOrEmpty()) {
            // 少数网关重发"完整 arguments"而不是增量：缓冲已非空、新片段又以 '{' 开头 → 视为整段替换
            if (partial.args.isNotEmpty() && fragment.startsWith("{") && partial.args[0] == '{') {
                warn("index=$index 收到整段重发，替换缓冲")
                partial.args.setLength(0)
            }
            partial.args.append(fragment)
        }
    }

    /** 喂一帧（JSON 形态），脏帧规则都在 [frameOf] 里做 */
    fun onToolCall(toolCall: JSONObject) = onFrame(frameOf(toolCall))

    /** 喂整个 `delta.tool_calls` 数组（单帧含多个调用是合法形态） */
    fun onToolCalls(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { onToolCall(it) }
        }
    }

    /**
     * 收尾：按 `index` 升序产出调用列表。
     *
     * - `name` 为空的 partial 丢弃（规则 4-①/⑤：没有名字的调用无法派发）；
     * - `arguments` 为空 → 规格化成 `{}`（坑 8：无参函数 `JSONObject("")` 会抛）；
     * - `arguments` 非法 → **原样带出**，由 `ToolCall.argsOrNull()` 判失败并回 `INVALID_ARGS`，
     *   这里不静默丢弃（RESEARCH §4：解析失败要报错，不能假装没调用）。
     */
    fun freeze(idPrefix: String = "call_local"): List<ToolCall> =
        state.values.sortedBy { it.index }.mapNotNull { partial ->
            if (partial.name.isBlank()) {
                warn("丢弃无名调用 index=${partial.index}")
                null
            } else {
                ToolCall(
                    id = partial.id.ifBlank { "${idPrefix}_${partial.index}" },
                    name = partial.name,
                    argumentsRaw = if (partial.args.isBlank()) "{}" else stripJsonNoise(partial.args.toString()),
                    index = partial.index,
                )
            }
        }

    /** 是否有待收尾的调用（`finish_reason` 缺失时的兜底判断用） */
    fun isEmpty(): Boolean = state.isEmpty()

    fun reset() {
        state.clear()
        warnings.clear()
        lastIndex = 0
        accepted = 0
        dropped = 0
    }

    private fun warn(message: String) {
        if (warnings.size < MAX_WARNINGS) warnings += message
    }

    companion object {
        private const val MAX_WARNINGS = 8

        /**
         * `JSONObject` → [ToolCallFrame]。
         *
         * 这里就是"脏帧防御"的第一道关口：
         *  - `function` 缺失 → 只保留 id（坑 6：LiteLLM/vLLM 首帧没有 function 对象）；
         *  - `arguments` 是 JSON null → 给 null，**绝不能** `optString` 成 `"null"`（坑 7）；
         *  - `index` 缺失或负数 → null（坑 1/2：index 是调用序号，不是数组下标）。
         */
        fun frameOf(toolCall: JSONObject): ToolCallFrame {
            val fn = toolCall.optJSONObject("function")
            val index = if (toolCall.has("index") && !toolCall.isNull("index")) {
                toolCall.optInt("index", -1).takeIf { it >= 0 }
            } else {
                null
            }
            return ToolCallFrame(
                index = index,
                id = optionalStr(toolCall, "id"),
                hasFunction = fn != null,
                name = fn?.let { optionalStr(it, "name") },
                arguments = fn?.let { optionalStr(it, "arguments") },
            )
        }

        /** 字段缺失或 JSON null → null；否则原样返回（可能是 `""`） */
        private fun optionalStr(json: JSONObject, key: String): String? {
            if (!json.has(key) || json.isNull(key)) return null
            return json.opt(key)?.toString()
        }
    }
}
