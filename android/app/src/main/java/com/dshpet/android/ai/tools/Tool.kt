package com.dshpet.android.ai.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个本地工具。
 *
 * 实现约定（SPEC §5.5 / DESIGN §5.4）：
 *  1. `execute` 第一行调 `ctx.scope.ensureActive()`，每个 `withContext` 前后看 `ctx.isActive()`；
 *  2. **不可逆动作放最后一步**（先解析候选、最后才 `startActivity`），把取消窗口留到最大；
 *  3. 参数一律用 [requireString] / [requireInt] 这类函数取，取不到就抛 [InvalidArgsException]，
 *     由执行层翻译成 `{ok:false,code:"invalid_args"}` 让模型改口；
 *  4. 不要在这里打日志到 TTS 通道，也不要抛业务异常：失败用 [ToolResult.failure] 返回。
 */
interface Tool {

    val spec: ToolSpec

    /** true = 重复执行无副作用（可提前派发、可重试）。首批只有 `remember`/`pet_visibility`/`close_conversation` */
    val idempotent: Boolean get() = false

    suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult
}

/** 参数不合法：执行层捕获后回 `INVALID_ARGS`，**不执行**任何副作用。 */
class InvalidArgsException(message: String) : Exception(message)

/**
 * **工具参数的 JSON Schema 骨架**。
 *
 * 存在的意义：5 个工具的 schema 手写 JSON 极易走形（少个 `required`、类型写成 `int`），
 * 而 schema 同时被模型和执行侧校验使用，写错一次就是"模型觉得能用、本地拒绝"。
 */
object Schema {

    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            put("required", JSONArray().apply { required.forEach { put(it) } })
            put("additionalProperties", false)
        }

    fun str(desc: String, maxLength: Int? = null, enum: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", "string")
            put("description", desc)
            maxLength?.let { put("maxLength", it) }
            enum?.let { values -> put("enum", JSONArray().apply { values.forEach { put(it) } }) }
        }

    fun int(desc: String, min: Int, max: Int): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", desc)
        put("minimum", min)
        put("maximum", max)
    }

    fun bool(desc: String): JSONObject = JSONObject().apply {
        put("type", "boolean")
        put("description", desc)
    }

    fun strArray(desc: String, maxItems: Int): JSONObject = JSONObject().apply {
        put("type", "array")
        put("description", desc)
        put("maxItems", maxItems)
        put("items", JSONObject().put("type", "string"))
    }
}

// ---------------------------------------------------------------- 取参数

fun JSONObject.requireString(key: String, maxLen: Int = 200): String {
    val v = optString(key, "").trim()
    if (v.isEmpty()) throw InvalidArgsException("缺少参数 $key")
    if (v.length > maxLen) throw InvalidArgsException("参数 $key 超过 $maxLen 字")
    return v
}

fun JSONObject.optionalString(key: String, maxLen: Int = 200): String? {
    val v = optString(key, "").trim()
    if (v.isEmpty()) return null
    if (v.length > maxLen) throw InvalidArgsException("参数 $key 超过 $maxLen 字")
    return v
}

fun JSONObject.requireInt(key: String, min: Int, max: Int): Int {
    val v = asInt(key) ?: throw InvalidArgsException("参数 $key 不是整数")
    if (v < min || v > max) throw InvalidArgsException("参数 $key 超出范围 $min..$max（收到 $v）")
    return v
}

fun JSONObject.requireBoolean(key: String): Boolean = when (val v = opt(key)) {
    is Boolean -> v
    is String -> when (v.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw InvalidArgsException("参数 $key 不是布尔值")
    }
    else -> throw InvalidArgsException("参数 $key 不是布尔值")
}

fun JSONObject.optionalEnum(key: String, allowed: Set<String>, default: String): String {
    val v = optString(key, "").trim()
    if (v.isEmpty()) return default
    if (v !in allowed) throw InvalidArgsException("参数 $key 只能是 ${allowed.joinToString("/")}")
    return v
}

fun JSONObject.optionalStringList(key: String, maxItems: Int = 6): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(minOf(arr.length(), maxItems))
    for (i in 0 until arr.length()) {
        if (out.size >= maxItems) break
        val s = arr.optString(i, "").trim()
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}

/** 数字宽容取法：模型偶尔把整数写成 `"30"` 或 `30.0` */
private fun JSONObject.asInt(key: String): Int? {
    val v = opt(key) ?: return null
    return when (v) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
        is Number -> {
            val d = v.toDouble()
            if (d % 1.0 == 0.0 && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
        }
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
        else -> null
    }
}

// ---------------------------------------------------------------- 本地校验

/**
 * 按 [ToolSpec.parameters] 校验参数，返回错误文案（中文，进日志与回灌），通过则返回 null。
 *
 * 只实现我们用到的最小子集：`required` / `type` / `enum` / `minimum` / `maximum` /
 * `maxLength` / `maxItems`。schema 之外的字段直接忽略（SPEC §2.1：模型会编造字段，
 * 忽略比拒绝更不容易把正常调用挡在外面）。
 */
fun validateArgs(spec: ToolSpec, args: JSONObject): String? {
    val params = spec.parameters
    params.optJSONArray("required")?.let { required ->
        for (i in 0 until required.length()) {
            val key = required.optString(i, "")
            if (key.isEmpty()) continue
            if (!args.has(key) || args.isNull(key)) return "缺少必填参数 $key"
        }
    }
    val props = params.optJSONObject("properties") ?: return null
    val it = args.keys()
    while (it.hasNext()) {
        val key = it.next()
        val schema = props.optJSONObject(key) ?: continue // 未知字段：忽略
        val value = args.opt(key) ?: continue
        if (value == JSONObject.NULL) continue
        val err = checkValue(key, value, schema)
        if (err != null) return err
    }
    return null
}

private fun checkValue(key: String, value: Any, schema: JSONObject): String? {
    return when (schema.optString("type")) {
        "string" -> {
            if (value !is String) return "$key 应为字符串"
            val max = schema.optInt("maxLength", -1)
            if (max >= 0 && value.length > max) return "$key 超过 $max 字"
            val allowed = schema.optJSONArray("enum")
            if (allowed != null && !arrayContains(allowed, value)) "$key 取值不在允许范围" else null
        }

        "integer", "number" -> {
            if (value !is Number && value !is String) return "$key 应为数字"
            val d = (value as? Number)?.toDouble()
                ?: (value as String).trim().toDoubleOrNull()
                ?: return "$key 应为数字"
            if (schema.optString("type") == "integer" && d % 1.0 != 0.0) return "$key 应为整数"
            if (schema.has("minimum") && d < schema.optDouble("minimum")) {
                return "$key 小于最小值 ${schema.optDouble("minimum")}"
            }
            if (schema.has("maximum") && d > schema.optDouble("maximum")) {
                return "$key 大于最大值 ${schema.optDouble("maximum")}"
            }
            null
        }

        "boolean" -> if (value is Boolean) null else "$key 应为布尔值"

        "array" -> {
            if (value !is JSONArray) return "$key 应为数组"
            val maxItems = schema.optInt("maxItems", -1)
            if (maxItems in 0..<value.length()) return "$key 超过 $maxItems 个元素"
            if (schema.optJSONObject("items")?.optString("type") == "string") {
                for (i in 0 until value.length()) {
                    if (value.opt(i) !is String) return "$key 的元素应为字符串"
                }
            }
            null
        }

        "object" -> if (value is JSONObject) null else "$key 应为对象"
        else -> null // schema 没写 type：不校验
    }
}

private fun arrayContains(arr: JSONArray, value: Any): Boolean {
    for (i in 0 until arr.length()) {
        val v = arr.opt(i)
        if (v == value || v?.toString() == value.toString()) return true
    }
    return false
}
