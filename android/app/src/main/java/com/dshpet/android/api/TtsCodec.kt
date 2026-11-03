package com.dshpet.android.api

import com.dshpet.android.voice.TtsConfig
import com.dshpet.android.voice.VoiceProfile

/**
 * 一次 TTS 请求的"线上形态"。
 *
 * 存在意义：把**怎么发**（URL / 头 / body / 是否流式）与**发出去**（HTTP 执行）分开。
 * codec 只产出本结构，`TtsApi` 负责真正调用 `ApiClient`，
 * 因此各个 provider 再也不需要持有 `OkHttpClient`、也不该出现域名与 `Bearer`。
 */
data class TtsHttpSpec(
    val url: String,
    val headers: Map<String, String>,
    val bodyJson: String,
    /** true = 边收边播（SSE / chunked）；false = 一次性二进制 */
    val stream: Boolean,
)

/**
 * TTS **编解码器**：只描述"这家服务商的报文长什么样、流怎么切"。
 *
 * 约定（配合 `checkApiHosts` 第二条规则）：
 *  - 实现类里**不允许**出现 `Request.Builder` / `newCall(` / `toRequestBody(`；
 *  - 不允许读 `apiKey` 之外的鉴权细节（key 由 [TtsApi] 注入到 [TtsHttpSpec.headers]）；
 *  - 不许抛异常穿透到播放层：解码失败返回 null，由调用方决定丢帧还是收尾。
 */
interface TtsCodec {

    /** 服务商标识，与 `TtsEngines.ID_*` 对齐 */
    val id: String

    /** 组装请求：文本 → 线上报文 */
    fun request(text: String, voice: VoiceProfile, cfg: TtsConfig): TtsHttpSpec

    /** 解析**行式流**（SSE / NDJSON）：返回一段可播音频或 null（控制帧/心跳） */
    fun decodeLine(line: String): ByteArray? = null

    /** 解析**二进制分块**：返回可播 PCM/编码块或 null */
    fun decodeChunk(bytes: ByteArray): ByteArray? = null
}