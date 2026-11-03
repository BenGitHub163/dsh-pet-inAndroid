# R4 · DeepSeek API function calling 实战细节

> 调研基线：2026-11-03。官方文档 `api-docs.deepseek.com`（注意：搜索引擎结果里大量 `deepseek-*.com.cn` 仿冒站，勿引用）。
> 分类口径：**已验证** = 官方文档明文 + 可复现的公开实测；**文档说明** = 仅官方文档写明，本次未实测；**待确认** = 无公开依据，需自测。

## 0. 先说结论：你实测的 `deepseek-chat` 已过停用期

官方公告 `deepseek-chat` / `deepseek-reasoner` **于 2026-07-24 15:59 UTC 停用**（[news260424](https://api-docs.deepseek.com/news/news260424)），当前 API 参考的 `model` 枚举只有两个：[create-chat-completion](https://api-docs.deepseek.com/api/create-chat-completion)

| model | 实际版本 | 上下文 / 最大输出 | Tool Calls | Vision |
|---|---|---|---|---|
| `deepseek-flash` | DeepSeek-V4.1-Flash-1017 | 1M / 384K | ✓ | ✓ |
| `deepseek-v4-pro` | DeepSeek-V4-Pro-0813 | 1M / 384K | ✓ | ✗ |

你仍能用 `deepseek-chat` 调通，说明存在兼容路由（[#1713](https://github.com/deepseek-ai/DeepSeek-V3/issues/1713) 在 2026-10-15 也报"现网可用"）——**这是待确认状态，不能作为实现前提**，直接迁移到 `deepseek-flash` 或 `deepseek-v4-pro`。

## 1. 字段速查表

### 请求（POST /chat/completions）

| 字段 | 取值 | 要点 |
|---|---|---|
| `messages` | system / user / assistant / tool | 工具轮必需三者齐全 |
| `tools[]` | `{type:"function", function:{name, description, parameters, strict?}}` | 仅 `function`；name 唯一，`[A-Za-z0-9_-]`，≤128；省略 `parameters` = 空参数表 |
| `tool_choice` | `"none"` / `"auto"` / `"required"` / `{"type":"function","function":{"name":"x"}}` | 无 tools 默认 `none`，有 tools 默认 `auto` |
| `thinking` | `{"type":"enabled"\|"disabled"}` | **默认 enabled**；与 `reasoning_effort` 同时给时 thinking 优先 |
| `reasoning_effort` | `none` / `low` / `high`(默认) / `max` | `minimal→low`、`medium/xhigh→high` |
| `max_tokens` | 1 ~ 393216 | 默认 8K（非思考）/ 64K（思考）/ 128K（effort=max） |
| `stream` + `stream_options.include_usage` | bool | `include_usage` 必须配 `stream:true`，否则 400 |
| `strict`（Beta） | bool | 需 `base_url=.../beta`；所有 function 都要 `strict:true` |

### 响应

| 字段 | 说明 |
|---|---|
| `finish_reason` | `stop` / `length` / `content_filter` / `tool_calls` / `insufficient_system_resource` / `aborted` |
| `message.content` | 可为 `null`；工具轮常为空串 |
| `message.reasoning_content` | 仅思考模式；**带 tools 时必须回传** |
| `message.tool_calls[].{id,type,function.name,function.arguments}` | `arguments` 是 **JSON 字符串**，官方明示"不保证合法 JSON、可能臆造参数" |
| `usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` | 缓存命中/未命中；两者之和 = `prompt_tokens` |
| `usage.completion_tokens_details.reasoning_tokens` | 推理 token 单列 |

### 流式分片（关键）

`delta.tool_calls[]` 的**首个分片带 `id` / `type` / `function.name`，后续分片只带 `function.arguments` 增量**；`index` 是必填字段，用于归并同一次调用。最后一个 chunk 带 `usage` + 非空 `finish_reason`，不会单独发 usage chunk。

## 2. 事实清单

### 2.1 已验证（有明文 + 可复现实测）

1. **thinking 与 tools 不互斥**，V3.2 起支持思考模式下的工具调用。[tool_calls](https://api-docs.deepseek.com/guides/tool_calls)
2. **思考模式下 `required` 与具名 `tool_choice` 直接 400**，报错原文 `Thinking mode does not support this tool_choice`。[#1376](https://github.com/deepseek-ai/DeepSeek-V3/issues/1376)（`deepseek-v4-pro`/`v4-flash` 必现；同 issue 称 `deepseek-chat` 不受影响）
3. **thinking 默认开启** —— 不显式关 thinking 就发 `required`，第一次请求就 400。[#1376](https://github.com/deepseek-ai/DeepSeek-V3/issues/1376) + API 参考默认值
4. **带 `tools` 时 `reasoning_content` 必须逐轮回传**（含未发生工具调用的轮）。缺失报错原文：`Missing reasoning_content field in the assistant message at message index N`。[thinking_mode](https://api-docs.deepseek.com/guides/thinking_mode#tool-calls) / [#1053](https://github.com/deepseek-ai/DeepSeek-V3/issues/1053)
5. **不带 `tools` 时 `reasoning_content` 会被忽略**，回传也不进上下文（同一链接）。→ 开关 tools 会改变多轮语义，不能中途切换。
6. **tool 消息格式**：`{"role":"tool","tool_call_id":<id>,"content":<string|content parts>}`，`tool_call_id` 必填。[API 参考 schema](https://api-docs.deepseek.com/api/create-chat-completion)
7. **缺配对的 tool 结果会 400**，但证据来自另外两个端点：Responses API 报 `No tool output found for tool call call_00_...`（[#1588](https://github.com/deepseek-ai/DeepSeek-V3/issues/1588)），Anthropic API 报 `tool_use ids were found without tool_result blocks immediately after`（[#967](https://github.com/deepseek-ai/DeepSeek-V3/issues/967)）。**Chat Completions 端点未验证**，但两端点都强校验，按"会 400"实现最安全。
8. **并行返回多个 tool_calls 是真实存在的**：`auto` 语义为 "calling one or more tools"（[API 参考](https://api-docs.deepseek.com/api/create-chat-completion)）；#967 的报错里一次出现 `call_1_...` 与 `call_2_...` 两个 id。
9. **没有 `parallel_tool_calls` 请求参数** —— [完整 schema](https://api-docs.deepseek.com/api/create-chat-completion) 里不存在此字段，不要传（传了也不会被识别，勿臆造）。

### 2.2 文档说明（仅文档写明，未实测）

10. **不支持在对话中间插入非模型生成的 tool_calls**；要插只能用 Responses API 或 Anthropic API。Chat Completions 支持中间插 `system`。[tool_calls §插入](https://api-docs.deepseek.com/guides/tool_calls)
11. **`strict` 模式的边界**：`object` 的所有属性必须全 `required` 且 `additionalProperties:false`；不支持 `minLength`/`maxLength`/`minItems`/`maxItems`；支持 `enum`、`anyOf`、`$ref`/`$def`。[tool_calls §strict](https://api-docs.deepseek.com/guides/tool_calls)
12. **流式 tool_calls 分片形状**如 §1 所述，见 [API 参考 streaming schema](https://api-docs.deepseek.com/api/create-chat-completion)。
13. **`max_tokens` 截断会破坏工具调用**：`finish_reason=length` 时 `arguments` 是半截 JSON，无法解析。默认 8K（非思考）对多参数工具偏紧。[API 参考](https://api-docs.deepseek.com/api/create-chat-completion)
14. **两个模型的采样差异**：`deepseek-v4-pro` 在思考模式下固定 `top_p=0.95`（启用工具调用时），`deepseek-flash` 非思考模式固定 1.0，传值被忽略。[pricing](https://api-docs.deepseek.com/quick_start/pricing) / [API 参考](https://api-docs.deepseek.com/api/create-chat-completion)
15. **`tool_choice:"none"` 会连带关掉工具调用**（即使传了 tools），可用于单轮强制走纯文本。[thinking_mode](https://api-docs.deepseek.com/guides/thinking_mode) / [API 参考](https://api-docs.deepseek.com/api/create-chat-completion)

### 2.3 待确认（无公开依据，必须自测）

16. **Chat Completions 端点缺 tool 消息是否 400**：无官方明文、无公开 issue。验证方法：构造 `assistant(tool_calls)` 后直接跟 `user`，看返回码与 message。
17. **并行调用时 `index` 的递增保证**：schema 只规定 `index` 必填，未承诺从 0 连续递增。按"按 index 分组、不假设连续"实现。
18. **`aborted` / `insufficient_system_resource` 是否计费**：文档未写，需在用量页比对。
19. **`reasoning_tokens` 是否按输出价计费**：usage 里单列，定价页只有"OUTPUT TOKENS"一档，未明说。建议按输出价做预算上限。
20. **"关闭微信"被识别成"关闭对话"**：未找到任何公开报告可引用（GitHub / V2EX 均无）。视为**你方场景假设**，按 §3 的写法防御。

### 2.4 计费：工具轮的真实成本结构

- **一次提问 = 2 次独立计费请求**（[pricing](https://api-docs.deepseek.com/quick_start/pricing)："billed on total input and output tokens"）。第 2 次要把全量历史再发一遍，**prompt 侧重复计费**。
- 缓存是唯一的对冲：`deepseek-flash` 峰时 cache hit $0.006 / miss $0.3（每 1M），**差 50 倍**。但缓存要求"**完全匹配**一个 cache prefix unit"，所以任何前缀改动（改写历史、丢 `reasoning_content`、在中间插 `system`）都会 miss，退回全价。[kv_cache](https://api-docs.deepseek.com/guides/kv_cache)
- **峰谷价差 2 倍**：峰时 UTC 01:00–04:00、06:00–10:00（周一至周五，中国法定节假日除外），其余半价。长工具链跨峰谷，单价不同。
- 真正的"重复计费陷阱"是**重试**：工具执行超时后重发整个请求，等于把最贵的那一轮 prompt 再买一次。用 `id` 去重 + 幂等工具，别用无脑重试。

## 3. 中文提示词与工具描述写法

中文指令下最贵的两类失败：**工具名/描述与近义动作不可区分**（"关闭微信" vs "关闭对话"），**多轮后模型返回历史工具名**（[#1713](https://github.com/deepseek-ai/DeepSeek-V3/issues/1713)，历史含 tool_calls 时 50/50 复现）。

### 正例

1. **带反例边界的单动作工具**
   ```json
   {"name":"close_app",
    "description":"关闭手机上的一个第三方 App。仅当用户明确说出 App 名称并要求关闭时调用（如「关掉微信」「把抖音退了」）。不要用于：关闭当前对话、退出页面、返回桌面、结束会话——这些不需要调用任何工具。",
    "parameters":{"type":"object","properties":{"app_name":{"type":"string","description":"App 中文名，如「微信」「抖音」，不要传包名"}},"required":["app_name"]}}
   ```
2. **相对时间显式归一**
   ```json
   {"name":"set_alarm","description":"设置闹钟。用户说「明早七点叫我」「半小时后提醒我」这类相对时间也要调用，由你换算成绝对时间。","parameters":{"type":"object","properties":{"time":{"type":"string","description":"24 小时制 HH:MM，必须已换算为本地时间"},"label":{"type":"string","description":"闹钟备注，可为空串"}},"required":["time"]}}
   ```
3. **路由型单工具 + enum 收敛**（比 10 个工具更稳）
   ```json
   {"name":"route_intent","description":"把用户的中文指令归到唯一动作；无法归类时用 answer 直接回答。","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["answer","close_app","set_alarm","open_app"]},"target":{"type":"string","description":"动作对象，如 App 名；answer 时为空串"}},"required":["action","target"]}}
   ```

### 反例

1. **`name:"close"`, `description:"关闭"`** —— 无宾语，与"关闭对话/关闭页面/关闭通知"完全无法区分，正是误触发的温床。
2. **`do_action(action: string)` 无 enum** —— 动作名靠模型自由发挥，参数无法校验，前端只能字符串匹配。
3. **中文场景写英文 description 且参数无说明** —— `{"name":"search","description":"Search."}`，`city` 不说明要"杭州"还是"Hangzhou"，模型会在两种格式间漂移，你的工具侧就得写兼容代码。

通用规则：description 写"**什么时候调用 + 什么时候不要调用**"，参数 description 给**中文示例值**，枚举用 `enum` 而不是靠描述约束。

## 4. 结论：实现时必须做的 5 个防御动作

1. **锁死 thinking 与 tool_choice 的组合**：要么全局 `thinking:{"type":"disabled"}`，要么永不用 `required`/具名 tool_choice（默认 thinking 开启，用就 400）。启动时断言，别靠 code review。
2. **assistant 消息原样回传，tool 结果按 id 一一配对**：把 `content` / `reasoning_content` / `tool_calls` 三个字段整体 append（等价于 `messages.append(resp.choices[0].message)`），发送前本地校验"每个 `tool_call.id` 都有且仅有一条对应 tool 消息"，缺失就补齐或丢弃该轮——不要指望服务端宽容。
3. **只信 `finish_reason == "tool_calls"` 才解析工具调用**，同时兜底扫描 `content` 里的工具调用文本：`deepseek-v4-pro` 有 11% 概率把调用写成纯文本且 `finish_reason=stop`（[#1244](https://github.com/deepseek-ai/DeepSeek-V3/issues/1244)）。`length` / `aborted` / `insufficient_system_resource` 一律当失败重来。
4. **对返回的 tool name 做白名单校验**：历史含 tool_calls 时模型会吐历史工具名（[#1713](https://github.com/deepseek-ai/DeepSeek-V3/issues/1713)），名字不在本次 `tools` 里就拒绝执行。`arguments` JSON.parse 失败或缺必填参数时，不执行副作用，回一条 tool 错误消息让模型重试。
5. **按"请求"而非"对话"记账，并守住前缀稳定**：预算累加每次 `usage`，监控 `prompt_cache_hit_tokens` 占比；稳定 system + 稳定工具表 + 不重排历史，才能吃到 50 倍缓存价差。另设 `max_tokens`（非思考 ≥4K）与工具轮上限（建议 ≤8），防止 `length` 截断和循环调用把费用打穿。
