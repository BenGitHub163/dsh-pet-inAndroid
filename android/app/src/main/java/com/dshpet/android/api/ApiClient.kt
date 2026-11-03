package com.dshpet.android.api

import com.dshpet.android.util.AppLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 全 App **唯一**的 HTTP 客户端。
 *
 * 为什么要有这个类：改造前有 10 个文件各自 `OkHttpClient.Builder()`（SseClient、Balance、
 * Updater、BreezeVoices、VisionClient、三个 TTS provider、VoiceApiActivity…），结果是
 *  - 10 套连接池 / 线程池，长连接完全无法复用；
 *  - 超时各写各的（有的 10s 有的 60s 有的 0）；
 *  - 日志各打各的，出了问题只能一个个文件翻；
 *  - 想加一个统一的重试/代理/证书策略，要改 10 处。
 *
 * 用法：
 *  - 普通一次性请求：`ApiClient.read(20)`（共享连接池，只覆盖读超时）
 *  - 流式（SSE / TTS 边收边播）：`ApiClient.stream()`（readTimeout = 0）
 *
 * **不要**再在业务代码里 new OkHttpClient。
 */
object ApiClient {

    /** 日志用的 tag，和现有 Dsh_* 体系保持一致 */
    private const val TAG = "Dsh_API"

    /**
     * 默认客户端：**读超时设 0（不限）**，因为 SSE / 流式 TTS 会在两帧之间停顿很久。
     * 普通请求请用 [read] 覆盖成有限值，避免真出问题时永远挂着。
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            // 连接层失败自动重试（不重试已发出的请求，避免"超时后整轮重发"重复计费）
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()
    }

    /** 普通一次性请求：共享连接池，只把读超时改成有限值 */
    fun read(readSec: Long): OkHttpClient =
        if (readSec <= 0) http
        else http.newBuilder().readTimeout(readSec, TimeUnit.SECONDS).build()

    /** 流式请求（SSE / TTS 流）：读超时不限 */
    fun stream(): OkHttpClient = http

    /**
     * 只记 method / host+path / 状态码 / 耗时。
     * **绝不打印 Authorization 或 query 里的 key** —— 这份日志会进用户可见的日志页。
     */
    private val logging = Interceptor { chain ->
        val req = chain.request()
        val started = System.currentTimeMillis()
        val resp: Response = try {
            chain.proceed(req)
        } catch (e: Exception) {
            AppLog.log(TAG, "${req.method} ${hostPath(req.url.toString())} 失败 ${System.currentTimeMillis() - started}ms：${e.message}")
            throw e
        }
        AppLog.log(
            TAG,
            "${req.method} ${hostPath(req.url.toString())} → ${resp.code} ${System.currentTimeMillis() - started}ms",
        )
        resp
    }

    /** `https://api.deepseek.com/chat/completions?x=y` → `api.deepseek.com/chat/completions` */
    private fun hostPath(url: String): String = url
        .substringAfter("://", url)
        .substringBefore('?')
}
