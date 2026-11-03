# 下一次接手要做的三件事（按顺序，每步编译通过再提交）

## 0. 当前状态

- 分支：`feature/tool-router-impl`（从 `main` 切出，`main` 已包含全部重构）
- 最近 tag：`v4.3.9-gate-2`
- 构建：`BUILD SUCCESSFUL`；`checkApiHosts` 通过（域名规则已强制、HTTP 位置规则仅告警）
- 已完成的重构：ChatApi / VisionApi / ApiDefaults / BreezeApi / BalanceApi / 删除 ChatConfigFactory / 删除 VisionClient / SseClient 三处修复

## 1. TtsApi + TtsCodec（最大的一块）

目标：三个 provider 不再发 HTTP，只做编解码。

```kotlin
// api/TtsApi.kt
class TtsApi(ctx: Context) {
    fun speak(text: String, profile: VoiceProfile): Flow<AudioChunk>
}

// api/TtsCodec.kt —— 无 OkHttpClient、无域名、无 key
interface TtsCodec {
    fun request(text: String, profile: VoiceProfile, cfg: TtsConfig): TtsHttpSpec
    fun decode(chunk: ByteArray): AudioChunk?
    fun decodeLine(line: String): AudioChunk?
}
data class TtsHttpSpec(val url: String, val headers: Map<String, String>, val bodyJson: String, val stream: Boolean)
```

顺序：`BreezeCodec` → `DoubaoCodec` → `OpenAiCodec`，每加一个就删对应 provider 里的
`Request.Builder` / `newCall` / `Bearer` / `X-Api-Key`，并跑一次 `:app:checkApiHosts` 看告警数量下降。

## 2. SseClient 并入 api/

`chat/SseClient.kt` 本质就是"对话接口"，与 `ChatApi` 重复。做法：
- 移入 `api/ChatStream.kt`（包名改 `com.dshpet.android.api`）；
- `ChatApi` 内部直接用它，`ChatCfg` 变成 `internal`；
- 删除 `chat/SseClient.kt`，修编译错误（`ChatClient`、`ChatViewModel`、`Memory*` 已经不再直接用 cfg）。

## 3. 闸门切失败模式

`app/build.gradle.kts` 里 `httpOffenders` 的告警改为 `throw GradleException`。
前提：第 1、2 步做完后 `checkApiHosts` 的告警应为空。

## 4. 然后回到 ToolRouter 批次 A

文档在 `docs/tool-router/`（SPEC / DESIGN / TESTPLAN / RESEARCH）。
第一步是 `ai/tools/` 四个基础类 + `ToolCallAccumulator`（脏帧规则见 RESEARCH.md §R2-A），
然后是 5 个工具，最后是测试页的「工具调用」入口。
---

## 进度快照（v4.3.9-tts-breeze-clean 之后）

已完成：`TtsCodec` + `TtsApi` + `BreezeCodec`；`BreezeTtsProvider` 已无 OkHttp。

### 剩余 5 个 HTTP 文件与做法

| 文件 | 处数 | 做法 |
|---|---|---|
| `OpenAiCompatTtsProvider.kt` | 4 | 写 `api/OpenAiCodec.kt`（body=`{model,input,voice,response_format}`，stream 读行式 SSE → `decodeLine`），provider 改成薄壳 |
| `VolcengineTtsProvider.kt` | 4 | 写 `api/DoubaoCodec.kt`（二进制分块 + 头 `X-Api-Key`/`X-Api-Resource-Id`），provider 薄壳 |
| `VoiceApiActivity.kt` | 4 | 试听直接 `TtsApi(BreezeCodec()).synthesize(...)`，删掉重复的 AudioTrack 播放实现 |
| `chat/SseClient.kt` | 9 | 整体移入 `api/ChatStream.kt`，`ChatCfg` 改 `internal`，删旧文件 |
| `SpeechPlayer.kt` | 1 | 误报：`Request.Builder` 是 AudioTrack 回调同名，闸门加路径白名单即可 |

### 之后

1. `checkApiHosts` 第二条规则从告警切失败；
2. 回到 ToolRouter 批次 A（`docs/tool-router/`）。

### 操作提醒（踩过的坑）

- 短类（<100 行）不要用"按行删"的脚本改，容易误删 `cancel()`、留下孤立的 `@Volatile`；**整文件重写**更安全；
- 每次写完立刻 `:app:compileReleaseKotlin`，不要攒到最后；
- 提交前看一眼 `git diff --stat`，若行数异常大，多半是 CRLF/LF 混了。