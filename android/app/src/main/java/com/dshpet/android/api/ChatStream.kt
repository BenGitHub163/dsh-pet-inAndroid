package com.dshpet.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * **对话流**：OpenAI 兼容 `POST {base}/chat/completions` 的 SSE 请求与解析。
 *
 * 原来叫 `chat/SseClient`，与 [ChatApi] 是同一件事的两半（一个拼 URL、一个发请求），
 * 合并到 api/ 后职责变成：**只发请求、只解 SSE**；端点/模型/鉴权由 [ChatApi] 决定。
 *
 * 1:1 移植 providers.py 的 normalize_chat_endpoint / SSEParser / OpenAICompatibleProvider.stream。
 */
object ChatStream {

    /** 端点与鉴权的快照。`internal`：外部只能通过 [ChatApi] 拿配置 */
    internal data class ChatCfg(
        val baseUrl: String,
        val chatPath: String,
        val model: String,
        val apiKey: String,
        val temperature: Double,
        val maxTokens: Int,
        val timeoutSec: Int,
        val verifySsl: Boolean,
    )

    /**
     * 一条消息。后三个字段是**工具调用**与**思考模式**必需的回传字段：
     *  - [reasoningContent]：带 tools 时 DeepSeek 要求逐轮回传，缺了报 400；
     *  - [toolCalls]：assistant 消息里的工具调用（原样 JSON 字符串）；
     *  - [toolCallId]：role=tool 的结果必须指回对应的调用 id。
     */
    data class Msg(
        val role: String,
        val content: String,
        val reasoningContent: String? = null,
        val toolCalls: String? = null,
        val toolCallId: String? = null,
    )

    fun normalizeEndpoint(baseUrl: String, chatPath: String = "/v1/chat/completions"): String {
        val base = baseUrl.trim().trimEnd('/')
        val path = chatPath.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (base.endsWith("/chat/completions")) return base
        // base 已带版本段（/v1、/v4 等）→ 只补 /chat/completions
        if (path == "/v1/chat/completions" && Regex("/v\\d+$").containsMatchIn(base)) {
            return base + "/chat/completions"
        }
        return base + path
    }

    /**
     * 流式请求。onDelta 在主线程回调；返回的 Job 可 cancel()。
     */
    @com.dshpet.android.util.TraceLog(module = "API", level = "I")
    internal fun stream(
        cfg: ChatCfg,
        messages: List<Msg>,
        onDelta: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ): Job {
        android.util.Log.i("DshChat", "STREAMURL base=${cfg.baseUrl} path=${cfg.chatPath} keyLen=${cfg.apiKey.length} maxTok=${cfg.maxTokens}")
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = normalizeEndpoint(cfg.baseUrl, cfg.chatPath)
                android.util.Log.i("DshChat", "url=$endpoint model=${cfg.model} keyLen=${cfg.apiKey.length} maxTokens=${cfg.maxTokens} temp=${cfg.temperature}")
                val payload = JSONObject().apply {
                    put("model", cfg.model)
                    val arr = JSONArray()
                    messages.forEach { m ->
                        val o = JSONObject().put("role", m.role).put("content", m.content)
                        m.reasoningContent?.let { o.put("reasoning_content", it) }
                        m.toolCalls?.let { o.put("tool_calls", JSONArray(it)) }
                        m.toolCallId?.let { o.put("tool_call_id", it) }
                        arr.put(o)
                    }
                    put("messages", arr)
                    put("stream", true)
                    put("temperature", cfg.temperature)
                    put("max_tokens", cfg.maxTokens)
                }
                val client = buildClient(cfg)
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Accept", "text/event-stream")
                    .apply {
                        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
                        android.util.Log.i("DshChat", "hdr authLen=" + cfg.apiKey.length + " accept=text/event-stream")
                    }
                    .build()
                val call = client.newCall(req)
                activeCall.set(call)
                // use{} 要包住**整段**：失败分支以前直接 return，Response 没人关 → 连接泄漏
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        val msg = safeErrorDetail(resp.body?.string().orEmpty())
                        withContext(Dispatchers.Main) {
                            onError(if (code in setOf(401, 403)) "认证失败（HTTP $code）：$msg"
                            else "请求失败（HTTP $code）：$msg")
                        }
                        return@use
                    }
                    val source = resp.body?.source() ?: run {
                        withContext(Dispatchers.Main) { onError("网络连接失败") }
                        return@use
                    }
                    val parser = SseParser()
                    while (!cancelled.get()) {
                        val line = source.readUtf8Line() ?: break
                        for (delta in parser.feedLine(line)) {
                            if (cancelled.get()) return@use
                            withContext(Dispatchers.Main) { onDelta(delta) }
                        }
                        if (parser.done) break
                    }
                }
                // 被打断的流不该回调 onDone（它是"取消"结束，不是"完成"）
                if (cancelled.get()) return@launch
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                val msg = when (e) {
                    is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
                        "TLS 证书校验失败：${e.message}；可在 AI 设置中勾选\"跳过 SSL 证书验证\"后重试"
                    is java.net.UnknownHostException -> "网络连接失败：无法解析主机"
                    else -> "网络请求失败：${e.message}"
                }
                withContext(Dispatchers.Main) { onError(msg) }
            }
        }
        // Job.cancel() 打不断阻塞的 readUtf8Line：先置位（让循环自己退），再 cancel 底层 Call
        job.invokeOnCompletion {
            cancelled.set(true)
            activeCall.getAndSet(null)?.cancel()
        }
        return job
    }

    /** 连接测试（移植 test_connection）：最小非流式请求 */
    internal fun testConnection(cfg: ChatCfg, onResult: (Result<String>) -> Unit) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = normalizeEndpoint(cfg.baseUrl, cfg.chatPath)
                android.util.Log.i("DshChat", "url=$endpoint model=${cfg.model} keyLen=${cfg.apiKey.length} maxTokens=${cfg.maxTokens} temp=${cfg.temperature}")
                val payload = JSONObject().apply {
                    put("model", cfg.model)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    put("max_tokens", 1)
                    put("stream", false)
                }
                val client = buildClient(cfg)
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .apply {
                        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
                    }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        withContext(Dispatchers.Main) { onResult(Result.success("连接成功（HTTP ${resp.code}）")) }
                    } else {
                        val detail = safeErrorDetail(resp.body?.string().orEmpty())
                        val msg = if (resp.code in setOf(401, 403)) "认证失败（HTTP ${resp.code}）：$detail"
                        else "请求失败（HTTP ${resp.code}）：$detail"
                        withContext(Dispatchers.Main) { onResult(Result.failure(IllegalStateException(msg))) }
                    }
                }
            } catch (e: Exception) {
                val msg = when (e) {
                    is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
                        "TLS 证书校验失败；可在 AI 设置中勾选\"跳过 SSL 证书验证\"后重试"
                    is java.net.UnknownHostException -> "网络连接失败：${e.message}"
                    else -> "网络连接失败：${e.message}"
                }
                withContext(Dispatchers.Main) { onResult(Result.failure(IllegalStateException(msg))) }
            }
        }
    }

    private fun buildClient(cfg: ChatCfg): OkHttpClient =
        ApiClient.config(
            connectSec = cfg.timeoutSec.toLong(),
            readSec = 0, // 流式：不设读超时，由连接层控制
            writeSec = cfg.timeoutSec.toLong(),
            verifySsl = cfg.verifySsl,
        )

    private fun safeErrorDetail(raw: String): String {
        if (raw.isBlank()) return "无响应内容"
        return try {
            val o = JSONObject(raw)
            val err = o.opt("error")
            if (err is JSONObject) {
                err.optString("message", raw.take(200))
            } else {
                raw.take(200)
            }
        } catch (e: Exception) {
            raw.take(200)
        }
    }

    /** SSE 事件解析（逐行 feed），1:1 移植 SSEParser */
    private class SseParser {
        private val lines = mutableListOf<String>()
        var done = false
            private set

        fun feedLine(line: String?): List<String> {
            if (line == null) return emptyList()
            val out = mutableListOf<String>()
            if (line.isBlank()) {
                // 空行 = 事件结束
                if (lines.isNotEmpty()) {
                    val data = lines
                        .filter { it.isNotEmpty() && !it.startsWith(":") && it.startsWith("data:") }
                        .map { it.removePrefix("data:").trimStart() }
                        .joinToString("\n")
                    lines.clear()
                    if (data.isNotEmpty()) {
                        if (data == "[DONE]") {
                            done = true
                        } else {
                            try {
                                val payload = JSONObject(data)
                                if (payload.has("error")) {
                                    val err = payload.get("error")
                                    val msg = if (err is JSONObject) err.optString("message", "Provider 返回错误") else err.toString()
                                    throw IllegalStateException(msg)
                                }
                                val choices = payload.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    // 思考模式下 delta.content 可能是 JSON null，optString 会把它变成字符串 "null"。
                                    // 必须显式判 isNull；reasoning_content（思考过程）一律丢弃，不进入播报。
                                    val content = delta?.let { if (it.isNull("content")) "" else it.optString("content") }
                                    if (!content.isNullOrEmpty()) out.add(content)
                                }
                            } catch (e: org.json.JSONException) {
                                throw IllegalStateException("Provider 返回了无效 JSON", e)
                            }
                        }
                    }
                }
            } else {
                lines.add(line)
            }
            return out
        }
    }
}

/** 拉取 /models 模型列表（OpenAI 兼容）。返回模型 id 列表。 */
fun fetchModels(
    baseUrl: String,
    apiKey: String,
    verifySsl: Boolean = true,
    timeoutSec: Int = 15,
): Result<List<String>> {
    if (apiKey.isBlank()) return Result.failure(IllegalStateException("未配置 API Key"))
    val url = baseUrl.trim().trimEnd('/') + "/models"
    return try {
        val client = ApiClient.config(
            connectSec = timeoutSec.toLong(),
            readSec = timeoutSec.toLong(),
            writeSec = timeoutSec.toLong(),
            verifySsl = verifySsl,
        )
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.failure(IllegalStateException("HTTP ${resp.code}"))
            val text = resp.body?.string().orEmpty()
            val arr = JSONObject(text).optJSONArray("data") ?: return Result.failure(IllegalStateException("响应无 data"))
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            Result.success(out.sorted())
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
