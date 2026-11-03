package com.dshpet.android.ai.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ToolCallAccumulator` 的单测。
 *
 * 用例逐条对应 `docs/tool-router/research/R2-sse-tool-calls.md` 的「坑清单」
 * （langchain4j 的同名用例：index 缺失、DeepSeek 尾哨兵、单帧多调用、无 function 对象…）。
 * 这些是**真实网关的脏帧**，不是假想：
 *  - index 不从 0 开始 / 不连续 / 单帧多 index；
 *  - `id`/`name` 只在首帧出现，后续帧可能是缺字段、`""`、或重复全名；
 *  - `arguments` 可能是 `null`（不能被 `optString` 变成 `"null"`）、`""`、或整段重发。
 */
class ToolCallAccumulatorTest {

    // ---------------------------------------------------------------- 归并

    @Test
    fun `index 不从 0 开始时仍按序号排序`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(call(index = 1, id = "call_b", name = "open_app", args = """{"app":"微信"}"""))
        acc.onToolCall(call(index = 0, id = "call_a", name = "remember", args = """{"text":"x"}"""))

        val calls = acc.freeze()
        assertEquals(2, calls.size)
        assertEquals(listOf("remember", "open_app"), calls.map { it.name })
        assertEquals(listOf(0, 1), calls.map { it.index })
    }

    @Test
    fun `两条调用交错到达不会串参`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", name = "open_app", args = """{"app":"""))
        acc.onToolCall(delta(index = 1, id = "c1", name = "set_timer", args = """{"duration_sec":"""))
        acc.onToolCall(delta(index = 0, args = """"微信"}"""))
        acc.onToolCall(delta(index = 1, args = "30}"))

        val calls = acc.freeze()
        assertEquals("""{"app":"微信"}""", calls[0].argumentsRaw)
        assertEquals("""{"duration_sec":30}""", calls[1].argumentsRaw)
        assertEquals("c0", calls[0].id)
        assertEquals("c1", calls[1].id)
    }

    @Test
    fun `单帧内含两个调用都要保留`() {
        val acc = ToolCallAccumulator()
        acc.onToolCalls(
            org.json.JSONArray(
                """
                [{"index":0,"id":"c0","type":"function","function":{"name":"open_app","arguments":"{}"}},
                 {"index":1,"id":"c1","type":"function","function":{"name":"remember","arguments":"{}"}}]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("open_app", "remember"), acc.freeze().map { it.name })
    }

    @Test
    fun `缺 index 时沿用上一次的序号`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 3, id = "c3", name = "open_app", args = "{\"app\":"))
        acc.onFrame(ToolCallFrame(index = null, id = null, hasFunction = true, name = null, arguments = """"微信"}"""))

        val call = acc.freeze().single()
        assertEquals(3, call.index)
        assertEquals("""{"app":"微信"}""", call.argumentsRaw)
    }

    // ---------------------------------------------------------------- id / name

    @Test
    fun `name 每帧重复全名时不会拼成一长串`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", name = "open_app", args = ""))
        acc.onToolCall(delta(index = 0, name = "open_app", args = """{"app":"""))
        acc.onToolCall(delta(index = 0, name = "open_app", args = """"微信"}"""))

        assertEquals("open_app", acc.freeze().single().name)
    }

    @Test
    fun `name 只在首帧给出时后续空串不覆盖`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", name = "open_app", args = """{"app":"""))
        acc.onToolCall(delta(index = 0, name = "", args = """"微信"}"""))

        assertEquals("open_app", acc.freeze().single().name)
    }

    @Test
    fun `id 只出现一次且后续给空串时保留首个`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "call_1", name = "open_app", args = """{"app":"""))
        acc.onToolCall(delta(index = 0, id = "", args = """"微信"}"""))

        assertEquals("call_1", acc.freeze().single().id)
    }

    @Test
    fun `没有 id 时合成一个可回灌的 id`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 2, name = "open_app", args = """{"app":"微信"}"""))

        assertEquals("call_local_2", acc.freeze().single().id)
    }

    // ---------------------------------------------------------------- 脏帧

    @Test
    fun `DeepSeek 尾部哨兵帧被丢弃`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(call(index = 0, id = "call_real", name = "open_app", args = """{"app":"微信"}"""))
        // 真实抓到的形态：id 空、没有 name、arguments 为 JSON null
        acc.onToolCall(JSONObject("""{"index":0,"id":"","type":"function","function":{"arguments":null}}"""))

        val calls = acc.freeze()
        assertEquals(1, calls.size)
        assertEquals("call_real", calls.single().id)
        assertEquals(1, acc.dropped)
    }

    @Test
    fun `function 对象整体缺失时先存住 id`() {
        val acc = ToolCallAccumulator()
        // LiteLLM / vLLM：首帧只有 id
        acc.onToolCall(JSONObject("""{"index":0,"id":"call_x","type":"function"}"""))
        acc.onToolCall(JSONObject("""{"index":0,"function":{"name":"open_app","arguments":"{}"}}"""))

        val call = acc.freeze().single()
        assertEquals("call_x", call.id)
        assertEquals("open_app", call.name)
        assertEquals("{}", call.argumentsRaw)
    }

    @Test
    fun `arguments 为 JSON null 时不会拼出字符串 null`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(JSONObject("""{"index":0,"id":"c0","function":{"name":"open_app","arguments":null}}"""))

        val call = acc.freeze().single()
        assertEquals("{}", call.argumentsRaw)
        assertTrue(call.argsOrNull() != null)
    }

    @Test
    fun `无参函数的空 arguments 规格化成空对象`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", name = "close_conversation", args = ""))

        val call = acc.freeze().single()
        assertEquals("{}", call.argumentsRaw)
        assertEquals(0, call.args().length())
    }

    @Test
    fun `无名调用在收尾时丢弃`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", args = """{"a":1}"""))

        assertTrue(acc.freeze().isEmpty())
    }

    // ---------------------------------------------------------------- 参数解析

    @Test
    fun `非法 JSON 原样带出交给上层报错`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", name = "open_app", args = """{"app":"""))

        val call = acc.freeze().single()
        assertNull(call.argsOrNull())
        assertThrows(InvalidArgsException::class.java) { call.args() }
    }

    @Test
    fun `整段重发 arguments 时替换缓冲而不是拼接`() {
        val acc = ToolCallAccumulator()
        acc.onToolCall(delta(index = 0, id = "c0", name = "open_app", args = """{"app":"微"""))
        acc.onToolCall(delta(index = 0, args = """{"app":"微信"}"""))

        assertEquals("""{"app":"微信"}""", acc.freeze().single().argumentsRaw)
    }

    @Test
    fun `markdown 围栏与尾逗号可以被无损清洗`() {
        val call = ToolCall("c0", "open_app", "```json\n{\"app\":\"微信\",}\n```")
        assertEquals("微信", call.args().getString("app"))
    }

    @Test
    fun `去重键有 id 用 id 没有 id 时按参数指纹`() {
        assertEquals("id:call_1", ToolCall("call_1", "open_app", "{}").dedupKey())
        val a = ToolCall("", "open_app", """{"app":"微信"}""", index = 0).dedupKey()
        val b = ToolCall("", "open_app", """{"app":"微信"}""", index = 0).dedupKey()
        val c = ToolCall("", "open_app", """{"app":"支付宝"}""", index = 0).dedupKey()
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.startsWith("fp:0:open_app:"))
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    fun `schema 校验拦住缺必填与越界也能放过未知字段`() {
        val spec = ToolSpec(
            name = "set_timer",
            description = "计时",
            parameters = Schema.obj(
                "duration_sec" to Schema.int("秒", 1, 86400),
                "label" to Schema.str("事项", maxLength = 20),
                required = listOf("duration_sec"),
            ),
        )

        assertNull(validateArgs(spec, JSONObject("""{"duration_sec":30}""")))
        assertNotNull(validateArgs(spec, JSONObject("""{}""")))                       // 缺必填
        assertNotNull(validateArgs(spec, JSONObject("""{"duration_sec":0}""")))       // 小于最小值
        assertNotNull(validateArgs(spec, JSONObject("""{"duration_sec":1.5}""")))     // 不是整数
        assertNotNull(validateArgs(spec, JSONObject("""{"duration_sec":30,"label":"一二三四五六七八九十一二三四五六七八九十一"}""")))
        // 模型编造的额外字段：忽略，不当作错误（SPEC §2.1）
        assertNull(validateArgs(spec, JSONObject("""{"duration_sec":30,"evil":"x"}""")))
    }

    @Test
    fun `取参数时接受字符串数字并拒绝越界`() {
        val args = JSONObject("""{"duration_sec":"30"}""")
        assertEquals(30, args.requireInt("duration_sec", 1, 86400))
        assertThrows(InvalidArgsException::class.java) { args.requireInt("duration_sec", 60, 90) }
        assertThrows(InvalidArgsException::class.java) { JSONObject("{}").requireString("app") }
    }

    // ---------------------------------------------------------------- 回灌格式

    @Test
    fun `带工具调用的 assistant 消息与 tool 消息能配对`() {
        val call = ToolCall("call_1", "open_app", """{"app":"微信"}""")
        val assistant = ChatTurn.Assistant(content = "这就开", toolCalls = listOf(call)).toJson()

        assertEquals("assistant", assistant.getString("role"))
        assertEquals("call_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(
            "open_app",
            assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"),
        )
        val tool = ChatTurn.Tool("call_1", """{"ok":true}""").toJson()
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
    }

    // ---------------------------------------------------------------- helpers

    private fun call(index: Int, id: String, name: String, args: String): JSONObject =
        JSONObject().apply {
            put("index", index)
            put("id", id)
            put("type", "function")
            put("function", JSONObject().put("name", name).put("arguments", args))
        }

    /** 后续分片：只有 index（+ 可选 name/args），没有 id */
    private fun delta(index: Int, id: String? = null, name: String? = null, args: String? = null): JSONObject =
        JSONObject().apply {
            put("index", index)
            if (id != null) put("id", id)
            put(
                "function",
                JSONObject().apply {
                    if (name != null) put("name", name)
                    put("arguments", args ?: "")
                },
            )
        }
}
