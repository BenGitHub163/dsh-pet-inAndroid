# ToolRouter 调研结论 → 设计修订（累积中）

> 用法：每轮调研回来后，把**已证实且会改变实现**的结论追加到这里，并在末尾写清"DESIGN.md 该改哪条"。
> R5 汇总时再合并进 `RESEARCH.md` 与 `DESIGN.md` 正文。

---

## R4-A：DeepSeek function calling 实战（来源：官方文档 + GitHub issue，2026-11）

### 结论

| # | 结论 | 证据 |
|---|---|---|
| 1 | `deepseek-chat` 已于 **2026-07-24** 过官方停用期；当前枚举只有 `deepseek-flash`(V4.1-Flash-1017) / `deepseek-v4-pro`(V4-Pro-0813)。实测仍可用 = 兼容路由，**不可作为实现前提** | api-docs.deepseek.com/news/news260424；issue #1713 |
| 2 | thinking **默认开启**；思考模式下 `tool_choice:"required"` 或具名 → `400 Thinking mode does not support this tool_choice` | issue #1376 |
| 3 | 带 `tools` 时 **`reasoning_content` 必须逐轮回传**（含未发生工具调用的轮），否则 `400 Missing reasoning_content field…`；不带 tools 时该字段被忽略 | issue #1053 |
| 4 | **不存在 `parallel_tool_calls` 参数**；并行调用靠 `delta.tool_calls[].index` 归并，**schema 未承诺 index 从 0 连续** | 官方 OpenAPI schema |

### 三个线上坑

| 坑 | 表现 | 防御 |
|---|---|---|
| 历史含 tool_calls 时会返回**历史工具名** | 50/50 复现（#1713） | 工具名**白名单校验**，未注册直接丢弃并记 `Dsh_TOOL drop=unknown_name` |
| `deepseek-v4-pro` 约 11% 把调用写成**纯文本**（`finish_reason=stop`、`tool_calls=null`） | #1244 | `finish_reason=="tool_calls"` 之外，**再扫 content** 里是否有 `{"name":…,"arguments":…}` 形态 |
| 缺配对 tool 结果 → 400 | #1588（Responses）、#967（Anthropic）；Chat Completions 未验证 | 按"会 400"实现：history 修补（补 placeholder tool 消息）→ 仍失败则整轮回滚 |

### 计费

- 一次带工具的提问 = **2 次独立计费请求**，第二轮全量重发历史；
- `deepseek-flash` 峰时 cache hit ≈ $0.006 vs miss ≈ $0.3（**差 50 倍**），任何前缀改动都会 miss；
- 真正陷阱是**超时后整体重试**（等于再来 2 次）。

### → 对 DESIGN.md 的修改

- **D-model**（新增）：模型名做成配置项 + **失败回退链** `配置值 → deepseek-flash`；每次请求日志打印实际使用的 model。
- **D2 补充**：请求体恒发 `thinking:{"type":"disabled"}`；**禁止**使用 `required`/具名 `tool_choice`。
- **D-history**（新增）：`Msg` 结构增加 `reasoning_content`、`tool_calls`、`tool_call_id`；**凡是带 tools 的会话，assistant 消息必须回传 reasoning_content**。
- **D3 补充**：累加器用 `Map<Int, Acc>`，**不假设 index 连续/从 0 开始**。
- **D9 补充**：`finish_reason=="tool_calls"` 之外，**content 兜底扫描**（针对 v4-pro 的文本化调用）。
- **D10**（新增，计费）：工具结果内容要短（≤200 字）、prompt 前缀保持稳定以命中缓存；超时只重试**单次**请求，不重试整轮。

---

## 待并入（其他轮次还在跑）

- R1：框架/开源项目选型
- R2：SSE 解析与 Android 系统能力
- R3：音频仲裁与 ROM 存活
- R4-B：安全与提示注入
---

## R2-A：SSE 流式 tool_calls 解析（来源：OpenAI/DeepSeek 文档 + langchain4j 单测 + okhttp-sse 源码）

### 决策：**自己写累加器（约 40 行），transport 不换**

| 方案 | 结论 | 理由 |
|---|---|---|
| 继续用现有 `SseClient.SseParser` | ✅ 采用 | 它已正确处理空行分组 / `data:` 前缀 / 注释行 / `[DONE]` / 流内 `error` / `delta.content` 的 JSON null；只缺 tool_calls 分支 |
| `okhttp-sse` | ❌ 不换 | 只做"行→事件"，不含 tool_call 累加；要求 2xx + `Content-Type: text/event-stream`，否则 `onFailure`；`onRetryChange` 被忽略。换它要同时改错误处理/取消/HTTP 状态三处 |
| 增量 JSON 解析库 | ❌ 伪需求 | `arguments` 分片本就不可解析；`decodeFromStream` 要完整文档 |

### 契约事实（已验证）

- `delta.tool_calls[].index` **必填**，语义是"第几个调用"而非数组下标：**不保证连续/从 0 开始**，单帧可含多个 index；
- `id` / `function.name` / `function.arguments` 在 OpenAI 类型里 **全是 optional** → "首块带 id+name" 是实践不是契约；
- **`arguments` 按帧字符串拼接是安全的**（每帧本身是合法 JSON，转义在传输层已解），风险只来自用正则截 JSON 或服务端把一段 JSON 拆进多条 `data:`；
- DeepSeek 的 `finish_reason` 比 OpenAI 多 `insufficient_system_resource` / `aborted`（**都算失败**），没有 `function_call`；末尾帧带 usage + 非空 finish_reason，无独立 usage 帧。

### 13 条坑里最要命的 4 条

| 坑 | 后果 | 防御 |
|---|---|---|
| 尾部哨兵帧 `{"index":0,"id":"","function":{"arguments":null}}` | 造出**幽灵调用**，回传后 400 | 丢弃 `id` 空且 `arguments` 为 null 的帧 |
| `function` 对象整体缺失（LiteLLM/vLLM） | NPE | `optJSONObject("function") ?: continue` |
| `arguments` 为 `null` 时 `optString` 得到字符串 `"null"` | 参数解析失败 | 显式判 `isNull` |
| `name` 每帧都带 vs 只首帧 | 覆盖或丢失 | 只取**首个非空**，后续忽略 |

> 这些不是推测：langchain4j 的 `OpenAiStreamingResponseBuilderTest` 里逐条落成了单测
> （`should_ignore_trailing_sentinel_chunk_from_deepseek_v4_flash`、`should_handle_null_tool_call_index`…），
> **建议直接照抄成我们的测试用例**（见 TESTPLAN 的 SSE 单测章节）。

### → 对 DESIGN.md 的修改

- §4 改为："扩展现有 `SseParser.feedLine` 返回值：`List<String>` → `List<StreamEvent>`（`ContentDelta` / `ToolCallDelta`）"；
- 累加器放 `ai/tools/ToolCallAccumulator.kt`，用 `LinkedHashMap<Int, Acc>`，首非空取 id/name，arguments 字符串相加；
- 新增 4 条"脏帧丢弃"规则（上表）；
- `insufficient_system_resource` / `aborted` → 与 `length` 一样按失败处理，不派发工具。
---

## R3-A：音频仲裁与打断（来源：Android/AOSP 文档 + Pipecat/LiveKit/Vivoka + SO）

### 三条推翻原设计的结论

| # | 结论 | 对 DESIGN 的影响 |
|---|---|---|
| 1 | **AudioFocus 必须由 SpeechGate 单点持有**（同一包名共用一个 clientId）。5-6 个入口各自 request/abandon 会互相把 App 摘出焦点栈 | DESIGN §5.2「gate 不管 focus」**作废**；改为 `SpeechGate` 内统一 request/abandon |
| 2 | **"说完最后一句"属于判定层，不属于执行层**：判定要打断就立即丢弃未播音频；要"说完这句"用**句级软停**（停投喂、不 cancel 写协程、`playbackHeadPosition` 等 drain、3s 兜底） | 新增 `softAbort()`；`abort()` 顺序固化为 `setVolume(0f) → pause() → flush() → onEvicted()` |
| 3 | **真 barge-in（AEC 边播边听）本期砍掉**：AOSP 只说 *should* 提供 AEC；`AcousticEchoCanceler.create()` 可能返回 null；部分机型 `isAvailable()==true` 但实测不消回声；`SpeechRecognizer` 常开还有 300ms+ 重启空窗 | 一期：点击打断 + 播完当前句；把"听声打断"留开关灰度（外放 + 300ms / ≥2 字） |

### 顺带发现的两个既有 bug

| 位置 | 问题 | 建议 |
|---|---|---|
| `SpeechPlayer.kt:214-216` | `if (focusRequest != null) return` 早退 → 一次 `LOSS` 后永不重申请；`LOSS_TRANSIENT` 被当 `stop()`；`CAN_DUCK` 完全没处理 | focus 收归 gate；`TRANSIENT` → `pause()` 保数据、`GAIN` 续播；`CAN_DUCK` → 音量降到 0.3 |
| `SpeechPlayer.kt:159` `playEncodedBlocking` | MediaPlayer + `Thread.sleep(40)` 轮询，最长阻塞 30s 且外部打不断 | 删掉，统一走 PCM track |

### 其它

- 桌宠短音效（"嗯/我在"）改用 `SoundPool`：天然多路混音、可单独 stop，**不要**塞进 SpeechGate 的独占租约；
- 独占租约只约束"人声 vs 人声"；音效与语音应允许 duck 到 0.3 混音；
- `AudioTrack.stop()` 在 `MODE_STREAM` 下语义是"放完最后一次写入的 buffer 才停"（官方原文），这正好支撑 `softAbort` 的实现。

### → 对 DESIGN.md 的修改

- §5.2 重写：`SpeechGate` 增加 focus 持有 + `softAbort()`；
- §5.3 barge-in 五步改为"判定层立即丢音频 / 执行层 softAbort"两条路径；
- 发声源清单里 `PetAccessibilityService`（系统 TTS）保持只靠抑制，不接 gate；
- 新增"音效走 SoundPool、不进 gate"的规则。
---

## R2-B：Android 系统能力落地（来源：developer.android.com 原文 + 真机）

### 三条会改决定的结论

| # | 结论 | 影响 |
|---|---|---|
| 1 | **`QUERY_ALL_PACKAGES` 可以不声明**：官方 package-visibility 文档明确 `startActivity()` 启动别的 App **不要求包可见性**（explicit/implicit 都不要求），可见性只过滤 `PackageManager` 的**查询** | 从 manifest 去掉 `QUERY_ALL_PACKAGES`（Play 受限项），只在需要"列应用"时用 `<queries><intent MAIN+LAUNCHER></intent></queries>` |
| 2 | 悬浮窗 Service 里 `startActivity` **是合规路径**：BAL 豁免清单包含"已获用户授予的 SYSTEM_ALERT_WINDOW" | 但**失败是静默的**（无异常）→ 必须 `FLAG_ACTIVITY_NEW_TASK`，并用 `ActivityTaskManager: Background activity launch blocked!` 日志或 UsageStats 复核 |
| 3 | **op 10021 的身份存疑**：HANDOFF 记"= 自启动"，第三方 MIUI 资料记 `OP_BACKGROUND_START_ACTIVITY`（后台弹出界面） | 我们只验证过"能读能写"，**没验证过系统「自启动」开关写的就是 10021**；且实测 op=ignore 时仍收到 BOOT_COMPLETED，符合"10021 不是自启动"的假设 → 需真机对照实验（见下） |

### 待做的对照实验（1 分钟）

1. `adb shell dumpsys appops | grep -A30 "Package com.dshpet.android"` 记录当前 MIUIOP 值；
2. 在系统「自启动管理」里把本 App 的自启动**打开**；
3. 再 dump 一次，看**哪个 op 变了** → 那个才是自启动。

### 其它关键事实

- Android 14 新装默认**拒绝** `SCHEDULE_EXACT_ALARM`；`USE_EXACT_ALARM` 仅闹钟/日历类可用（Play 限制）；
- Doze 下 `setExactAndAllowWhileIdle` 每 App **每 9 分钟**才允许一次；
- `Handler.postDelayed` 走 uptime，**深睡不计时**；
- `POST_NOTIFICATIONS` 被拒时前台服务照跑，但常驻通知不可见；MIUI 还要过 channel `IMPORTANCE_NONE` 这一关；
- 悬浮窗显隐：默认 `removeViewImmediate`（真正释放 Surface），高频切换才用 `FLAG_NOT_TOUCHABLE|FLAG_NOT_FOCUSABLE` + alpha；**不要用 `View.GONE`**；
- `targetSdk 31+` 禁止"通知点击 → BroadcastReceiver/Service → 再跳 Activity"，必须 `PendingIntent.getActivity` 直达。

### → 对 DESIGN.md 的修改

- `open_app`：不声明 `QUERY_ALL_PACKAGES`；用 `<queries>` + 启动后校验；
- `set_timer`：一期 `Handler`/内存计时 + 通知；不申请精确闹钟；
- `pet_visibility`：用 `removeViewImmediate` / `addView`，不用 `GONE`；
- 新增"启动失败静默"的检测与用户提示路径。
---

## R1-A：工具调用框架横评（外链逐条 HEAD 验证 200）

### 决策：**手写（不引框架）**

| 库 | 版本 | 结论 | 决定性理由 |
|---|---|---|---|
| Koog | 1.3.0 | ❌ 不用 | 最小组合 **+3.2 MB**（agents-core-android 2.19 + openai-client-android 0.97 + …）；`@Tool` 注解式是 JVM-only，**开 R8 反射崩溃**（#1068 open）；专用 DeepSeek client 只到 1.3.0-beta 且在 Android `List.removeLast()` 崩（#2185 open）；要求 Kotlin 2.2+/JDK17/Gradle8 |
| LangChain4j | 1.23.0 | ❌ 不用 | 默认 HTTP 用 `java.net.http.HttpClient` → Android `NoClassDefFoundError`（#2836 open）；OkHttp 变体仍是 beta；体积 ≈6.6 MB（jtokkit 占 3.17 MB 只为分词） |
| openai-kotlin | 4.2.0 | ⚠️ 备选 | tools/tool_choice/流式 delta 全支持、ProGuard 规则自带、`OpenAIHost(baseUrl)` 能接 DeepSeek；但 ≈6.4 MB 且要**接管现有 SSE**（等于重写 SseClient） |
| MCP Kotlin SDK | 0.16.0 | ❌ 不用 | 无 android 制品；server 绑 ktor-server；进程内只能 `ChannelTransport`（官方定位测试用） |
| 手写 | — | ✅ 采用 | 增量 ≈0：请求加 `tools`/`tool_choice`、按 `index` 归并、`when` 分发 + `role:"tool"` 回传 |

### 分界线（以后什么时候该引库）

- **≤5 工具 + 单 provider → 手写**（200–300 行封顶）；
- 5–15 工具 → 手写 + `ToolSpec` 数据类 / schema builder，仍不引；
- 只有出现「多 provider 统一抽象 / 并行工具聚合+重试 / 多步 agent 编排 / 工具 >15 要注解生成 schema」才引库，引则选 **Koog 类式 Tool**（排除注解式，排除 apache5）。

### → 对 DESIGN.md 的修改

- 明确写"不引第三方 agent 框架"，`ai/tools/` 全部自研；
- 依赖清单不动（OkHttp 已有），**不新增依赖**；
- 若未来换 provider 抽象，再考虑 openai-kotlin（不是 Koog）。
---

## R1-B：开源参考实现（GitHub REST API 实测，2026-11-03）

### 结论：没有整包可用，但有 3 个"可拆代码"的点

| 项目 | 星/许可 | 可借鉴 | 用法 |
|---|---|---|---|
| **stixez/droid-mcp** | 40★ / Apache-2.0 | `McpTool`(name/description/parameters/**destructiveHint**/outputSchema/execute→ToolResult) 契约；一能力一 Gradle module；`LaunchAppTool` 正确处理 API30+ `<queries>` 包可见性与 BAL 前置校验 | **照抄契约形状**（我们自己实现，不引库） |
| **yuga-hashimoto/openclaw-assistant** | 324★ / MIT | `SpeechPlaybackTransitions` 播放状态机、`SpeechRecognizerManager` 单点持有 ASR | 做"工具执行期间强制停 ASR 再恢复"的参考 |
| **Voine/ChatWaifu_Mobile** | 1.4k★ / MIT | 按能力拆 module（Live2D/ASR/TTS/LLM 各自独立） | 结构参考 |
| 组件：EasyWindow（1.7k★ Apache-2.0） | 悬浮窗管理 | 备选，不强依赖 |
| 组件：**android-vad**（512★ MIT） | WebRTC/Silero/YAMNet 三后端静音判停 | 首音优化阶段可直接用（当前暂缓） |

### 只能看结构、**禁止拷代码**（许可证）

- **Farewell-coder/Dollhouse**（GPL-3.0）：结构与我们最像（`ChatTool|ChatToolRegistry|ChatToolRunner|MemoryTool`、`PetWindow|PetWindowController`、`KeepAliveAlarmReceiver`），但 GPL → **只读思路**。
- rikkahub（AGPL-3.0）、RemindMe（GPL-3.0）、VRS-Desktop-Pet / CakePet_Android（无许可证）→ 一律不拆。

### 最值得抄的 3 个点（落点已定）

1. **ToolSpec 单一定义同时生成** DeepSeek `tools` JSON 与本地参数校验；`destructiveHint` 用于**二次确认**策略；
2. **voice 层显式播放状态机**：工具执行期间强制停 ASR、执行完再恢复（避免麦克风与工具抢）；
3. **pet 层 Controller 暴露幂等 `show()/hide()`**：工具只调 Controller，不直接碰 `WindowManager`。

### → 对 DESIGN.md 的修改

- `ToolSpec` 增加 `destructiveHint` / `outputSchema` 字段；
- 工具实现**只依赖 `PetController` 接口**（新增），不直接引用 `PetOverlayService`；
- 明确"参考 droid-mcp 契约，但代码自研"。
---

## R3-B：国产 ROM 存活（小米官方适配文档 + 社区实证）

### 四条会改代码的结论

| # | 结论 | 对实现的影响 |
|---|---|---|
| 1 | **op 10021 很可能不是「自启动」**：多个来源称它是 "Display pop-up windows while running in the background"（后台弹出界面），且编号是厂商私有、不稳定 | 与 R2-B 结论一致 → **必须先标定**；`AutoStartHelper` 的文案与判定要跟着改，避免"显示的是后台弹出界面、文案写自启动" |
| 2 | **Android 15 起 `BOOT_COMPLETED` 启动 FGS 有类型白名单**（LOCATION/CONNECTED_DEVICE/REMOTE_MESSAGING/HEALTH/SYSTEM_EXEMPTED/**SPECIAL_USE**），其它类型抛 `ForegroundServiceStartNotAllowedException` | 我们 manifest 是 `specialUse|microphone` → **开机只报 SPECIAL_USE**；语音时才追加 MICROPHONE（每次 `startForeground` 都要带全类型） |
| 3 | **HyperOS「后台发送本地通知」权限默认拒绝**（只给音乐/日程白名单） | 提醒主路径改为 **FGS 内 Handler/协程计时 → 到点弹悬浮窗气泡+动画**；通知只 best-effort + 启动时对账"错过的提醒" |
| 4 | **HyperOS「后台弹出页面」默认拒绝**（白名单只给音乐歌词/运动/VOIP），AOSP 的 SAW 豁免在 MIUI 不生效 | `open_app` **不做后台静默拉起**：仅在"用户刚交互/有可见悬浮窗"时启动；否则给通知或悬浮窗按钮让用户点（`PendingIntent` 点击是 BAL 豁免） |

### 其它要点

- Android 14+ 常驻通知可被用户划掉 → **不能当保活手段**；且必须 `setSmallIcon()`，否则被系统替换成"某应用正在运行…"；
- 精确闹钟在 Android 14+ 默认拒绝（小米文档确认），电源白名单可豁免；
- 一加/ColorOS 12+ 是否有独立「后台弹出界面」待确认。

### → 对 DESIGN.md 的修改

- `BootReceiver`：开机只 `startForeground(SPECIAL_USE)`；语音期间再带 `MICROPHONE`；
- `set_timer`：**主路径 = FGS 内计时 + 悬浮窗气泡**，通知 best-effort；
- `open_app`：新增"前台可见性"前置判断，不满足就走"请点击"路径；
- `AutoStartHelper`：增加"op 标定结果"日志，文案与实测 op 对齐。
---

## R4-B：工具调用安全（OWASP LLM01 + OpenAI Agents SDK + Android 文档）

### 结论：边界必须在**执行侧**

| # | 结论 | 落点 |
|---|---|---|
| 1 | 注入无法在模型侧根治（OWASP LLM01）；**二次确认不是沙箱**（OpenAI Agents SDK 原文：approval does not provide a sandbox） | 参数校验/白名单/限频必须在本地代码 |
| 2 | 风险三档：`close_conversation`/`pet_visibility`/`set_timer` **低风险直接执行**；`remember` **中风险**（≤120 字、拒收指令型、永不参与权限判断、可查可删）；`open_app` **高风险**（白名单 + 同轮二次确认） | 工具注册时声明 `destructiveHint` |
| 3 | **`open_app` 只吃 `appKey`**，package 由本地映射表解析；禁 Intent/URI/component/extras；启动前 `resolveActivity` 比对包名 | 一举解决包名注入 + Play 审核风险 |
| 4 | 真正的放大器是 BAL 豁免（SAW 让我们"真能跳"）+ 看屏 OCR 的间接注入 | 本轮含屏幕内容时 `open_app` 强制确认 |
| 5 | 屏幕文本**永不进 system**，只作带分隔符的 user 数据 | 现有看屏链路要改 |
| 6 | 合规：`QUERY_ALL_PACKAGES` 在 Play 需批准；《必要个人信息范围规定》39 类无"应用列表"；**无障碍不承担工具执行** | manifest + 审核说明 |

### 派发顺序（实现时照此）

`名字白名单 → 本地 schema 校验 → 风险级确认（绑定 callId+argsDigest，8s 超时=拒绝）→ 幂等/限频 → 执行 → 审计（只存 argsDigest）`

### → 对 DESIGN.md 的修改

- `ToolSpec` 增 `riskLevel` / `destructiveHint`；
- 新增 `ToolGuard`（白名单 + schema + 限频 + 审计）；
- `open_app` 参数只保留 `app_key`；
- 看屏内容包成 `<screen>…</screen>` 的 user 内容。
