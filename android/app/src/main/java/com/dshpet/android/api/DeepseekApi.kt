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
 * DeepSeek 厂家接口：对话 completions / 模型列表 / 余额。
 * Key 从 [com.dshpet.android.data.KeyFactory] 取。业务请走 [ChatApi] / [ApiFactory]，不要直接打这里。
 */
object DeepseekApi {

    data class ChatReq(
        val baseUrl: String,
        val chatPath: String,
        val model: String,
        val temperature: Double,
        val maxTokens: Int,
        val timeoutSec: Int,
        val verifySsl: Boolean,
    )

    fun normalizeEndpoint(baseUrl: String, chatPath: String = "/v1/chat/completions"): String {
        val base = baseUrl.trim().trimEnd('/')
        val path = chatPath.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (base.endsWith("/chat/completions")) return base
        if (path == "/v1/chat/completions" && Regex("/v\\d+$").containsMatchIn(base)) {
            return base + "/chat/completions"
        }
        return base + path
    }

    @com.dshpet.android.util.TraceLog(module = "API", level = "I")
    fun startStream(
        req: ChatReq,
        messages: List<ChatApi.Msg>,
        onDelta: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ): Job {
        val apiKey = chatKey()
        android.util.Log.i("DshChat", "STREAMURL base=${req.baseUrl} path=${req.chatPath} keyLen=${apiKey.length} maxTok=${req.maxTokens}")
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = normalizeEndpoint(req.baseUrl, req.chatPath)
                android.util.Log.i("DshChat", "url=$endpoint model=${req.model} keyLen=${apiKey.length} maxTokens=${req.maxTokens} temp=${req.temperature}")
                val payload = JSONObject().apply {
                    put("model", req.model)
                    put("messages", encodeMessages(messages))
                    put("stream", true)
                    put("temperature", req.temperature)
                    put("max_tokens", req.maxTokens)
                }
                val httpReq = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody(JSON))
                    .header("Accept", "text/event-stream")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .build()
                val call = client(req, stream = true).newCall(httpReq)
                activeCall.set(call)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val code = resp.code
                        val msg = safeErrorDetail(resp.body?.string().orEmpty())
                        withContext(Dispatchers.Main) {
                            onError(
                                if (code in setOf(401, 403)) "认证失败（HTTP $code）：$msg"
                                else "请求失败（HTTP $code）：$msg",
                            )
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
        job.invokeOnCompletion {
            cancelled.set(true)
            activeCall.getAndSet(null)?.cancel()
        }
        return job
    }

    fun testConnection(req: ChatReq, onResult: (Result<String>) -> Unit) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val ping = JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
            val r = complete(req.copy(maxTokens = 1), ping)
            withContext(Dispatchers.Main) {
                onResult(
                    r.fold(
                        onSuccess = { Result.success("连接成功") },
                        onFailure = { Result.failure(it) },
                    ),
                )
            }
        }
    }

    /**
     * 非流式 completions。 [messages] 已是 OpenAI 形态的 messages 数组
     *（文本对话或视觉多模态都走这里）。
     */
    suspend fun complete(req: ChatReq, messages: JSONArray): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = chatKey()
        try {
            val endpoint = normalizeEndpoint(req.baseUrl, req.chatPath)
            val payload = JSONObject().apply {
                put("model", req.model)
                put("messages", messages)
                put("stream", false)
                put("temperature", req.temperature)
                put("max_tokens", req.maxTokens)
            }
            val httpReq = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(JSON))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            client(req, stream = false).newCall(httpReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = safeErrorDetail(body)
                    val msg = if (resp.code in setOf(401, 403)) "认证失败（HTTP ${resp.code}）：$detail"
                    else "请求失败（HTTP ${resp.code}）：$detail"
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val content = JSONObject(body)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                    ?.trim().orEmpty()
                Result.success(content)
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
                    "TLS 证书校验失败；可在 AI 设置中勾选\"跳过 SSL 证书验证\"后重试"
                else -> "网络连接失败：${e.message}"
            }
            Result.failure(IllegalStateException(msg, e))
        }
    }

    fun listModels(baseUrl: String, verifySsl: Boolean = true, timeoutSec: Int = 15): Result<List<String>> {
        val apiKey = chatKey()
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("未配置 API Key"))
        val url = baseUrl.trim().trimEnd('/') + "/models"
        return try {
            val http = ApiClient.config(
                connectSec = timeoutSec.toLong(),
                readSec = timeoutSec.toLong(),
                writeSec = timeoutSec.toLong(),
                verifySsl = verifySsl,
            )
            val httpReq = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()
            http.newCall(httpReq).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(IllegalStateException("HTTP ${resp.code}"))
                val text = resp.body?.string().orEmpty()
                val arr = JSONObject(text).optJSONArray("data")
                    ?: return Result.failure(IllegalStateException("响应无 data"))
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

    /** `GET {baseUrl}/user/balance`（DeepSeek 专属） */
    suspend fun balance(
        baseUrl: String,
        verifySsl: Boolean = true,
        timeoutSec: Int = 15,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = chatKey()
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("未配置 API Key"))
        try {
            val url = baseUrl.trim().trimEnd('/') + "/user/balance"
            val httpReq = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            ApiClient.config(
                connectSec = timeoutSec.toLong(),
                readSec = timeoutSec.toLong(),
                writeSec = timeoutSec.toLong(),
                verifySsl = verifySsl,
            ).newCall(httpReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${resp.code}（该端点可能不支持余额查询）"),
                    )
                }
                val body = resp.body?.string().orEmpty()
                val info = JSONObject(body).optJSONArray("balance_infos")?.optJSONObject(0)
                    ?: return@withContext Result.failure(IllegalStateException("响应中没有余额信息"))
                val total = info.optString("total_balance", "")
                if (total.isBlank()) return@withContext Result.success("余额信息为空")
                val granted = info.optString("granted_balance", "")
                val topped = info.optString("topped_up_balance", "")
                Result.success("$total 元（赠送 $granted / 充值 $topped）")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun encodeMessages(messages: List<ChatApi.Msg>): JSONArray {
        val arr = JSONArray()
        messages.forEach { m ->
            val o = JSONObject().put("role", m.role).put("content", m.content)
            m.reasoningContent?.let { o.put("reasoning_content", it) }
            m.toolCalls?.let { o.put("tool_calls", JSONArray(it)) }
            m.toolCallId?.let { o.put("tool_call_id", it) }
            arr.put(o)
        }
        return arr
    }

    private fun chatKey(): String = com.dshpet.android.data.KeyFactory.current().deepseek

    private fun client(req: ChatReq, stream: Boolean): OkHttpClient =
        ApiClient.config(
            connectSec = req.timeoutSec.toLong(),
            readSec = if (stream) 0 else req.timeoutSec.toLong(),
            writeSec = req.timeoutSec.toLong(),
            verifySsl = req.verifySsl,
        )

    private fun safeErrorDetail(raw: String): String {
        if (raw.isBlank()) return "无响应内容"
        return try {
            val o = JSONObject(raw)
            val err = o.opt("error")
            if (err is JSONObject) err.optString("message", raw.take(200)) else raw.take(200)
        } catch (_: Exception) {
            raw.take(200)
        }
    }

    private class SseParser {
        private val lines = mutableListOf<String>()
        var done = false
            private set

        fun feedLine(line: String?): List<String> {
            if (line == null) return emptyList()
            val out = mutableListOf<String>()
            if (line.isBlank()) {
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

    private val JSON = "application/json".toMediaType()
}
