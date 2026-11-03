package com.dshpet.android.ai.tools

import org.json.JSONArray

/**
 * **工具注册表**：UI、路由与执行层唯一的工具入口。
 *
 * 三个过滤条件（DESIGN D7 / SPEC §5.1 P1）：
 *  1. 总开关 [enabled] 关 → 不给模型任何工具（行为与改造前完全一致，可回滚）；
 *  2. [instanceId] != 0 → 不给工具。多开时工具副作用全是进程级的（`petHidden`、
 *     `VoiceRuntime`、记忆文件），让第二个实例也能调只会互相打脸；
 *  3. 本轮熔断（连续失败/幻觉工具名）：只在本轮生效，[resetTurn] 后恢复。
 *
 * 注意：这里**不做**参数校验与执行，那些在 `ToolExecutor`；注册表只回答"这一轮有哪些工具可用"。
 */
class ToolRegistry(
    val instanceId: Int = 0,
    var enabled: Boolean = true,
) {

    private val tools = LinkedHashMap<String, Tool>()
    private val disabledThisTurn = LinkedHashMap<String, String>()

    /** 本轮是否允许下发工具（开关 + 实例号） */
    val isAvailable: Boolean get() = enabled && instanceId == 0

    fun register(tool: Tool): ToolRegistry {
        tools[tool.spec.name] = tool
        return this
    }

    fun registerAll(vararg list: Tool): ToolRegistry = apply { list.forEach { register(it) } }

    /** 按名字找可执行的工具；本轮被熔断或未注册都返回 null */
    fun find(name: String): Tool? = if (disabledThisTurn.containsKey(name)) null else tools[name]

    fun names(): List<String> = tools.keys.toList()

    /** 本轮要发给模型的声明（已按开关/实例/熔断过滤） */
    fun specs(): List<ToolSpec> =
        if (!isAvailable) emptyList()
        else tools.values.map { it.spec }.filter { !disabledThisTurn.containsKey(it.name) }

    /** 请求体里的 `tools` 字段；返回 null 表示**不要**带这个字段（而不是空数组） */
    fun toolsJson(): JSONArray? {
        val list = specs()
        if (list.isEmpty()) return null
        return JSONArray().apply { list.forEach { put(it.toJson()) } }
    }

    /** 熔断一个工具：本轮后续请求不再下发它，模型也就不会继续撞墙 */
    fun disableForTurn(name: String, why: String) {
        if (tools.containsKey(name)) disabledThisTurn[name] = why
    }

    fun disabledReason(name: String): String? = disabledThisTurn[name]

    /** 每轮开始调一次，清空熔断 */
    fun resetTurn() = disabledThisTurn.clear()

    /** 给模型看的可用工具名（幻觉工具名时回灌用，≤300 字） */
    fun namesForModel(): String = names().joinToString("/").take(300)

    val size: Int get() = tools.size
}
