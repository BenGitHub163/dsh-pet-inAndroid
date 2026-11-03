package com.dshpet.android.ai.tools

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.security.MessageDigest

/**
 * **工具层的基础类型**：模型发来的调用、回给模型的声明、执行结果、以及"轮"的表示。
 *
 * 为什么单独一个文件：这四类东西有严格的**契约含义**（名字合法字符集、参数是 JSON 文本、
 * 结果必须能直接用 `role:"tool"` 回灌），分散到各处写迟早会走形。
 *
 * 约束（来自 SPEC §2.1 / RESEARCH R4-A）：
 *  - 工具名只允许 `a-zA-Z0-9_-`，≤128 字符；
 *  - 参数是**模型输出的原始 JSON 文本**，可能非法，因此只提供 [ToolCall.argsOrNull]/[ToolCall.args]
 *    两种取法，不允许在别处用字符串拼接；
 *  - 回灌给模型的 `content` 必须是**紧凑 JSON**（voice 场景 token 预算紧），用 [toolJson] 生成；
 *  - 参数里出现的未知字段一律忽略（`additionalProperties:false` 只约束模型，不该让执行崩）。
 */

/** 工具的风险级别。R4-B：低风险直接执行、中风险限长、高风险要白名单 + 二次确认。 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** 发给模型的一条工具声明（OpenAI 兼容 `tools[]` 元素）。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema：`{"type":"object","properties":{…},"required":[…]}`，同时用于本地校验 */
    val parameters: JSONObject,
    val risk: RiskLevel = RiskLevel.LOW,
    /** 需 beta `base_url` 才生效，本期恒 false（SPEC §2.1） */
    val strict: Boolean = false,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "工具名只允许字母数字下划线短横线：$name" }
    }

    /** `destructiveHint`：MCP 的命名习惯，等价于"不是只读" */
    val destructiveHint: Boolean get() = risk != RiskLevel.LOW

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            },
        )
    }

    private companion object {
        val NAME_PATTERN = Regex("^[a-zA-Z0-9_-]{1,128}$")
    }
}

/**
 * 模型要求执行的一次调用。
 *
 * [argumentsRaw] 是**原样**的参数字符串：模型可能给出带围栏、带尾逗号、甚至被截断的 JSON。
 * 需要结构化访问时用 [args]（会抛 [InvalidArgsException]），不要自己 `JSONObject(raw)`。
 */
data class ToolCall(
    /** 模型给的 `tool_call_id`；缺失时由累加器合成（回灌时必须一一对应） */
    val id: String,
    val name: String,
    val argumentsRaw: String,
    /** 同一条 assistant 消息内的序号，保持模型给的顺序 */
    val index: Int = 0,
) {
    /** 解析参数；失败返回 null（调用方据此回 `INVALID_ARGS`，而不是抛到上层） */
    fun argsOrNull(): JSONObject? = runCatching { JSONObject(stripJsonNoise(argumentsRaw)) }.getOrNull()

    /** 解析参数；失败抛 [InvalidArgsException] */
    fun args(): JSONObject = argsOrNull() ?: throw InvalidArgsException("arguments 不是合法 JSON：${argumentsRaw.take(80)}")

    /**
     * 去重键：正常情况下就是 `id`；模型没给 id（部分代理）时退化为
     * `index::name::参数摘要`，避免"同一调用被网关重放"导致重复副作用。
     */
    fun dedupKey(): String {
        val raw = id.trim()
        if (raw.isNotEmpty()) return "id:$raw"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(argumentsRaw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
        return "fp:$index:$name:$digest"
    }

    /** 回灌 `assistant.tool_calls[]` 用 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                // 原样回传：模型看到的就是自己写的那份（参数非法时也要照原样回）
                put("arguments", argumentsRaw)
            },
        )
    }
}

/** 失败类别。执行层据此决定"能不能让模型改口"（参数类）还是"直接给兜底文案"（执行类）。 */
enum class ToolError {
    /** 参数不合法/不是 JSON：不执行，允许模型重填一次 */
    INVALID_ARGS,

    /** 工具不存在（模型幻觉工具名）或本轮被禁用 */
    UNSUPPORTED,

    /** 单工具超时（默认 3s） */
    TIMEOUT,

    /** 前置条件不满足（没权限、没有恢复入口…） */
    DENIED,

    /** 目标不存在（应用没装、找不到唯一匹配） */
    NOT_FOUND,

    /** 执行时抛异常 */
    EXEC_FAILED,

    /** 同一 turn 内重复调用，已执行过 */
    DUPLICATE,

    /** 用户打断（协作式取消） */
    CANCELLED,
}

/** 一次工具执行的结果。[content] 会被原样作为 `role:"tool"` 的消息回灌（调用方负责截断）。 */
data class ToolResult(
    val callId: String,
    val ok: Boolean,
    val content: String,
    val error: ToolError? = null,
    val elapsedMs: Long = 0L,
) {
    companion object {
        /** 成功。`content` 用 [toolJson] 造，例如 `{"ok":true,"app":"微信"}` */
        fun success(callId: String, content: String, elapsedMs: Long = 0L): ToolResult =
            ToolResult(callId = callId, ok = true, content = content, error = null, elapsedMs = elapsedMs)

        /**
         * 失败。`content` 固定形如 `{"ok":false,"code":"app_not_allowed","retryable":false}`：
         * 模型据此改口，**不要**把异常类名/HTTP 码写进去（SPEC §6.2 文案规则 4）。
         */
        fun failure(
            callId: String,
            error: ToolError,
            code: String,
            retryable: Boolean = false,
            elapsedMs: Long = 0L,
        ): ToolResult = ToolResult(
            callId = callId,
            ok = false,
            content = toolJson("ok" to false, "code" to code, "retryable" to retryable),
            error = error,
            elapsedMs = elapsedMs,
        )
    }
}

/**
 * 工具拿到的依赖胶囊。
 *
 * 工具**不直接**引用 `VoiceLoop` / `PetOverlayService`：一是便于离线测试，二是
 * 以后换"谁在说话"的实现时不用改 5 个工具。
 */
interface ToolContext {
    val appContext: Context

    /** 多开时只有 0 号实例开放工具（DESIGN D7），工具可据此做保守判断 */
    val instanceId: Int

    /** 本轮的稳定 id，用于合成 tool_call_id、日志与去重表生命周期 */
    val turnId: String

    /** 与本轮绑定的协程作用域：用户打断时被级联取消，工具应协作式检查 */
    val scope: CoroutineScope

    /** 本轮是否还有效（被打断后为 false）。工具在不可逆动作前应检查 */
    fun isActive(): Boolean

    /**
     * 当前是否处于语音对话中。
     *
     * `close_conversation` 判 `not_in_voice_mode` 要用它，system 提示里"当前对话状态"
     * 那段也来自这里（SPEC §4.6）。
     */
    fun isVoiceActive(): Boolean

    /** 工具想直接说一句（走对话同一条音频通道，不新建 session） */
    fun say(text: String)

    /** `close_conversation` 用：由 VoiceLoop 实现（先播完最后一句再停） */
    fun closeConversation(reason: String)

    fun log(tag: String, msg: String)
}

/**
 * 对模型说话的"一轮"。
 *
 * 与 `ChatApi.Msg` 的关系：`Msg` 是**线上格式**（字段名照抄 API），[ChatTurn] 是**编排层**
 * 的表示（Assistant 可以直接持有 `List<ToolCall>`）。两者用 [toJson] 转换，别在编排层拼 JSON。
 */
sealed interface ChatTurn {

    fun toJson(): JSONObject

    data class System(val content: String) : ChatTurn {
        override fun toJson(): JSONObject = JSONObject().put("role", "system").put("content", content)
    }

    data class User(val content: String) : ChatTurn {
        override fun toJson(): JSONObject = JSONObject().put("role", "user").put("content", content)
    }

    /**
     * 助手这一轮。`content` 可为 null（只调工具不说话）；
     * [reasoningContent] 在带 tools 的会话里**必须原样回传**（RESEARCH R4-A 结论 3），否则 400。
     */
    data class Assistant(
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null,
    ) : ChatTurn {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("role", "assistant")
            put("content", content ?: JSONObject.NULL)
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", org.json.JSONArray().also { arr -> toolCalls.forEach { arr.put(it.toJson()) } })
            }
            if (reasoningContent != null) put("reasoning_content", reasoningContent)
        }
    }

    /** 工具结果回灌。`tool_call_id` 必须与 [Assistant.toolCalls] 里的 id 一一对应 */
    data class Tool(val callId: String, val content: String) : ChatTurn {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", callId)
            put("content", content)
        }
    }
}

/** 工具结果/成功回执的紧凑 JSON：`toolJson("ok" to true, "app" to "微信")` */
fun toolJson(vararg pairs: Pair<String, Any?>): String = JSONObject().apply {
    pairs.forEach { (k, v) -> put(k, if (v == null) JSONObject.NULL else v) }
}.toString()

/**
 * 清洗模型给的 JSON 文本：去 markdown 围栏、去尾逗号、trim。
 *
 * 只做**无损**清洗（不改语义），解析仍然失败就交给 [InvalidArgsException] 的处理路径，
 * 不做"猜它想写什么"的修补。
 */
internal fun stripJsonNoise(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```")
        if (s.startsWith("json", ignoreCase = true)) s = s.substring(4)
        s = s.trim()
        if (s.endsWith("```")) s = s.dropLast(3).trim()
    }
    return dropTrailingCommas(s)
}

/** 去掉 `,}` / `,]` 这种尾逗号；字符串内部的逗号不受影响（带转义状态机） */
private fun dropTrailingCommas(s: String): String {
    if (!s.contains(',')) return s
    val sb = StringBuilder(s.length)
    var inString = false
    var escaped = false
    for (i in s.indices) {
        val c = s[i]
        if (inString) {
            sb.append(c)
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        if (c == '"') {
            inString = true
            sb.append(c)
            continue
        }
        if (c == ',') {
            var j = i + 1
            while (j < s.length && s[j].isWhitespace()) j++
            if (j < s.length && (s[j] == '}' || s[j] == ']')) continue
        }
        sb.append(c)
    }
    return sb.toString()
}
