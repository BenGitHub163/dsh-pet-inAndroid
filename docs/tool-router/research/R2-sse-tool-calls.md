# R2 · 流式（SSE）解析 OpenAI 兼容 function calling

> 调研基线：2026-11-03。契约以 DeepSeek 官方 API 参考为准，OpenAI 侧以 `openai-node` 生成类型为准（`platform.openai.com` 直连 403，无法引用原文）。
> 标注口径：**已验证** = 读到官方文档/源码原文；**待确认** = 无公开依据，需自测。
> 工具调用语义（thinking、reasoning_content 回传、tool_choice 限制）见 [R4-deepseek-tools.md](./R4-deepseek-tools.md)，本文不重复。

## 1. 契约摘要

### 1.1 传输层帧（SSE）

| 帧类型 | 形态 | 说明 |
|---|---|---|
| 数据帧 | `data: {"id":"...","choices":[...]}` | 每个 chunk 是一个**完整、可独立解析**的 JSON 对象 |
| 心跳/注释 | `: ping` 或空 `data:` | 必须忽略；`data:` 后可有 0 或 1 个空格 |
| 结束帧 | `data: [DONE]` | 非 JSON。DeepSeek 明文："流以 `data: [DONE]` 结尾"（[API 参考](https://api-docs.deepseek.com/api/create-chat-completion)） |
| 错误帧 | `data: {"error":{"message":...}}` | 部分网关在 200 流里下发；必须在流内检测，不能只看 HTTP 状态 |
| 事件分隔 | 空行 | 多行 `data:` 按 SSE 规范以 `\n` 连接（[WHATWG](https://html.spec.whatwg.org/multipage/server-sent-events.html)） |

### 1.2 chunk 对象字段

| 字段 | 取值 | 说明 |
|---|---|---|
| `object` | `chat.completion.chunk` | 仅 OpenAI；DeepSeek 未强制，勿依赖 |
| `choices[]` | 通常长度 1 | `n>1` 才有多个；`finish_reason` 非空的那帧 `choices` 仍只有 1 个元素 |
| `choices[].delta` | object | 增量；**可能只有 `role`、只有 `content`、只有 `tool_calls`，或全空** |
| `choices[].finish_reason` | `null` / 见 1.4 | 中间帧恒为 `null`，**不要用它判断"还在流中"以外的含义** |
| `usage` | 仅最后一帧 | DeepSeek 明文：不发独立的 usage 帧，统计信息挂在最后那个"无新增内容且 `finish_reason` 非空"的帧上 |

### 1.3 `delta.tool_calls[]`（OpenAI 类型定义，已验证）

来源：[openai-node `completions.ts`](https://github.com/openai/openai-node/blob/master/src/resources/chat/completions/completions.ts) → `ChatCompletionChunk.Choice.Delta.ToolCall`。

| 字段 | 必填 | 说明 |
|---|---|---|
| `index` | **是** | 本次响应内 tool_call 的序号，0 基，跨 chunk 稳定。**语义是"第几个调用"，不是数组下标**：不保证连续、不保证从 0 开始、单帧内可同时出现多个不同 index |
| `id` | 否 | 实际只在首帧出现，类型上却是 optional；后续帧可能缺字段，也可能给 `""`（见坑 4） |
| `type` | 否 | `function`（OpenAI 另有 `custom`）；DeepSeek 只有 `function` |
| `function.name` | 否 | 首帧给全名；后续帧可能缺字段、给 `""`，个别网关会**每帧重复全名** |
| `function.arguments` | 否 | **JSON 文本的任意切段**，可为 `""`、可为 `null` |

**`arguments` 是否保证是合法 JSON 片段**：不保证"可解析"，但保证"可安全拼接"。每帧自身是合法 JSON，因此帧内 `arguments` 的转义已被传输层正确解码——**解码后按帧顺序做字符串相加，结果必然正确**，不存在"转义序列被拆坏"的必然风险。风险只来自两处：(a) 绕过 JSON 解析、用正则/截取取字段；(b) 服务端把同一段 JSON 拆进多个 `data:` 行（SSE 会用 `\n` 合并，直接破坏 JSON）——OpenAI/DeepSeek 不这么做，代理不确定。

### 1.4 `finish_reason` 取值

| 来源 | 取值 |
|---|---|
| OpenAI（已验证） | `stop` / `length` / `tool_calls` / `content_filter` / `function_call`(deprecated) / `null` |
| DeepSeek（已验证） | `stop` / `length` / `content_filter` / `tool_calls` / `insufficient_system_resource` / `aborted` |

差异两条：DeepSeek 多 `insufficient_system_resource`、`aborted`（**都会让流在中途结束，必须当失败处理**）；无 `function_call`。→ 枚举按并集写，未知值一律按"结束"处理而不是崩溃。

## 2. 现成方案对比

| 方案 | 推荐 | 理由 | 迁移成本 |
|---|---|---|---|
| 保留现有 `SseClient.SseParser` + 自写累加器 | **✅ 推荐** | 现有解析器已正确处理空行分组、`data:` 前缀、注释行、`[DONE]`、流内 `error`、`delta.content` 的 JSON `null`（`SseClient.kt:198-244`）。缺的只是 tool_calls 分支，约 40 行 | 低：`feedLine` 返回值从 `List<String>` 换成小 sealed class |
| `okhttp-sse`（`com.squareup.okhttp3:okhttp-sse`，最新 5.5.1，[Maven](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp-sse/maven-metadata.xml)） | ⚠️ 可选，不建议现在换 | 只解决"行→事件"这一层，**不含任何 tool_call 累加**。`RealEventSource` 要求 2xx + `Content-Type: text/event-stream`，否则直接 `onFailure`（错误体在被关闭前同步读取，见坑 9）；`onRetryChange` 被显式忽略（无自动重连）。收益≈现有 30 行解析器，风险是行为差异 | 中：改写回调模型 + 处理非 SSE 响应 |
| langchain4j `OpenAiStreamingResponseBuilder` | 🔶 摘逻辑，不引依赖 | 工业级累加器，7 个单测覆盖 index 为 null、DeepSeek 尾哨兵、单帧多调用、无 `function` 对象等真实边界 | 低（抄思路）；引入框架则高 |
| Spring AI `MessageAggregator` | 🔶 只作参考 | 把多帧 `ChatResponse` 合并成一个，按 index 合并 ToolCall；绑定 Spring 生态 | 高 |
| `openai-kotlin`（aallam） | ❌ 不推荐 | 强类型 chunk + Ktor 传输；是否内置累加器**待确认**（README 拉取失败）。为 40 行逻辑引入整套 SDK 不划算 | 高 |
| `openai-go` `streamaccumulator.go` | 🔶 语义参照 | 官方实现，含 `checkedToolCallIndex` 这类防御（index 越界不 panic） | — |
| 增量 JSON 解析（`kotlinx-serialization.decodeFromStream` / `Moshi JsonReader` / `android.util.JsonReader` / `json-stream`） | ❌ 不需要 | `decodeFromStream` 需要**完整**文档；pull parser 遇到不完整 JSON 就抛。`arguments` 分片本就不可解析，增量解析没有收益，只增加状态机复杂度 | — |

## 3. 坑清单

| # | 坑 | 症状 | 如何验证 |
|---|---|---|---|
| 1 | **按数组位置归并**而非按 `index` | `IndexOutOfBounds`，或把第 2 个调用的参数拼到第 1 个上 | 造一帧 `tool_calls:[{index:1,...}]`（无 index 0），跑累加器；再让两调用交错（0,1,0,1） |
| 2 | **单帧内含多个 tool_calls** | 只处理 `tool_calls[0]`，并行调用丢一半 | 单帧塞 `index:0` 和 `index:1` 两条，断言产出 2 个调用（langchain4j 有同名用例 `should_keep_all_tool_calls_from_same_delta`） |
| 3 | **`name` 每帧覆盖** | 工具名变空串，分发时 `no such tool ""` | 打印原始帧：第 2 帧起 `name` 是缺字段 / `""` / 重复全名，三种都要能过 |
| 4 | **`id` 只出现一次 vs 每帧重复** | 覆盖式赋值 → id 变 `""`；累加式追加 → id 变 `call_abc...call_abc` | 同上，抓真实流看 id 出现次数；累加器用"空则设、相同则跳、不同才追加" |
| 5 | **DeepSeek 尾部哨兵帧** | 多出一个 `name=""`、`arguments=null` 的幽灵调用，回传时 400 | 构造 `{"index":0,"id":"","type":"function","function":{"arguments":null}}` 追加在真实调用之后，断言只产出 1 个（langchain4j `should_ignore_trailing_sentinel_chunk_from_deepseek_v4_flash`） |
| 6 | **`function` 对象整体缺失** | NPE（LiteLLM/vLLM 会先发 `{index:0,id:"call_x",type:"function"}`，下一帧才给 function） | 先喂"只有 id"的帧再喂"只有 function"的帧，断言 id 与参数都保留 |
| 7 | **`arguments` 为 `null`** | `optString` 得到字符串 `"null"`，拼进 JSON 直接解析失败 | 单测里对 `arguments:null` 断言"不追加任何字符" |
| 8 | **`arguments` 为 `""`（无参函数）** | 收起时 `JSONObject("")` 抛异常，工具白调 | 断言空参数规格化为 `{}` 后再解析 |
| 9 | **非 SSE 响应 / 非 2xx** | 手写循环会把 JSON 错误体当 SSE 行吞掉；`okhttp-sse` 则走 `onFailure` 且 `response.body` 在回调返回后即被关闭 | 用错误 baseUrl 或错 key 各跑一次，确认能拿到 httpCode + 原始 message |
| 10 | **`finish_reason` 全程为 `null` 就收到 `[DONE]`**（代理剥离） | 永远不派发工具 | 造只含 content/tool_calls、`finish_reason` 恒 null 的流，确认 `[DONE]`/EOF 兜底能派发 |
| 11 | **`content` 与 `tool_calls` 混流** | 把"我来查一下"当最终答案播完就不调工具，或反之丢弃播报 | 造同一条流先给 `delta.content` 再给 `tool_calls`，断言文案照播、工具照调 |
| 12 | **`aborted` / `insufficient_system_resource`** | 当正常结束处理，用户看到半截 `arguments` 被静默丢弃 | 造 `finish_reason:"aborted"` 的末帧，断言报错而不是"成功但没工具" |
| 13 | **协程取消打不断阻塞读** | `readTimeout(0)` + `readUtf8Line()` 阻塞，`job.cancel()` 后线程仍占着连接 | 取消后看 OkHttp 线程是否回 idle；修法：`invokeOnCompletion { source.close() }` 或 `resp.use {}` |

## 4. 结论

**自己写累加器，不引库。** 累加器是 40 行状态机（`Map<Int, Partial>` + 三条 append 规则），而"省下来的"解析层现有代码已经写对了；唯一值得借鉴的是 langchain4j 的边界处理，直接照它的单测用例写自己的测试即可。`okhttp-sse` 留作后续重构选项，不放进这次改动（改 transport 会同时动错误处理、取消、HTTP 状态三条既有逻辑）。

```kotlin
// key = index；Partial(id, name, args: StringBuilder)
fun onToolCall(tc: JSONObject, acc: MutableMap<Int, Partial>) {
    val p = acc.getOrPut(tc.optInt("index", -1)) { Partial() }
    tc.optString("id").takeIf { it.isNotEmpty() }?.let { if (p.id.isEmpty()) p.id = it }
    val fn = tc.optJSONObject("function") ?: return          // 坑 6
    fn.optString("name").takeIf { it.isNotEmpty() }?.let {   // 坑 3/5
        p.name = when { p.name.isEmpty() -> it; p.name == it -> it; it.startsWith(p.name) -> it; else -> p.name + it }
    }
    if (!fn.isNull("arguments")) p.args.append(fn.optString("arguments"))  // 坑 7/8
}
// 触发收尾：finish_reason=="tool_calls" || data=="[DONE]" || EOF（坑 10）
// 收尾：name 为空则丢弃（坑 5）；args 空则 "{}"；JSONObject 解析失败 → 报错并回传纯文本，不静默
```

落地位置：`chat/SseClient.kt` 的 `SseParser.feedLine`（现返回 `List<String>`，改为返回内容片段 + tool_call 增量两种事件），累加器放在 `feedLine` 之外、`onDone` 之前统一收敛。
