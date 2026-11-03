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