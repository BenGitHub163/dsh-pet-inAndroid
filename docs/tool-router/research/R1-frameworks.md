# R1 · Android/Kotlin 本地工具调用（function calling）可用的库与框架

> 调研基线：2026-11-03。版本、体积取自 Maven Central `maven-metadata.xml` 与 jar/aar 实际字节数；API 结论取自官方文档原文。
> 标注口径：**已验证** = 读到官方文档/仓库/issue 原文；**待确认** = 无公开依据，需自测。
> 传输与字段细节不在此重复：[R2-sse-tool-calls.md](./R2-sse-tool-calls.md)（流式分片）、[R4-deepseek-tools.md](./R4-deepseek-tools.md)（DeepSeek 语义）。

## 0. 结论

1. **采用手写**：现有 `SseClient` + `kotlinx.serialization` 只差"请求带 `tools`、聚合 `delta.tool_calls`、本地 `when` 分发"三件事，体积增量为 0。
2. 五个候选里 **Koog / LangChain4j 不采用**（各有 Android 上的硬伤），**openai-kotlin 作备选**（只在你不想自己维护 SSE 解析时才值），**MCP Kotlin SDK 不采用**（单 App 本地工具上 MCP 是纯开销）。

## 1. 横向对比

| 库 | 最新版本 | 体积（未 R8，实测 jar/aar 合计） | Android 可用性 | tools 支持 | DeepSeek 兼容 | 维护活跃度 | 结论 |
|---|---|---|---|---|---|---|---|
| **Koog**（JetBrains） | 1.3.0（2026-09-23） | 最小组合 ≈3.2 MB：agents-core-android 2.19 MB + prompt-executor-openai-client-android 0.97 MB + agents-tools-android 84 KB + http-client-okhttp 30 KB；走 `koog-agents` 聚合包会拉 **35 个模块**（含 bedrock/anthropic/ollama/opentelemetry） | ✅ 有 android 制品；minSdk 已从 35 降到 23 | ✅ `@Tool`+`@LLMDescription`+`ToolSet`+`ToolRegistry`；`@Tool` 为 **JVM-only**，另有类式 `Tool` API | ⚠️ 专用 `prompt-executor-deepseek-client` 最新只到 **1.3.0-beta**；且该 client 在 Android 上崩溃（issue 未修） | 高：4.65k★，2026-11-02 有提交，259 open | **不采用** |
| **LangChain4j** | 1.23.0（2026-11-02） | ≈6.6 MB：langchain4j-core 812 KB + open-ai 403 KB + jackson-databind 1.67 MB + jackson-core 585 KB + **jtokkit 3.17 MB** | ❌ 默认 HTTP 走 `java.net.http.HttpClient`（Android 无此类），换 `langchain4j-http-client-okhttp`（仍 beta）才行 | ✅ 低层 `ToolSpecification`+`JsonObjectSchema`，高层 `@Tool`+`AiServices`，流式有 `onPartialToolCall` | ✅ OpenAI 兼容 `baseUrl` | 高：13.3k★，但 Android issue 多为 open | **不采用** |
| **openai-kotlin**（aallam） | 4.2.0（2026-10-31） | ≈6.4 MB：openai-core 4.05 MB + openai-client 1.65 MB + ktor-client-core 765 KB + ktor-client-okhttp 54 KB（R8 后预计 1.5–2 MB） | ✅ KMP，JVM/Android 目标；需自选 Ktor engine（OkHttp 引擎可复用现有 OkHttp） | ✅ `tools{}`/`toolChoice`/`message.toolCalls`/`ToolCall.Function.argumentsAsJson()`，流式 delta 有指南 | ✅ `OpenAIConfig(host = OpenAIHost(baseUrl = "..."))` 直指 `https://api.deepseek.com` | 高：1.85k★，2026-10-31 发版，80 open | **备选** |
| **MCP Kotlin SDK** | 0.16.0（2026-10-27） | ≈3 MB：kotlin-sdk-core 1.37 MB + kotlin-sdk-server 368 KB + ktor-server-core 927 KB | ⚠️ 制品只有 JVM/JS/Native/Wasm，**无 android 变体**；进程内需用 `ChannelTransport` | ✅ 协议层 `addTool(name, inputSchema)` | ✅ 工具元数据与 provider 无关 | 高：1.49k★，2026-11-02 有提交，138 open | **不采用** |
| **手写（schema + SSE + when）** | — | ≈0（复用 OkHttp 4.12 + 已有 kotlinx.serialization） | ✅ 已在跑 | 自己写（`buildJsonObject` 构造 schema） | ✅ 原生 | 取决于自己 | **采用** |

## 2. 逐项结论

### 2.1 Koog（JetBrains）——能力最全，但对本项目是负资产

- Android 是真支持：Maven Central 上有 `agents-core-android`、`agents-tools-android`、`prompt-executor-openai-client-android` 等 AAR；仓库内有 `examples/demo-android-app`。minSdk 曾是 35，[PR #2053](https://github.com/JetBrains/koog/pull/2053)（2026-05）降到 23，[issue #2059](https://github.com/JetBrains/koog/issues/2059) 已关闭。
- HTTP 层可换：[官方文档](https://docs.koog.ai/prompts/http-clients/)明确 `http-client-okhttp` 是 "Android-friendly"，但 `koog-agents` 默认带 `ktor-client-apache5`，必须手工 exclude 并显式传 `Factory`。
- 工具注册：注解式 `@Tool` / `@LLMDescription` / `ToolSet` / `ToolRegistry`（[文档](https://docs.koog.ai/tools/annotation-based-tools/)）；**注解式标注为 JVM-only**，Android 上走反射 → [issue #1068](https://github.com/JetBrains/koog/issues/1068)（open）：开 R8/ProGuard 后 `kotlin.reflect` 直接 `IllegalArgumentException: Class declares 0 type parameters`。
- DeepSeek：provider 表里 DeepSeek 标 β（[文档](https://docs.koog.ai/llm-providers/)），专用 client 最新版是 `1.3.0-beta`（稳定线 1.3.0 没有）；[issue #2185](https://github.com/JetBrains/koog/issues/2185)（open）：`DeepSeekLLMClient.prepareMessagesForDeepSeek()` 调 `List.removeLast()`，在 API 31 上 `NoSuchMethodError`。
- 版本要求：Kotlin 2.2.0+ / JDK 17+ / Gradle 8.0+（[quickstart](https://docs.koog.ai/quickstart/)），`koog-agents-jvm` 的 POM 里 kotlin-stdlib 已是 2.3.10。别用 OK。
- 链接：[github.com/JetBrains/koog](https://github.com/JetBrains/koog)（Apache-2.0）

### 2.2 LangChain4j——Android 不是它的目标平台

- 默认 HTTP 客户端是 `langchain4j-http-client-jdk`（`java.net.http.HttpClient`），Android 运行时没有这个类 → [issue #2836](https://github.com/langchain4j/langchain4j/issues/2836)（open）里就是 `NoClassDefFoundError: Failed resolution of: Ljava/net/http/HttpClient;`，栈顶一路到 `OpenAiChatModel`。
- 历史上还有 dex 失败 [issue #865](https://github.com/langchain4j/langchain4j/issues/865)、`Stream.toList()` 在 Android 14 以下 `NoSuchMethodError` [issue #4839](https://github.com/langchain4j/langchain4j/issues/4839)（2026-03 已修）。修的是一个个点，不是"支持 Android"。
- 规避路径存在但很脆：换成 `dev.langchain4j:langchain4j-http-client-okhttp:1.23.0-beta33`（16 KB），且要自己确认每个 integration 没用 Java 11+ 的 stream/集合 API。
- 体积主要来自 `jtokkit`（3.17 MB 的 BPE 词表，只为算 token）和 Jackson 2.22 全家桶——桌宠根本不需要本地分词。
- ProGuard/R8：无官方 Android 规则（仓库里搜不到 proguard/r8 issue），Jackson + `@Tool` 反射要自己写 keep —— **待确认**。
- 链接：[github.com/langchain4j/langchain4j](https://github.com/langchain4j/langchain4j)、[Tools 文档](https://docs.langchain4j.dev/tutorials/tools/)（Apache-2.0）

### 2.3 openai-kotlin——功能对得上，代价是再引一套 HTTP 栈

- tools 支持完整：[Chat & Tool Calls 指南](https://github.com/Aallam/openai-kotlin/blob/main/guides/ChatToolCalls.md) 给的是"发 tools → 读 `message.toolCalls` → 本地 map 分发 → 追加 `ChatRole.Tool` 消息 → 二次请求"的完整闭环，流式也返回 `choices[].delta` 供你自行拼装。
- KMP 库，Android 直接用 `openai-client` + `ktor-client-okhttp`；ProGuard 规则**已随 jar 打包**（`META-INF/proguard/openai.pro`，R8 自动读取），这点比 Koog/LangChain4j 好。
- DeepSeek 兼容：`OpenAIConfig` 的 `host` 参数是 `OpenAIHost(baseUrl = ...)`，指向 `https://api.deepseek.com` 即可（模型名按 [R4](./R4-deepseek-tools.md) 用 `deepseek-flash`）。
- 代价：`openai-core` 4.05 MB 是全量 API 模型（R8 能砍掉大部分），但会与现有 OkHttp 直连并存两套客户端；且要替换你已经调通的 `SseClient`，属于"为了省 200 行引入 6 MB + 一层抽象"。
- 链接：[github.com/Aallam/openai-kotlin](https://github.com/Aallam/openai-kotlin)（MIT）

### 2.4 MCP Kotlin SDK——协议层很完整，但解决的不是你的问题

- 制品只有 `kotlin-sdk-core/client/server` 的 JVM/JS/Native/Wasm 变体，**没有 android 变体**（Maven `io/modelcontextprotocol/` 目录可核对）；`kotlin-sdk-server-jvm` 直接依赖 `ktor-server-core` + `ktor-server-sse`，在 Android 进程里起 Ktor server 不现实。
- 进程内跑通的理论路径是 SDK 自带的 `ChannelTransport`（协程 channel 的全双工内存传输），但它官方定位是 "for testing and local development"，不是生产传输。
- 价值判断：MCP 解决的是"工具提供方与调用方跨进程/跨厂商解耦"。桌宠的 5 个工具都在同一个 APK 里，走 MCP 只多一层 JSON-RPC 编解码 + 生命周期协商，没有第二方消费。
- 链接：[github.com/modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)、[API 参考](https://kotlin.sdk.modelcontextprotocol.io/)（Apache-2.0 / MIT 混合）

### 2.5 手写——本项目实际该走的路

- 三个改动点，其余复用：① 请求体加 `tools` + `tool_choice`（字段约束见 [R4](./R4-deepseek-tools.md)）；② SSE 里按 `delta.tool_calls[].index` 归并 `function.arguments` 字符串（坑见 [R2](./R2-sse-tool-calls.md)）；③ `when(name)` 分发后，追加 `{"role":"tool","tool_call_id":...,"content":...}` 再发一轮。
- schema 生成：5 个工具用 `buildJsonObject` 直接写（约 60–80 行），比引任何 schema 库都短；`strict` 模式的类型白名单是 object/string/number/integer/boolean/array/enum/anyOf。
- 参考实现（两个都读过原文）：[DeepSeek Tool Calls 官方文档](https://api-docs.deepseek.com/guides/tool_calls)（schema + 多轮消息顺序）、[openai-kotlin ChatToolCalls.md](https://github.com/Aallam/openai-kotlin/blob/main/guides/ChatToolCalls.md)（Kotlin 的 `availableFunctions` map + `execute()` 就是标准分发写法，可直接照搬结构，不引库）。
- 成本估计：200–300 行（含参数校验、非法 JSON 兜底、`index` 乱序保护）；测试重点是"模型给坏参数"和"多工具并行"两个分支。

## 3. 对本项目的建议

**推荐：手写，不引框架。** 理由按权重排序：

1. **已有资产可复用**：`SseClient` + OkHttp 4.12 已在跑，改造是增量而非重写；引库等于把调通的东西换成未验证的抽象。
2. **体积**：手写增量 ≈0（schema 用现成的 kotlinx.serialization）；最省的库方案（Koog 最小组合）也要 +3.3 MB 且 R8 keep 规则会和反射打架（issue #1068）。
3. **DeepSeek 路径风险**：Koog 的 DeepSeek client 是 beta 且在 Android 崩（#2185）；LangChain4j 默认 HTTP 客户端在 Android 直接 `NoClassDefFoundError`。两条都要额外绕路。
4. **工具都是本地能力**（音量/亮度/通知/计时/截图），没有跨进程复用、没有第三方工具接入需求，框架的"注册中心 + 生命周期 + 多 provider 路由"全是净开销。

**5 个工具时，库与手写的分界线**（按触发条件，不看工具数本身）：

| 情况 | 选择 | 说明 |
|---|---|---|
| ≤5 个工具、参数是扁平标量、只接 DeepSeek 一个 provider | **手写** | 200–300 行封顶；多引一个库的维护成本高于自写 |
| 5–15 个工具、仍单 provider | **手写 + 一个 schema builder** | 抽 `ToolSpec(name, desc, schemaJson, handler)` 数据类即可，仍不需要框架 |
| 出现以下任一：多 provider 混用需要统一抽象 / 需要并行工具调用聚合 + 重试 / 需要多步 agent 编排（planner、断点续跑、历史压缩）/ 工具数 >15 且想从注解自动生成 schema | **引库** | 这时选 **Koog**（类式 `Tool` API + `http-client-okhttp`，排除 apache5/ktor 默认后端，注解式别用）；LangChain4j 在 Android 上不建议 |

**如果一定要引库**：`openai-kotlin` 优先于 Koog —— 它只做"API 客户端 + 工具调用原语"，不绑 agent 编排，ProGuard 规则自带，DeepSeek 只是换 `baseUrl`；缺点是 +6.4 MB（R8 后 1.5–2 MB）且要接管你现有的 SSE 解析。

## 4. 待确认

- Koog 1.3.0 的全部 android 制品是否都为 minSdk 23（PR #2053 已合，但未逐模块核对 AAR manifest）——待确认。
- Koog 文档无 Android 专页，Android 用法只能从 `examples/demo-android-app` 反推——待确认是否有未公开的官方指南。
- LangChain4j 在 R8 下的 keep 规则无官方说明（仓库无 proguard/r8 相关 issue）——待确认。
- MCP `ChannelTransport` 具体落在哪个 artifact（core / testing）——待确认。
