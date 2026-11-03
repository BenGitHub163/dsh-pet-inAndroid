# ToolRouter 技术设计（DeepSeek function calling + 本地工具执行）

| 项 | 值 |
|---|---|
| 分支 | `feature/tool-router` |
| 目标文件 | `android/app/src/main/java/com/dshpet/android/ai/tools/**`（新增）+ 5 个既有文件改造 |
| 依据基线 | `VoiceLoop.kt`(610) / `SseClient.kt`(296) / `ChatClient.kt`(32) / `SpeechPlayer.kt`(247) / `SpeechPipeline.kt`(115) / `QuickTts.kt`(65) / `PetOverlayService.kt`(3060) |
| 工具链假设 | compileSdk 34 / minSdk 28 / targetSdk 34 / OkHttp 4.12.0 / coroutines 1.8.1 / org.json（`build.gradle.kts:10-107`，**不新增依赖**） |
| 状态 | 设计草案。本文档只描述设计，**未改动任何代码**，未提交 |

---

## 0. 结论先行

1. **工具执行点 = `finish_reason` 到达那一刻，不是文本流结束那一刻**。DeepSeek 的 `tool_calls` 在 `delta.tool_calls[]` 分片里给出，`finish_reason="tool_calls"` 是唯一的"参数已完整"信号；到这里就派发，此时 TTS 还在播第一轮的文本 → 天然满足"边说边做"（冲突 A + B）。
2. **一轮对话只有一个音频会话（`SpeechPlayer.startSession`），但可以喂多轮模型文本**。把 `VoiceLoop.oneTurn()` 里"开 session → 建 segments 通道 → 收流 → 关通道"的线性结构，抽成 `TurnSpeech`（一轮一次 session，可多次 `feed()`）。工具 latency 被正在播放的音频遮住，第二轮文本无缝接上，不会出现 600ms 空档。
3. **音频单通道由进程级 `SpeechGate` 仲裁**，不靠 `!VoiceLoop.running` 这种布尔启发式（现网 `PetOverlayService.kt:1098` 就是这种写法）。对话持有 `CONVERSATION` 租约，自说自话 `SELFTALK`、试听/自测 `TEST` 一律让路；被抢占方通过 `abort` 回调立刻静音。
4. **工具串行 + 单次 `withTimeout(3000)` + 整轮预算 12s**。5 个工具全是 UI/系统副作用，并发只会制造竞态（隐藏桌宠 + 开应用 + 起计时），且让级联取消变复杂。串行是这个规模下的正确取舍。
5. **打断是级联取消，顺序固定：静音 → 停播 → 断流 → 取消工具**。并且必须**修补 history**（见 §5.4），否则"assistant 带 tool_calls 但没有对应 tool 消息"会让下一轮直接 400。
6. **多实例只在 `instanceId == 0` 开放工具**，其它实例退化成纯聊天（`tools` 字段不下发），错误文案明确写"多开实例不支持工具"。

### 0.1 关键决策一览

| # | 决策点 | 选择 | 一句话理由 |
|---|---|---|---|
| D1 | 工具执行时机 | `finish_reason=="tool_calls"`（或 `[DONE]` 兜底）时立即派发 | 唯一能证明 `arguments` 拼完的信号，且不必等文本播完 |
| D2 | 是否打断 TTS 去执行工具 | **不打断**，并行执行 | 需求"边说边做"；音频被工具 latency 遮住反而是优势 |
| D3 | 工具并发度 | 串行（`Channel`/单协程 for 循环），单工具 3s 超时 | 副作用互斥、取消简单、日志可读 |
| D4 | 音频通道 | 进程级 `SpeechGate` 单持有者 + 优先级抢占 | 现有 5 个发声入口各自 new `SpeechPlayer`/`AudioTrack`，必须有一处仲裁 |
| D5 | 打断语义 | 取消整轮 + 回滚未完成的 tool_calls + 释放 gate 租约 | 保 history 合法，避免下一轮 400 |
| D6 | 去重键 | `tool_call_id` 为主，缺失时退化为 `index::name::argsHash` | 网关重试/早派发都可能重复触发同一副作用 |
| D7 | 多实例 | `instanceId != 0` 不下发 `tools` | 工具操作的是进程级单例（`petHidden`、`VoiceRuntime`），多开无意义且会互相打脸 |
| D8 | 降级 | 端点×模型维度缓存"不支持 tools"，一次性退回纯聊天 | 自建/代理端点大量存在；不能让用户每轮都吃 400 |
| D9 | thinking 模式 | 工具请求显式带 `{"thinking":{"type":"disabled"}}` | 官方要求带 `tools` 时 `reasoning_content` 必须回传，否则 400；语音场景也不需要 CoT 延迟 |

---

## 1. 总体数据流

### 1.1 正常路径（用户说完 → 流式 → tool_calls → 执行 → 二次请求 → 播报）

```
用户          VoiceLoop(turn)        SseClient         DeepSeek        ToolRouter/Tool      SpeechGate+Player
 |                  |                   |                |                    |                     |
 |--说话(ASR完)---->|                   |                |                    |                     |
 |                  |--gate.acquire(CONVERSATION)----------------------------->|  占有音频通道        |
 |                  |--TurnSpeech.open(): startSession(rate)----------------->|  建/复用 AudioTrack  |
 |                  |--ChatTurn.System+User+... -> streamEvents(tools=5)----->|                     |
 |                  |                   |--POST /chat/completions------------>|                     |
 |                  |                   |<== data: {"delta":{"content":"好"}}  |                     |
 |                  |<--StreamEvent.Content("好")--------------------------------|                     |
 |                  |--splitter.feed()->segments.trySend() -> pipeline(预取2) -|-> 开始出声          |
 |                  |                   |<== delta.content("，我这就关")        |                     |
 |                  |<--Content(...)   |                |                    |                     |
 |                  |--feed()->pipeline --------------------------------------|-> 继续播（不中断）  |
 |                  |                   |<== delta.tool_calls[i].id/name       |                     |
 |                  |<--ToolCallDelta  |                |                    |                     |
 |                  |                   |<== delta.tool_calls[i]."arguments"  |                     |
 |                  |<--ToolCallDelta  |                |                    |                     |
 |                  |                   |<== finish_reason:"tool_calls" ------>|                     |
 |                  |<--Finished("tool_calls")           |                    |                     |
 |                  |--acc.freeze() -> List<ToolCall> ----------------------->|  (去重/校验/预算)    |
 |                  |                   |                |                    |--tool.execute() 3s->|
 |                  |                   |                |                    |   （侧效立即生效）   |
 |                  |                   |                |<--(工具结果，回灌)---|                     |
 |                  |                   |--POST 第二次（history 含 assistant.tool_calls + tool 消息）|
 |                  |                   |<== content("关好啦") -----------------|                     |
 |                  |<--Content("关好啦")--------------------------------------|-> 同一 session 接着播|
 |                  |                   |<== finish_reason:"stop" / [DONE]    |                     |
 |                  |--TurnSpeech.close(): flush() -> segments.close() -> join()|-> awaitDrain+finish |
 |                  |--gate.release()----------------------------------------->|  归还焦点            |
 |<--听到完结---------|                   |                |                    |                     |
```

### 1.2 轮次状态机（`TurnPhase`）

| 阶段 | 进入条件 | 允许并发的事 | 退出条件 |
|---|---|---|---|
| `STREAMING` | 发出请求 | pipeline 合成/播放第 1 段文本 | `Finished(tool_calls)` / `Finished(stop)` / 异常 |
| `TOOL_RUNNING` | `Finished(tool_calls)` 且通过去重校验 | **继续播放**（不阻塞）；串行跑工具 | 所有 tool 都有了 `ToolResult` |
| `FOLLOWUP` | 工具结果全部回灌 | 继续播放 | `Finished(stop)` / 达到 `maxSubRounds` |
| `DRAINING` | 无新内容 | 只等播放排空 | `awaitDrain` 返回 |
| `CANCELLED` | 用户打断 / 超时 / 关闭 | 无（全部子 job 已取消） | 终态 |

### 1.3 三条硬约束（实现时不能破）

- **同一轮内 AudioTrack 只能被 `startSession` 一次**（`SpeechPlayer.kt:42` 会 `flush` 掉未播完的音频）；第二轮文本必须走同一个 session 的 `segments` 通道，不能重新 `startSession`。
- **`assistant` 消息里 `tool_calls` 的顺序必须被保留**，其后紧跟与之一一对应的 `role:"tool"` 消息（OpenAI 兼容约定；缺失或乱序会 400。DeepSeek 是否对缺失 tool 消息报 400 —— 待确认，按 400 设计，成本更低）。
- **Chat Completion API 不支持事后插入 tool 消息**（官方 Tool Calls 文档明说），所以工具消息只能在当轮按序追加，不能"攒起来下一轮补"。

---

## 2. 文件清单

### 2.1 新增（包名 `com.dshpet.android.ai.tools`）

| 文件 | 职责 | 预估行数 |
|---|---|---|
| `ToolTypes.kt` | `ToolSpec` / `ToolCall` / `ToolResult` / `ToolError` / `ToolContext` / `ChatTurn`；`JSONObject` 互转 | ~170 |
| `Tool.kt` | `interface Tool`（`spec` / `idempotent` / `execute`）+ 参数校验工具函数（必填、类型、枚举、区间） | ~90 |
| `ToolRegistry.kt` | 注册 5 个工具；按 `instanceId`/开关/单轮熔断过滤；`specs()` 产出 `JSONArray` | ~100 |
| `ToolRouter.kt` | 核心编排：`runTurn()` 多轮循环、`finish_reason` 判定、二次请求、预算、降级信号 | ~280 |
| `ToolCallAccumulator.kt` | SSE 分片按 `index` 累加 `name`/`arguments`；`freeze()`；坏 JSON 处理 | ~130 |
| `ToolExecutor.kt` | 串行队列 + `withTimeout(3000)` + 取消传播 + 去重表 + 熔断计数 | ~120 |
| `SpeechGate.kt` | 进程级音频单通道：优先级、租约、抢占、泄漏回收、`StateFlow` 供 UI | ~150 |
| `TurnSpeech.kt` | 一轮的语音出口：单 session + 单 segments 通道 + 多次 `feed()` + `close()` | ~110 |
| `AppIndex.kt` | `open_app` 的应用名→包名索引（`PackageManager` 查询 + 标签归一化 + 缓存） | ~110 |
| `TimerHub.kt` | `set_timer` 的调度：`AlarmManager` + 通知 + 到点回灌 | ~130 |
| `tools/CloseConversationTool.kt` | 结束会话 | ~50 |
| `tools/OpenAppTool.kt` | 启动应用（含候选歧义返回） | ~90 |
| `tools/SetTimerTool.kt` | 起计时 | ~70 |
| `tools/RememberTool.kt` | 写长期记忆 | ~70 |
| `tools/PetVisibilityTool.kt` | 显示/隐藏桌宠 | ~45 |
| `TimerReceiver.kt` | `AlarmManager` 到点的 `BroadcastReceiver`（改 `AndroidManifest.xml` 注册） | ~60 |

合计约 **1770 行**（含注释；不含测试）。

### 2.2 修改

| 文件 | 改动 | 预估 |
|---|---|---|
| `chat/SseClient.kt` | ① `Msg`（:33）扩展为可携带 `tool_calls`/`tool_call_id`/`reasoning_content`；② `stream()`（:49）payload（:62-70）增加 `tools`/`thinking`/`tool_choice`；③ `SseParser`（:198-244）改为产出 `StreamEvent`（文本/工具分片/结束/错误）；④ 非 2xx 分支（:82-91）增加 400/422 分类与"tools 不支持"标记；⑤ 取消/结束时关闭 response source | +200 / −60 |
| `voice/chat/ChatClient.kt` | 新增 `streamEvents(cfg, turns): Flow<StreamEvent>`；旧 `stream()` 用 `map` 保持兼容（记忆/自省/自说自话不迁移） | +60 |
| `voice/core/VoiceLoop.kt` | `oneTurn()`（:398）改为 `ToolRouter` 驱动；`history`（:137）类型换 `ChatTurn`；`stop()`（:197）增加级联取消 + `gate.release()`；`start()`（:184）拿 gate 租约；system 提示（:435-451）追加工具使用约束 | +130 / −45 |
| `voice/core/QuickTts.kt` | `speak()`（:28）改为先 `gate.acquire(SELFTALK)`，拿不到直接返回 false（替换 `PetOverlayService.kt:1098` 的布尔判断） | +15 |
| `voice/core/VoiceState.kt` | `VoiceEvent` 增加 `ToolCalled(name, argsPreview)` / `ToolDone(name, ok, info)`；`VoiceState.Thinking` 增加可选 `tool: String?` | +12 |
| `voice/core/VoiceSettings.kt` | 增加 `toolRouterEnabled`（默认 true）/`toolMaxSubRounds`（默认 2）/`toolTimeoutMs`（默认 3000）；`load/save` 各补两行（:92-146） | +18 |
| `voice/VoiceLoopActivity.kt` | 订阅新事件，日志区显示"🔧 open_app → ok"；打字输入也走 router（:501 `sendText`） | +25 |
| `voice/VoiceApiActivity.kt` | 试听（:492）包 `gate.acquire(TEST)`，对话进行中直接提示"对话中不能试听" | +12 |
| `voice/VoiceTestActivity.kt` | 自测（:361 `speak`）同上 | +12 |
| `voice/accessibility/PetAccessibilityService.kt` | `speak()`（:84）前判断 `SpeechGate.isActive()`，语音对话时不自动念屏 | +6 |
| `pet/PetOverlayService.kt` | `pet_visibility` 复用既有 `"hide"/"show"` action（:627-628），**无需改**；`startVoiceInOverlay()`（:1607）把 `instanceId` 传给 `VoiceRuntime.ensureStarted` 以便判定是否开放工具 | +8 |
| `AndroidManifest.xml` | 新增 `TimerReceiver`（`exported=false`），无新权限（`POST_NOTIFICATIONS` 已声明） | +6 |
| `app/build.gradle.kts` | **不新增依赖** | 0 |

### 2.3 首批 5 个工具规格

| 工具 | 参数（JSON Schema 要点） | 副作用 | 幂等 | 落到现有代码 |
|---|---|---|---|---|
| `close_conversation` | `reason?: string`（`enum:["user_request","finished"]`） | 结束本轮对话 | 是 | `VoiceLoop.stop()`(:197) + `VoiceRuntime.stop()`(:50) + 收起 UI |
| `open_app` | `app: string`（应用名/别名）、`package?: string`（优先） | 拉起另一个 App | 否 | `packageManager.getLaunchIntentForPackage`（与 `PetMenu.kt:276` 同款写法）；`QUERY_ALL_PACKAGES` 已在 manifest 声明 |
| `set_timer` | `seconds: integer`(1..86400) 或 `minutes`(1..1440)、`label?: string`(≤20 字) | 起计时 + 到点提醒 | 否 | 新增 `TimerHub`；`AlarmManager` 用法参考 `PetOverlayService.kt:2856-2863` |
| `remember` | `text: string`(1..120 字)、`type?: enum[preference,habit,fact,event]`、`importance?: number`(0..1) | 写长期记忆 | 是（文件级去重） | `MemoryStore.upsert()`（`MemoryCore.kt:141`）+ `MemoryItem.makeId()`(:106) |
| `pet_visibility` | `visible: boolean` | 显示/隐藏桌宠 | 是 | `Intent(action="hide"/"show")` → `PetOverlayService.onStartCommand`(:627-628) → `setHidden()`(:944) |

工具 `description` 统一写"**仅当用户明确要求时调用**"（降误触发），并在参数说明里给出示例值。`strict` 模式需要 beta `base_url`（官方文档），本期**不开**。

---

## 3. 核心类型定义

```kotlin
package com.dshpet.android.ai.tools

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/** 发给模型的一条工具声明（OpenAI 兼容 tools[] 元素）。 */
data class ToolSpec(
    val name: String,                 // ^[a-zA-Z0-9_-]{1,128}$
    val description: String,
    val parameters: JSONObject,       // JSON Schema: {"type":"object","properties":{...},"required":[...]}
    val strict: Boolean = false,      // 需 beta base_url，本期恒 false
) {
    fun toJson(): JSONObject
}

/** 模型要求执行的一次调用（id 用于回灌时配对）。 */
data class ToolCall(
    val id: String,                   // 模型给的 tool_call_id（缺失时由 Acc 合成）
    val name: String,
    val argumentsRaw: String,         // 模型输出的原始 JSON 字符串（可能非法）
    val index: Int,                   // 同一条 assistant 消息内的序号（保持顺序）
) {
    fun argsOrNull(): JSONObject?
}

enum class ToolError { INVALID_ARGS, UNSUPPORTED, TIMEOUT, DENIED, NOT_FOUND, EXEC_FAILED, DUPLICATE, CANCELLED }

/** 工具执行结果；content 会被原样回灌给模型（截断到 400 字）。 */
data class ToolResult(
    val callId: String,
    val ok: Boolean,
    val content: String,
    val error: ToolError? = null,
    val elapsedMs: Long = 0L,
    val abortableSideEffect: Boolean = false,   // 是否已经产生不可撤销的副作用（用于日志/提示）
)

/** 一个本地工具。execute 必须是可协作取消的（见 §5.4）。 */
interface Tool {
    val spec: ToolSpec
    /** true = 重复执行无副作用（可提前派发、可重试） */
    val idempotent: Boolean get() = false
    suspend fun execute(ctx: ToolContext, call: ToolCall): ToolResult
}

/** 工具拿到的胶囊依赖（不直接依赖 VoiceLoop / Service，便于测试）。 */
interface ToolContext {
    val appContext: Context
    val instanceId: Int                  // 只有 0 会拿到非空 registry（见 §5.6）
    val turnId: String
    val scope: CoroutineScope            // 已绑定本轮，barge-in 时被级联取消
    fun isActive(): Boolean
    /** 工具想直接说一句（如"定了 3 分钟"）；走对话同一条音频通道，不新建 session */
    fun say(text: String)
    /** 结束会话（close_conversation 用）；由 VoiceLoop 提供实现 */
    fun closeConversation(reason: String)
    fun log(tag: String, msg: String)
}

/** 对模型说话的"轮"（替代 history 里的 SseClient.Msg）。 */
sealed interface ChatTurn {
    data class System(val content: String) : ChatTurn
    data class User(val content: String) : ChatTurn
    data class Assistant(
        val content: String?,                        // 可能为 null（只调工具不说话）
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoningContent: String? = null,        // thinking 模式下必须回传
    ) : ChatTurn
    data class Tool(val callId: String, val content: String) : ChatTurn

    fun toJson(): JSONObject
}

/** SSE 解析产物（替代现在"只有 String"的 delta 流）。 */
sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsFragment: String?,
    ) : StreamEvent
    data class Finished(val reason: String, val usage: JSONObject? = null) : StreamEvent
    data class Failure(val message: String, val httpCode: Int? = null, val unsupportedTools: Boolean = false) : StreamEvent
}

/** 注册表：UI 与 router 唯一的工具入口。 */
class ToolRegistry(private val instanceId: Int) {
    fun register(tool: Tool): ToolRegistry
    fun find(name: String): Tool?
    fun specs(): List<ToolSpec>                       // instanceId != 0 或开关关闭时返回 emptyList
    fun disableForTurn(name: String, why: String)     // 熔断（见 §6）
    fun resetTurn()
}

/** 编排器：一次用户发言 = 一个 runTurn。 */
class ToolRouter(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
) {
    /** 把模型文本流送到 onText，把工具事件送到 onTool；返回本轮所有结果。 */
    suspend fun runTurn(
        ctx: ToolContext,
        turns: MutableList<ChatTurn>,
        speech: TurnSpeech,
        streamEvents: (List<ChatTurn>) -> kotlinx.coroutines.flow.Flow<StreamEvent>,
        onTool: (ToolResult) -> Unit = {},
    ): TurnOutcome
}

data class TurnOutcome(val answerText: String, val toolResults: List<ToolResult>, val finishReason: String)
```

**与现有类型的关系**：`SseClient.Msg`（`SseClient.kt:33`）继续存在（记忆抽取、自省、自说自话、`ChatViewModel` 仍在用），只把 `VoiceLoop.history`（:137）换成 `ChatTurn`；`ChatClient.stream()`（:17）继续返回 `Flow<String>`，新增 `streamEvents()` 供 router 用。

---

## 4. SSE 流式 tool_calls 解析方案

### 4.1 真实分片形状

首块带 `id`/`name`/`index`（实测抓包，模型 `deepseek-v4-flash`；形状与 OpenAI 兼容一致）：

```
data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"","name":"get_weather"},
        "id":"call_REDACTED_1","index":0,"type":"function"}]},"finish_reason":null,"index":0}],...}

data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"location\""},"index":0}]},
        "finish_reason":null,"index":0}],...}

data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":":\"杭州\"}"},"index":0}]},
        "finish_reason":"tool_calls","index":0}],...}

data: [DONE]
```

要点：**后续分片只有 `index` 和 `arguments` 片段，没有 `id`/`name`**；`arguments` 是**字符串拼接**，不是增量 JSON patch；`finish_reason` 在中间所有块为 `null`。

### 4.2 累加算法（`ToolCallAccumulator`）

```
state: TreeMap<Int, Partial(id="", name="", args=StringBuilder(), seenId=false)>
lastIndex = 0

on ToolCallDelta(index, id, name, frag):
    i = index (若 JSON 里缺 index 字段 → 用 lastIndex，避免碎片归到 0)
    lastIndex = i
    p = state.getOrPut(i)
    if (id != null && p.id.isEmpty())      p.id = id          // 只在首块接受 id
    if (id != null && p.id != id)          log.warn 并保留首个 id
    if (name != null && name.isNotEmpty()):
        if (p.name.isEmpty())              p.name = name
        else if (p.name != name && !name.startsWith(p.name))  p.name = name  // 网关重发整名
    if (frag != null && frag.isNotEmpty()):
        // 少数网关会重发"完整 arguments"而不是增量：首字符是 '{' 且缓冲已非空 → 替换而非追加
        if (p.args.isNotEmpty() && frag.startsWith("{") && p.args.startsWith("{")) p.args.replaceAll(frag)
        else                                                                     p.args.append(frag)

freeze(): List<ToolCall> = state 按 index 升序映射；id 为空时合成 "call_local_$turnId_$index"
```

### 4.3 `finish_reason` 判定

| 条件 | 判定 | 动作 |
|---|---|---|
| `Finished("tool_calls")` | 参数已完整 | `freeze()` → 去重 → 串行执行 → 二次请求 |
| `Finished("stop")` | 正常结束 | 关通道，排空，收尾 |
| `Finished("length")` | 被 `max_tokens` 截断 | **不执行**任何 tool（参数多半被截断）；把已播文本当完整回答，日志告警 |
| `Finished("insufficient_system_resource" / "aborted")` | 服务端中断 | 有完整 tool 则执行；否则按网络错误重试 1 次（仅当 `answer` 为空） |
| `Finished("content_filter")` | 内容拦截 | 不重试，播兜底话术，`VoiceEvent.Error` |
| 流结束但**没有** `Finished`（EOF/`[DONE]` 前断） | 兜底 | 若 `state` 非空且每个 partial 的 `args` 能被 `JSONObject` 解析 → 视作 `tool_calls`；否则丢弃并回一条纯文本错误 |
| `[DONE]` 但 `finish_reason` 全程为 `null`（部分代理端点） | 兜底 | 同上一行 |

`SseParser.feedLine()` 现在只返回 `List<String>`（`SseClient.kt:203`），改为返回 `List<StreamEvent>`，`TEXT` 与 `TOOL_DELTA` 可同块共存（DeepSeek 允许 `content` 与 `tool_calls` 同时出现）。

### 4.4 异常兜底

| 异常 | 检测 | 处理 |
|---|---|---|
| `arguments` 不是合法 JSON | `JSONObject(raw)` 抛 `JSONException` | 先做无损清洗：去 markdown 围栏、`trim`、去尾部逗号；仍失败 → `ToolResult(INVALID_ARGS)`，**不执行**，把错误回灌让模型重填（同一 call 最多 1 次） |
| 幻觉字段/类型错 | 本地 schema 校验（必填、类型、枚举、范围） | 同上；`additionalProperties` 直接忽略并记日志 |
| `delta` 里 `content` 是 JSON `null` | 现有 `isNull("content")` 判断（`SseClient.kt:230`）保留 | 不产生 `Content` 事件 |
| `reasoning_content` 混入 | 会出现新字段 | 一律不进 TTS；存入 `ChatTurn.Assistant.reasoningContent` 以便回传 |
| 流中出现 `{"error":{...}}` | 现有 :220-224 抛 `IllegalStateException` | 改为 `StreamEvent.Failure`，不吞错（原实现抛在 `feedLine` 内，会被外层 catch 成"网络请求失败"，语义丢失） |
| 连接中途断 | `IOException`/`readUtf8Line` 返回 null | 已有 partial 按 4.3 兜底；否则 `Failure` |
| 响应不是 SSE（HTTP 200 返回 JSON 报错） | 首行不以 `data:` 开头 | 读全 body，按 4xx 分类报错 |

---

## 5. 并发模型（重点）

### 5.1 三层护栏

| 层 | 解决的问题 | 机制 | 失败模式（无此层时） |
|---|---|---|---|
| `SpeechGate` | 冲突 C：多个发声源抢音频 | 进程级单持有者 + 优先级抢占 + `abort` 回调 | 自说自话与对话叠着念；试听页把对话顶掉 |
| `TurnSpeech` + 工具串行队列 | 冲突 A/B：边说边做、工具与播报共存 | 一轮一个音频 session、多次 `feed()`；工具串行 + 3s 超时 | 工具执行时 TTS 被 `startSession` 掐断（"做的时候突然哑了"） |
| barge-in 级联 | 打断要"立刻没声、且不再产生副作用" | 固定顺序取消 + 协作式取消 + history 修补 | 打断后仍有半句在响、工具在后台把 App 打开了 |

### 5.2 SpeechGate（音频单通道）

```kotlin
object SpeechGate {
    enum class Priority(val value: Int) { A11Y(0), TEST(1), SELFTALK(2), CONVERSATION(3) }

    class Lease internal constructor(val owner: String, val priority: Priority) : AutoCloseable {
        override fun close()          // = release()
    }

    fun acquire(owner: String, p: Priority, onEvicted: () -> Unit): Lease?  // 被占用且对方优先级 >= p → null
    fun currentOwner(): String?
    fun isActive(p: Priority): Boolean
    fun forceAbort(): Boolean        // barge-in / 关闭：调 onEvicted 并清空
    val state: StateFlow<String>     // "CONVERSATION:voice-loop"，调试页与徽标用
}
```

规则：

| 规则 | 说明 |
|---|---|
| 单持有者 | 任何时刻最多一个 `Lease` 有效；`acquire` 是原子 CAS |
| 抢占 | 新请求优先级**严格更高**才抢占；抢占时先 `onEvicted()`（同步静音）再交接 |
| 不排队 | `SELFTALK`/`TEST` 拿不到就直接放弃（自说自话排队后在对话里插一嘴是 bug，不是特性） |
| 同步静音 | `onEvicted` 必须只做"立刻停声"（`SpeechPlayer.abort()` / `provider.cancel()`），不做网络/IO，避免持锁 |
| 泄漏回收 | `Lease` 记 `since`；超过 **90s** 未释放的设备端视为泄漏，下一次 `acquire` 时强制回收并 `AppLog` 告警 |
| 锁外回调 | `onEvicted` 在 `synchronized` 外调用，防止回调里再进 gate 造成死锁 |

现有发声源接入：

| 发声源 | 位置 | 接入方式 |
|---|---|---|
| 语音对话 | `VoiceLoop.buildTts()`(:573)、`player`(:67) | `start()` 里 `acquire("voice-loop", CONVERSATION){ activeTts?.cancel(); player.abort() }`；`stop()/release()` 释放 |
| 自说自话 | `QuickTts.speak()`(:28) | `acquire("selftalk", SELFTALK)`，拿不到直接 `return false`；释放放在 `finally`（现在 :60-63 已有 `finally`，只加一行） |
| 「语音 API」试听 | `VoiceApiActivity.kt:492-520`（自建 `AudioTrack`） | `acquire("tts-preview", TEST)`；对话进行中提示"对话中不能试听" |
| 语音自测 | `VoiceTestActivity.kt:361`（`AudioSink`，:391） | 同上 |
| 读屏服务 | `PetAccessibilityService.kt:84-89`（系统 `TextToSpeech`） | 独立 TTS 通道，gate 管不住：只在 `speak()` 前 `if (SpeechGate.isActive()) return` 抑制 |
| 游戏/系统音乐 | 外部 | 不治理；`SpeechPlayer` 已有 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`（:221） |

> 注意：`SpeechGate` 只仲裁"谁都能改的 App 内发声"，**不接管 `AudioFocus` 的申请**——那仍然由各自的 `SpeechPlayer` 持有（`SpeechPlayer.kt:214-231`）。两者职责不重叠：gate 管"谁说话"，focus 管"别人要不要让路"。

### 5.3 工具串行队列 + `withTimeout(3s)`

```kotlin
class ToolExecutor(registry: ToolRegistry, timeoutMs: Long = 3000) {
    suspend fun run(ctx: ToolContext, calls: List<ToolCall>): List<ToolResult>
}
```

实现要点：

1. **串行**：`for (call in calls) { ... }`，前一个结果的 `ToolResult` 立刻进 `onTool` 回调（UI 可以先播"好，定了"）。
2. **单个超时**：`withTimeoutOrNull(3000) { tool.execute(ctx, call) } ?: TIMEOUT`。超时不等于杀线程：工具体里的阻塞调用（`startActivity`、`PackageManager` 查询、写 JSON）必须短，**禁止**在工具里 `Thread.sleep` 或 `MediaPlayer` 阻塞。
3. **不可中断调用的边界**：`PackageManager.queryIntentActivities` 在低端机上可能 200~400ms，可接受；`MemoryStore.upsert` 是同步文件写（`MemoryCore.kt:141`），放在 `Dispatchers.IO` 上执行。
4. **UI 线程**：需要主线程的副作用（`startActivity`、`Toast`）用 `withContext(Dispatchers.Main)`；Service 侧用 `Context.startForegroundService` + action（`pet_visibility` 就是这条路，见 §5.6）。
5. **整轮预算**：`turnDeadlineMs = 12_000`（首请求 + 所有工具 + 二次请求）；`maxSubRounds = 2`。超预算 → 停止再请求，用已有文本收尾并 `VoiceEvent.Info("工具太慢，先这样")`。
6. **熔断**：同一工具连续 2 次失败（异常或超时）→ `registry.disableForTurn(name, why)`，本轮后续请求不再下发该工具的 spec。

### 5.4 barge-in 级联取消

**触发源**（现状）：桌宠单击 → `onTapCommitted` 的 `else` 分支 → `stopVoiceInOverlay()`（`PetOverlayService.kt:1681-1688`）；页面 `stopBtn`（`VoiceLoopActivity.kt:573`）；`VoiceRuntime.stop()`（:50）。目前这些只调 `stop()`，没有"取消工具"的概念。

**取消顺序（固定，不可换序）**：

| 步 | 动作 | 代码锚点 | 为什么这个顺序 |
|---|---|---|---|
| 1 | `SpeechGate.forceAbort()` → 目标 `onEvicted` → `player.abort()` | `SpeechPlayer.kt:54` | 先让人闭嘴（<1 帧），用户感知最强 |
| 2 | `speechJob.cancel()`（`SpeechPipeline` 的段通道关闭） | `VoiceLoop.kt:411` | 防止已排队句子继续合成（省 TTS 费用） |
| 3 | `llmJob.cancel()` + **关闭 OkHttp response** | `SseClient.kt:81-104` | 见下方"坑" |
| 4 | `toolScope.cancel()`，逐个 `toolJob.cancel()` | 新增 `ToolExecutor` | 工具在前三步之后才死，避免"取消了但 App 已经开了"的错觉（顺序上是最后兜底） |
| 5 | history 修补 + `gate.release()` + `_state=Idle` | `VoiceLoop.kt:197-208` | 保上下文合法 |

**坑（必须一起修）**：`SseClient` 用 `readTimeout(0)`（:163）并且 `source.readUtf8Line()` 是阻塞调用；`job.cancel()` 只让协程不再继续 `withContext`，**不会**打断阻塞中的读。真正的止损是关闭 response：在 `invokeOnCompletion { runCatching { source.close() } }`，或把 `resp.use { }` 包起来。验证方法：打断后 `dumpsys`/Profiler 看 `OkHttp` 线程是否退回 idle，以及日志里 `activeTts?.cancel()` 后是否还有增量。

**history 修补（关键，否则下一轮 400）**：

| 场景 | 处理 |
|---|---|
| 打断时 `assistant` 已带 `tool_calls`，但部分 call 还没执行 | 为每个未执行的 call 补一条 `ChatTurn.Tool(callId, "cancelled by user")`，然后追加一条 `User` 提示"（用户打断）" |
| 打断发生在工具执行之后、二次请求之前 | 同上补 `Tool` 消息（内容为"已执行，但用户打断"），保证 pairing 完整 |
| 打断发生在纯文本流中 | 丢弃本轮 assistant 文本（不回写 history），避免"半句话"进上下文 |

**工具侧协作式取消约定**（写进 `Tool` 接口注释）：`execute` 第一行 `ctx.scope.ensureActive()`；每个 `withContext` 前后检查 `isActive`；**不可逆动作放在最后一步**（例如 `open_app` 先解析候选、最后才 `startActivity`），这样"取消窗口"最大。

### 5.5 `tool_call_id` 去重

| 场景 | 去重键 | 行为 |
|---|---|---|
| 正常 | `call.id` | 首次执行并记入 `executed` 集合；重复出现 → `ToolResult(DUPLICATE)`，**仍然回灌一条 tool 消息**（不能丢，否则 pairing 断） |
| 模型没给 id（部分代理/自建端点） | `"$index::${name}::${args.sha1().take(8)}"` | 同上 |
| 早派发 + `finish_reason` 双触发 | 同一 key | 第二次只回灌、不执行副作用 |
| 网关重试导致整条消息重放（同一 turn 内） | 同一 key | 同上 |
| 集合生命周期 | 每 `turnId` 一个 `MutableSet`，最多 32 条；turn 结束即弃 | 跨轮不共享（同一工具在下一轮是合法调用） |

只在 `idempotent == true` 的工具上允许"提前派发"（在 `finish_reason` 之前、`arguments` 已能被 `JSONObject` 解析时执行）。首批 5 个里只有 `pet_visibility`、`remember`、`close_conversation` 幂等，默认 `EARLY_DISPATCH=false`，先按 `finish_reason` 派发，稳定后再开。

### 5.6 多实例（`instanceId == 0`）限制

| 事实 | 依据 | 设计 |
|---|---|---|
| `setHidden()` 对 `instanceId != 0` 直接 return | `PetOverlayService.kt:944-945` | `pet_visibility` 只能作用于主实例 |
| 每个实例是**不同的 Service 类**，但 `VoiceLoop` 是进程单例 | `PetOverlayService.kt:181-192` + `VoiceRuntime.kt:16-48` | 谁先起对话谁持有 gate；工具作用于进程单例（`petHidden` 静态、`VoiceRuntime`、`MemoryStore`、`AlarmManager`） |
| 工具副作用全是进程级 | §2.3 | 结论：**只有从 `instanceId == 0` 发起的对话才下发 `tools`** |

实现：`VoiceLoop.start(s, toolInstanceId: Int = 0)`；`PetOverlayService.startVoiceInOverlay()`（:1607）与 `VoiceLoopActivity`（视作 0）分别传入。`ToolRegistry(instanceId)` 在 `instanceId != 0` 时 `specs()` 返回空 `JSONArray` → 请求体里不带 `tools` → 走纯文本路径，并在 `VoiceEvent.Info` 里说明一次"多开实例不支持工具（只在主桌宠可用）"。

---

## 6. 错误处理与降级

| 场景 | 检测点 | 行为 | 用户可见 |
|---|---|---|---|
| 模型/端点在 thinking 模式下拒绝 `required`/具名 `tool_choice` | HTTP 400 + message 含 `tool_choice` | 我们**从不发** `required`/具名，恒发 `auto`（或字段缺省）；命中则去掉 `tool_choice` 重试 1 次 | 无感 |
| `thinking` 字段不被接受（自建代理） | HTTP 400 + message 含 `thinking`/`unknown field` | 去掉 `thinking` 重试 1 次；若返回了 `reasoning_content`，后续请求回传它（官方要求，否则 400） | 无感 |
| 端点完全不支持 `tools` | HTTP 400/422 + message 含 `tools`/`function` | 记 `unsupportedTools=true`，按 `baseUrl+model` 缓存（内存，进程内有效）；重发不带 `tools` 的请求 | 一次 `VoiceEvent.Info("当前模型不支持工具，已改用普通对话")` |
| 工具执行超时（3s） | `withTimeoutOrNull` 返回 null | 回 `ToolResult(TIMEOUT)`；模型据实回答"没成功"；该工具计 1 次失败 | "定了…没成功" |
| 参数非法 | `JSONObject` 解析失败 / schema 校验失败 | `INVALID_ARGS`，不执行；允许模型重填 1 次（`maxSubRounds` 内） | 无感（模型改口） |
| 工具抛异常 | `catch (e: Throwable)`（`CancellationException` 单独 rethrow） | `EXEC_FAILED`，`content` = `${类名}: ${msg.take(120)}`；连续 2 次熔断该工具 | 模型说"这个我做不了" |
| 工具不存在（模型幻觉工具名） | `registry.find(name) == null` | `UNSUPPORTED`，把可用工具名列表回灌（≤300 字） | 无感 |
| 用户打断 | `CancellationException` | 静默（沿用 `VoiceLoop.kt:479` 的既有约定）；执行 §5.4 修补 | 立刻安静 |
| `finish_reason` 为 `length` | 见 §4.3 | 不执行工具、不二次请求 | 正常播报 |
| `content_filter` / `insufficient_system_resource` | 见 §4.3 | 前者播兜底话术；后者仅在无文本时重试 1 次 | 兜底话术 |
| 无 DeepSeek Key（mock 模式） | `settings.deepseekKey.isBlank()`（`VoiceLoop.kt:269`） | 完全绕过 router（`mockReply` 路径），工具不参与 | 既有提示不变 |
| `toolRouterEnabled=false` | 设置页开关 | `specs()` 为空，行为与改造前完全一致 | 设置项说明 |

回灌给模型的内容统一**截断到 400 字**并去掉换行（voice 场景输出预算紧，`ReplyLengthPolicy.maxTokens` 已经不大）。

---

## 7. 与现有 VoiceLoop 的接入点

| 文件:行 | 现有代码 | 改动 |
|---|---|---|
| `VoiceLoop.kt:137` | `private val history = ArrayDeque<SseClient.Msg>()` | 换成 `ArrayDeque<ChatTurn>`；`nudge()`(:341)、`collectOneShot()` 里构造 `SseClient.Msg` 的地方改为 `ChatTurn`（或就地 `toMsg()` 适配） |
| `VoiceLoop.kt:184 start()` | `history.clear(); job = scope.launch { runLoop() }` | 增加 `gateLease = SpeechGate.acquire("voice-loop", CONVERSATION) { activeTts?.cancel(); player.abort() }`；`start(s, toolInstanceId)` |
| `VoiceLoop.kt:197-208 stop()` | 依次 `activeTts.cancel()` → `job.cancel()` → `player.abort()` → `Idle` | 改为 §5.4 五步；`gateLease?.close()`；`toolExecutor.cancelAll()` |
| `VoiceLoop.kt:222 submitText()` | `stop()` → `oneTurn(t, tts, mock)` | 走同一条 `oneTurn`（内部已换成 router），文案输入也支持工具 |
| `VoiceLoop.kt:398 oneTurn()` | 开 session(:409) → 建 `Channel(8)`(:410) → `pipeline.run`(:411-423) → 组 messages(:452) → 收流(:467) → finally 关通道(:482-486) → 回写 history(:488-511) | 见 §7.1 结构；`session`/`segments`/`pipelineJob` 上移进 `TurnSpeech`；`finally` 的 `segments.close()` 交给 `TurnSpeech.close()` |
| `VoiceLoop.kt:421` | `onSegment` 里 `_state = Speaking(userText, answer, seg)` | `answer` 改为指向 `TurnSpeech.accumulated`（跨子轮累加），否则第二轮句子会显示成第一条回答 |
| `VoiceLoop.kt:435-451` system 拼接 | 硬约束/长度/环境/记忆 | 末尾追加 3 行工具约束（"需要动作时调用工具；不要复述工具参数；工具失败就说没做成"） |
| `VoiceLoop.kt:465-466` | `chat.stream(buildChatCfg(...), messages)` | 换成 `router.runTurn(..., streamEvents = { chatClient.streamEvents(cfg, it) })`；`buildChatCfg`(:588) 增加 `tools` 参数或由 router 组 `JSONObject` |
| `VoiceLoop.kt:478-481` | `catch`：非 Cancellation 就 `VoiceEvent.Error` | 增加 `Failure.unsupportedTools` 分支（降级重试），其余保持 |
| `VoiceLoop.kt:488-511` | `AssistantSaid` + `history.addLast` + `memory.rememberAsync` | 用 `TurnOutcome.answerText`；history 由 router 内部按序写好；`rememberAsync` 保持不变（工具 `remember` 写的是同一份 store，天然合并） |
| `VoiceLoop.kt:573 buildTts()` | 每次 `start()` 建链，`activeTts` 供 `stop()` 掐断 | 不改（工具不需要 TTS 实例），但 `TurnSpeech` 要复用它 |
| `SseClient.kt:33 Msg` | `data class Msg(role, content)` | 保持；新增 `ChatTurn.toMsg()` 供老路径使用 |
| `SseClient.kt:49-116 stream()` | 回调式 + 只回文本 | 新增 `fun streamEvents(cfg, turns, extra: JSONObject?, onEvent): Job`；把现 `onDelta` 路径改为其薄包装（内部实现只留一份） |
| `SseClient.kt:62-70` | `model/messages/stream/temperature/max_tokens` | 按 `extra` 追加 `tools`、`tool_choice:"auto"`、`thinking:{"type":"disabled"}`、`stream_options:{include_usage:true}` |
| `SseClient.kt:82-91` | 非 2xx → `onError("请求失败（HTTP xxx）")` | 保留文案，加 `unsupportedTools` 判定（4.4/§6） |
| `SseClient.kt:198-244 SseParser` | `feedLine(): List<String>`；只取 `delta.content` | 返回 `List<StreamEvent>`；新增 `delta.tool_calls` 处理；`finish_reason` 提取；保留 `isNull("content")`(:230) 与 `reasoning_content` 丢弃逻辑（改为存入事件） |
| `ChatClient.kt:17-29` | `stream()` 适配 `Flow<String>` | 新增 `streamEvents()`；`stream()` 用 `filterIsInstance<Content>().map{it.text}` 实现，避免两份解析 |
| `QuickTts.kt:28` | 直接建 `SpeechPlayer`（:37） | 加 gate 租约（§5.2），`finally` 里释放 |
| `PetOverlayService.kt:1098` | `if (curSelfTalkAiVoice && !VoiceLoop.running)` | 改为 `if (curSelfTalkAiVoice)`，由 `QuickTts` 内部的 gate 决定成败（这个布尔量现在是错的：对话只是"在听"也会挡住自说自话） |
| `PetOverlayService.kt:1607-1640` | `VoiceRuntime.ensureStarted(this)` | 传 `instanceId`；`!=0` 时只拿实例不放工具 |
| `VoiceLoopActivity.kt:510-549 observe()` | `when (e)` 穷举 5 个 `VoiceEvent` | 新增两个分支；`when` 是 sealed，编译期会强制补全 |

### 7.1 改造后的 `oneTurn` 骨架（伪代码，非实现）

```
suspend fun oneTurn(userText, tts, mock, from) {
    turns += ChatTurn.User(userText)
    state = Thinking(userText)
    val speech = TurnSpeech(tts, player, pipeline, scope).open()      // 唯一 startSession
    try {
        val outcome = router.runTurn(ctx, turns, speech, streamEvents = { chatClient.streamEvents(cfg, it, tools) })
        speech.closeAndDrain()                                        // flush + close + join
        if (outcome.answerText.isNotBlank()) { events.emit(AssistantSaid(...)); memory.rememberAsync(...) }
    } catch (e: CancellationException) {
        speech.abortQuietly(); historyRepair(turns); throw e          // 打断：静默 + 修补
    } finally {
        speech.closeQuietly()
        state = Listening()
    }
}
```

---

## 8. 风险清单

| # | 风险 | 触发条件 | 影响 | 缓解 | 验证方式 |
|---|---|---|---|---|---|
| R1 | 打断后 OkHttp 线程悬挂、请求仍在计费 | 流未结束就 `job.cancel()`；`readTimeout(0)`（`SseClient.kt:163`）让阻塞读永不超时 | 后台线程泄漏、token 继续消耗 | `invokeOnCompletion` 里 `source.close()`；`stop()` 先 `activeTts.cancel()`（已有）再加 `streamHandle.close()` | 打断 20 次后看线程数与 `AppLog` 增量；Profiler 确认无增长 |
| R2 | 带 `tools` 时 `reasoning_content` 未回传 → 400 | 请求带 `tools` 且模型处于 thinking 模式（官方默认开） | 每轮对话直接失败 | 显式 `thinking:{"type":"disabled"}`；同时**仍然捕获并回传** `reasoning_content`（兼容忽略 `thinking` 的代理） | 构造"带 tools + 有 reasoning_content"回放用例，断言 200 |
| R3 | 打断后 history 里 `assistant.tool_calls` 无对应 `tool` 消息 | 打断发生在工具执行/二次请求之间 | 下一轮 400，"聊聊就崩" | §5.4 的 `historyRepair`（补 `Tool(callId,"cancelled")` 或整轮回滚） | 单测：打断 3 种时点各构造一次，断言序列 pairing 完整 |
| R4 | 工具参数幻觉/坏 JSON 触发误动作 | 模型输出 `{"app":"微信"}` 之外的多余字段，或截断 | 打开错应用、定时错 | 本地 schema 白名单校验 + 拒绝执行 + 允许模型重填 1 次；不可逆动作放最后一步 | 用 20 条构造样本跑 `arguments` 解析，统计拒绝率 |
| R5 | 后台启动 Activity 被系统拦截 | `open_app` 在桌宠处于后台/息屏时调用（Android 10+ 后台启动限制） | 用户听不到反馈、"她骗人" | 依赖 `SYSTEM_ALERT_WINDOW` 豁免（已声明且悬浮窗在显示）；失败时回灌 `EXEC_FAILED` 并让模型改口；兜底用气泡/通知 | 在 HyperOS / Android 14 真机各测 10 次前后台切换 |
| R6 | 同一调用被执行两次 | 网关重试 / 早派发 + `finish_reason` 双触发 | 起两个计时器、开两次应用 | §5.5 去重（`id` → 指纹降级）+ 回灌不丢消息 | 单测：同一 `ToolCall` 注入两次，断言只执行一次且两条 `tool` 消息 |
| R7 | 多发声源叠播 | 自说自话 / 试听 / 读屏 / 对话同时 | 两个声音叠在一起，最刺耳的一类 bug | `SpeechGate` + 读屏抑制 + 试听页禁入 | 三个入口同时点，`AppLog` 只应出现一个 owner |
| R8 | 工具比 TTS 慢，二次请求迟到出现静音空洞 | 工具 3s、第一轮文本只有 1~2 句（<2s） | 明显的"卡住"体感 | 同一 session 不关通道；`maxSubRounds=2` 压预算；必要时插一句填充语（可配置，默认关） | 埋点：`toolDoneAt - firstAudioStart` 分布，>1.5s 的比例 |
| R9 | `AlarmManager` 非精确 / Doze / 进程被杀 → 计时器丢 | `set_timer` 长时间（>15min）、息屏、国产 ROM 清理 | 到点不提醒 | `setWindow`（不需要 `SCHEDULE_EXACT_ALARM`）+ 开机重排 + 通知；不追求精确到秒 | 设 5min/30min 各一次，锁屏后观察 |
| R10 | 端点/模型不支持 tools（自建代理、旧模型） | 用户在设置里改了 `baseUrl`/`model`（仓库默认仍是 `deepseek-chat`，与现网文档的 `deepseek-flash`/`deepseek-v4-pro` 不一致 —— 待确认） | 每轮 400，功能整体不可用 | 按 `baseUrl+model` 缓存降级；设置页"测试连接"增加一次带 `tools` 的探测 | 故意填错 model，断言第二轮起自动退回纯聊天 |
| R11 | token 成本翻倍 | 每轮最多 3 次请求 + 工具结果回灌 | 账单上升 | 工具结果 ≤400 字；`maxSubRounds=2`；history 仍受 `settings.historyLimit`(:400) 限制 | 记录每轮 `usage`，工具轮次增量 < 2× |

---

## 9. 实施顺序（建议）

1. `SpeechGate` + `QuickTts`/试听/读屏接入 —— 独立可验证，先解决冲突 C，不碰对话主链路。
2. `SseClient` → `StreamEvent` + `ToolCallAccumulator`（纯解析，可离线用回放样本测）—— 对应 §4。
3. `TurnSpeech` 抽取 + `oneTurn` 改造（**不加工具**，先证明"多子轮喂同一 session"不劣化现有播报）—— 对应冲突 A/B 的地基。
4. `ToolRouter` + `ToolExecutor` + 2 个零风险工具（`pet_visibility`、`remember`）打通全链路。
5. 再上 `close_conversation`、`set_timer`、`open_app`（副作用最强，最后上，配 §8 的真机验证）。

## 10. 未决问题（待确认）

| # | 问题 | 影响 | 验证方式 |
|---|---|---|---|
| Q1 | 仓库默认模型 `deepseek-chat` 是否仍支持 `tools` | 默认配置可能直接 400 | 用当前 key 发一次带 `tools` 的请求，看 400 message |
| Q2 | DeepSeek 对"缺失 `tool` 消息"是否报 400 | 决定 `historyRepair` 是"补消息"还是"整轮回滚" | 手工构造残缺 messages 数组请求一次 |
| Q3 | `delta.tool_calls` 是否支持并行多工具（`index` 0..n） | 决定串行队列是否需要"多 call 排序策略" | 抓一次要求"同时做两件事"的回复 |
| Q4 | 是否允许 `open_app` 在前台服务 + 悬浮窗场景下稳定拉起 Activity | 决定是否要退回"通知 + 气泡"提示 | 真机前后台各 10 次 |
| Q5 | 打断是否需要"听到环境声就打断"（真 barge-in） | 需要常开 ASR + 回声消除，成本高；本期按"手势/按钮打断"实现 | 产品确认 |
