# ToolRouter 调研汇总（R5）

> 输入：8 份调研报告（`docs/tool-router/research/R1–R4*.md`）+ 逐条决策记录（`RESEARCH-DECISIONS.md`）。
> 本文是**给实现用的最终结论**：能直接用 / 改造后用 / 不能用，以及与 `DESIGN.md` 的差异清单。

---

## 0. 十条结论（先看这个）

1. **不引任何 agent 框架**：Koog 最小 +3.2 MB 且 R8 反射崩、LangChain4j 在 Android 直接 `NoClassDefFoundError`、openai-kotlin 要接管 SSE、MCP 无 Android 制品 → **手写 200–300 行**。
2. **不换 SSE transport**：现有 `SseParser` 已做对，只把返回值从 `List<String>` 扩成 `List<StreamEvent>`（ContentDelta / ToolCallDelta），累加器另放。
3. **累加契约**：`index` 唯一必填、不连续、不从 0 开始；`id/name` 取首个非空；`arguments` 字符串相加（每帧合法 JSON，转义已解）。
4. **四个脏帧必须丢**：DeepSeek 尾部哨兵（id 空 + arguments null）、`function` 对象缺失、`arguments:null` 被 `optString` 变成 `"null"`、name 每帧重复。
5. **DeepSeek 侧五条硬约束**：`thinking:disabled`；禁用 `required`/具名 `tool_choice`；带 tools 必须回传 `reasoning_content`；没有 `parallel_tool_calls`；模型名可配置 + 回退到 `deepseek-flash`。
6. **工具名白名单 + content 兜底扫描**：模型会回"历史工具名"（#1713），v4-pro 有 11% 把调用写成纯文本（#1244）。
7. **音频只留一个"人声"通道**：`SpeechGate` 持有 AudioFocus；`abort()` 顺序 `setVolume(0)→pause→flush→onEvicted`；新增 `softAbort()`（说完当前句）；**真 barge-in（AEC）本期砍掉**；音效用 SoundPool，不进 gate。
8. **系统能力收敛**：不声明 `QUERY_ALL_PACKAGES`；`open_app` 用 `<queries>` + 本地 appKey→package 映射 + 启动后校验；HyperOS「后台弹出页面」默认拒绝 → 必须有前台可见性才拉起。
9. **提醒不靠通知**：HyperOS「后台发送本地通知」默认拒绝 → 主路径 = FGS 内计时 + 悬浮窗气泡；通知仅 best-effort；开机 FGS 只报 `SPECIAL_USE`。
10. **安全边界在执行侧**：确认框不是沙箱；`open_app` 高风险的形状是"模型只给 appKey"；屏幕文本永进 system 消息；审计只存 argsDigest。

---

## 1. 三分类

### ✅ 能直接用（拿来即用 / 照抄契约）

| 来源 | 用法 |
|---|---|
| `stixez/droid-mcp`（Apache-2.0） | `McpTool` 契约形状、`LaunchAppTool` 的包可见性 + BAL 前置校验思路 |
| `openclaw-assistant`（MIT） | 播放状态机 + ASR 单点持有（工具执行期停 ASR） |
| `gkonovalov/android-vad`（MIT） | 静音判停（首音优化阶段，当前暂缓） |
| langchain4j 的 `OpenAiStreamingResponseBuilderTest` | **直接抄成我们的 SSE 单测**（7 条真实网关用例） |
| Android 官方文档 | `startActivity` 不需包可见性；SAW 在 BAL 豁免清单；`AudioTrack.stop()` 语义 |

### 🔧 改造后用（思路可用，代码要重写）

| 来源 | 改造点 |
|---|---|
| Dollhouse（**GPL-3.0**） | 只读结构：ToolRegistry / PetWindowController / KeepAliveAlarmReceiver 的分层 |
| ChatWaifu_Mobile（MIT） | 按能力拆 module 的组织方式 |
| Pipecat / LiveKit 的 barge-in | 判定层/执行层分离、句级软停、阈值参考（0.5s / ≥2 字） |
| EasyWindow | 若自研悬浮窗管理成本过高再考虑引入 |

### ❌ 不能用

| 对象 | 原因 |
|---|---|
| Koog / LangChain4j / openai-kotlin / MCP Kotlin SDK | 体积、R8、Android 兼容、需接管传输 |
| okhttp-sse | 不含累加器，且强校验 Content-Type，换它要改三处既有逻辑 |
| rikkahub（AGPL）、RemindMe（GPL） | 许可证传染 |
| VRS-Desktop-Pet / CakePet_Android | 无 LICENSE，默认不可拆 |

---

## 2. 最终技术选型

| 领域 | 选型 | 一句话理由 |
|---|---|---|
| 工具协议 | 自研 `ToolSpec`（含 `destructiveHint`/`outputSchema`） | 同时生成 DeepSeek `tools` JSON 与本地校验 |
| SSE 解析 | 扩展现有 `SseParser` + `ToolCallAccumulator` | 增量最小、风险最低 |
| 模型 | `deepseek-flash`（配置项，回退链 chat→flash） | `deepseek-chat` 已过停用期 |
| 音频仲裁 | 进程级 `SpeechGate`（持 AudioFocus） | 6 个发声源需要一个仲裁者 |
| 打断 | 点击打断 + `softAbort()`（说完当前句） | AEC 在国产机型不可靠 |
| 开应用 | `<queries>` + appKey 映射 + 前台可见性校验 | HyperOS 后台弹页默认拒绝 |
| 定时 | FGS 内计时 + 气泡（通知兜底） | 后台本地通知默认拒绝 |
| 显隐桌宠 | `PetController.show()/hide()`（removeViewImmediate） | 不用 `View.GONE`，不碰 WindowManager |
| 记忆 | 边写边约束（≤120 字、拒收指令型、可查可删） | 防"记忆投毒" |

---

## 3. 与 `DESIGN.md` 的差异（实现以本表为准）

| # | DESIGN 原文 | 修订后 | 依据 |
|---|---|---|---|
| 1 | §5.2 "gate 不管 focus" | **gate 单点持有 AudioFocus**；`LOSS_TRANSIENT`→pause、`CAN_DUCK`→0.3 | R3-A |
| 2 | §5.3 barge-in 五步 | 拆成"判定层立即丢音频 / 执行层 `softAbort`"两条路径 | R3-A |
| 3 | — | 新增：真 barge-in 本期不做，留开关灰度 | R3-A |
| 4 | §4 扩 `SseParser` | 加 4 条脏帧规则；`index` 用 Map 不假设连续 | R2-A |
| 5 | D9 thinking disabled | 保留，并**禁用 required/具名 tool_choice** | R4-A |
| 6 | — | 新增：`Msg` 必须带 `reasoning_content` / `tool_calls` / `tool_call_id` | R4-A |
| 7 | — | 新增：工具名白名单 + content 兜底扫描 + 模型回退链 | R4-A |
| 8 | §2 `open_app` 用 PackageManager 全量匹配 | 改 `<queries>` + 本地 appKey 映射 + `resolveActivity` 校验 + 二次确认 | R2-B / R4-B |
| 9 | §2 `set_timer` 用 AlarmManager | 改 FGS 内计时 + 气泡；通知 best-effort；不申请精确闹钟 | R3-B |
| 10 | §5 显隐用 setHidden | 改 `PetController` 幂等 `show()/hide()`（removeViewImmediate） | R1-B / R2-B |
| 11 | — | 新增：开机 FGS 只报 `SPECIAL_USE`，语音时加 `MICROPHONE` | R3-B |
| 12 | — | 新增：音效走 SoundPool；删 `playEncodedBlocking` | R3-A |
| 13 | §7 接入点 | 增加 `PetController` 抽象层（工具不直接引用 PetOverlayService） | R1-B |
| 14 | — | 新增：审计日志只存 `argsDigest`，不存原文 | R4-B |

---

## 4. 实施顺序（研究结论已足够支撑开工）

1. **批次 A（先做）**：修 `SseClient` 三个 bug（cancelled 不置位、Response 未 `use{}`、`Msg` 缺字段）→ `ToolSpec`/`ToolRegistry`/`ToolContext` → 5 个工具 → 测试页"工具调用"入口；
2. **批次 B**：`ToolCallAccumulator` + VoiceLoop 接入 + `SpeechGate`（先接 VoiceLoop/QuickTts）+ `PetController`；
3. **批次 C**：按 `TESTPLAN.md` 43 条跑，重点 P0 24 条 + 7 条硬门禁，双机型回归。

---

## 5. 需要用户配合的两件事

1. **op 10021 标定**（1 分钟）：在系统「自启动管理」里开关一次本 App，我 dump 前后 diff，确认哪个 op 才是自启动；
2. **HyperOS 权限确认**：设置里「后台弹出界面」「后台发送本地通知」两项的当前状态（用于决定 `open_app` / 提醒的默认降级文案）。

---

## 6. 未决 / 待确认（不阻塞开工）

- ColorOS 12+ 是否有独立「后台弹出界面」权限；
- Play `QUERY_ALL_PACKAGES` / 无障碍政策原文（调研时网络不可达，已用替代来源）；
- 非精确计时在系统冻结下的实际延迟分布（需真机埋点）；
- `deepseek-chat` 兼容路由还能活多久（已做回退链，不阻塞）。
