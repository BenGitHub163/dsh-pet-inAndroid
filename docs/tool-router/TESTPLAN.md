# ToolRouter 测试计划 + 代码评审清单

> 分支：`feature/tool-router`（基线 `ef8f800`）｜范围：DeepSeek function calling + 5 个本地工具（`close_conversation` / `open_app` / `set_timer` / `remember` / `pet_visibility`）
> 机型：Xiaomi 13（Android 16 / HyperOS）、OnePlus 7T Pro（Android 11）
> 本文只定"验什么、怎么验、评审看哪里"，不含实现代码；第 2 节的假 SSE 文本属于测试数据。
> 编写：测试与评审负责人｜日期：2026-11-03

---

## 0. 前置状态（先看这里，它决定后面一半用例能不能跑）

### 0.1 代码现状核对（2026-11-03 逐项实测）

| 检查项 | 实测结果 | 对测试的影响 |
|---|---|---|
| `tool_call` 相关实现 | 全仓 grep `tool_call` / `toolCalls` / `ToolRouter` / `"tools"` **零命中** | 功能尚未落地。本计划先锁契约与用例；实现合并后按表执行 |
| `SseClient.SseParser` | 只取 `choices[0].delta.content`，返回 `List<String>` | 承载不了 tool_calls；单测暂时没有可断言的出口（见 2.8） |
| `SseClient.Msg` | 只有 `role` + `content` | 第二轮请求无法回填 `tool_calls` / `tool_call_id` |
| `SseClient.stream` 的 `cancelled` | 声明后**从未被置位**，`Call` 也从未 `cancel()` | "停止"只停播放，连接仍在读（评审项 R-08） |
| `stream()` 的 `Response` | 没有 `use {}`（`testConnection` 有） | 异常路径泄漏连接（R-09） |
| 通知 | 无 `POST_NOTIFICATIONS` 运行时申请；渠道只有 `PetOverlayService.CHANNEL_ID`(LOW) 与 `chat`(DEFAULT) | Android 13+ 上 `set_timer` 到点可能根本不弹（TC-13 / TC-18） |
| 单元测试基建 | `src/test` 不存在；`app/build.gradle.kts` 无任何 `testImplementation` | 第 2 节用例要先补基建 |
| 多开 | `PetOverlayService1..9`，`EXTRA_INSTANCE = "instance_id"`；`instanceId == 0` 才建灵动岛 | 副作用必须按实例门控（TC-35..TC-39） |
| 语音状态机 | `VoiceState` 是密封类，`VoiceStateBadge.render()` 用穷举 `when` | 新增状态会编译失败——这是好事，别用 `else` 兜住（R-24） |

### 0.2 测试环境

| 项 | Xiaomi 13（Android 16 / HyperOS） | OnePlus 7T Pro（Android 11） |
|---|---|---|
| 主要风险 | 后台启动 Activity 限制、通知默认拒绝、`QUERY_ALL_PACKAGES` 可能被 ROM 裁剪 | 无 `POST_NOTIFICATIONS`（13+ 才有）、Doze 更激进 |
| 必开权限 | 悬浮窗、麦克风"始终允许"、使用情况访问、通知 | 同左 |
| 通知渠道 | 会弹"是否允许通知"系统窗，务必允许 | 直接弹 |

### 0.3 三个假设（不成立就把结论改成"待确认"）

1. 模型用 `deepseek-chat`，支持 OpenAI 兼容的 `tools` + 流式 `tool_calls`；`deepseek-reasoner` 是否支持 function calling（待确认，需对官方文档逐条核对后再决定要不要在 reasoner 上开工具）。
2. 工具执行在 `VoiceLoop` 的协程链内，能随 `stop()` 一起取消；`set_timer` 已落地的闹钟属于**不可回滚副作用**，走显式规则（见 TC-30）。
3. "主实例" = `instanceId == 0`。

### 0.4 可测性前置条件（不满足则对应用例无法判定，实现方需先补）

- [ ] **必须**：新增 `src/test/kotlin`；`testImplementation` 加 `junit:junit:4.13.2`、`kotlinx-coroutines-test:1.8.1`（与运行时 `kotlinx-coroutines-android:1.8.1` 对齐）、`org.json:json`（Android 的 `org.json` 在 JVM 单测里是空桩，会抛 "Stub!"；版本以本地 Gradle 能解析到的为准）。
- [ ] **必须**：工具解析抽成不依赖 `Context` / `android.util.Log` 的纯类，签名形如 `feed(line: String): List<Delta>`（进字符串、出结构），否则 JVM 单测跑不起来。
- [ ] **必须**：日志统一 tag `Dsh_TOOL`，每次调用固定打印 6 个字段：`tool=` / `callId=` / `instance=` / `args=` / `result=` / `costMs=`；被取消再补一行 `canceled callId=`。**用例断言全靠这一行**。
- [ ] **必须**：`set_timer` 用独立通知渠道（`IMPORTANCE_HIGH`）+ 申请 `POST_NOTIFICATIONS`，否则 TC-13 无法通过。
- [ ] 建议：`VoiceLoopActivity` 复用现成的 `--es autotext` / `--es autotext2` 注入句子；再加 `--ez toolsDisabled true`（关工具做对照组）与 `--es forceTool "open_app|微信"`（跳过模型直打工具，用于隔离"模型选错"和"工具做错"）。
- [ ] 建议：`remember` / `set_timer` 的落点可被 adb 读到（AppLog 固定行或 `dumpsys`），否则只能靠"下一轮能不能召回"间接判。

### 0.5 判定用语

- **P0**：误执行副作用、崩溃、卡死、主链路不可用 → 阻断发布。
- **P1**：体验明显受损但有替代路径 → 修完再发。
- **P2**：边缘或机型差异 → 记录，可不阻断。
- 所有"**不该执行**"的断言，以 `adb logcat` 中**没有**对应 `Dsh_TOOL tool=<名字>` 行为准，比"我看它没动"可靠。

---

## 1. 测试矩阵

默认用打字台（`VoiceLoopActivity` 发送框或 `--es autotext`）复现——ASR 会把一句话切碎，语义类用例必须先排除识别噪声；语音入口单独用 TC-01b 覆盖。
共 **43 条**：P0 24 / P1 14 / P2 5。

### 1.1 A 组：语义命中与误判（`close_conversation` 是唯一会关掉用户正在用东西的工具，误判代价最高）

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-01 | 语音对话运行中（胶囊显示"我在听"/"说话中"） | 发送"关闭对话" | 调用 `close_conversation`；播报一句收尾语；随后 `VoiceState.Idle`、胶囊消失、麦克风释放；`VoiceLoop.running == false`；日志出现 `Dsh_TOOL tool=close_conversation instance=0 result=ok` | P0 |
| TC-01b | 同上 | 真语音说"关闭对话"（走 ASR） | 与 TC-01 一致；ASR 可能切成"关闭 对话""关掉对话"，同义词表要覆盖，识别偏差不得变成"不执行"或"误执行" | P1 |
| TC-02 | 微信已安装、对话运行中 | 发送"关闭微信" | **不得**调用 `close_conversation`；播报"我只能关掉咱们的对话，微信得你自己关"一类回复；胶囊仍在、`running == true`；日志无 `tool=close_conversation` | P0 |
| TC-03 | 同上 | 发送"关闭对话吧"/"结束对话"/"退出对话" | 仍命中；同义词表要覆盖"关掉/结束/退出"，同时排除"结束微信""退出微信" | P1 |
| TC-04 | 同上 | 发送"先这样吧"/"不聊了" | 允许命中，但**必须先确认**（"要我把对话关掉吗？"）；未确认前不得 stop | P1 |
| TC-05 | 上一轮刚聊过"关闭微信" | 发送"关掉它" | 指代不明时不调用 `close_conversation`；允许追问"关掉哪个" | P2 |
| TC-06 | 已执行 TC-01 | 对话关闭后单击桌宠，再连点 3 次 | 单击走"退出"分支、不重进语音；三击能重新进语音且胶囊正常出现（`VoiceLoop.running` 语义正确） | P0 |

### 1.2 B 组：`open_app` 应用名匹配

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-07 | 微信已装 | 发送"打开微信" | 拉起 `com.tencent.mm`；播报"打开了"；`tool=open_app args={"app":"微信"} result=ok`；工具自身耗时 < 1.5s（不含冷启） | P0 |
| TC-08 | 抖音 + 抖音极速版都装 | 发送"打开抖音" | 命中标准版；不得打开极速版；歧义时按"精确 > 前缀 > 包含 + 使用频率"取一，且播报里带上选中的名字 | P0 |
| TC-09 | 同上 | 发送"打开抖音极速版" | 命中极速版（证明没硬编码 `com.ss.android.ugc.aweme`） | P1 |
| TC-10 | 无 | 发送"打开设置" | 打开系统设置；包名先用 `pm list packages` 在机子上核对并记入用例（Xiaomi 一般为 `com.android.settings`） | P1 |
| TC-11 | "剪映"未安装 | 发送"打开剪映" | 播报"没找到剪映"（可给相近候选）；不崩溃；不得弹出系统"没有应用可执行此操作" | P0 |
| TC-12 | "微信"已装、"微信读书"未装 | 发送"打开微信读书" | **不得**退化成打开微信（前缀误配）；必须报"没找到微信读书" | P0 |

### 1.3 C 组：`set_timer`

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-13 | 通知权限已给 | 发送"1 分钟后提醒我喝水" | 到点：通知出现（专用渠道、有声音）；桌宠同时播报；点通知回到桌宠/对话页；偏差 ≤ 5s | P0 |
| TC-14 | 同上 | 分别发送"定 0 秒""定 -5 分钟""定 100 小时" | 一律拒绝并追问；`result=invalid_arguments`；不建闹钟（`dumpsys alarm` 里无新增） | P0 |
| TC-15 | 同上 | 发送"提醒我一会儿" | 视为缺参数 → 追问"多久？"，不猜默认值 | P1 |
| TC-16 | 已有 5 分钟定时 | 再定 2 分钟；再发"取消那个 5 分钟的" | 两个并存；按显式时长匹配取消；不得取消错 | P1 |
| TC-17 | 同上 | 到点前息屏 10 分钟（OnePlus，Doze） | 仍按时响（`setExactAndAllowWhileIdle` 级）；偏差 ≤ 15s；**只响一次** | P1 |
| TC-18 | 通知权限**拒绝**（Android 16 默认） | 复现 TC-13 | 降级：桌宠气泡 + 播报必须有；不得静默失败；首次触发时提示去开通知权限 | P1 |

### 1.4 D 组：`remember` / `pet_visibility`

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-19 | 记忆为空 | 说"记住我喜欢喝美式"；下一轮问"我喜欢喝什么" | 第二轮答出美式；`tool=remember result=ok`；记忆页计数 +1 | P0 |
| TC-20 | 已执行 TC-19 | `am force-stop` 后重启 App | 记忆仍在（落盘）；重启后第一轮可召回 | P1 |
| TC-21 | 无 | 说"记住我的 key 是 sk-xxxx" | 不写入原文（或只写"用户提过 API key"这类脱敏摘要）；日志里 key 必须打码 | P2 |
| TC-22 | 桌宠可见 | 说"你先藏起来"，再连点 3 次（或说"出来吧"） | 悬浮窗与胶囊/气泡一起消失，**语音仍在听**（不 stop）；恢复时回到原坐标（实例 1 带 48/32px 偏移，不得跳回主实例位置） | P0 |
| TC-23 | 悬浮窗权限被撤销 | 复现 TC-22 | 播报"没有悬浮窗权限"；`WindowManager.BadTokenException` 不得逃逸 | P2 |

### 1.5 E 组：参数非法 / 超时 / 网络降级

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-24 | 无 | 模型返回未知工具名（用 `--es forceTool "fly_to_moon"` 注入） | 不执行；回一条 tool 错误给模型自我纠正（最多 1 次）；仍失败则降级为普通回复 | P0 |
| TC-25 | 无 | `set_timer` 缺 `seconds`；`arguments` 传数组 `[1,2]` | 校验层拒绝，`result=invalid_arguments`，播报"我没听清要定多久" | P0 |
| TC-26 | 目标应用冷启 > 3s（王者/淘宝） | 说"打开王者荣耀" | 超工具预算（建议 3s）→ 播报"我点了，但好像有点慢"，状态**回 `Listening`**；不得卡在 `Thinking` > 5s | P0 |
| TC-27 | 飞行模式开 | 说"打开微信" | 3s 内给可恢复错误（"网络断了"），状态回 `Listening`；`VoiceLoop` 不退出；关飞行模式后同一会话下一句正常 | P0 |
| TC-28 | 飞行模式开 | 说"关闭对话" | 纯本地工具不依赖网络，**照常执行**（证明工具没挂在 chat 请求上） | P0 |

### 1.6 F 组：并发 / 打断（本次重点）

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-29 | 模型先吐一句再给工具（"好，我这就去开。" + `open_app`） | 说"帮我打开抖音" | **不得边说边做**：要么这句播完再执行，要么执行完再播报，只允许一种；音频里不得出现"念白 + 工具结果播报"重叠 | P0 |
| TC-30 | 同 TC-29 | 工具执行途中按"停止"或再说一句打断 | TTS 立即静音（≤ 200ms）；工具收到取消（`canceled callId=`）；`open_app` 若已 `startActivity` 允许生效但不得再播报；`set_timer` 已落地的闹钟保留并**明确播报**"闹钟我留着" | P0 |
| TC-31 | 长回复播放中（第 2 句） | `set_timer` 秒回，工具结果先到 | 播报串行：工具结果排在当前句之后，不插队、不双声；胶囊同一时刻只显示一种状态（Thinking 与 Speaking 不得并存） | P0 |
| TC-32 | 自说自话开 + AI 语音开 | 让自说自话在"说话中"触发 | 自说自话不抢音频（现有实现靠 `VoiceLoop.running` 跳过）；若抢了即缺陷；对话结束后自说自话恢复正常 | P1 |
| TC-33 | 无 | 1 秒内连发两句（`autotext` + `autotext2`） | 第二句打断第一句；只有一条在播；第一句未执行的 `tool_call` 整体作废（不得"上一轮的工具在下一轮执行"） | P0 |
| TC-34 | 定了 30s 定时 | 到点时用户正在说话 | 提醒等当前句结束再说，或明确打断后播报；不得两段音频叠加 | P2 |

### 1.7 G 组：多开实例（`instanceId > 0`）

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-35 | 主实例 + 实例 1（桌宠菜单"多开"） | 对实例 1 说"打开微信" | 副作用只在主实例执行：实例 1 播报"让老大来开"或直接拒绝；日志只有 `instance=0` 的执行行 | P0 |
| TC-36 | 同上 | 对实例 1 说"提醒我 1 分钟" | 只有一个闹钟、一条通知；不得两只各响一次 | P0 |
| TC-37 | 同上 | 对实例 1 说"记住我不吃香菜" | 记忆落主实例存储（或明确"每实例独立记忆"，但必须与设置页展示一致）；不得两份互相覆盖 | P1 |
| TC-38 | 同上 | 依次触发两只说话 | 同一时刻只有一只出声；另一只静音但状态正确（不得两只都"说话中"且都在响） | P1 |
| TC-39 | 主实例执行 `close_conversation` | 观察实例 1 | 实例 1 不受影响（仍可见、无残留胶囊）；主实例胶囊/气泡清零 | P2 |

### 1.8 H 组：状态残留（胶囊 / 气泡 / 窗口）

| 编号 | 前置条件 | 步骤 | 期望结果 | 优先级 |
|---|---|---|---|---|
| TC-40 | 无 | 正常走完一轮 | 胶囊消失、气泡按设定时长消失；进入 `Idle` 后不得再刷 `Listening`（旧实现曾出现"停止后仍在听"） | P0 |
| TC-41 | 工具失败触发 `Failed` | 断网失败一次，再恢复 | `Failed` 文案 3s 内被下一状态覆盖，不长期挂在桌宠上方 | P1 |
| TC-42 | 无 | 连续 5 轮工具调用后比对窗口数（方法见 3.7） | 悬浮窗口数量不随轮次增长（差值 ≤ 0），无窗口泄漏 | P0 |

---

## 2. SSE 解析的单元测试设计

### 2.1 先把契约写死（实现按这个来，测试按这个断言）

| 契约项 | 规定 |
|---|---|
| 驱动方式 | 逐行 `feed(line)`（OkHttp 的 `readUtf8Line()` 保证行边界）；单元测试还要用"任意块边界切分"再跑一遍（防实现改成 `readUtf8` 缓冲后崩） |
| 输出 | 每个事件 → `Delta(content: String, toolCalls: List<ToolCallDelta>, finishReason: String?)` |
| 累加器 | `ToolCallAcc(index: Int, id: String?, name: String, args: StringBuilder)` |
| 键 | `index` 是唯一键；**`index` 缺失时按 0 处理**，不许 `getInt("index")` 抛异常 |
| 取值规则 | `id` / `name` 只取第一次非空值（后续分片常不带）；`arguments` **原文拼接，绝不逐片 parse** |
| 结束 | `finish_reason == "tool_calls"` 或收到 `[DONE]` |
| 校验 | `name` 为空 → `INVALID_TOOL_CALL`；拼完不是合法 JSON → `INVALID_ARGUMENTS`；是合法 JSON 但字段类型不符 → `INVALID_ARGUMENTS` |
| 异常兜底 | 解析异常必须**返回错误结构**，不得抛到编排层/主线程 |

### 2.2 SSE-01：单个 tool_call，arguments 被切成 4 片

```text
data: {"id":"b1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_0","type":"function","function":{"name":"open_app","arguments":""}}]},"finish_reason":null}]}

data: {"id":"b1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"app\":"}}]},"finish_reason":null}]}

data: {"id":"b1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"微信\""}}]},"finish_reason":null}]}

data: {"id":"b1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":null}]}

data: {"id":"b1","object":"chat.completion.chunk","created":1700000000,"model":"deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

**期望解析结果**

| 字段 | 期望值 |
|---|---|
| `content` | `""`（全程 `content: null`，不得变成字符串 `"null"`） |
| `toolCalls.size` | `1` |
| `toolCalls[0].id` | `"call_0"` |
| `toolCalls[0].name` | `"open_app"` |
| `toolCalls[0].arguments` | `{"app":"微信"}`（原文逐字节相等，不得补空格、不得二次序列化） |
| `finishReason` | `"tool_calls"` |
| `done` | `true` |

### 2.3 SSE-02：两个 tool_call 交错到达（index 0 / 1），且第二片起只有 arguments

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"remember","arguments":""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"set_timer","arguments":""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":\"用户"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\"seconds\":"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"喜欢喝美式\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"600}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

**期望解析结果**

| 字段 | 期望值 |
|---|---|
| `toolCalls.size` | `2`（按 `index` 升序，**不得**因交错而变成 4 条） |
| `toolCalls[0]` | `id=call_a`，`name=remember`，`arguments={"text":"用户喜欢喝美式"}` |
| `toolCalls[1]` | `id=call_b`，`name=set_timer`，`arguments={"seconds":600}` |
| `content` | `""` |
| `finishReason` | `"tool_calls"` |

### 2.4 SSE-03：content 与 tool_calls 混在同一条流里（"边说边做"的解析侧对照）

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"好，","tool_calls":null},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"我这就"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"去开。"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"app\":\"抖音\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

**期望解析结果**

| 字段 | 期望值 |
|---|---|
| `content` | `"好，我这就去开。"`（三个 delta 按序拼接） |
| `toolCalls.size` | `1` |
| `toolCalls[0]` | `id=call_x`，`name=open_app`，`arguments={"app":"抖音"}` |
| 边界要求 | 解析层**只产出** content 与 tool_calls，不做"先播还是先做"的策略；该策略属编排层（对应 TC-29） |

### 2.5 异常样例 A（SSE-04）：`name` 全程缺失

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_n","type":"function","function":{"arguments":"{\"app\""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"微信\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

**期望**：`arguments` 虽拼成合法 JSON，但 `name` 为空 → 结果标记 `INVALID_TOOL_CALL`；**不执行任何工具**；不写半截 `assistant.tool_calls` 进历史；打一行 `WARN`（含 `callId=call_n`）；编排层降级为一句"我没搞懂要做什么"并回到 `Listening`。

### 2.6 异常样例 B（SSE-05）：`arguments` 是非法 JSON（被截断）

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_t","type":"function","function":{"name":"set_timer","arguments":"{\"seconds\": 60,"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

**期望**：拼接结果 `{"seconds": 60,` → `JSONException` 被捕获，返回 `INVALID_ARGUMENTS`；**不崩溃、不执行**；可向模型回一条 `tool` 错误让其重发一次（**最多 1 次**，防死循环）；日志 `Dsh_TOOL result=invalid_arguments callId=call_t`。

> 附加异常（建议同批覆盖，不单独算一条）：`arguments` 是合法 JSON 但类型不符，例如 `{"seconds":"六十"}` 或 `"[1,2]"` → 同样 `INVALID_ARGUMENTS`，不得等工具内部崩了再 catch。

### 2.7 断言清单（每个样例都要跑一遍这些）

| # | 断言 | 说明 |
|---|---|---|
| A1 | `toolCalls.size` 精确相等 | 拦"交错合并成多条" |
| A2 | 每条 `name` 精确相等 | 拦"name 取到空/取到后一片的空串把它覆盖掉" |
| A3 | 每条 `arguments` 与期望字符串**逐字节**相等 | 拦"补空格/重新序列化/丢掉转义" |
| A4 | `content` 与期望相等；`null` 不得变 `"null"` | 思考模式下的经典坑 |
| A5 | `finishReason`、`done` 正确 | 拦"读到 [DONE] 还继续" |
| A6 | 两个驱动方式结果一致：按 `\n` 切行 vs 按任意块切 | 防实现改成缓冲后崩 |
| A7 | 异常样例**不抛未捕获异常**，且返回结构化错误 | 主线程安全 |
| A8 | 同一份输入连跑 100 次结果稳定 | 拦共享可变状态（累加器没清） |

### 2.8 跑测基建（当前缺什么，补完才跑得起来）

| 现状 | 需要补 |
|---|---|
| 无 `src/test`，无 `testImplementation` | 建 `android/app/src/test/kotlin/...`；加 `junit:junit:4.13.2`、`kotlinx-coroutines-test:1.8.1` |
| `SseParser` 用 Android 的 `org.json`（JVM 上是空桩） | `testImplementation("org.json:json:<可解析版本>")`；或改用 Robolectric（更重，不推荐） |
| `SseParser` 藏在 `object SseClient` 里、只返回 `List<String>` | 提取为独立类并返回结构化 `Delta`，否则断言无处落（这也是 0.4 的"必须"项） |

跑法：

```shell
cd android
.\gradlew.bat :app:testDebugUnitTest --tests "com.dshpet.android.chat.SseToolParseTest"
```

---

## 3. 真机验证脚本（可直接粘贴）

### 3.1 选设备与装包

```powershell
adb devices
$env:ANDROID_SERIAL = "8b0dbc3c"     # 换成 adb devices 里那台；两台同时在线时必须指定
```

```shell
# 编译
cd D:\Data\commonData\dev\zc\src-4.3.7\dsh-pet-inAndroid-android-4.3.7\android
.\gradlew.bat :app:assembleDebug

# 安装（-r 保留数据，-d 允许降级）
adb install -r -d "D:\Data\commonData\dev\zc\src-4.3.7\dsh-pet-inAndroid-android-4.3.7\android\app\build\outputs\apk\debug\app-debug.apk"

# 确认版本
adb shell dumpsys package com.dshpet.android | findstr versionName
```

### 3.2 发指令（走现成的 `autotext` 后门）

```shell
adb shell am force-stop com.dshpet.android
adb shell am start -W -n com.dshpet.android/.voice.VoiceLoopActivity --es autotext "关闭对话"
```

```shell
# 连发两句：验证打断 / 上一轮工具作废（TC-33）
adb shell am start -W -n com.dshpet.android/.voice.VoiceLoopActivity --es autotext "把抖音打开" --es autotext2 "算了别开了"
```

```shell
# 记忆整理（已有入口）
adb shell am start -W -n com.dshpet.android/.voice.VoiceLoopActivity --ez curateNow true
```

> `am start` 能拉起 `exported="false"` 的 Activity（shell 持 `START_ANY_ACTIVITY`）；MIUI 上若被拦，先 `am start -n com.dshpet.android/.MainActivity` 再手动切页。

### 3.3 看日志（断言全靠这里）

```shell
adb logcat -c
adb logcat -v time -s Dsh_TOOL:V Dsh_VOICE:V DshVoiceLoop:V DshVoiceChain:V User_语音:V AndroidRuntime:E
```

```shell
# 只看工具行（Windows）
adb logcat -d -v time | findstr "Dsh_TOOL"
# macOS / Linux
adb logcat -d -v time | grep "Dsh_TOOL"
```

### 3.4 断网 / 恢复（TC-27、TC-28）

```shell
adb shell cmd connectivity airplane-mode enable
adb shell cmd connectivity airplane-mode disable
```

```shell
# 备用：只断 Wi-Fi / 数据
adb shell svc wifi disable
adb shell svc data disable
```

### 3.5 多开（TC-35..TC-39）

```shell
# 看当前起了哪些实例（输出里找 instance=）
adb shell dumpsys activity services com.dshpet.android | findstr /i "PetOverlayService"
```

```shell
# 试着直接拉实例 1；服务是 exported="false"，shell 可能被拒（见 3.8）
adb shell am start-foreground-service -n com.dshpet.android/.pet.PetOverlayService1 --ei instance_id 1
```

> 可靠做法：在桌宠菜单点「多开」（内部走 `spawnPet()` → `ensureRunning(ctx, id)`）。adb 只是辅助。

### 3.6 通知与定时器（TC-13、TC-18）

```shell
adb shell "dumpsys notification --noredact | grep -A8 'pkg=com.dshpet.android'"
adb shell "dumpsys alarm | grep -i dshpet"
```

```shell
# 通知权限状态（Android 13+，0=默认/1=允许/2=拒绝）
adb shell cmd appops get com.dshpet.android POST_NOTIFICATION
```

### 3.7 窗口残留 / 截图（TC-40..TC-42）

```shell
adb shell "dumpsys window windows | grep -c 'dshpet'"
adb exec-out screencap -p > shots\tool-round-1.png
```

### 3.8 已知坑

| 坑 | 现象 | 处理 |
|---|---|---|
| `am broadcast` 打不到 `VoiceTestReceiver` | `Broadcast completed: result=0` 或 `Permission Denial: ... is not exported` | 该 receiver 在 manifest 里是 `exported="false"`，但类注释写的是 `exported=true`（**不一致，已记入评审 R-36**）。测试请走 3.2 的 `autotext`；确实要用语音链路广播时，在 debug 变体里临时置 `exported="true"` 并只打在测试机 |
| MIUI 日志丢行 | `-s` 挂太多 tag，崩溃前的行看不到 | 先 `adb logcat -c`；崩溃另看 `adb logcat -b crash -d` |
| Android 16 通知默认关闭 | `set_timer` 到点无声 | 先手动打开通知，TC-18 才是"关掉"的分支 |
| 一次性麦克风授权 | 切到别的应用后就"听不到" | 授权选"始终允许"（`VoiceLoop.runLoop` 里有专门报错文案） |

---

## 4. 代码评审清单（逐条勾选）

### 4.1 主线程与阻塞

- [ ] **R-01** 工具的 `PackageManager` 查询、`startActivity`、文件写、`AlarmManager` 调用**都不在 Main 上**。`VoiceLoop.scope` 是 `Dispatchers.Main.immediate`，`oneTurn` 直接在 Main 跑；新代码漏写 `withContext(IO)` 会直接卡 UI（现成反例：`memory.retrieveTiered()` 不是 suspend 函数，却在 Main 上调，内部要读记忆库；工具层别照抄这个写法）。
- [ ] **R-02** 工具内无 `Thread.sleep` / `runBlocking`；超时统一 `withTimeout`。
- [ ] **R-03** 每个工具有单次预算（建议 ≤ 3s）并有 `costMs` 日志。
- [ ] **R-04** JSON 解析、拼接、日志格式化都不在 Main（流式 delta 目前在 Main 回调，见 `SseClient.stream` 的 `withContext(Dispatchers.Main)`）。

### 4.2 协程取消与生命周期

- [ ] **R-05** 工具在 `oneTurn` 的 job 内（或作为它的 child）执行；**不要** `scope.launch` 到 `VoiceLoop.scope`，那种任务不会随 `stop()` 取消。
- [ ] **R-06** `catch (e: Throwable)` 必须 rethrow `CancellationException`（`oneTurn` 现在这么写，别破坏）。
- [ ] **R-07** 不可取消的阻塞点要有显式取消路径：`SpeechPlayer.playEncodedBlocking` 的主循环是 `Thread.sleep(40)`（最长 30s，取消不了）、`Call.execute()` 同理。
- [ ] **R-08** `SseClient.stream` 的 `cancelled` 标志**从未被置位**，`Job.cancel()` 也不会 `Call.cancel()`；结果"UI 说停了、socket 还在读"。要么真正 cancel `Call`，要么删掉这个死标志（现在是"假装能取消"）。
- [ ] **R-09** `stream()` 里 `resp` 没有 `use {}`（`testConnection` 有），异常分支会漏连接；顺手补。
- [ ] **R-10** 打断/工具结束后必须清空本轮 tool 状态（pending 列表、去重集合、`finishReason`），否则下一轮带着上一轮 callId。
- [ ] **R-11** `submitText()` 里先 `stop()` 再跑；工具改动不得让它变成"stop 之后仍向旧 job 投递工具结果"。

### 4.3 OkHttp / 网络

- [ ] **R-12** 别每轮 `OkHttpClient.Builder().build()`（每次新建连接池 + 线程池）；工具会让一轮变成两次请求，申请共享单例。
- [ ] **R-13** `readTimeout(0)` + 无边界流：工具轮必须靠 `withTimeout` 兜底，不能只依赖连接超时。
- [ ] **R-14** 第二次请求（带 `tool` 结果那条）要复用同一 endpoint 归一化逻辑（`normalizeEndpoint`），别手拼 URL。

### 4.4 JSON 解析安全

- [ ] **R-15** `arguments` 只在拼接完成后 parse 一次；**绝不**在每个 delta 上 parse（分片必炸）。
- [ ] **R-16** 用 `optString` / `optJSONObject` / `isNull`，不用 `getString`。`content: null` 已被处理，`tool_calls: null` 同理必须处理。
- [ ] **R-17** `index` 缺失默认 0；`id`/`name` 只取第一次非空值。
- [ ] **R-18** 参数类型校验（`seconds` 为 Int 且 1..86400；`app` 非空 String；`visible` 为 Boolean）在**执行前**做，不靠工具内部崩了再 catch。
- [ ] **R-19** 解析失败返回结构化错误对象，不向编排层/主线程抛异常。

### 4.5 tool_call 去重与幂等

- [ ] **R-20** 同一响应里重复 `index` 的分片要**合并**，不能追加成两条。
- [ ] **R-21** 同一 `callId` 的重放（网关重试 / 断线重连）只执行一次；去重键 `callId`，窗口 = 当前轮次。
- [ ] **R-22** 跨轮不复用 `callId`；`close_conversation` / `pet_visibility` 做成**幂等**（重复执行不报错）。
- [ ] **R-23** `set_timer` 同参数连发两次：宁可建两个，也不静默丢一个（丢定时器比多一个更伤）。

### 4.6 状态机与 UI 残留

- [ ] **R-24** 若给 `VoiceState` 加 `ToolRunning`，`VoiceStateBadge.render()` 的 `when` 必须补分支（现在是穷举 `when`，别加 `else` 兜住——`else` 会把新状态静默显示成旧文案）。
- [ ] **R-25** `close_conversation` 必须走 `VoiceLoop.stop()`（含 `player.abort()` + `activeTts?.cancel()`），只改 `_state.value = Idle` 是错的：那只改 UI，音频还在放、合成请求还在计费。
- [ ] **R-26** `close_conversation` 后 `VoiceLoop.running` 必须为 false（桌宠单击语义依赖这个 companion 标志）。
- [ ] **R-27** `pet_visibility` 隐藏时必须同时处理 `voiceBadge.hide()` 与气泡/胶囊，不能只 `view.visibility = GONE`（悬浮窗会留在窗口栈里，`dumpsys window` 数得出来）。
- [ ] **R-28** 工具失败/超时后必须回 `Listening`（或 `Failed(recoverable=true)` 后自动回来），不得停在 `Thinking`。
- [ ] **R-29** `VoiceLoopActivity.ownsLoop` 逻辑不能被工具改动破坏：共享实例（`VoiceRuntime` 那份）不能被 `release()`。

### 4.7 副作用、权限、多开

- [ ] **R-30** 副作用门控 `instanceId == 0` 且 gate 写在 Router 内部（不是每个调用点各写一遍，会漏）。
- [ ] **R-31** `startActivity` 必须带 `FLAG_ACTIVITY_NEW_TASK`；Android 10+/HyperOS 后台启动受限，失败要 catch `ActivityNotFoundException` / `SecurityException` 并降级播报。
- [ ] **R-32** `set_timer` 用 `setExactAndAllowWhileIdle`（或 `AlarmManagerCompat`），到点靠 broadcast 拉起，不依赖进程存活；`SCHEDULE_EXACT_ALARM`（Android 12+）是否需要在 manifest 声明：按目标 ROM 实测确认（待确认）。
- [ ] **R-33** 通知渠道独立 + `IMPORTANCE_HIGH` + `POST_NOTIFICATIONS` 运行时申请（现在全仓 0 处申请）；通知 id 避开 `1000 + instanceId` 的常驻通知。
- [ ] **R-34** `remember` 必须复用 `MemoryManager` 既有写入路径（含 `synchronized`），别另开存储；写入失败不得让整轮对话失败。

### 4.8 日志与隐私

- [ ] **R-35** 日志里 key/token 打码；`args` 落日志前截断（≤ 200 字）；`remember` 内容涉及凭证时只记"已脱敏"。
- [ ] **R-36** `Dsh_TOOL` 固定字段（见 0.4）；顺带把 `VoiceTestReceiver` 的 manifest `exported="false"` 与类注释写的 `exported=true` 对齐（现在不一致，adb 广播打不进来）。

### 4.9 可测性

- [ ] **R-37** 解析器纯函数化（无 `Context`、无 `android.util.Log`），签名"进字符串出结构"。
- [ ] **R-38** 提供 `--ez toolsDisabled true` 与 `--es forceTool` 注入点，并**用 `BuildConfig.DEBUG` 门控**（别把后门带进 release）。
- [ ] **R-39** PR 描述里列出新增/变更的权限与 manifest 改动。

> 共 39 条；R-01/05/08/15/25/30/33 是**必须挡住合并**的 7 条。

---

## 5. 回归范围（改动后必须重测的既有功能）

| # | 回归项 | 入口 | 判定标准 | 优先级 |
|---|---|---|---|---|
| REG-01 | 语音对话主链路 | 桌宠三击 / `VoiceLoopActivity` | 一轮完整"听→想→说"；`DshVoiceLoop` 状态序列 `Listening→Thinking→Speaking→Listening` | P0 |
| REG-02 | 打字台 `submitText` | 发送框 / `autotext` | 与 REG-01 同；仍先 `stop()` 再跑 | P0 |
| REG-03 | 自说自话（内置台词 / AI 台词 / AI 语音） | 桌宠定时器 | `Dsh_SELFTALK` 有"排期 / 显示"；语音对话进行中不插嘴 | P0 |
| REG-04 | TTS 试音台 | `VoiceTestActivity` 或 `VOICE_TEST` 广播 | `DshVoice TEST OK chunks=… pcmBytes=…`（注意 3.8 的导出问题） | P1 |
| REG-05 | 语音设置热更新 | 「语音 API」页改音色/语速 | `Dsh_VOICE 配置热更新：provider=…`；**下一句**立即生效，不需重启 | P0 |
| REG-06 | 记忆（抽取/整理/清空/计数） | 记忆页、「立即整理」 | 计数变化、`curateNow` 有日志、清空后为 0 | P1 |
| REG-07 | 多开 `spawnPet` 与实例偏移 | 桌宠菜单「多开」 | 新实例偏移 48/32px；灵动岛只在主实例 | P1 |
| REG-08 | 胶囊/气泡生命周期 | 任意一轮对话 | 见 TC-40..TC-42 | P1 |
| REG-09 | 断网与 TLS 跳过 | 飞行模式 / AI 设置勾选跳过校验 | 错误文案仍准确（"TLS 证书校验失败…"） | P1 |
| REG-10 | 崩溃/ANR 基线 | 全流程 | `adb logcat -b crash -d` 无新增；`Dsh_CRASH` 无新记录 | P0 |

---

## 6. 发布前 checklist

### 6.1 构建与包

- [ ] `versionCode` / `versionName` 已 bump，`BuildConfig.BUILD_STAMP` 与出包时间一致
- [ ] 签名用 `keystore/dshpet-release.keystore`（release + debug 均如此配置，别混）
- [ ] 朋友版（`-Pdsh.noKey=true`）单独验证：两处 key 为空、设置页能填
- [ ] `keys.properties` 未入库、未打进包（`aapt dump` 或解包确认无明文 key）

### 6.2 测试入口与后门

- [ ] `VoiceLoopActivity` 的 `autotext` / `autotext2` / `curateNow` 后门已用 `BuildConfig.DEBUG` 门控或移除
- [ ] `VoiceTestReceiver` / `VoiceTestActivity` 的处置有明确结论（保留须 `exported=false`，且注释与 manifest 一致）

### 6.3 功能门禁

- [ ] 43 条用例中 P0 全绿；P1 有明确结论（修 / 已知问题登记）
- [ ] 两台机型都跑过 P0（Android 16 与 Android 11 各一遍，别只跑新机）
- [ ] TC-29 / TC-30 / TC-31 三条并发用例有录屏或 logcat 片段作为证据
- [ ] 第 2 节单测全绿，且 CI 会跑 `:app:testDebugUnitTest`

### 6.4 升级与干净线

- [ ] `adb install -r` 覆盖升级不丢记忆/设置/悬浮窗位置
- [ ] 卸载重装后首启流程正常（权限申请、引导）
- [ ] `am force-stop` 后无僵尸通知、无残留悬浮窗（`dumpsys window` 计数回零）
- [ ] 回滚方案就绪：上一个 APK 路径 + 关闭工具的开关（若实现里带开关）

### 6.5 文档

- [ ] 新增权限（通知、精确闹钟等）在设置页/说明里有用户能看懂的一句话
- [ ] 本文件随实现一起更新（用例编号别漂移）

---

## 附录 A 日志锚点速查

| tag | 来源 | 看什么 |
|---|---|---|
| `Dsh_TOOL` | 新增（0.4 要求） | `tool=` / `callId=` / `instance=` / `args=` / `result=` / `costMs=` |
| `Dsh_VOICE` | `AppLog.log("VOICE", …)` | 配置热更新、provider 切换 |
| `DshVoiceLoop` | `VoiceLoop.TAG`（注意不是 `Dsh_VOICE`） | 每轮 prompt、nudge、chat 异常 |
| `DshVoiceChain` | `TtsChain` | `tts fail via …` / `tts ok via …` |
| `DshVoicePlayer` | `SpeechPlayer` | `AudioTrack created rate=…` |
| `DshVoiceCache` | `CachingTtsProvider` | `cache hit …` |
| `DshSvc` | `PetOverlayService` | `onStartCommand instance=…` |
| `Dsh_ISLAND` / `Dsh_SELFTALK` | `AppLog.log("ISLAND"/"SELFTALK", …)` | 灵动岛、自说自话 |
| `User_语音` / `User_桌宠` | `AppLog.u(…)` | 用户可读事件、沉默判定、气泡是否出现 |
| `Dsh_CRASH` | `PetApp` 未捕获异常 | 崩溃栈 + 内存快照 |

## 附录 B 与实现方的交接要点

1. 第 0.4 的 4 条"必须"是**开工前置**，尤其"纯函数解析器 + `Dsh_TOOL` 固定字段"，否则第 1、2 节大半用例没有判定依据。
2. `SseClient` 的 `Msg` 需要能表达 `tool_calls` 与 `role="tool"` + `tool_call_id`，否则第二轮请求发不出去（当前只有 `role` + `content`）。
3. `deepseek-reasoner` 是否支持 function calling 属**待确认**，不要凭猜测在 reasoner 上开工具。
4. 本文件只改一个文件；实现合入时按"用例编号 → 日志行"的对应关系贴证据，不要只写"测过了"。
